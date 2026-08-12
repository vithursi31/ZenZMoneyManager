# Plan — Push Notifications over FCM (REST stays the transport)

**Status:** Draft for review. **No code has been written.** This document is the
implementation plan only.

**Serves:** *(IDs per BRD v1.0, renumbered 2026-08-08)*
F-1.20 (notifications & reminders) now; later the shared-space updates behind
F-3.3 / F-3.5 / F-3.8. No new feature ID — this is transport in service of those.
**Domain basis:** [§1.7 `Budget`](../domain/domain-documentation.md#17-budget),
[§1.8 `RecurringTransaction`](../domain/domain-documentation.md#18-recurringtransaction),
[Part 5 — sharing](../domain/domain-documentation.md#part-5--sharing--multi-user-phase-3).
**Reads with:** [chat-transaction-entry-plan.md](chat-transaction-entry-plan.md) (the
capture pipeline, which stays plain REST).

> **Supersedes the WebSocket/STOMP transport plan** (`chat-websocket-collaboration-plan.md`,
> removed 2026-08-12 — never committed, so it is not recoverable from git history).
> §8 records why it was dropped rather than built, so the question does not come back.

---

## 1. Decision

**Chat does not need a socket, and neither does anything else in this product.**

Chat is one message in, one draft out, then an explicit confirm — request/response, and
already correct. Nothing streams: the reply is a sentence from a message catalog, not
generated prose. A socket there would replace a working REST call with a connection to
keep alive, reconnect, authorize, and rate-limit.

What REST genuinely cannot do is speak **unprompted** — "your Food budget just crossed
90%", "rent is due tomorrow", "the trial you started ends on Friday". That is F-1.20, and
it is a *mobile notification* problem, not a live-connection problem: the user is not
looking at the app when it fires. The app being closed is the normal case, and a socket
is dead the moment the app is backgrounded.

**So: Firebase Cloud Messaging for the push, REST for everything else.** FCM already owns
the hard parts — an OS-level connection the app does not maintain, delivery to a
backgrounded or killed app, retry while the device is offline, and one integration
covering Android, iOS and web.

```
something changes ──► NotificationService ──► FCM ──► device
(budget crossed,       (decides audience,      (delivers, even
 bill due, …)           builds a data msg)      when app is closed)
                                                     │
                                                     ▼
                                          client re-reads the REST API
                                          (the notification is a hint,
                                           the API is the truth)
```

That last arrow is the whole design. **A push is a hint that something changed, never the
data itself** — which is what keeps this from needing delivery guarantees, an outbox, or
an event log.

---

## 2. Scope

**In scope**
- **Device token registration** — a `user_device` table and two endpoints (§4, §5).
- **Sending** — one `NotificationService` over the Firebase Admin SDK, called outside any
  DB transaction (§6).
- **Producers** — budget threshold crossed, recurring/bill due, subscription renewal,
  free-trial ending (§7). All server-side; most already have a scheduler.
- **Token lifecycle** — refresh on app start, delete on logout, prune on FCM's
  `UNREGISTERED` response (§6.3).
- **The audience seam** (§9) so Phase 3 fans out to a space's members without a rewrite.

**Out of scope (deliberately)**
- **In-app real-time updates.** One user, one device at a time — a pull-to-refresh and a
  re-read on resume are correct and cost nothing. Revisit only if Phase 3 shows a real
  need, and revisit it as SSE (§8), not a socket.
- **A stored notification history / in-app inbox.** Worth doing, but it is a table and a
  list endpoint, not transport — plan it with F-1.20's UI.
- **User notification preferences** beyond an on/off per device — quiet hours, per-type
  toggles, digest batching. §10 records the question.
- **Phase 3 entities** — space, membership, roles. Part 5's job; this plan only owes them
  the seam in §9.
- **Email/SMS as a second channel.** FCM first; email reuses the existing `EmailSender`
  seam if it is ever wanted.

---

## 3. What ships where

| Piece | Where it lives |
|---|---|
| `DevicePlatform` enum (`ANDROID`, `IOS`, `WEB`) | `common/domain` — enums live there, not in `core` |
| `UserDevice` entity + repository | `core/entity`, `core/repository` |
| `DeviceController` (register / unregister) | `core/web/controller` |
| `NotificationService`, `FcmSender` (interface) + `FirebaseFcmSender` | `core/service/notification` |
| `V4__user_device.sql` | `core/src/main/resources/db/migration` |

`FcmSender` is an interface for the same reason `EmailSender` is one: it is the seam that
makes the provider swappable and the tests possible without a network.

---

## 4. Data model

One new table. Nothing else about push is persisted.

```
user_device
  id           VARCHAR(36) PK        -- BaseEntity
  user_id      VARCHAR(36) NOT NULL  -- indexed; the ownership invariant applies
  token        VARCHAR(512) NOT NULL -- FCM registration token, UNIQUE
  platform     VARCHAR(50) NOT NULL  -- DevicePlatform, @Enumerated(STRING)
  app_version  VARCHAR(50)           -- nullable; for diagnosing per-build delivery bugs
  last_seen    BIGINT NOT NULL       -- epoch millis, refreshed on every register call
  enabled      BOOLEAN NOT NULL      -- user turned push off on this device
  + BaseEntity audit columns
```

- **`token` is UNIQUE, not `(user_id, token)`.** A registration token identifies a *device
  install*, and a device that logs out and back in as a different user must not leave the
  first user's row behind — the second user would receive the first's balances. Register
  therefore **reassigns** the row's `user_id` rather than inserting a second one.
- **`last_seen`** is what makes pruning possible: a token untouched for ~9 months is dead
  (FCM invalidates them itself, but only after it tries a send). A scheduled sweep is a
  later nicety, not a step-1 requirement.
- Multiple rows per user are expected here — phone plus tablet plus web. That is the one
  place this design differs from the old socket plan, which capped a user at one live
  session; with FCM, fan-out to N tokens is one multicast call and costs nothing.
- **Flyway is the authority** — write `V4__user_device.sql`, and do not rely on
  `ddl-auto=update` having created the column locally.

---

## 5. Endpoints

Both `@RolesAllowed({"USER","ADMIN"})`, both scoped to the caller. Neither goes anywhere
near `PUBLIC_PATHS`.

| Method + path | Purpose |
|---|---|
| `POST /api/v1/devices` | Register or refresh this device's token — `{token, platform, appVersion}`. Idempotent: same token → update `user_id`/`last_seen`, never a duplicate row. |
| `DELETE /api/v1/devices/{id}` | Unregister. Called on logout, so a shared handset stops receiving the previous user's alerts. |

`POST` is what the client calls on every app start — the token rotates on reinstall, on
app-data clear, and occasionally at FCM's discretion. Treating it as an upsert on `token`
is what keeps that from accumulating garbage.

**Ownership, as everywhere else:** the `DELETE` resolves through
`findByIdAndUserId(...)`, never a bare `findById` — otherwise anyone can unregister
anyone's device and silently kill their alerts.

---

## 6. Sending

### 6.1 Message shape

**Data-only messages, not FCM `notification` messages.** A `notification` message is
rendered by the OS before the app sees it, which means the *server* has authored the
lock-screen text — wrong language (F-1.25 is per-user), wrong currency formatting, and
the amount is then sitting in a third party's payload and on a lock screen.

```json
{ "data": {
    "event":      "BUDGET_THRESHOLD_CROSSED",
    "entityId":   "…",
    "occurredAt": "1785257839417",
    "percent":    "92"
} }
```

The client receives that, formats the text in the user's own language and currency, and
re-reads the REST API for anything it needs to display. Rules carried over from the REST
contract, unchanged:

- **Money is minor units + currency** if an amount is carried at all — no formatted
  amounts in a payload, ever. Prefer carrying no amount: send the id, let the client read.
- **Time is epoch millis. Enum names, not display strings.** (FCM data values are strings
  on the wire; that is a serialization detail, not a licence to send `"92%"` or `"$5.00"`.)
- **Never the user's free-form text.** A transaction note or chat message is private
  financial detail; it does not go through Google's infrastructure to land on a lock
  screen. Same rule as the logs.

### 6.2 Where the call happens

**Outside the transaction, always.** FCM is a network call to a third party, and holding a
DB connection across it is how the pool starves. The shape is the one this codebase
already uses for email: short transaction to persist, commit, *then* send. A send failure
must not roll back the budget evaluation that triggered it.

Most producers (§7) are already on the scheduler, so the send is naturally off the request
path. For the request-path producer — a transaction that pushes a budget over its limit —
the send is fire-and-forget on a bounded executor, never inline in the write.

### 6.3 Failure handling

| FCM response | Action |
|---|---|
| `UNREGISTERED` / `INVALID_ARGUMENT` on the token | **Delete the `user_device` row.** The install is gone; retrying forever is the only way this table grows without bound. |
| `UNAVAILABLE` / 5xx | Retry with backoff, bounded — a notification is worthless late. Give up and log `WARN`. |
| Anything else | `ERROR` with the response code. |

**Log the outcome and the duration of every send** — a slow FCM and a broken FCM look
identical from the outside, and the whole feature is invisible when it silently stops.
Log ids, event type, token count, and outcome; never the token itself (it is a bearer
credential for that device's delivery) and never the payload's user-facing text.

### 6.4 Rate limiting

Push is a user-triggered action that costs compute and, more importantly, **goodwill** —
an app that fires six notifications in a minute gets its permission revoked and never
reaches the user again. Apply [`RedisRateLimitService`](../../svcs/core/src/main/java/com/zenzmoney/core/service/ratelimit/RedisRateLimitService.java)
per user per event type, and **fail open** here: a limiter outage should drop a
notification, not block the ledger write that produced it. (The opposite call from OTP,
deliberately — there, fail-closed protects an abuse surface; here, nothing user-triggered
amplifies, and availability of the *write* beats delivery of the *alert*.)

Suppression that belongs in the producer, not the limiter: a budget alert fires **once per
budget per threshold per period**, not on every transaction over the line. That is a
persisted "already alerted" marker on the budget period, and it is the difference between
a useful feature and an uninstall.

---

## 7. Producers

| Trigger | Where it fires | Event |
|---|---|---|
| Budget usage crosses 80% / 100% | After a transaction write commits (§6.2) | `BUDGET_THRESHOLD_CROSSED` |
| Recurring item due tomorrow | Daily scheduler pass over `next_run_date` | `BILL_DUE_SOON` |
| Subscription renews tomorrow | Same pass | `SUBSCRIPTION_RENEWING` |
| Free trial ends in 2 days | Same pass over `trial_end_date` | `TRIAL_ENDING` |
| Savings goal reached (F-3.1) | On contribution | `GOAL_REACHED` |

The recurring producers ride
[`RecurringTransactionScheduler`](../../svcs/core/src/main/java/com/zenzmoney/core/scheduler/RecurringTransactionScheduler.java)'s
existing daily pass — a second query over the same rows, not a second scheduler.

**Timezone matters more than it looks.** "Due tomorrow" and "at 9am" are per-user
(`User.timezone`, the same field the monthly position uses). A single UTC pass that sends
everyone's reminders at once wakes half the user base at 3am. Either bucket users by
timezone offset, or run the sweep hourly and select only users whose local hour is the
send hour — the second is simpler and is the recommendation.

---

## 8. Why not a socket (and what SSE was for)

Recorded here so the question does not get re-opened from scratch:

- **WebSocket / STOMP — rejected.** It solves in-app bidirectional traffic; F-1.20's
  actual requirement is reaching a user whose app is *closed*, which a socket cannot do at
  all. Building it would have cost a handshake-auth ticket flow (a browser `new WebSocket`
  cannot set an `Authorization` header), a subscription authorizer, a session registry, MDC
  propagation onto pooled frame threads, heartbeat/idle tuning, and nginx upgrade config —
  every one of those a place to leak a session or a permission — while still needing FCM
  for the closed-app case. Two transports for one feature.
- **SSE — deferred, not rejected.** If a live *in-app* view ever genuinely needs
  server→client push (a shared-space activity feed, F-3.3), SSE is the right build: plain
  HTTP so the existing `Authorization` header and filter chain work, automatic browser
  reconnect, no new protocol. It complements FCM (foreground detail vs background alert)
  rather than competing with it. Do not build it before a screen exists that needs it.
- **Client polling** — what happens today, and adequate while the app is open and single-user.
- **Email-only reminders** — kept as a possible second channel for the ones that matter
  (trial ending), not as the primary.

**Code cleanup this implies** (a separate change, not part of this plan): the empty
`WebSocketConfig` stub and the `spring-boot-starter-websocket` dependency in
`svcs/core/pom.xml` are now dead — a dependency on the classpath is a maintenance and CVE
surface for something nothing uses. Remove both.

---

## 9. The Phase 3 seam

One decision keeps sharing additive: **an event's audience comes from the affected row's
owner scope, never from the user who acted.**

```
        event about a row  ──►  ownerScopeOf(row)  ──►  tokens
Phase 1:  transaction        →  Scope(USER,  user_id)  →  that user's devices
Phase 3:  transaction        →  Scope(SPACE, space_id) →  every member's devices
```

Phase 1 ships the `USER` branch — a one-liner returning the row's `user_id` — behind
`NotificationService.notify(audience, event)`. Part 5 adds the `SPACE` branch and a
membership lookup. **No call site names a user or a token directly**, so nothing written
now is rewritten then. The acting user rides separately as `actorId` on the event — that is
attribution ("who spent what", F-3.3), not addressing, and keeping the two apart is what
lets a son's expense reach his mother while still reading as *his*.

The entire Phase 3 contract, in two methods:

```java
Audience    ownerScopeOf(entity)          // USER(user_id) today; SPACE(space_id) later
Set<String> memberIds(String spaceId)     // for fan-out; roles per F-3.4 if streams differ
```

**Payload scoping still applies** — a space event carries what a member is entitled to
see, and membership removal must stop delivery. With FCM that is simpler than it was with
subscriptions: the audience is resolved per send, so a removed member is excluded on the
very next one, with no live connection to evict.

---

## 10. Configuration & secrets

The Admin SDK needs a **service account JSON**. It is a credential.

- Supply it by path or base64 through the environment (`.env` on the server, per
  [DEPLOYMENT.md](../../DEPLOYMENT.md)), read via `zenzmoney.fcm.*` properties in the
  three profile files in lockstep. **Never commit the file**, and never log it.
- Note the standing `.gitignore` gap in [CLAUDE.md](../../CLAUDE.md) — a missing trailing
  newline means `svcs/core/src/main/resources/google.properties` is *tracked*. Fix that
  before any real Google credential goes anywhere near this repo.
- **`loc` must work with no credential at all.** A no-op `FcmSender` that logs what it
  would have sent, selected when the property is absent — the same deliberate degrade as
  chat without `ollama`. Nobody should need a Firebase project to run the app locally.
- **Web push additionally needs a service worker and a CSP change**
  (`connect-src`/`script-src` for the FCM SDK in
  [`CspNonceFilter`](../../svcs/core/src/main/java/com/zenzmoney/core/web/filter/CspNonceFilter.java)).
  Android/iOS first; do web when the web client is more than placeholder Thymeleaf.

---

## 11. Testing plan

| Tier | What | How |
|---|---|---|
| **Security** | `DELETE /devices/{id}` for another user's device → 404, not 204; register without a token → 401; both asserted against the **API directly** | `@SpringBootTest` + `spring-security-test` (MockMvc) |
| **Token lifecycle** | Same token registered twice → one row, `last_seen` updated; registered by a second user → row reassigned, first user no longer notified; `UNREGISTERED` from FCM → row deleted | Service test with a stubbed `FcmSender` |
| **Audience** | An event about user A's row reaches A's tokens and **only** A's — asserted with a second user's devices registered. This is the Phase 1 test that keeps Phase 3 honest | Service test, two users |
| **Producers** | 80%/100% budget crossing fires once per threshold per period, not per transaction; a `nextRunDate` one day out fires exactly one reminder | Unit tests with a fixed clock |
| **Timezone** | A user in `Asia/Colombo` and one in `UTC` get the same local send hour | Unit test over the hourly sweep |
| **Persistence** | `V4__user_device.sql` applies and the unique constraint on `token` holds | Testcontainers Postgres — on the classpath and still unused |
| **Degrade** | No credential configured → no-op sender, no exception, one log line | Context test on the `loc` wiring |

The stubbed `FcmSender` is the point of the interface: none of these touch the network.

---

## 12. Build order

1. **`user_device` table + `V4` migration + entity/repository + the two endpoints**, with
   the security and lifecycle tests. No sending yet — a client can register from day one.
2. **`FcmSender` interface + no-op implementation + `NotificationService.notify(audience, event)`**
   with the `USER` audience branch (§9). Still nothing leaves the JVM.
3. **`FirebaseFcmSender`** + config + failure/pruning handling (§6.3) + rate limit (§6.4).
4. **First producer: budget threshold** — the one users feel immediately, and the one that
   proves the once-per-threshold suppression works.
5. **Scheduler producers** — bill due, renewal, trial ending, with the timezone sweep (§7).
6. **Remove the dead WebSocket stub and dependency** (§8).
7. *(with Part 5)* `SPACE` branch in the audience resolver. A branch and a member lookup —
   not a redesign.

Steps 1–6 are entirely single-user and carry no dependency on the sharing model.

---

## 13. Open questions

1. **Notification history / in-app inbox** — does F-1.20 need a stored, readable list, or
   is a transient OS notification the whole feature? A table changes step 1's scope.
2. **Per-type preferences and quiet hours** — likely needed before launch (a budget alert
   at 2am is an uninstall), but it is a preferences shape, not transport. Decide with the
   F-1.20 UI.
3. **Thresholds** — 80/100 only, or user-configurable per budget? Fixed first.
4. **iOS APNs key** — FCM still needs an APNs auth key uploaded to the Firebase project;
   confirm the Apple developer account covers it before step 3 (the same account already
   used for Apple OAuth sign-in).
5. **Web push** — worth it before there is a real web client? Probably not (§10).
