# API curl scripts

Runnable `curl` scripts for every REST endpoint, one script per operation. Handy
for manual testing and as living request/response examples.

## Setup

```bash
cd docs/api
cp .env.local.example .env.local     # then edit: HOST, API_EMAIL, API_PASSWORD
```

`.env.local` is gitignored (`.env.local.example` is the tracked template). Run the
scripts **from inside `docs/api/`** so the `source` lines resolve.

## How auth works

- **Public scripts** (`api-register.sh`, `api-authenticate.sh`, `api-verify-email.sh`,
  `api-forgot-password.sh`, `api-reset-password.sh`, `api-authenticate-{google,apple,facebook}.sh`)
  need no token — they `source load-env.sh` for `$HOST`.
- **Protected scripts** `source define-envars.sh`, which logs in with
  `API_EMAIL`/`API_PASSWORD` and exports `$ACCESS_TOKEN` + `$REFRESH_TOKEN`
  automatically — so you never paste a token. Pin `ACCESS_TOKEN` in `.env.local`
  to skip the auto-login.

Every call uses `-kv` (verbose) so you see the status line and headers. Responses
come in the `ApiResponse` envelope: `{status, data, message, errorCode}`.

## Scripts

| Area | Script | Endpoint |
|---|---|---|
| Auth | `api-register.sh` | `POST /api/v1/register` |
| Auth | `api-verify-email.sh` | `POST /api/v1/verify-email` |
| Auth | `api-authenticate.sh` | `POST /api/v1/authenticate` |
| Auth | `api-authenticate-google.sh` | `POST /api/v1/authenticate/google` |
| Auth | `api-authenticate-apple.sh` | `POST /api/v1/authenticate/apple` |
| Auth | `api-authenticate-facebook.sh` | `POST /api/v1/authenticate/facebook` |
| Auth | `api-forgot-password.sh` | `POST /api/v1/forgot-password` |
| Auth | `api-reset-password.sh` | `POST /api/v1/reset-password` |
| Auth | `api-refresh-token.sh` | `POST /api/v1/refresh-token` |
| User | `api-me.sh` | `GET /api/v1/me` |
| User | `api-admin-ping.sh` | `GET /api/v1/admin/ping` (ADMIN) |
| Accounts | `api-account-create.sh` | `POST /api/v1/accounts` |
| Accounts | `api-account-list.sh` | `GET /api/v1/accounts` |
| Accounts | `api-account-get.sh` | `GET /api/v1/accounts/{id}` |
| Accounts | `api-account-update.sh` | `PUT /api/v1/accounts/{id}` |
| Accounts | `api-account-archive.sh` | `POST /api/v1/accounts/{id}/archive` |
| Accounts | `api-account-delete.sh` | `DELETE /api/v1/accounts/{id}` |

The `{id}` account scripts take the id as arg 1 or from `ACCOUNT_ID`:
`./api-account-get.sh <accountId>`.

## Typical first run

```bash
./api-register.sh                    # → OTP logged to app console (SMTP unset)
./api-verify-email.sh                # paste the code → returns tokens, account active
./api-me.sh                          # auto-logs in, shows the current user
./api-account-create.sh             # create a BANK account ($5000 = 500000 minor units)
./api-account-list.sh                # see it
```

> **Money is integer minor units** — `openingBalance: 500000` means $5000.00.
