# ZenZHabits Build Guide

Please note that this project requires a 64 bit Ubuntu Linux machine to build the project.

This project is a Spring Boot 3.4.4 multi-module Maven build (`svcs/common`, `svcs/core`) on Java 17, backed by PostgreSQL 14 + Redis 7.

---

## Quick Start (TL;DR)

For someone who already has Java 17, Maven 3.8+, and Docker installed:

```bash
git clone <repo-url> zenzhabits
cd zenzhabits

# 1. Start Postgres + Redis
docker compose up -d

# 2. Build
mvn clean install -Dmaven.test.skip=true

# 3. Run
mvn -pl svcs/core spring-boot:run

# App is at http://localhost:8080
```

If you don't have the toolchain yet, follow the full setup below.

---

## Required third party Software

 Following Tools/SDKs should be installed to build this project

 1. Ubuntu 22.04

 2. Java 17 (Zulu JDK 17)

 3. Git 2.34.x

 4. Maven 3.8.x

 5. Docker 20.10.x (with the `docker compose` plugin)

 6. Other CLI Tools (psql, redis-cli)


### 1. Install Ubuntu 22.04

 Make sure you have Ubuntu 22.04 in your dev machine.


### 2. Install Java 17

 Spring Boot 3.4.4 requires Java 17 (the parent POM sets `java.version=17`).

 Download the latest Zulu JDK 17 LTS for Linux x64 from https://www.azul.com/downloads/?package=jdk

```bash
mkdir -p ~/Installs
cp ~/Downloads/zulu17.*-ca-jdk17.*-linux_x64.tar.gz ~/Installs/
cd ~/Installs
tar -xvf zulu17.*-ca-jdk17.*-linux_x64.tar.gz
mv zulu17.*-ca-jdk17.*-linux_x64 jdk17
```


### 3. Install Git 2.34.x

 Install Git from APT:

```bash
sudo apt install git
```

 Configure Git:

```bash
git config --global user.name "<Your full name here>"
git config --global user.email "<Your email address here>"
git config --global core.eol lf
git config --global core.autocrlf input
```


### 4. Install Maven 3.8.x

 Download apache-maven-3.8.5-bin.tar.gz from https://archive.apache.org/dist/maven/maven-3/3.8.5/binaries/apache-maven-3.8.5-bin.tar.gz

```bash
mkdir -p ~/Installs
cp ~/Downloads/apache-maven-3.8.5-bin.tar.gz ~/Installs/
cd ~/Installs
tar -xvf apache-maven-3.8.5-bin.tar.gz
```


### 5. Install Docker 20.10.x

 Follow https://docs.docker.com/engine/install/ubuntu/ and install Docker (with the `docker compose` plugin — the repo uses `docker-compose.yml`).

 Add your user to the `docker` group so you don't need `sudo`:

```bash
sudo usermod -aG docker $USER
newgrp docker
```


### 6. Other CLI Tools

```bash
sudo apt install postgresql-client redis-tools
```


## Setup Bash Profile

 Add the following lines to `~/.profile` and reload it (`source ~/.profile`):

```bash
export JAVA_HOME=~/Installs/jdk17
export M3_HOME=~/Installs/apache-maven-3.8.5
export PATH=$M3_HOME/bin:$JAVA_HOME/bin:$PATH
```

 Verify:

```bash
java -version    # should report 17.x
mvn -version     # should report 3.8.x running on JDK 17
docker --version
```


## Clone the Repository

```bash
git clone <repo-url> zenzhabits
cd zenzhabits
```


## Setup Local Databases

The repo ships a `docker-compose.yml` at the root that defines both Postgres and Redis with the right names, credentials, and ports. **Use this — it matches the app config out of the box.**

### Option A — `docker compose` (recommended)

Create and start both containers:

```bash
docker compose up -d
```

This launches:

 - **postgres-zenzhabit** — `bitnami/postgresql:14.4.0` on `localhost:5434` (mapped to container's 5432), DB/user/pass = `zenzhabit`/`zenzhabit`/`zenzhabit`
 - **redis-zenzhabit** — `bitnami/redis:7.2.4` on `localhost:6379`, no password

Check they're running:

```bash
docker compose ps
```

Stop / restart / wipe:

```bash
docker compose stop           # stop containers, keep data
docker compose start          # start them again
docker compose down           # stop and remove containers (volumes survive)
docker compose down -v        # stop, remove containers AND wipe volumes (fresh DB)
```

### Option B — plain `docker create` / `docker start`

If you prefer not to use compose, create the two containers manually with matching names and credentials:

```bash
# Postgres
docker create \
  --name=postgres-zenzhabit \
  -p 5434:5432 \
  -e POSTGRESQL_USERNAME=zenzhabit \
  -e POSTGRESQL_PASSWORD=zenzhabit \
  -e POSTGRESQL_DATABASE=zenzhabit \
  bitnami/postgresql:14.4.0

docker start postgres-zenzhabit

# Redis
docker create \
  --name=redis-zenzhabit \
  -p 6379:6379 \
  -e ALLOW_EMPTY_PASSWORD=yes \
  bitnami/redis:7.2.4

docker start redis-zenzhabit
```

To recreate from scratch:

```bash
docker stop  postgres-zenzhabit redis-zenzhabit
docker rm    postgres-zenzhabit redis-zenzhabit
# then re-run the create + start commands above
```

### Connect to verify

```bash
psql -h localhost -p 5434 -U zenzhabit -W      # password: zenzhabit
redis-cli -h localhost -p 6379 ping            # should reply: PONG
```

These settings match `svcs/core/src/main/profile/loc/resources/application-loc.properties` — no app config changes needed.


## How to Build The Modules In Local Machine

 Run the following from the repo root (the first run will take a while as Maven resolves dependencies):

```bash
mvn clean install -Dmaven.test.skip=true
```

 To build a single module:

```bash
mvn -pl svcs/core -am clean install -Dmaven.test.skip=true
```

 The build should finish without any error — if you hit one, please report it.


## How to Run the Modules in Local Machine

 1. Make sure the Postgres + Redis containers are running:

    ```bash
    docker compose up -d
    ```

 2. Run the app from the command line:

    ```bash
    mvn -pl svcs/core spring-boot:run
    ```

    …or open the project in IntelliJ IDEA and run **`com.habit.core.CoreApplication`** (right-click → Run).

 3. The app listens on **http://localhost:8080**. The default Spring profile is `loc` (set in `application.properties`: `spring.profiles.default=loc`).


## Spring Profiles

This project defines three Maven profiles that map to Spring profiles of the same name. Each profile's properties live in its own directory under `svcs/core/src/main/profile/<env>/resources/` — only the active profile's file is copied onto the classpath at build time:

```
svcs/core/src/main/
├── resources/                                   # shared across all profiles
│   ├── application.properties                   # base config (always loaded)
│   ├── google.properties
│   ├── logback-spring.xml
│   ├── db/migration/                            # Flyway
│   ├── templates/, static/
└── profile/
    ├── loc/resources/application-loc.properties # local dev (default)
    ├── dev/resources/application-dev.properties # remote dev
    └── prd/resources/application-prd.properties # production
```

 - **loc** — local dev (default; reads `application-loc.properties`, hard-coded local DB/Redis)
 - **dev** — remote dev (reads `application-dev.properties`, expects `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_HOST` env vars)
 - **prd** — production (reads `application-prd.properties`)

The core `pom.xml` wires each Maven profile to add `src/main/profile/<env>/resources/` as an extra resource root, so `mvn -P dev install` produces a jar containing **only** `application-dev.properties` — never the others.

To activate a profile at build time:

```bash
mvn clean install -P dev -Dmaven.test.skip=true
```

To activate at runtime instead:

```bash
java -jar svcs/core/target/habit-core-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```


## How to Build the Modules for Remote Machine

```bash
mvn clean install -P <loc|dev|prd> -Dmaven.test.skip=true
```


## Required Environment Variables (dev / prd)

The `dev` and `prd` profiles read configuration from env vars. At minimum:

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://host:5432/zenzhabit` |
| `DATABASE_USERNAME` | Postgres user |
| `DATABASE_PASSWORD` | Postgres password |
| `REDIS_HOST` | Redis host |
| `REDIS_PORT` | Redis port (default `6379`) |
| `JWT_SECRET` | HMAC signing key — must be ≥ 256 bits |
| `APP_BASE_URL` | Public base URL, used in verification / reset-password links |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | SMTP for transactional email |
| `GOOGLE_APP_ID` / `GOOGLE_APP_SECRET` / `GOOGLE_REDIRECT_URL` / `GOOGLE_IOS_APP_ID` / `GOOGLE_ANDROID_APP_ID` | Google OAuth |
| `APPLE_CLIENT_ID` / `APPLE_CLIENT_ID_WEB` / `APPLE_TEAM_ID` / `APPLE_KEY_ID` / `APPLE_PRIVATE_KEY` | Sign in with Apple |
| `FACEBOOK_APP_ID` / `FACEBOOK_APP_SECRET` / `FACEBOOK_REDIRECT_URL` / `FACEBOOK_GRAPH_VERSION` | Facebook OAuth |

See `svcs/core/src/main/resources/application.properties` (shared base) and `svcs/core/src/main/profile/<env>/resources/application-<env>.properties` (per-environment overrides) for the full list and defaults.


## Database Migrations

Schema is managed by Flyway. Migrations live in `svcs/core/src/main/resources/db/migration/` (e.g. `V1__initial_schema.sql`, `V2__auth_schema.sql`) and run automatically on application startup.


## Module Layout

```
svcs/
├── common/          # shared domain, DTOs, exceptions (com.habit.common)
└── core/            # Spring Boot app — web, security, persistence (com.habit.core)
    ├── src/main/java/com/habit/core/CoreApplication.java       # main class
    ├── src/main/resources/                                     # shared (always on classpath)
    │   ├── application.properties                              # base config
    │   ├── google.properties, logback-spring.xml
    │   ├── db/migration/                                       # Flyway scripts
    │   └── templates/, static/
    └── src/main/profile/                                       # per-profile overrides
        ├── loc/resources/application-loc.properties
        ├── dev/resources/application-dev.properties
        └── prd/resources/application-prd.properties
```


## Troubleshooting

- **App can't connect to Postgres** — confirm the container is up (`docker ps | grep postgres-zenzhabit`) and reachable on port `5434` (`psql -h localhost -p 5434 -U zenzhabit -W`).
- **`docker compose: command not found`** — install the Docker Compose plugin: `sudo apt install docker-compose-plugin`.
- **Port already in use (5434 / 6379 / 8080)** — find the conflicting process (`sudo lsof -i :5434`) and stop it, or change the host-side port in `docker-compose.yml` (and match it in `svcs/core/src/main/profile/loc/resources/application-loc.properties`).
- **Flyway "migration checksum mismatch"** — only safe in dev: `docker compose down -v && docker compose up -d` for a clean DB, then rebuild.
