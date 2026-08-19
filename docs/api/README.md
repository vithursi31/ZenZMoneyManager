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
| User | `api-update-profile.sh` | `PUT /api/v1/me` (firstName/lastName) |
| User | `api-change-password.sh` | `POST /api/v1/change-password` |
| User | `api-admin-ping.sh` | `GET /api/v1/admin/ping` (ADMIN) |
| Onboarding | `api-onboarding-complete.sh` | `POST /api/v1/onboarding` (currency/language/timezone; creates the primary account + seeds default categories) |
| Onboarding | `api-onboarding-currencies.sh` | `GET /api/v1/onboarding/currencies` |
| Accounts | `api-account-primary.sh` | `GET /api/v1/account` (primary account) |
| Accounts | `api-account-list.sh` | `GET /api/v1/account/active` |
| Accounts | `api-account-get.sh` | `GET /api/v1/account/{id}` |
| Accounts | `api-account-create.sh` | `POST /api/v1/account` |
| Accounts | `api-account-update.sh` | `PUT /api/v1/account/{id}/name` (rename) |
| Accounts | `api-account-delete.sh` | `DELETE /api/v1/account/{id}` (soft delete) |
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
| Transactions | `api-transaction-list.sh` | `GET /api/v1/transactions` (filter by accountId/type/startDate/endDate, any combination) |
| Transactions | `api-transaction-get.sh` | `GET /api/v1/transactions/{id}` |
| Transactions | `api-transaction-update.sh` | `PUT /api/v1/transactions/{id}` |
| Transactions | `api-transaction-delete.sh` | `DELETE /api/v1/transactions/{id}` |
| Summary | `api-summary-monthly.sh` | `GET /api/v1/summary/monthly` (income/expenses/position for one month; optional accountId) |
| Summary | `api-summary-breakdown.sh` | `GET /api/v1/summary/breakdown` (income/expenses split by category over a period; optional accountId) |
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
| Chat | `api-chat-draft.sh` | `POST /api/v1/chat/draft` (answer a suggestion, or edit the draft) |
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
./api-verify-email.sh                # paste the code → returns tokens
./api-onboarding-complete.sh         # sets currency/language/timezone → creates the primary account + default categories
./api-me.sh                          # auto-logs in, shows the current user
./api-account-create.sh              # add a second, named account
./api-account-list.sh                # see all active accounts
```

> **Money is integer minor units** — e.g. a budget's `amountLimit: 50000` means $500.00. Accounts hold no balance at all.

## Chat transaction entry (F-1.11)

Chat is **plain REST, not a WebSocket** — one request per turn, and the draft is
confirmed by a second call. Nothing streams, so there is no socket to open. The
one thing REST cannot do — telling a user something while their app is closed —
is F-1.20's job and goes out as **FCM push**, not over a connection
([plan](../features/push-notifications-fcm-plan.md)).

`POST /chat` and `POST /chat/draft` only ever produce a draft; `POST /chat/confirm`
is the single path from chat to the ledger. That two-step gate is the safety story
for AI money entry, and the confirmable state lives on the server (the persisted
`ChatMessageStatus`), not in anything the client sends.

**A conversation refines one draft.** A first message rarely carries everything, so
a reply that is short of something comes back with `prompt` — one question and the
answers to offer for it:

```json
{"status":"NEEDS_CLARIFICATION","reply":"What did you spend it on?",
 "draft":{"txnType":"EXPENSE","amountMinor":2000,"currency":"USD","categoryId":null},
 "prompt":{"field":"category","question":"What did you spend it on?","options":[
   {"label":"Food & Drinks","value":"c-food"},{"label":"Fuel","value":"c-fuel"},
   {"label":"Other","freeform":true}]}}
```

Send the answer back either way — as a value through `/chat/draft` (a tapped chip:
no model call, not rate limited), or as language through `/chat` (the "Other" path:
read by the model, then merged into the same draft). Amount options carry
`amountMinor` and **no label**: the client formats money, the backend never does.
Taking a draft further marks the turn it grew out of `SUPERSEDED`, so a corrected
draft never leaves its pre-correction self confirmable.

```bash
docker compose --profile llm up -d                             # opt-in model service
docker compose exec ollama ollama pull qwen2.5:1.5b-instruct    # ~1 GB, once

./api-chat-send.sh "I spent 2000"          # → data.messageId, data.status, data.prompt
CATEGORY_ID=<id> ./api-chat-draft.sh <messageId>   # tap a suggestion → PARSED
./api-chat-confirm.sh <messageId>          # → the created transaction
./api-chat-reject.sh  <messageId>          # or discard it
./api-chat-history.sh <sessionId>          # replay the conversation
```

**Questions are answered, not captured.** A message the model reads as a question
takes a second pass: the backend aggregates the user's own figures and hands them to
the model with the question, so the model writes the sentence and never the
arithmetic. The reply comes back `ANSWERED`, with no draft and no chips, and the
aggregates alongside so a client can draw the breakdown the prose is describing:

```bash
./api-chat-send.sh "how can I reduce my expenses?"
```
```json
{"status":"ANSWERED",
 "reply":"Food & Drinks is your biggest cost at 450.05 USD…",
 "draft":null,"prompt":null,
 "insight":{"currency":"USD","timezone":"Asia/Colombo","months":[
   {"month":"2026-08","income":300000,"expenses":120000,"position":180000,
    "categories":[{"categoryId":"c-food","name":"Food & Drinks","amount":45005}]}]}}
```

`insight` is minor units like everything else — the major-unit text the model reads
exists only inside the prompt, because a model handed `45005` writes advice about
forty-five thousand. Only aggregates leave: notes, payees, and individual
transactions never reach the model. Answering carries **its own tighter limit** on
top of the chat one (5/min, 30/hour, 100/day, fail-closed), and a user with nothing
recorded is answered without a model call at all.

Without the `ollama` service every message answers `FAILED` ("I couldn't read
that just now") — a deliberate degrade, not a 500, and the draft already in
progress stays live. Statuses to expect: `PARSED` (confirmable),
`NEEDS_CLARIFICATION` (see `prompt` and `draft.missingFields`), `ANSWERED`,
`SUPERSEDED`, `FAILED`. `/chat` is rate limited per user — 10/min, 100/hour, 500/day,
**fail-closed**, so a 429 with `Retry-After` also happens when Redis is down.

A browser screen for the same flow — transcript, chips, and the preview with
**Create Expense** / **Edit** / **Cancel** — is served at `/chat`.
