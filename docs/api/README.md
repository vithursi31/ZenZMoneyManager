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
| Categories | `api-category-create.sh` | `POST /api/v1/categories` |
| Categories | `api-category-list.sh` | `GET /api/v1/categories` |
| Categories | `api-category-get.sh` | `GET /api/v1/categories/{id}` |
| Categories | `api-category-update.sh` | `PUT /api/v1/categories/{id}` |
| Categories | `api-category-delete.sh` | `DELETE /api/v1/categories/{id}` |
| Categories | `api-category-seed-defaults.sh` | `POST /api/v1/categories/seed-defaults` |
| Payees | `api-payee-create.sh` | `POST /api/v1/payees` |
| Payees | `api-payee-list.sh` | `GET /api/v1/payees` |
| Payees | `api-payee-get.sh` | `GET /api/v1/payees/{id}` |
| Payees | `api-payee-update.sh` | `PUT /api/v1/payees/{id}` |
| Payees | `api-payee-delete.sh` | `DELETE /api/v1/payees/{id}` |
| Transactions | `api-transaction-create.sh` | `POST /api/v1/transactions` |
| Transactions | `api-transaction-list.sh` | `GET /api/v1/transactions` |
| Transactions | `api-transaction-get.sh` | `GET /api/v1/transactions/{id}` |
| Transactions | `api-transaction-update.sh` | `PUT /api/v1/transactions/{id}` |
| Transactions | `api-transaction-delete.sh` | `DELETE /api/v1/transactions/{id}` |
| Budgets | `api-budget-create.sh` | `POST /api/v1/budgets` |
| Budgets | `api-budget-list.sh` | `GET /api/v1/budgets` |
| Budgets | `api-budget-get.sh` | `GET /api/v1/budgets/{id}` |
| Budgets | `api-budget-update.sh` | `PUT /api/v1/budgets/{id}` |
| Budgets | `api-budget-archive.sh` | `POST /api/v1/budgets/{id}/archive` |
| Budgets | `api-budget-delete.sh` | `DELETE /api/v1/budgets/{id}` |
| Goals | `api-goal-create.sh` | `POST /api/v1/goals` |
| Goals | `api-goal-list.sh` | `GET /api/v1/goals` |
| Goals | `api-goal-get.sh` | `GET /api/v1/goals/{id}` |
| Goals | `api-goal-update.sh` | `PUT /api/v1/goals/{id}` |
| Goals | `api-goal-archive.sh` | `POST /api/v1/goals/{id}/archive` |
| Goals | `api-goal-delete.sh` | `DELETE /api/v1/goals/{id}` |
| Goals | `api-goal-contribution-add.sh` | `POST /api/v1/goals/{id}/contributions` |
| Goals | `api-goal-contribution-list.sh` | `GET /api/v1/goals/{id}/contributions` |
| Goals | `api-goal-contribution-delete.sh` | `DELETE /api/v1/goals/{id}/contributions/{contributionId}` |
| Recurring | `api-recurring-create.sh` | `POST /api/v1/recurring` |
| Recurring | `api-recurring-list.sh` | `GET /api/v1/recurring` |
| Recurring | `api-recurring-get.sh` | `GET /api/v1/recurring/{id}` |
| Recurring | `api-recurring-update.sh` | `PUT /api/v1/recurring/{id}` |
| Recurring | `api-recurring-delete.sh` | `DELETE /api/v1/recurring/{id}` |
| Chat | `api-chat-send.sh` | `POST /api/v1/chat` |
| Chat | `api-chat-confirm.sh` | `POST /api/v1/chat/confirm` |
| Chat | `api-chat-reject.sh` | `POST /api/v1/chat/reject` |
| Chat | `api-chat-history.sh` | `GET /api/v1/chat?sessionId=` |

The `{id}` scripts take the id as arg 1 or from the matching env var —
`./api-account-get.sh <accountId>`, `ACCOUNT_ID=… ./api-account-get.sh`. Same
shape for `CATEGORY_ID`, `PAYEE_ID`, `TRANSACTION_ID`, `BUDGET_ID`, `GOAL_ID`,
`RECURRING_ID`, `MESSAGE_ID`, `SESSION_ID`.

## Typical first run

```bash
./api-register.sh                    # → OTP logged to app console (SMTP unset)
./api-verify-email.sh                # paste the code → returns tokens, account active
./api-me.sh                          # auto-logs in, shows the current user
./api-category-seed-defaults.sh      # a starter category set (chat needs categories)
./api-account-create.sh              # create a BANK account ($5000 = 500000 minor units)
./api-account-list.sh                # see it
```

> **Money is integer minor units** — `openingBalance: 500000` means $5000.00.

## Chat transaction entry (F-1.9a)

Chat is **plain REST, not a WebSocket** — one request per turn, and the draft is
confirmed by a second call. Nothing streams, so there is no socket to open.

`POST /chat` only ever produces a draft; `POST /chat/confirm` is the single path
from chat to the ledger. That two-step gate is the safety story for AI money
entry, and the confirmable state lives on the server (the persisted
`ChatMessageStatus`), not in anything the client sends.

```bash
docker compose --profile llm up -d                             # opt-in model service
docker compose exec ollama ollama pull qwen2.5:1.5b-instruct    # ~1 GB, once

./api-chat-send.sh "spent 1500 on lunch"   # → data.messageId, data.status, data.draft
./api-chat-confirm.sh <messageId>          # → the created transaction
./api-chat-reject.sh  <messageId>          # or discard it
./api-chat-history.sh <sessionId>          # replay the conversation
```

Without the `ollama` service every message answers `FAILED` ("I couldn't read
that just now") — a deliberate degrade, not a 500. Statuses to expect:
`PARSED` (confirmable), `NEEDS_CLARIFICATION` (see `draft.missingFields`),
`FAILED`. The endpoints are rate limited per user — 10/min, 100/hour, 500/day,
**fail-closed**, so a 429 with `Retry-After` also happens when Redis is down.
