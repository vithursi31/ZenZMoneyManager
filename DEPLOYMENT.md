# Deployment & Hosting Guide — ZenZ Money Manager

This guide gets the backend running on a **free** host with **Postgres + Redis in
Docker**, exactly as intended.

## The one thing to understand first

A cloud-hosted app must reach its database **over the network**. Docker running
on your **home laptop is not reachable** from the internet (no public IP, home
router blocks inbound connections). So "host the app for free" + "run the DB in
Docker" only works if the Docker host is itself a machine with a public IP.

That leads to two viable free architectures:

| | **Path A — One free VM (recommended)** | **Path B — App host + managed DB** |
|---|---|---|
| Host | Oracle Cloud **Always Free** VM | Koyeb / Render (free web service) |
| Your Docker DB? | **Yes** — Postgres + Redis in Docker on the VM | No — a managed Neon Postgres replaces it |
| Cost | Free **forever** (not trial credit) | Free, but app **sleeps** after ~1h idle |
| Always on? | **Yes**, no cold starts | No — cold start on first request |
| Redis | Yes (in Docker) | Hard (few free managed Redis) |
| Setup effort | Higher (one-time SSH + firewall) | Lower (git push → deploy) |

**Because your priority is "free" and you want the DB in Docker, use Path A.**
Path B is documented at the end as a lighter fallback.

> **Files this guide uses** (already in the repo root): [`Dockerfile`](Dockerfile),
> [`docker-compose.prod.yml`](docker-compose.prod.yml), [`.env.example`](.env.example).

---

# Path A — Oracle Cloud Always Free VM (recommended)

You get a real Linux VM that is free for life. You install Docker on it and run
the **app + Postgres + Redis together** with `docker-compose.prod.yml`. Everything
talks over an internal Docker network; only the app's port `8080` faces the
internet.

```
Oracle Cloud VM (Ubuntu, Always Free, public IP)
└── Docker (docker-compose.prod.yml)
     ├── postgres-zenzmoney   (internal only)
     ├── redis-zenzmoney      (internal only)
     └── zenzmoney-app  ──►  postgres:5432 / redis:6379   (by service name)
                        published: 0.0.0.0:8080  ──► the internet
```

> **2026 note on resources.** Oracle's Always Free Ampere (ARM) allowance was
> reduced in June 2026 to **2 OCPU / 12 GB RAM total** for free-tier users (was
> 4/24). That is still plenty for this app + Postgres + Redis. Create an **ARM
> (Ampere A1)** instance — the `Dockerfile` and all images used here are
> multi-arch, so ARM works fine.

## A.1 Create the VM

1. Sign up at <https://www.oracle.com/cloud/free/> and choose **Always Free**.
   (Oracle may ask for a card to verify identity; Always Free resources are not
   charged. Pick a home region close to you — you cannot change it later.)
2. **Compute → Instances → Create instance.**
   - **Image:** Canonical **Ubuntu 22.04**.
   - **Shape:** Change shape → **Ampere** → `VM.Standard.A1.Flex`. Set **2 OCPU /
     12 GB** (the free max). (An AMD `E2.1.Micro` micro shape also exists but is
     too small — prefer Ampere.)
   - **SSH keys:** Upload your public key, or let Oracle generate one and
     **download the private key** — you need it to log in.
3. Create. Note the instance's **public IPv4 address**.

## A.2 Open the firewall (two layers — both matter)

Oracle blocks inbound traffic in **two** places; open port `8080` in both.

**a) Cloud-side (Security List / NSG):** VCN → your subnet's **Security List** →
**Add Ingress Rule**:
- Source CIDR `0.0.0.0/0`, IP Protocol **TCP**, Destination port range **8080**.
- (Port 22 for SSH is usually already open.)

**b) OS-side firewall on the VM** (Ubuntu images ship with strict iptables):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT
sudo netfilter-persistent save
```

Do **not** open 5432 or 6379 — the DB and Redis must stay internal.

## A.3 Install Docker on the VM

SSH in first (`ubuntu` is the default user on the Ubuntu image):

```bash
ssh -i /path/to/your-private-key ubuntu@<VM_PUBLIC_IP>
```

Then install Docker Engine + the Compose plugin:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Run docker without sudo (log out/in after this, or run `newgrp docker`):
sudo usermod -aG docker $USER
newgrp docker
docker --version
```

## A.4 Get the code onto the VM

```bash
git clone <your-repo-url> zenzmoney
cd zenzmoney
```

(If the repo is private, use a deploy key or a personal access token. No repo?
`scp -r` the project up, or `rsync -av --exclude target ./ ubuntu@<IP>:~/zenzmoney/`.)

## A.5 Configure secrets

```bash
cp .env.example .env
nano .env
```

Set at minimum:
- `POSTGRES_PASSWORD` — a strong password.
- `JWT_SECRET` — a long random string. Generate one on the VM:
  ```bash
  openssl rand -base64 64 | tr -d '\n'; echo
  ```
- `APP_BASE_URL` — `http://<VM_PUBLIC_IP>:8080` for now (or your domain later).

Leave SMTP/OAuth blank to start — the app runs fine without them (it logs
verification/reset links to the container console as a dev fallback).

## A.6 Build & run

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

First run takes a few minutes (it builds the app image, which runs the Maven
build inside the container, then pulls Postgres + Redis). Check status:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

Wait for the log line `Started CoreApplication`. Flyway auto-runs
`V1__auth_schema.sql` on first boot, creating the `app_user` / `user_roles`
tables.

## A.7 Verify it's live

From the VM:

```bash
curl -i http://localhost:8080/
```

From your laptop / browser: `http://<VM_PUBLIC_IP>:8080/`

Smoke-test the auth flow (see [README](README.md) §Endpoints):

```bash
curl -s -X POST http://<VM_PUBLIC_IP>:8080/api/v1/register/begin \
  -H 'Content-Type: application/json' \
  -d '{"username":"test@example.com","password":"Passw0rd!"}'
```

## A.8 Day-2 operations

```bash
# Update after a git push:
git pull && docker compose -f docker-compose.prod.yml up -d --build

# Logs / restart / stop:
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml restart app
docker compose -f docker-compose.prod.yml down          # stop (keeps data volumes)

# Back up the database (volumes survive `down`, but back up anyway):
docker exec postgres-zenzmoney \
  sh -c 'PGPASSWORD=$POSTGRESQL_PASSWORD pg_dump -U $POSTGRESQL_USERNAME $POSTGRESQL_DATABASE' \
  > backup-$(date +%F).sql
```

## A.9 (Optional) HTTPS + a domain

Port 8080 over HTTP is fine for testing but you'll want HTTPS for real use
(OAuth providers require HTTPS redirect URLs). Easiest: put **Caddy** in front —
it gets a free Let's Encrypt certificate automatically.

1. Point a domain's `A` record at the VM's public IP.
2. Open ports **80** and **443** (both cloud Security List *and* OS iptables, per A.2).
3. Add a `caddy` service or run Caddy on the host with a two-line `Caddyfile`:
   ```
   your-domain.com {
       reverse_proxy localhost:8080
   }
   ```
4. Update `APP_BASE_URL=https://your-domain.com` in `.env` and
   `docker compose -f docker-compose.prod.yml up -d`.

---

# Path B — Free app host + managed Postgres (fallback)

Use this if you'd rather **push-to-deploy** and skip server admin. Tradeoff: the
DB is a **managed Neon Postgres** (not your Docker DB), the app **sleeps** after
~1h idle (cold start on next hit), and **Redis is the sticking point** — free
managed Redis is scarce, so you typically disable caching.

### B.1 Database — Neon (free, always-free tier)

1. Sign up at <https://neon.tech>, create a project (Postgres 16).
2. Copy the connection string. Convert it to JDBC form for the app:
   - Neon gives: `postgresql://user:pass@ep-xxx.region.aws.neon.tech/dbname?sslmode=require`
   - App needs: `DATABASE_URL=jdbc:postgresql://ep-xxx.region.aws.neon.tech/dbname?sslmode=require`,
     `DATABASE_USERNAME=user`, `DATABASE_PASSWORD=pass`.

### B.2 App — Koyeb (free web service, Dockerfile deploy)

1. Sign up at <https://koyeb.com> and **Create Web Service → GitHub**, pick this repo.
2. Build: **Dockerfile** (the repo's `Dockerfile` is used automatically).
3. Instance: **Free**. Region: **Frankfurt** or **Washington, D.C.** (free-tier
   regions). Port: **8080**.
4. Set environment variables (from B.1 plus auth):
   `SPRING_PROFILES_ACTIVE=prd`, `DATABASE_URL`, `DATABASE_USERNAME`,
   `DATABASE_PASSWORD`, `JWT_SECRET`, `APP_BASE_URL=https://<your>.koyeb.app`.
5. Deploy. Koyeb gives you an HTTPS URL and redeploys on every git push.

> **Render** works the same way (free web service, Dockerfile, `PORT`/env vars)
> but has heavier Spring Boot cold starts on 512 MB. **Railway** is smooth but its
> free tier is a small monthly credit that runs out, so it's not truly free.

### B.3 The Redis problem on Path B

This app uses Redis for caching. On a free managed host with no free Redis, pick one:
- **Simplest:** switch the cache to in-memory so Redis isn't needed. Replace the
  Redis `cacheManager` bean in
  [`RedisConfig.java`](svcs/core/src/main/java/com/zenzmoney/core/config/RedisConfig.java)
  with a `ConcurrentMapCacheManager`, and drop `spring-boot-starter-data-redis`
  usage. (Ask and this can be wired behind a profile so local dev still uses Redis.)
- **Or:** use a free Redis-compatible tier (e.g. Upstash) and set `REDIS_HOST`/
  `REDIS_PORT` accordingly.

Path A avoids this entirely — Redis just runs in Docker.

---

# Quick reference — required environment variables

| Variable | Required? | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | `prd` (or `dev`) — both read config from env vars |
| `DATABASE_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://postgres:5432/zenzmoney` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | yes | Postgres credentials |
| `REDIS_HOST` / `REDIS_PORT` | yes (Path A) | `redis` / `6379` in compose |
| `JWT_SECRET` | yes | ≥ 64 bytes random — `openssl rand -base64 64` |
| `APP_BASE_URL` | yes | Public URL used in email links / OAuth redirects |
| `MAIL_*` | optional | Blank → links logged to console instead of emailed |
| `GOOGLE_*` / `APPLE_*` / `FACEBOOK_*` | optional | Fill only providers you enable |

Full list and defaults: [`README.md`](README.md) §Required Environment Variables,
and the per-profile files under `svcs/core/src/main/profile/`.

---

# Troubleshooting

- **Browser can't reach `http://<IP>:8080`** — almost always the firewall. Confirm
  **both** layers from A.2: Oracle Security List ingress rule *and* the OS
  `iptables` rule. Test locally on the VM first (`curl localhost:8080`) to isolate
  app-vs-network.
- **App container restarts / `Connection refused` to DB** — the app started before
  Postgres was ready. The compose file already gates on a Postgres healthcheck;
  if you changed it, ensure `depends_on: postgres: condition: service_healthy`.
- **`Started CoreApplication` never appears** — check `docker compose ... logs app`.
  Common cause: `JWT_SECRET` too short (HS512 needs ≥ 256 bits) or a bad
  `DATABASE_URL`.
- **Out of memory on the VM** — the `Dockerfile` sets `-XX:MaxRAMPercentage=75`;
  with 12 GB you're fine. On a tiny 512 MB host, lower it (e.g. `JAVA_OPTS=-Xmx350m`).
- **OAuth redirect errors** — providers require an **HTTPS** redirect URL and it
  must exactly match what's registered. Do Path A.9 (HTTPS) before enabling OAuth.
- **Flyway checksum mismatch** — only in throwaway dev: recreate the DB volume
  (`docker compose -f docker-compose.prod.yml down -v`), then bring it back up.

---

# Sources (free-tier landscape, 2026)

- [Free Hosting for Spring Boot: Best Options in 2026 — BSWEN](https://docs.bswen.com/blog/2026-02-28-springboot-free-hosting/)
- [Platforms with a real free tier for developers in 2026 — Render](https://render.com/articles/platforms-with-a-real-free-tier-for-developers-in-2026)
- [Koyeb Free Tier 2026: Pricing, Limits & Credit Card — srvrlss.io](https://www.srvrlss.io/provider/koyeb/)
- [Deploy a Spring Boot App — Koyeb docs](https://www.koyeb.com/docs/deploy/spring-boot)
- [Oracle Cloud free tier 2026: 4 OCPU/24GB cut to 2 OCPU/12GB — TerminalBytes](https://terminalbytes.com/oracle-cloud-free-tier-changes-2026/)
- [How to Set Up Docker on an Oracle Cloud Free Tier Instance — OneUptime](https://oneuptime.com/blog/post/2026-02-08-how-to-set-up-docker-on-an-oracle-cloud-free-tier-instance/view)
- [Top PostgreSQL Database Free Tiers in 2026 — Koyeb](https://www.koyeb.com/blog/top-postgresql-database-free-tiers-in-2026)
- [Neon Free Tier 2026: Limits, Pricing & What Changed — AgentDeals](https://agentdeals.dev/vendor/neon)
