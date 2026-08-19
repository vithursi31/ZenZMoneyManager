# Production release guide — ZenZ Money Manager

How a change on `main` becomes the running production app, and how to get back if it goes wrong.

**Scope.** This is the *release process* — gates, versioning, build, deploy, verify, roll back.
[DEPLOYMENT.md](../DEPLOYMENT.md) is the *infrastructure* runbook (creating the VM, firewall layers,
installing Docker, HTTPS). Do that once; do this every release.

> **Never deploy unless explicitly asked.** Building the `prd` profile or the Docker image locally is
> normal work. Pushing it to a server is not, and neither is running any command in
> §5–§7 below. Building is reversible; deploying is outward-facing.

---

## 1. What a release is here

| | |
|---|---|
| Cadence | On demand. No schedule, no release train. |
| Topology | One always-on VM, one container per service, `docker-compose.prod.yml`. **No blue/green, no staging environment.** |
| Downtime | Yes — a deploy restarts the app container. Expect roughly 1–3 minutes of 502s while it rebuilds and boots. |
| Build location | **On the VM.** `up -d --build` runs the Maven build inside the Docker build stage. |
| Automation | None. No CI, no release scripts, no image registry. Every step below is manual. |
| Deployed today | Nothing — the Oracle Cloud VM was never provisioned (free-tier capacity). The first release will also be the first deploy, so §8 applies. |

Because there is no staging environment, **`loc` is the only place a release is exercised before
production.** That makes the pre-flight gates in §3 the entire safety net, not a formality.

---

## 2. Release identity

There is no release tooling and **no tags in this repo yet**. The intended convention:

```
YYYY-MM-DD-rN          # 2026-07-29-r1 — first release of that day
                       # 2026-07-29-r2 — same-day hotfix
```

Date-based, because they sort naturally and read as dates instead of pretending to carry semantic
meaning about compatibility.

- **The pom stays at `0.0.1-SNAPSHOT`.** Do not bump it; do not run `mvn release:prepare` or
  `release:perform`. The git tag is the release identity, the pom version is not.
- The jar is always `zenzmoney-core-0.0.1-SNAPSHOT.jar` and the image is always
  `zenzmoney-core:latest`. **Both are overwritten on every build** — see §7 for what that costs you
  at rollback time and how to avoid paying it.

Tag the commit you are releasing, on `main`:

```bash
git tag -a 2026-07-29-r1 -m "Release 2026-07-29-r1"
git push origin 2026-07-29-r1
```

Tag *before* deploying, so the thing running in production has a name you can return to.

---

## 3. Pre-flight gates

All of these must pass. They are ordered cheapest-first.

### 3.1 Tests — the only real gate

**The `prd` image build skips tests** (`Dockerfile`: `mvn … -DskipTests -P prd`). Nothing verifies
this code after you leave your machine. So:

```bash
mvn test -pl svcs/core -am
```

> **This wipes your local database.** The `@SpringBootTest` classes run against the *same* local
> Postgres as `loc` (`application-test.properties` → `localhost:5454/zenzmoney`) with
> `ddl-auto=create-drop`. Hibernate drops every application table at the end but does **not** touch
> `flyway_schema_history`, so the next boot reports "Schema public is up to date" and starts with no
> tables. Recover with `TRUNCATE flyway_schema_history` via psql on 5454 then boot once, or
> `docker compose down -v && docker compose up -d`. Either way you re-seed your local test user.
>
> Run the full suite deliberately, at a point where losing local data is fine. To check only the unit
> tiers without touching the DB:
> `mvn test -pl svcs/core -am -Dtest='!CoreApplicationTest,!ChatControllerSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false`

### 3.2 Migration review

```bash
git diff --stat <last-release-tag>..HEAD -- svcs/core/src/main/resources/db/migration/
```

Applied migrations today: `V1__auth_schema.sql`, `V2__finance_schema.sql`, `V3__chat_ingestion.sql`.

- **Never edit an applied migration.** Flyway fails the boot on a checksum mismatch, and on a
  server with real data the only fix is a corrective `V<n+1>` migration. Locally you can
  `docker compose down -v`; in production you cannot.
- For each new migration, state **what it does to existing rows**, not just to the shape. A
  `NOT NULL` column added to a populated table needs a default or a backfill, or the migration fails
  mid-deploy and leaves the app down.
- **A release containing a migration is not rollback-safe.** See §7.

### 3.3 Secrets audit on the server

Check the live `.env` against [.env.example](../.env.example) — a key added to the template since the
last release is missing from the server's `.env`, and most of them fail *open* to a default.

**`JWT_SECRET` is the dangerous one.** [application.properties](../svcs/core/src/main/resources/application.properties)
falls back to a hard-coded literal:

```
zenzmoney.jwt.secret=${JWT_SECRET:default-secret-key-change-in-production-must-be-at-least-256-bits-long}
```

If `JWT_SECRET` is unset the app **boots normally** and signs tokens with a value that is in this
public repo — anyone can forge an access token for any account. There is no startup check for this.
Verify explicitly, on the VM:

```bash
grep -c '^JWT_SECRET=change-me\|^JWT_SECRET=$' .env    # must print 0
docker compose -f docker-compose.prod.yml exec app printenv JWT_SECRET | head -c 12
```

Also confirm:
- `POSTGRES_PASSWORD` is not `change-me-strong-db-password`.
- `APP_BASE_URL` is the real public URL. It is embedded in verification and password-reset emails — a
  stale value sends users to a dead link, and the OTP is single-use.
- `SPRING_PROFILES_ACTIVE` is `prd` (or `dev`). **A `loc` container points at a localhost DB that
  does not exist in the container** and will fail to start.
- `.env` is still gitignored and untracked: `git check-ignore -v .env`.

### 3.4 Log directory ownership (first deploy, and after any `logs/` recreation)

`docker-compose.prod.yml` bind-mounts `./logs:/app/logs`. A bind mount keeps the **host**
directory's ownership and overrides the image's `chown`, while the container runs as non-root UID
1001. Left root-owned, Logback cannot open its files and **the app starts with file logging silently
dead** — it comes up healthy, which is exactly what makes this easy to miss.

```bash
mkdir -p logs && sudo chown -R 1001:1001 logs
```

Details and the retention table: [CLAUDE.md](../CLAUDE.md) §Checking Logs.

### 3.5 Database backup — mandatory when the release carries a migration

```bash
docker exec postgres-zenzmoney \
  sh -c 'PGPASSWORD=$POSTGRESQL_PASSWORD pg_dump -U $POSTGRESQL_USERNAME $POSTGRESQL_DATABASE' \
  > backup-$(date +%F-%H%M).sql
ls -lh backup-*.sql        # confirm it is not 0 bytes before proceeding
```

This dump is the **only** way back from a bad migration. Take it even for a code-only release; it is
cheap and the one time you skip it will be the time you need it.

### 3.6 Dependencies

New dependencies carrying CVEs are unwelcome on `main` even when not currently reachable. If the diff
touches a `pom.xml`, review what came in transitively.

---

## 4. Build

Verify the `prd` build locally before asking the VM to do it — a compile failure found on your
machine costs seconds, the same failure found mid-deploy costs downtime.

```bash
mvn clean install -P prd -Dmaven.test.skip=true
```

> **Switching Maven profiles requires `clean`.** Each profile contributes only its own
> `src/main/profile/<env>/resources/`, so building `prd` over a `loc` `target/` leaves
> `application-loc.properties` in `target/classes` and **both** land on the classpath. Same profile →
> incremental `mvn package`; changed profile → `mvn clean install -P<new>` once.

Optionally verify the image builds:

```bash
docker build -t zenzmoney-core:test .
```

---

## 5. Deploy

On the VM:

```bash
cd <deployment-dir>
git fetch --tags
git checkout 2026-07-29-r1          # the tag from §2, not "main" — deploy a named thing

# Keep the currently-running image so §7 can roll back without a rebuild.
docker tag zenzmoney-core:latest zenzmoney-core:previous

docker compose -f docker-compose.prod.yml up -d --build
```

First build takes several minutes (Maven runs inside the image build, then Postgres/Redis pull).
Watch it come up:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

Wait for `Started CoreApplication`. Flyway runs pending migrations automatically at boot, before the
web layer accepts traffic.

> **Hibernate also mutates the schema.** `spring.jpa.hibernate.ddl-auto=update` is set in
> `application.properties` and applies in `prd` alongside Flyway. It will silently add a column for a
> new entity field that **no migration records** — so a field can work in production having never
> been reviewed as SQL, and the schema drifts away from what the migrations describe. Treat Flyway as
> the only authority: write the migration and never rely on `ddl-auto` having done it. Removing this
> setting is tracked as an open gap in §9.

---

## 6. Verify

Do not call a release done because the container is up. Check the three layers.

### 6.1 It responds

```bash
curl -i http://localhost:8080/                       # on the VM
curl -i http://<public-host>:8080/                   # from outside — proves the firewall too
```

### 6.2 The auth flow works end to end

Called directly against the API, not through a browser — that is where an authorization bug hides.
The [docs/api/](api/) scripts do this with a `.env.local` pointed at the production host:

```bash
cd docs/api && ./api-register.sh && ./api-verify-email.sh && ./api-me.sh
```

Or by hand — note the contract is `email` / `password` on `POST /api/v1/register`:

```bash
curl -s -X POST http://<public-host>:8080/api/v1/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"release-check@example.com","password":"Passw0rd!"}'
```

Expect the `ApiResponse` envelope: `{status, data, message, errorCode}`.

Then confirm authorization is actually closed, which a happy-path test never shows:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://<public-host>:8080/api/v1/me          # 403
curl -s -o /dev/null -w '%{http_code}\n' http://<public-host>:8080/api/v1/admin/ping  # 403
```

Both must refuse an anonymous caller. URL rules are `permitAll()` by design here — `@RolesAllowed`
on the handler is the only control — so a `200` from either means a method-security regression, which
is the highest-severity thing this checklist can catch.

### 6.3 The logs are clean

```bash
docker compose -f docker-compose.prod.yml logs --tail=100 app   # console is attached in prd
tail -50 logs/error.log                                          # must not be growing
grep -c . logs/audit.log                                         # your smoke registration/login
```

`debug.log` is the superset — start there. `audit.log` carries the security events and is **not**
duplicated into `debug.log`. Every line carries a correlation id; grep it across files to follow one
request. See [CLAUDE.md](../CLAUDE.md) §Checking Logs.

Confirm the OTP code did **not** land in a log file:

```bash
grep -c 'DEV FALLBACK' logs/*.log     # must be 0 in prd
```

---

## 7. Rollback

Decide fast: an app that boots but misbehaves is worse than one release older.

### Code-only release — clean rollback

```bash
cd <deployment-dir>
git checkout <previous-tag>

# If you tagged the old image in §5, no rebuild needed:
docker tag zenzmoney-core:previous zenzmoney-core:latest
docker compose -f docker-compose.prod.yml up -d --no-build

# Otherwise rebuild from the previous tag (several minutes):
docker compose -f docker-compose.prod.yml up -d --build
```

Data volumes survive both `down` and `up --build`, so the database is untouched. **`docker compose
down -v` destroys the database** — never reach for it in production.

### Release containing a migration — no clean rollback

**Flyway is forward-only here.** There are no `undo` scripts (a paid Flyway feature) and the older
jar does not know how to run against the newer schema. Two real options:

1. **Roll forward.** Write a corrective `V<n+1>__…sql` that restores the behaviour, release again.
   This is almost always the right answer and the only one that keeps the migration history honest.
2. **Restore the §3.5 dump.** Genuine data loss for everything written since the dump, and only
   justified when the migration corrupted data. Stop the app first so nothing writes during the
   restore:

```bash
docker compose -f docker-compose.prod.yml stop app
cat backup-<stamp>.sql | docker exec -i postgres-zenzmoney \
  sh -c 'PGPASSWORD=$POSTGRESQL_PASSWORD psql -U $POSTGRESQL_USERNAME $POSTGRESQL_DATABASE'
git checkout <previous-tag>
docker compose -f docker-compose.prod.yml up -d --build
```

This is why §3.2 asks what a migration does to existing rows. The review is the mitigation; the
restore is the admission that it failed.

---

## 8. First release only

One-time steps that do not repeat:

1. **Provision the VM and network** per [DEPLOYMENT.md](../DEPLOYMENT.md) §A.1–A.4. Currently blocked
   on Oracle Always Free capacity for `VM.Standard.A1.Flex`; the fallbacks are the AMD
   `VM.Standard.E2.1.Micro` (needs a 1 GB memory re-tune first) or Path B (Koyeb + Neon).
2. **Open port 8080 in both layers** — the cloud VCN Security List *and* the VM's own iptables.
   Missing either produces the same symptom: a hang from outside, `200` from on the VM. Leave
   5454/6363 closed to the internet.
3. **`cp .env.example .env`** and fill it (§3.3).
4. **`mkdir -p logs && sudo chown -R 1001:1001 logs`** (§3.4).
5. **Designate an admin.** No code path grants `ADMIN` — not registration, not OAuth, and there is no
   promotion endpoint or migration seed. `ADMIN` routes (`/api/v1/admin/**`, the `/admin` page) are
   unreachable until you insert the row by hand:
   ```sql
   INSERT INTO user_roles (user_id, role) VALUES ('<user-id>', 'ADMIN');
   ```
   The role loads into the JWT principal on that user's **next login**.
6. **Decide on HTTPS.** OAuth providers require HTTPS redirect URLs, so Google/Apple/Facebook
   sign-in will not work on plain `http://<ip>:8080`. [DEPLOYMENT.md](../DEPLOYMENT.md) §A.9 covers
   putting Caddy in front for automatic Let's Encrypt certificates. Doing this changes
   `APP_BASE_URL` and every provider's configured redirect URL.
7. **Set up off-host log archiving**, or accept the retention windows as the history limit — there is
   no destination configured today ([CLAUDE.md](../CLAUDE.md) §Checking Logs).

---

## 9. Known gaps that affect releases

Honest list of things that make a release riskier than it needs to be. None are blockers; all are
worth closing.

| Gap | Consequence at release time |
|---|---|
| `ddl-auto=update` active in `prd` | Hibernate can alter the production schema unreviewed, drifting it from the migrations. Removing it means trusting Flyway completely — do it once the migration set is known-complete. |
| `JWT_SECRET` has a hard-coded default | A missing env var yields a booting app signed with a public secret. Fail-fast on startup would turn a silent compromise into a crash. |
| No CI | Nothing enforces §3.1. The gate is you remembering to run it. |
| Tests share the `loc` database | Discourages running the suite, which is the only gate. Testcontainers is already on the classpath and unused. |
| Image is always `:latest` | Rollback needs a rebuild unless you remember the `docker tag … :previous` step in §5. |
| No health endpoint | Verification is `curl /` plus reading logs; there is no `/actuator/health` for compose or a proxy to probe. |
| `/stripe/webhook` is permitted but unimplemented | An open path with no handler behind it. Remove it or implement it deliberately. |
| DEPLOYMENT.md §A.7 smoke test is stale | It calls `POST /api/v1/register/begin` with a `username` field; the real endpoint is `POST /api/v1/register` with `email`/`password`. Use §6.2 above. |

---

## 10. Checklist

Copy into the release notes and tick as you go.

**Before**
- [ ] `mvn test -pl svcs/core -am` green (accepting the local DB reset)
- [ ] Migrations reviewed for effect on existing rows; no applied migration edited
- [ ] `mvn clean install -P prd -Dmaven.test.skip=true` succeeds locally
- [ ] Server `.env` matches `.env.example`; `JWT_SECRET`, `POSTGRES_PASSWORD`, `APP_BASE_URL` real
- [ ] `SPRING_PROFILES_ACTIVE=prd`
- [ ] `logs/` exists and is owned by `1001:1001`
- [ ] `pg_dump` taken and non-empty
- [ ] Release tagged `YYYY-MM-DD-rN` and pushed

**Deploy**
- [ ] Old image tagged `zenzmoney-core:previous`
- [ ] Checked out the release **tag**, not `main`
- [ ] `up -d --build`; `Started CoreApplication` in the log
- [ ] Flyway applied the expected migrations and no others

**After**
- [ ] `/` responds from outside the VM
- [ ] register → verify-email → `/me` works against the API directly
- [ ] `/api/v1/me` and `/api/v1/admin/ping` both `403` anonymously
- [ ] `error.log` not growing; `audit.log` shows the smoke events
- [ ] `grep -c 'DEV FALLBACK' logs/*.log` is 0
- [ ] Rollback path confirmed available (previous tag exists, dump retained)
