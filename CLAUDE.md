# ZenZ Money Manager — Claude Instructions

## Product Overview

ZenZ Money Manager is a **personal finance application**. A single user tracks where money lives (accounts), where it moves (transactions, transfers), plans against it (budgets, savings goals, debts, subscriptions), and gets insight from it (dashboard, reports, AI assistant) — in their own language and currency, behind a device lock.

**Current state:** the backend implements the **authentication foundation** only. The finance domain (accounts, transactions, budgets, goals, recurring, contributions) exists as **entities + repositories + Flyway schema** (`V2__finance_schema.sql`), but has **no services, controllers, or DTOs yet** — that's the work in flight. Frontend templates are placeholder Thymeleaf: no CSS framework, no JS, no design system.

What's actually working today:
- Email/password **registration** with email-verification OTP, and **login** (JWT access + refresh).
- **Google**, **Apple**, **Facebook** OAuth sign-in.
- **Password reset** via OTP, JWT request filter, BCrypt hashing, Redis-backed **rate limiting** on OTP issuance.
- Page shell: `/`, `/dashboard`, `/admin`, error pages.

Product docs — read these before designing any finance feature:

| Doc | What it carries |
|---|---|
| [docs/features-list.md](docs/features-list.md) | Feature catalogue with stable IDs (`F-1.3`, `F-2.x`) grouped by phase, with per-feature status. **Feature IDs are the shared vocabulary** — reference them in commits, branches, and tests. |
| [docs/domain/domain-documentation.md](docs/domain/domain-documentation.md) | The consolidated domain model — entities, ERD, enums, invariants, per-part scope. The authority on schema shape. |
| [docs/roadmap.md](docs/roadmap.md) | Phase sequencing and what is deliberately unscheduled. |
| [docs/features/chat-transaction-entry-plan.md](docs/features/chat-transaction-entry-plan.md) | Implementation plan for F-1.9a (NLP transaction entry). |
| [svcs/AUTH_FLOW_PORTABLE.md](svcs/AUTH_FLOW_PORTABLE.md) | Framework-free walkthrough of the whole auth flow. |

### Domain invariants that constrain every change

These come from [domain-documentation.md §0](docs/domain/domain-documentation.md) and are **not negotiable per-feature** — violating one is a bug, not a style choice:

- **Money is integer minor units in a `BIGINT`, paired with an ISO-4217 `currency`.** `amount = 1050` + `USD` = $10.50. **No `DECIMAL`, `double`, or `BigDecimal` for money anywhere.** Interest rates are **basis points** in an `int` (`750` = 7.50%). Clients format for display; the backend stores and sums minor units.
- **One active currency per user** (MVP). Every money-bearing entity still carries its own `currency` column — that per-row column is the seam that makes multi-currency additive later. No implicit FX conversion.
- **Every user-owned row carries `user_id VARCHAR(36) NOT NULL`, indexed.** Reads and writes are always scoped to the authenticated user — there are no cross-user reads until Part 5 sharing. Repository finders follow `findByIdAndUserId(...)` ([TransactionRepository.java:17](svcs/core/src/main/java/com/zenzmoney/core/repository/TransactionRepository.java#L17)); a bare `findById` on a user-owned entity in a request path is an authorization hole.
- **Time is epoch milliseconds in a `BIGINT`** — audit columns *and* domain dates (`txn_date`, `next_run_date`). No `LocalDate`/`Timestamp` columns.
- **Balances are derived from the ledger** (opening balance ± transactions), so they re-derive on every edit or delete — see [domain-documentation.md §1.10](docs/domain/domain-documentation.md#110-balance-derivation-invariant). `account.current_balance` is a cache of that derivation, never an independent source of truth.
- **Enums are `@Enumerated(EnumType.STRING)` in `VARCHAR(50)`**, and the enum types live in `common/domain` — not in `core`.

## Project Info

**Backend:** Java 17, Spring Boot 3.4.4, Spring Security, Spring Data JPA (Hibernate), QueryDSL 5.1, Flyway, PostgreSQL 14, Redis 7, Thymeleaf, JJWT 0.12.6, Lombok
**Build:** Maven 3.8.x (multi-module reactor)
**Local infra:** Docker Compose (Postgres on `5454`, Redis on `6363`)
**Hosting:** Docker image + `docker-compose.prod.yml` on a single always-on VM (Oracle Cloud Always Free) — see [DEPLOYMENT.md](DEPLOYMENT.md)

### Modules (`svcs/`)

| Module | Artifact | Purpose |
|---|---|---|
| `core` | `zenzmoney-core` | The only runnable module — web, security, persistence, services. Depends on `common`. |
| `common` | `zenzmoney-common` | Library — `BaseEntity`, domain enums, `ApiResponse`, exceptions, `Decryptor`. No Spring web/security deps. |

Package roots: `com.zenzmoney.core` and `com.zenzmoney.common`. Group id `com.zenzmoney`, version `0.0.1-SNAPSHOT` (the version stays put — see *Release Process*).

**Where a type belongs:** shared across modules or referenced by the schema/DTO contract → `common` (enums, `ApiResponse`, exceptions). Spring-wired behaviour → `core`. A domain enum in `core/entity` is misplaced.

## Backend Architecture

The stack is **plain Spring Boot** — no internal framework layer:

```
Controller (@RestController / @Controller)
  → Service (@Service, @Transactional)
    → Repository (Spring Data JPA / QueryDSL)
      → Entity (JPA, extends BaseEntity)
```

### Key types and conventions

- **[BaseEntity](svcs/common/src/main/java/com/zenzmoney/common/domain/BaseEntity.java)** — `@MappedSuperclass` every entity extends. Carries `String id` (UUID assigned in `@PrePersist` if unset), `createdTime`/`modifiedTime` (epoch-millis `Long`, JPA auditing), `createdBy`/`modifiedBy`, and `@Version version` for optimistic locking. Auditing is enabled by [JpaAuditingConfig](svcs/core/src/main/java/com/zenzmoney/core/config/JpaAuditingConfig.java) — never set audit fields by hand.

- **[ApiResponse&lt;T&gt;](svcs/common/src/main/java/com/zenzmoney/common/dto/ApiResponse.java)** — the envelope for **every** JSON response: `{status, data, message, errorCode}`. Build with `ApiResponse.success(data)` / `ApiResponse.error(code, message)`. Controllers return `ResponseEntity<ApiResponse<T>>`. Never return a bare entity or map.

- **Exceptions → status + error code.** Throw the typed exception from `common/exception`; [GlobalExceptionHandler](svcs/core/src/main/java/com/zenzmoney/core/web/advice/GlobalExceptionHandler.java) translates it once at the boundary. Do not catch-and-wrap inside services, and do not build error responses in controllers.

  | Exception | HTTP | Code |
  |---|---|---|
  | `NotFoundException` | 404 | `E1010` |
  | `BadRequestException` | 400 | `E1013` |
  | `ForbiddenException` | 403 | `E1014` |
  | `UnauthorizedException` | 401 | carries its own `errorCode` |
  | `TooManyRequestsException` | 429 | own code + `Retry-After` header |
  | `MethodArgumentNotValidException` (bean validation) | 400 | `E1015` |

- **Entities use raw foreign-key ID fields, not JPA relationships.** `Transaction.accountId` is a `String`, not `@ManyToOne Account` ([Transaction.java:32](svcs/core/src/main/java/com/zenzmoney/core/entity/Transaction.java#L32)). The only association mapping in the codebase is `User.roles` (`@ElementCollection`). Follow this — it keeps queries explicit and avoids lazy-loading traps (see `open-in-view=false` below).

- **JSON columns:** `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")` (see `Account.metadata`, `Transaction.tags`, `User.preferences`). Both annotations are required.

- **Repositories** extend `JpaRepository<T, String>`. Add `QuerydslPredicateExecutor<T>` only where dynamic filtering is genuinely needed (currently `TransactionRepository`, for search/filter F-1.19). A `JPAQueryFactory` bean is available from [QueryDslConfig](svcs/core/src/main/java/com/zenzmoney/core/config/QueryDslConfig.java) for projections/aggregates that don't fit a derived finder. QueryDSL `Q` classes are generated by the annotation processor at `package` time — a missing `QTransaction` means the module hasn't been rebuilt.

- **Transactions are demarcated on the service, not the controller or repository** — `@Transactional` on the service method (see [RegistrationService](svcs/core/src/main/java/com/zenzmoney/core/service/RegistrationService.java), [OtpService](svcs/core/src/main/java/com/zenzmoney/core/service/OtpService.java)). **`spring.jpa.open-in-view=false`**, so nothing lazy-loads after the service returns: fetch everything you need inside the transaction. Keep slow work (email send, HTTP calls, OCR/AI, image processing) **outside** the transaction — process first, then a short transaction to persist. Holding a DB connection across a network call is how the pool starves under load.

## Web Layer

### Authentication

Hybrid model, both paths converging on Spring Security's `SecurityContext`:

| | JWT-based | Session-based |
|---|---|---|
| Used by | Mobile apps, REST API clients | Browser page controllers |
| Token | `Authorization: Bearer <token>` header, or `?authorization=<token>` query param | `JSESSIONID` cookie |
| Entry | `POST /api/v1/authenticate` (+ `/google`, `/apple`, `/facebook`) | Session created `IF_REQUIRED` |
| Filter | [JwtAuthenticationFilter](svcs/core/src/main/java/com/zenzmoney/core/web/filter/JwtAuthenticationFilter.java), before `UsernamePasswordAuthenticationFilter` | Spring Security default |

- Access tokens live 1h, refresh tokens 30d (`zenzmoney.jwt.*`). The filter **rejects a refresh token used as an access token** — token `type` is checked explicitly.
- No token → the request proceeds as `ROLE_ANONYMOUS` (not 401). The 401/403 comes from method security.
- CSRF is **disabled**; passwords use `BCryptPasswordEncoder`.
- **Public paths** are the `PUBLIC_PATHS` prefix set in the filter: `/api/v1/authenticate`, `/register`, `/refresh-token`, `/forgot-password`, `/reset-password`, `/verify-email`, `/service-status`. Adding a public endpoint means adding it **here**, and prefix matching means `/api/v1/register-anything` is public too — name new endpoints with that in mind.

> **Authorization is method-level, not URL-level — this is the single most important web-layer fact.** [SecurityConfig](svcs/core/src/main/java/com/zenzmoney/core/config/SecurityConfig.java) sets `.anyRequest().permitAll()` on purpose and relies on `@EnableMethodSecurity(jsr250Enabled = true)` + **`@RolesAllowed`** on each handler. **A new controller method with no `@RolesAllowed` is wide open to anonymous callers.** Every non-public endpoint needs `@RolesAllowed({"USER","ADMIN"})` (or `"ADMIN"`), and — per the ownership invariant — must also scope its query by the caller's `user_id`. Roles are `ANONYMOUS`, `USER`, `ADMIN` ([Role](svcs/common/src/main/java/com/zenzmoney/common/domain/Role.java)); resolve the caller via [AuthUtil](svcs/core/src/main/java/com/zenzmoney/core/web/util/AuthUtil.java).

> **Who is ADMIN?** Every registered / OAuth user is granted **`USER`** only (`setRoles(Set.of(Role.USER))` in `RegistrationService` / `OAuthLoginService`). **No code path assigns `ADMIN`** — there is no promotion endpoint, bootstrap, or migration seed (deliberate, single-author project). So `ADMIN`-gated routes (`/api/v1/admin/**`, the `/admin` page) are unreachable until an admin is designated **manually**:
> ```sql
> INSERT INTO user_roles (user_id, role) VALUES ('<user-id>', 'ADMIN');
> ```
> The role is loaded into the JWT principal on the user's next login (`AppUserDetailsService` maps it to `ROLE_ADMIN`). When admin management is needed, replace this note with the chosen mechanism (config-driven bootstrap or an admin-only role endpoint).

- **Access denied** splits by path: `/api/**` gets a JSON `ApiResponse.error("E1014", …)`; anything else redirects to `/error/403`.
- **CORS** comes from [CorsConfig](svcs/core/src/main/java/com/zenzmoney/core/config/CorsConfig.java).
- **Vestigial:** `/stripe/webhook` is permitted in `SecurityConfig` and skipped by the CSP filter, but there is no Stripe code in this repo. Don't build on it; remove it or implement it deliberately.

### CSP

[CspNonceFilter](svcs/core/src/main/java/com/zenzmoney/core/web/filter/CspNonceFilter.java) puts a fresh 16-byte nonce on every HTML response (`script-src 'nonce-…' 'strict-dynamic'`) and skips `/api/`, `/static/`, `/stripe/`. [CspModelAdvice](svcs/core/src/main/java/com/zenzmoney/core/web/advice/CspModelAdvice.java) exposes it as the `cspNonce` model attribute.

**Every `<script>` in a Thymeleaf template must carry `th:attr="nonce=${cspNonce}"`** — without it the browser silently blocks the script. Inline styles are currently allowed (`style-src 'unsafe-inline'`); inline scripts are not.

### Routes

**API** — [AuthController](svcs/core/src/main/java/com/zenzmoney/core/web/controller/AuthController.java), [MeController](svcs/core/src/main/java/com/zenzmoney/core/web/controller/MeController.java), all under `/api/v1`:

| Method + path | Auth | Purpose |
|---|---|---|
| `POST /register` | public | Register; issues email-verification OTP |
| `POST /verify-email` | public | Verify OTP → tokens |
| `POST /authenticate` | public | Email/password login |
| `POST /authenticate/{google,apple,facebook}` | public | OAuth login |
| `POST /forgot-password` | public | Issue reset OTP |
| `POST /reset-password` | public | OTP + new password → tokens |
| `POST /refresh-token` | refresh token in `Authorization` | New access token |
| `GET /me` | `USER`/`ADMIN` | Current user |
| `GET /admin/ping` | `ADMIN` | Admin smoke check |

**Pages** — `/` (`home`), `/dashboard` (`USER`/`ADMIN`), `/admin` (`ADMIN`), `/error/{403,404,500}`, `POST /logout` → `/?logout=true`. `/login` and `/register` page mappings are **commented out** in [MarketingPageController](svcs/core/src/main/java/com/zenzmoney/core/web/controller/MarketingPageController.java) even though the templates exist — the header links to them and currently 404s.

## Database & Migrations

Schema is managed by **Flyway**: [svcs/core/src/main/resources/db/migration/](svcs/core/src/main/resources/db/migration/), run automatically at startup, `baseline-on-migrate=true`.

- `V1__auth_schema.sql` — `app_user`, `user_roles`, `verification`
- `V2__finance_schema.sql` — `account`, `category`, `transaction`, `budget`, `recurring_transaction`, `savings_goal`, `goal_contribution` (each with a `user_id` index)

**New schema goes in a new `V<n>__<name>.sql`.** Never edit an applied migration — Flyway fails on a checksum mismatch, and the only fix on a shared DB is a corrective migration. A migration must be **idempotent-safe to review**: state what it does to existing rows, not just to the shape.

> **Gotcha — Hibernate and Flyway both manage the schema right now.** `spring.jpa.hibernate.ddl-auto=update` is set in [application.properties](svcs/core/src/main/resources/application.properties) alongside Flyway. Hibernate will silently add a column for a new entity field that **no migration records**, so it works locally and is missing in prd (where the same setting then mutates the production schema unreviewed). Treat **Flyway as the only source of truth**: write the migration, and don't rely on `ddl-auto` having done it. Tests use `create-drop` deliberately.

## Building

Run Maven from the **repo root** so the reactor resolves `common` → `core` in order — no `mvn install` of `common` needed:

```bash
# fast iterative build (loc is the default Maven profile)
mvn package -pl svcs/core -am -DskipTests

# full install
mvn clean install -Dmaven.test.skip=true

# a specific profile
mvn clean install -P dev -Dmaven.test.skip=true
```

`package` is enough for local development and running. Prefer it (without `clean`) for incremental builds.

> **Why not `cd svcs/core && mvn package`?** From inside a module, Maven resolves `zenzmoney-common` from `~/.m2` — so an edit under `svcs/common/` compiles against a **stale jar** and produces confusing failures. Always use `-pl svcs/core -am` from the root.

> **Switching profiles requires a `clean`.** Each Maven profile adds only its own `src/main/profile/<env>/resources/` as a resource root, so building `dev` over a `loc` `target/` leaves the previous `application-loc.properties` in `target/classes` and both land on the classpath. Same profile → incremental `mvn package`; changing profile → `mvn clean install -P<new>` once.

**Profiles:** `loc` (default — hard-coded local Postgres `5454` / Redis `6363`), `dev`, `prd` (both env-var driven). `spring.profiles.default=loc`. Maven profile name == Spring profile name; at runtime you can also override with `--spring.profiles.active=dev`.

## Running

```bash
docker compose up -d                      # Postgres + Redis first
mvn -pl svcs/core spring-boot:run         # → http://localhost:8080
```

Or run `com.zenzmoney.core.CoreApplication` from the IDE. Restarting locally is always fine; **deploying is not** — see *Deployment*.

## Running Tests

```bash
mvn test -pl svcs/core -am                       # all tests
mvn test -pl svcs/core -am -Dtest=MyTestClass    # one class
mvn test -pl svcs/core -am -Dtest=MyTestClass#myMethod
```

The test tier today is thin — [CoreApplicationTest](svcs/core/src/test/java/com/zenzmoney/core/CoreApplicationTest.java) (context load, `@ActiveProfiles("test")`) and `DecryptorTest`. [application-test.properties](svcs/core/src/test/resources/application-test.properties) points at the **local Postgres on 5454 with `ddl-auto=create-drop`** and Redis on **6379** (not the compose port `6363`) — so `@SpringBootTest` needs the containers up, and a Redis-touching test won't connect until that port is reconciled. Testcontainers (`postgresql`, `junit-jupiter`) is on the test classpath but **unused** — prefer it for new persistence tests over depending on a developer's local DB.

## Local Database Setup (Docker)

The root [docker-compose.yml](docker-compose.yml) matches `application-loc.properties` out of the box:

```bash
docker compose up -d        # postgres-zenzmoney :5454, redis-zenzmoney :6363
docker compose ps
docker compose down         # keep volumes
docker compose down -v      # wipe the DB (the fix for a Flyway checksum mismatch locally)
```

Verify: `psql -h localhost -p 5454 -U zenzmoney -W` (password `zenzmoney`), `redis-cli -h localhost -p 6363 ping`.

## Checking Logs

**Read the logs and the DB before theorising — runtime evidence ranks with the code as a source of truth.** When something misbehaves, find the failing request in the log and inspect the DB state *first*, rather than reasoning from a hypothesis about what should happen. The same discipline verifies a fix: after the change, read the log and confirm the expected lines appear and the unwanted ones are gone.

[logback-spring.xml](svcs/core/src/main/resources/logback-spring.xml) is profile-split:

- **`loc`** — console only, root `INFO`; `com.zenzmoney` at `DEBUG` and Spring Security at `DEBUG` via `application-loc.properties`. There is **no log file locally** — capture the console output.
- **`dev` / `prd`** — rolling files at `${DEPLOYMENT_DIRECTORY:-.}/logs/`: `debug.log` (all levels — a superset) and `error.log` (ERROR only). Start with `debug.log`. In the container that resolves to `/app/logs`.

```bash
docker compose -f docker-compose.prod.yml logs -f app     # on the server
```

`DEPLOYMENT_DIRECTORY` is host-specific — read it from the environment, never hard-code a path.

## Deployment

Deployment is a **Docker image** ([Dockerfile](Dockerfile) — multi-stage, builds with `-P prd`, runs as non-root `appuser` on a JRE) orchestrated by [docker-compose.prod.yml](docker-compose.prod.yml): app + Postgres + Redis on one internal bridge network, with **only `8080` published**. Postgres/Redis bind to `127.0.0.1` — reachable on the VM or over an SSH tunnel, never from the internet. Do not open `5454`/`6363` in the VM firewall.

The full runbook (VM creation, firewall's two layers, secrets, day-2 ops, backup, HTTPS, and the Neon/Koyeb fallback path) is [DEPLOYMENT.md](DEPLOYMENT.md). Secrets come from `.env` on the server (`.env.example` is the template; `.env` is gitignored and must stay that way).

**Never deploy unless explicitly asked.** Building a `prd` profile or the Docker image locally is fine; pushing it to a server is not. `SPRING_PROFILES_ACTIVE` must be `dev`/`prd` in the cloud — a `loc` container would point at a nonexistent localhost DB.

## Development Workflow

### Ways of working

1. **Source of truth is the repo.** Learn from the source before you act — read the code, git history, this `CLAUDE.md`, and `docs/`, and trust them over memory or assumption; when the repo contradicts what you expected, the repo wins and you verify against it rather than guess. It's also where durable knowledge goes back: capture the non-obvious *why* (not what the code already states) and keep it lean. Use `docs/` to record planned work and hand a plan off; delete it once the work ships and the code carries the truth.
2. **Consistency, symmetry, and orthogonality.** *Consistency* — naming and layout (packages, class names, config keys, `V<n>__` migrations, feature IDs) follow the established convention, not per-author taste. *Symmetry* — parallel things keep parallel shape: a new entity/repository/service mirrors the existing ones; the three profile property files stay in lockstep. *Orthogonality* — one logical change touches one place. A new case that doesn't match the shape of its siblings broke symmetry; one change that forces edits in many places broke orthogonality.
3. **Favour simplicity.** Reach for the simplest design that meets the requirement; cut accidental complexity and don't add moving parts before a real need forces them. Prefer a little duplication over the wrong abstraction — let the copies show you the real seam before extracting it.
4. **Tackle complexity with layering.** Keep responsibilities in their layer (Controller → Service → Repository → Entity) and don't let logic leak: no business rules in controllers, no HTTP concepts in services, no queries in controllers. Push cross-cutting machinery to the edge so the core stays pure — validate at the seam (bean validation on DTOs), demarcate the transaction on the service, and let exceptions propagate to be translated **once** in `GlobalExceptionHandler`.
5. **Write clean, readable, secure code.** Small single-purpose units, honest names, and the *why* in a comment where the code can't carry it (never the *what*). Security-first: never trust the client, enforce server-side, fail closed, rate-limit abusable actions yourself.
6. **Make it robust.** Design for failure and concurrency deliberately — timeouts, bounded pools, optimistic-lock conflicts handled, no slow work inside a DB transaction. Keep swappable components behind a seam (the `EmailSender` interface, the rate limiter) so a component can be traded up without a rewrite. Avoid one-way doors.
7. **Verify with tests.** Every change ships with a test in the right tier (below). Reproduce a bug with a failing test before fixing it — the red test proves the fix and guards the regression. Push past the happy path: money arithmetic, boundary dates, concurrent edits, and the **API called directly** rather than only through the UI.

### Standing rules

- **Money never touches a float.** Minor-unit `long` end to end — request DTO, entity, aggregate, response. A `double` or `BigDecimal` amount in a diff is a defect.
- **Scope every query by the authenticated user.** A finder without `user_id` in a request path is an authorization bug even when the UI would never send another user's id.
- **Every new endpoint gets `@RolesAllowed`** (or is a deliberate, reviewed addition to `PUBLIC_PATHS`). URL rules are permissive by design; the annotation is the control.
- **Rate-limit every user-triggered action that sends email or costs money/compute** — OTP issuance, invites, password reset, and later the AI/OCR paths. Use [RedisRateLimitService](svcs/core/src/main/java/com/zenzmoney/core/service/ratelimit/RedisRateLimitService.java) with `tryConsumeOrDeny` (fail **closed**) for abuse-sensitive paths and `tryConsume` (fail **open**) only where availability genuinely beats throttling. Never lean on a provider's cap as the guardrail.
- **Bind identity server-side across every step of a multi-step flow.** The OTP is issued and verified per `(email, purpose)`, and the reset resolves the user from that same email — so a client can't validate one account's code and reset another's. Preserve that property in any change to registration, verification, or reset.
- **Flyway is the only schema authority** (see the `ddl-auto` gotcha above).
- **Secrets stay out of git.** `.env` is gitignored (`.env.example` is the tracked template). Never commit a real key, and never print one into logs. **Known gap:** [.gitignore](.gitignore) is missing a trailing newline on its `playwright-report/` line, so that pattern and the next one are concatenated into `playwright-report/svcs/core/src/main/resources/google.properties` — meaning **neither** is actually ignored, and `svcs/core/src/main/resources/google.properties` is tracked. It currently holds placeholders only; putting real Google credentials in it would commit them. Fix the newline and `git rm --cached` the file before it carries anything real.
- **New dependencies carrying CVEs are unwelcome on `main`** even when not currently reachable.

### Test discipline

**Every change merges with at least one test in the appropriate tier.** The tier follows what the change *does*, not where the files live; a change crossing tiers needs a test in each.

| Change type | Test | Where |
|---|---|---|
| Service logic, validator, controller, mapper, money/date arithmetic | JUnit unit test (mock the repository) | `svcs/core/src/test/java/...` |
| Repository query, derived finder, QueryDSL predicate, migration | Persistence test against a real Postgres — **prefer Testcontainers** (already on the classpath) over the developer's local DB | `svcs/core/src/test/java/...` |
| Security: role gating, `PUBLIC_PATHS`, token type, ownership scoping | `@SpringBootTest` + `spring-security-test` (`MockMvc`), asserting the **API directly** | `svcs/core/src/test/java/...` |
| Shared type in `common` (enum, `ApiResponse`, exception, `Decryptor`) | JUnit unit test | `svcs/common/src/test/java/...` |
| Thymeleaf template / static asset | Controller test asserting the view + model, and a manual check that scripts carry the CSP nonce | `svcs/core/src/test/java/...` |

**Rare exceptions** — documentation-only edits (`CLAUDE.md`, `README.md`, `docs/`, javadoc), and generated artifacts. Everything else gets a test; if you believe you've found another exception, state it explicitly as `n/a — <reason>` so it can be pushed back on.

## Security Posture

This codebase **ports its rate limiter directly** ([RedisRateLimitService](svcs/core/src/main/java/com/zenzmoney/core/service/ratelimit/RedisRateLimitService.java)) from a predecessor system, and inherits the lessons from that system's real production incidents — the flows here are the same shape, so the failures would be too. Treat **auth, email, sharing, and uploads** as first-class security surfaces.

- **Mass-email abuse.** An attacker triggered 500k+ emails through a user-facing send; only the mail provider's daily cap limited actual delivery — an accidental backstop, not a control. **Lesson:** every user-triggered send needs a real per-user, per-window limit. Here that is the OTP policy in [OtpService](svcs/core/src/main/java/com/zenzmoney/core/service/OtpService.java) — **3 per 10 min AND 5 per hour AND 10 per day**, keyed `otp:<email>`, **fail-closed**. Any new send path (invites, reminders, notifications F-1.20) needs its own policy before it ships.
- **Account takeover via password reset.** The OTP was validated, but at the final step the attacker swapped in a *different* victim's email and reset that account — exploited by calling the API directly, bypassing the UI. **Lesson:** bind identity server-side across the whole flow, and always test the API directly. The current flow binds code to `(email, purpose)`; keep it that way.
- **Crawler/botnet DoS.** A traffic surge took the edge down. **Lesson:** cap and rate-limit at the edge too, not only in the app — relevant when HTTPS/reverse proxy lands per [DEPLOYMENT.md §A.9](DEPLOYMENT.md).

Rate-limiter semantics worth knowing before you use it: fixed windows mean up to ~2× a window's limit can pass across a boundary (acceptable for abuse throttling, not for hard quotas), and all of a policy's windows are checked and consumed **atomically in one Lua script** — a denial in one window never burns a token in another.

Additional current posture: BCrypt password hashing, JWT type checking (a refresh token can't act as an access token), OTP superseded on reissue with attempt counting, `forgotPassword` not disclosing whether an account exists, per-request CSP nonce, and DB/Redis unexposed in production.

## Commit & Branch Policy

Default branch is **`main`** (`origin` = `vithursi31/ZenZMoneyManager`). History to date is direct commits on `main` by a single author, with no merge commits.

- **Don't commit or push unless asked.** Leave the working tree for the developer to inspect; when a commit is requested and you're on `main`, **branch first**.
- **Branch naming:** descriptive kebab-case matching what the change does, and reference the feature ID where one applies — `add-account-crud-f-1-1`, `fix-otp-rate-limit-window`, `flyway-migration-for-payee`. The branch is throwaway; its name exists to make `git log --oneline main` readable later.
- **Commit messages:** short imperative summary, ideally under ~70 chars, saying what the change does — `Add deployment tooling for Oracle Cloud (Docker + compose + guide)` over `updated signup method`. Body for the *why* when it isn't obvious.
- **Prefer merge over rebase** when a branch falls behind `main`. Rebase rewrites shas, breaks a reviewer's local checkout, and produces a history shape nobody asked for. Use it only when explicitly requested.
- **History-rewriting operations** — force-push, amend on a pushed commit, rewriting a shared branch — need explicit authorisation. Prefer a new commit over amending.

## Release Process

There is no release tooling yet and **no tags in this repo**. The pom stays at `0.0.1-SNAPSHOT` — do not bump it, and do not suggest `mvn release:prepare`/`release:perform`.

When a release process is needed, the intended shape is **date-based git tags** — `YYYY-MM-DD-rN`, e.g. `2026-07-27-r1`, same-day hotfix `-r2` — because they sort naturally and read as dates rather than pretending to be semantic versions.

Pre-release verification is the local test suite: the `prd` build skips tests, so `mvn test -pl svcs/core -am` on `loc` is what actually gates a release. Then build (`mvn clean install -P prd -Dmaven.test.skip=true`) and deploy per [DEPLOYMENT.md](DEPLOYMENT.md) — **only when explicitly asked**.
