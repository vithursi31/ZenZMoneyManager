# Plan — Multi-language user-facing messages (locale at the edge, render at the boundary)

**Status:** **Phase 1 shipped 2026-08-21.** Every API error message, bean-validation
reason and OTP email now renders in the caller's language, in `en` and `si`, with no
change to any `errorCode`. §§1–6 record *why* it is shaped this way — the design
decisions the code cannot state for itself. §7.3 is the work that is still open, and
§12 the questions that remain.

The operating rules for day-to-day work are in
[CLAUDE.md → Messages and Languages](../../CLAUDE.md#messages-and-languages) and
[domain-documentation.md §0.5](../domain/domain-documentation.md#05-user-facing-messages-are-localised);
this document is the reasoning behind them, not a second copy.

**Serves:** **F-1.26** (multi-language support) — the half of it that is server-side.
Client-side string catalogues and RTL layout are the app's problem, not this plan's.
**Domain basis:** `app_user.language` (already in
[`V2__finance_schema.sql`](../../svcs/core/src/main/resources/db/migration/V2__finance_schema.sql#L7),
`VARCHAR(10)`), seeded at signup from the client's locale hint and corrected in
onboarding (**F-1.27**).
**Reads with:** the *Error codes* contract in
[mobile-api-guide.md](../mobile-api-guide.md) — this plan does not change a single
`errorCode`.

---

## 1. Answer to the question that started this

> *"…all the error messages should be sent based on the user language — I think better
> to set the user language in the context?"*

**Yes to a locale in the request context. No to resolving text from it inside services.**

Putting a locale in a `ThreadLocal` and then calling `messageSource.getMessage(...)`
wherever an exception is thrown is the obvious move and the wrong one, for three
reasons that all bite later:

1. **It leaks presentation into the service layer.** `CategoryService` would decide
   what a rejection *reads like*, which is the one thing
   [`GlobalExceptionHandler`](../../svcs/core/src/main/java/com/zenzmoney/core/web/advice/GlobalExceptionHandler.java)
   exists to own. The house rule is *translate once, at the boundary* — this is
   literally the same translation, one layer over.
2. **There isn't always a request thread.** The recurring-transaction scheduler, and
   later the FCM producers, throw and construct the same messages off a worker thread.
   A `ThreadLocal` read there silently returns the JVM default and nobody notices,
   because the wrong language is not an exception.
3. **It bakes the language into the exception.** Once the exception carries rendered
   Sinhala, the log line is Sinhala too. Logs must stay English (§6).

So: **resolve the locale once at the edge, carry a *key* not a *string* through the
service layer, and render exactly once in `GlobalExceptionHandler`.** That is the
existing architecture with one more thing flowing through it, not a new one.

---

## 2. The real problem this uncovers

The message is currently doing work the error code is not.

| | count |
|---|---|
| `new BadRequestException("…literal…")` / `NotFoundException` / `Forbidden` | **76** |
| `StatusCode.with("…literal…")` call sites | **51** |
| distinct `E11xx` domain codes defined | **0** |
| bean-validation messages written by hand | **0** |

Every one of those 76 rejections answers **`E1013`** or **`E1010`**. Today the English
sentence is the only thing that distinguishes "category kind must match the transaction
type" from "at least one active account is required". The guide already admits this —
*"Today those rejections all answer `E1013`"*
([mobile-api-guide.md, §Codes not yet in use](../mobile-api-guide.md)).

Localising by error code alone would therefore **collapse all 76 into one string**. That
forces a decision, and it is the central one in this plan:

**A message key is a separate identity from an error code.** The code says *what class of
failure this is and how the client should branch*; the key says *what sentence the human
reads*. Keys are fine-grained (one per rejection); codes stay coarse and keep their band
map. Promoting a rejection to its own `E11xx` code later stays an independent,
client-visible decision — it does not become a prerequisite for translating anything.

*(Rejected alternative: mint an `E11xx` per rejection and key messages off the code. It
needs ~76 codes against sub-bands of 10 — `E112x` for category, `E114x` for transaction —
so the band map would have to be redrawn on day one, and every new sentence would become
a client-contract change. Wrong trade.)*

---

## 3. Shape

```
edge                          service layer                    boundary
────                          ─────────────                    ────────
RequestLocaleFilter           throw new BadRequestException(   GlobalExceptionHandler
  resolves Locale               Msg.CATEGORY_DUPLICATE, name)    renders key + args
  → LocaleContextHolder       (no locale, no text, no I/O)      in the resolved locale
                                                                logs the English default
```

Three rules, and they are the whole design:

- **Services never see a `Locale`.** They throw a key plus arguments.
- **`GlobalExceptionHandler` is the only place `MessageSource` is called** for API
  responses. Same statement as today's "the only place a failed request is logged".
- **The English text stays on the `StatusCode` as the fallback.** A missing translation
  degrades to English, never to a raw key.

---

## 4. Locale resolution

### 4.1 Precedence

1. **`app_user.language`** — the user deliberately chose this in onboarding. Wins.
2. **`Accept-Language`**, matched against the supported-locale allowlist.
3. **`en`.**

**Why the stored preference beats the header:** the language picker in onboarding is a
promise. A user who set Sinhala on a phone whose OS is English has told us which one they
meant, and a header quietly overriding it makes the setting look broken. The header is
what we have when there is no user yet — which is exactly the public endpoints
(`/register`, `/authenticate`, `/verify-email`, `/forgot-password`) and the rejected-token
path in
[`JwtAuthenticationFilter`](../../svcs/core/src/main/java/com/zenzmoney/core/web/filter/JwtAuthenticationFilter.java#L139),
where by definition we could not load a user row.

*(Counter-argument, for the record: a shared or borrowed device gets the account owner's
language. Accepted — this is a single-user personal finance app, and F-3.x sharing does
not share a session.)*

### 4.2 Cost

**Resolve lazily.** Errors are rare; the happy path must not pay for them.

`RequestLocaleFilter` sets only the **header-derived** locale into
`LocaleContextHolder` — no DB access, so every successful request is unaffected. The
`app_user.language` lookup happens **inside `GlobalExceptionHandler`**, on the error path
only, through a small `RequestLocale` service that reads the already-authenticated
principal. One extra query on a request that is already failing is a fair price, and it is
always fresh.

*(Rejected alternative: a `lang` JWT claim. Zero DB hit, but stale for up to an hour after
the user changes language, and the fix — reissuing tokens on a profile update — is a
bigger change than the problem. Revisit only if error-path latency ever shows up.)*

### 4.3 The header is attacker-controlled

`AcceptHeaderLocaleResolver` with `setSupportedLocales(...)` and
`setDefaultLocale(Locale.ENGLISH)`. The header **selects from a fixed allowlist** and can
never become a bundle name, a file path, or a format string. Same discipline as the
correlation-id sanitising in
[`MdcContextFilter`](../../svcs/core/src/main/java/com/zenzmoney/core/web/filter/MdcContextFilter.java).

### 4.4 Filter ordering

`RequestLocaleFilter` goes in the security chain next to `MdcContextFilter`, and for the
same reason it is **not** a `@Component`: an auto-registered `Filter` bean runs ahead of
the security chain, and `OncePerRequestFilter` then suppresses the real pass. It must also
`LocaleContextHolder.resetLocaleContext()` in a `finally` — worker threads are pooled, and
a leaked locale is the same bug class as a leaked MDC user.

---

## 5. Code changes

### 5.1 `common` — the key travels with the status code

`StatusCode` gains an optional message key and arguments, alongside the existing
`description` which becomes the **English fallback**:

```java
// existing — unchanged, still English-only. For diagnostics (§7.2).
StatusCode with(String description);

// new — same code, same HTTP status, a localisable message.
StatusCode with(MessageKey key, Object... args);
```

`MessageKey` is a plain value type in `common/status` (or `common/i18n`) holding the
bundle key. The constants live in one registry, `Msg`, mirroring how `ServiceCodes` works —
**grouped by the same bands**, so `Msg.CATEGORY_*` sits where `E112x` would:

```java
public interface Msg {
    MessageKey EMAIL_IN_USE        = key("error.auth.email-in-use");
    MessageKey CATEGORY_DUPLICATE  = key("error.category.duplicate");   // {0} = name
    MessageKey LAST_ACTIVE_ACCOUNT = key("error.account.last-active");
}
```

The three exceptions with a default code get a matching constructor, so the call site
barely changes:

```java
throw new BadRequestException("A category named '" + name + "' already exists.");   // before
throw new BadRequestException(Msg.CATEGORY_DUPLICATE, name);                        // after
```

`ApiResponse` gains **one** overload, `error(StatusCode, String renderedMessage)`. The code
still comes only from the registry — the standing rule holds — and the overload is for
boundary code only (`GlobalExceptionHandler`, plus the two other boundary writers in §5.3).

`common` gets **no Spring dependency**: it carries keys, not a `MessageSource`.

### 5.2 `core` — resolution and bundles

| Thing | Where |
|---|---|
| `MessageSourceConfig` — `ReloadableResourceBundleMessageSource` | `core/config` |
| `zenzmoney.i18n.available-languages` — the allowlist, as config | `core/src/main/resources/application.properties` |
| `RequestLocaleFilter` | `core/web/filter` |
| `RequestLocale` — precedence from §4.1, used by the handler and by email | `core/web/util` or `core/service` |
| Bundles | `core/src/main/resources/i18n/messages[_xx].properties` |

`MessageSource` settings that are not optional:

- `setDefaultEncoding("UTF-8")` — Sinhala and Tamil are unreadable without it.
- `setFallbackToSystemLocale(false)` — otherwise a server whose JVM default is `fr`
  serves French to an unmatched locale. This one is a silent production-only bug.
- `setUseCodeAsDefaultMessage(false)`, and always pass `sc.description()` as the
  `defaultMessage`. A missing translation degrades to English, never to `???key???`.

### 5.3 The error writers outside `GlobalExceptionHandler`

There are **three**, not one, and missing one is the failure mode where "the app is in
Sinhala except when your session expires" — which is when the copy matters most:

- [`AccessDeniedAdvice`](../../svcs/core/src/main/java/com/zenzmoney/core/web/advice/AccessDeniedAdvice.java) — where `@RolesAllowed` actually refuses a caller, and therefore the **common** 403. Easy to miss because `SecurityConfig` also has an access-denied handler and looks like the only one; that one is the filter-chain fallback for requests that never reach a controller.
- [`JwtAuthenticationFilter`](../../svcs/core/src/main/java/com/zenzmoney/core/web/filter/JwtAuthenticationFilter.java) — writes 401s **before** a principal exists, so it is `Accept-Language` or `en`, always.
- [`SecurityConfig` `accessDeniedHandler`](../../svcs/core/src/main/java/com/zenzmoney/core/config/SecurityConfig.java) — the API branch. The page branch redirects to `/error/403` and is Thymeleaf's problem (§7.3).

> `AccessDeniedAdvice` was found by booting the app and curling a 403, **not** by a passing
> build — nothing about the type system says a response body has to be localised. That is
> why §9 has a MockMvc case per writer rather than one for the handler alone.

### 5.4 One matcher, not two

`AcceptHeaderLocaleResolver` matches its own `supportedLocales` list, and its idea of a match is
not ours: given `zh-CN` and `zh-TW` to choose from it answers `zh-HK` and `zh-Hant` with the
default. Post-processing its answer does **not** fix that — by then the script subtag is gone and
every Traditional-reading variant has already collapsed to English. Unit tests on
`SupportedLanguages` passed the whole time; only curling the running app showed it.

So [`SupportedLocaleResolver`](../../svcs/core/src/main/java/com/zenzmoney/core/i18n/SupportedLocaleResolver.java)
**replaces** that list-matching rather than wrapping it, and is the bean DispatcherServlet uses too
— otherwise the dispatcher would recompute a different locale for the same request after the filter
had set the right one. What is kept from the superclass is the part worth keeping: the container has
already parsed the header into q-value-ordered locales, so the first one we can serve wins.

---

## 6. Logs stay English. Always.

`GlobalExceptionHandler` currently logs `ex.getMessage()`. After this change it logs
`sc.description()` — the English default — and responds with the rendered text. Two
different strings from one exception, deliberately:

- `debug.log` / `audit.log` are read by one developer at 2am. A Tamil audit line for a
  Sinhala user's failed login is unreadable and, worse, **ungreppable** — you can no
  longer grep one phrase across a year of `audit.log`.
- Retention makes this permanent: audit is kept 365 days.

Rule to hold: **`MessageSource` is never called from a logging statement.**

---

## 7. Scope

### 7.1 Shipped

- The **76** typed-exception literals and the user-facing subset of the **51** `.with(...)` sites.
- The **`E1015` bean-validation** reason text, via a `LocalValidatorFactoryBean` wired to
  the app `MessageSource` with a `LocaleContextMessageInterpolator`. **Field names stay in
  English** — `amount`, `categoryId` are contract identifiers the client branches on, and
  the client already owns the label for its own form field. Only the reason is translated.
  Framework-provided, so it is nearly free.
- The two OTP emails. `EmailSender` grows a locale parameter on both methods — and the
  registration flow already has the signal it needs, because `RegisterRequest.locale` is
  captured *before* the first code is sent.

### 7.2 Shipped — as a split, not a translation

The OAuth connectors carry ~33 `.with(...)` messages, and a good number are **diagnostics
sent to the client today**:

```java
.with("Apple id token verification failed: " + e.getMessage())   // AppleAuthConnector:149
.with("Apple key not found for kid: " + kid)                     // AppleAuthConnector:196
```

These are not translated — they **stopped being user-facing**. The split is now structural
rather than per-call-site: `StatusCode.with(String)` sets a *diagnostic* that only the log
reads, and the client gets the code's own generic message. So the disclosure is closed for
every present and future call site at once, not just the ones anyone remembered to fix.

### 7.3 Still open — same machinery, different callers

- **Chat copy** — `ChatSuggestions` ("How much did you spend on {0}?") and `ChatService`'s
  reply sentences. F-1.26 says *"honoured across the application **and** the
  intelligent-assistance features"*, and the push-notification plan already calls this a
  "message catalog". **Trap:** the interpolated category and payee names are **user data**
  and must pass through untranslated.
- **Thymeleaf** — templates switch to `#{...}` and a `LocaleResolver` bean. Low value while
  the frontend is placeholder; the infrastructure will be sitting there when it isn't.
- **Notifications (F-1.20)** — FCM title/body render from the recipient's `language` at
  send time, not at enqueue time.

### 7.4 Out

- **`errorCode` values.** Nothing moves. **This plan is wire-compatible by construction** —
  a client that branches on codes sees no difference at all.
- **LLM-generated prose (F-1.16).** Passing the user's language into the extraction /
  assistant prompt is a prompt-engineering change with its own eval; the seam is
  `ExtractionPrompt`, and it is not this plan.
- **Enum values, currency codes, ISO dates, ids** — protocol, not copy.
- **Number, date and currency *formatting*.** Clients format for display; that is a
  standing domain invariant and localisation does not change it. Corollary worth stating
  because it will be tempting: **do not put a money amount inside a message string.**
- **Schema.** `app_user.language` already exists. No `V8__`.

---

## 8. Languages

`en` is the base bundle. The repo already assumes Sri Lanka (`si-LK → LKR` in
`SignupDefaults`), so the realistic set is **`en`, `si`, `ta`**.

**The list is the `zenzmoney.i18n.available-languages` property** (`en,si`, env var
`AVAILABLE_LANGUAGES`) — adding a language is "drop the bundle in, add its tag to the property",
with no Java change and no second place to remember.

It started life as a file packaged beside the bundles, mirroring
[`SupportedCurrencies`](../../svcs/core/src/main/java/com/zenzmoney/core/util/SupportedCurrencies.java),
and moved into `application.properties` so there is one place to look for settings.
**That trade is worth naming**: as Spring config the value can be overridden per profile or by an
env var on a running server, so the build-time guarantee that every listed language had a bundle is
gone. `SupportedLanguages` replaces it with a startup check — a configured language with no
`messages_<tag>.properties` on the classpath is dropped and logged at ERROR, because a language a
user can pick that silently answers in English is worse than one that was never offered.

**12 bundles ship**: `en` `zh-CN` `zh-TW` `fr` `es` `pt` `de` `it` `ru` `ja` `ko` `si` — Chinese
twice, because the script changes the text and not just the formatting.

**The config names bundles exactly; matching a caller's tag is what is lenient.** Two jobs, split on
purpose. A configured tag must have a file (`zh-TW` → `messages_zh_TW.properties`) and is dropped
loudly if it does not. A *caller's* tag is matched on **language + script**: region ignored, so
`fr-CA` and `pt-BR` need no bundles; script respected, so `zh-HK` and `zh-Hant` reach Traditional.
The region→script map is gated on `zh`, or `en-SG` would come out Chinese.

> **`messages_zh_TW.properties` is not a character conversion of the Simplified file.** Taiwan usage
> differs in vocabulary too — 登入 not 登录, 支援 not 支持, 設定 not 设置, 預設 not 默认, 權杖 not
> 令牌 — so a tool-converted file would read as mainland Chinese in Traditional characters.

Everything but English is **machine-assisted and unreviewed**, marked as such at the top of
every bundle. That is a deliberate, and reversible, trade: the mechanics are proven — every
bundle is key-complete, every `{0}` interpolates in every language, and the quoting holds —
but the *wording* has had no native speaker over it. The cheap way to fix one language is to
hand its single `messages_<tag>.properties` to a reviewer; the cheap way to withdraw one is to
drop its tag from the property, which is a one-line change that needs no rebuild of anything
else (§12.1).

`language` is stored **normalised to the bundle's tag** — `si-LK` in, `si` stored — rather
than storing what the user sent and matching leniently on every read. Both work; normalising
on write means the stored value can be trusted, and there is exactly one place
(`SupportedLanguages`) that knows how a tag maps to a bundle.

---

## 9. Testing — what shipped

420 unit tests pass. Per the test-discipline table: service logic and boundary behaviour,
so JUnit plus MockMvc.

| Test | Asserts |
|---|---|
| `MessageResolverTest` | the whole fallback ladder: a key resolves in `si`; a key missing from `si` falls back to English; an unknown key falls to the code's generic and never to the raw key; a **diagnostic never reaches the rendered message** |

| `SupportedLanguagesTest` | lenient region matching (`si-LK` → `si`, `si_LK` too), that junk is `null` rather than a guess, that a **configured language with no bundle is dropped**, that English survives a config that omits it, and that the value shipped in `application.properties` names only bundles that are in the jar |
| `SupportedLanguagesTest` — Chinese | every Traditional variant (`zh-TW` `zh-HK` `zh-MO` `zh-Hant` `zh-Hant-TW` `zh_TW`) reaches Traditional and every Simplified one reaches Simplified; a bare `zh` is Simplified; **`en-SG` and `en-HK` stay English** — the region map must not leak out of Chinese; and offering only `zh-CN` leaves `zh-TW` *unmatched* rather than quietly serving the wrong script |
| `RequestLocaleTest` | precedence in both directions, plus: a lookup that throws still returns a locale — resolving a message must never be what fails a request that was already failing |
| `GlobalExceptionHandlerTest` | a diagnostic answers the generic message; a key answers its own text; the same rejection in `si` **keeps its `errorCode` and changes only the sentence**; an unsupported locale falls back to English |
| `MeControllerSecurityTest` | the other two writers — the access-denied 403 and a rejected-token 401 — are localised, and an unsupported `Accept-Language` falls back |
| `ProfileServiceTest` / `OnboardingServiceTest` | the language can be changed, is normalised on the way in, and an unservable one is refused rather than stored |
| `SmtpEmailSenderTest` | the OTP survives whichever bundle rendered the body |
| `RegistrationServiceTest` | the seeded language decides the verification email's language |

---

## 10. Traps — all of these are now covered by a test

- **`MessageFormat` eats single quotes.** `A category named '{0}' already exists.` renders
  as `A category named {0} already exists.` — the quote escapes the placeholder. It must be
  `''{0}''`. Classic, silent, and caught only by the test above.
- **Any message with an argument is now a `MessageFormat` string**, so a stray `'` anywhere
  in a *translated* sentence breaks that sentence and no test that only checks English will
  see it. Worth a note at the top of each bundle file.
- **`setFallbackToSystemLocale(true)` (the default)** — works everywhere the JVM default is
  English, which includes every development machine, and fails only in production.
- **Unrendered keys reaching the client.** `setUseCodeAsDefaultMessage(true)` turns a
  missing translation into `error.category.duplicate` in a user's face. Off, with an English
  default supplied instead.
- **Leaked `LocaleContextHolder`** on a pooled thread — clear it in `finally`.
- **The base bundle drifting from `Msg`.** Only the completeness test prevents this.

---

## 11. Build order — as executed

Each step compiled and shipped on its own; nothing here was a big-bang.

1. **`common`:** `MessageKey`, the `Msg` registry (empty), `StatusCode.with(key, args)`,
   the exception constructors, the `ApiResponse.error(sc, message)` overload. Unit tests.
   *Nothing behaves differently yet.*
   → **remember `mvn install -pl svcs/common` before restarting** — a changed `common`
   is not picked up by `spring-boot:run`.
2. **`core` infrastructure:** `MessageSourceConfig`, `RequestLocaleFilter`, `RequestLocale`,
   the base `messages.properties`, and `GlobalExceptionHandler` rendering. Still English-only
   end to end, so the diff is provably behaviour-preserving.
3. **Migrate the call sites**, one service at a time — auth first (highest-traffic copy),
   then the ledger services. Each one adds its keys to the base bundle; the completeness
   test keeps them honest.
4. **The two boundary writers** (§5.3) and bean-validation interpolation.
5. **`messages_si.properties`** — the first real translation, plus the MockMvc locale tests.
6. **Emails** (locale on `EmailSender`).
7. **Update [mobile-api-guide.md](../mobile-api-guide.md):** state that `message` is
   localised by the caller's language, that `Accept-Language` is honoured when no user is
   authenticated, list the supported languages, and repeat that `errorCode` is unchanged
   and remains the thing to branch on.
8. `PUT /me` accepts `language` — see §12.3, which is now closed.
9. **Phase 2** (§7.3) remains as separate work.

Steps 1–2 were the whole architecture. Step 3 was volume, and it was mechanical.

---

## 12. Open questions

1. **Eleven of the twelve bundles have not been reviewed by a native speaker.** They are
   machine-assisted and marked as such at the top of each file. The auth and OTP block is
   what a user reads while already frustrated, so it is the part worth checking first.
   **This is the one thing blocking "done" for real users** — and it is per-language, so
   a reviewed language can ship while the others wait.
2. **Tamil (`ta`) is still not supported.** It was named in a code comment and in an onboarding
   test, but there was never a bundle — so onboarding silently stored a language nothing
   could serve. It is now refused. Adding it is one bundle and one tag.
3. **Two behaviour changes worth telling a client about**, both narrowing what is accepted:
   `POST /onboarding` and `PUT /me` now reject a `language` outside the supported set
   (`400 E1013`) instead of storing it. Storing an unservable tag was a lie the user only
   discovered by every message staying English.
4. **Do any rejections deserve a real `E11xx` code now?** Independent of this work by
   design (§2) — but "last active account", "category in use by a budget", and the chat
   draft-state refusals are the ones a client would genuinely branch on.
5. **`PUT /me` renders its own response in the *old* language** when the call changes the
   language, because the message is resolved before the new preference is saved. Documented
   in the API guide; only observably wrong on a validation failure in that same request.
