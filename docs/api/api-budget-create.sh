#!/usr/bin/env bash
# POST /api/v1/budgets — create a spending cap on a specific account (USER/ADMIN).
#   accountId   : required; one of the caller's own accounts (see api-account-list.sh)
#   categoryId  : optional; omit/null for an OVERALL budget, else an EXPENSE category
#   period      : MONTHLY | YEARLY
#   periodKey   : required; the ONE period this cap applies to — yyyy-MM for MONTHLY,
#                 yyyy for YEARLY. Food at 2000 in July and 3000 in August is two
#                 rows, and a cap created in May never applies to January.
#   amountLimit : integer MINOR units (50000 = $500.00), positive
#   rollover    : carry unused amount into the next period (default false; not yet applied)
# Currency is not sent — it's derived from the linked account.
# At most one ACTIVE budget per (account, category, period, periodKey).
source define-envars.sh;

# --- category budget example ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/budgets" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "<account-id>",
    "categoryId": "<expense-category-id>",
    "period": "MONTHLY",
    "periodKey": "2026-08",
    "amountLimit": 50000,
    "rollover": false
  }'

# --- overall budget example (uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/budgets" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "accountId": "<account-id>",
#     "period": "MONTHLY",
#     "periodKey": "2026-08",
#     "amountLimit": 300000
#   }'

# --- yearly budget example (uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/budgets" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "accountId": "<account-id>",
#     "period": "YEARLY",
#     "periodKey": "2026",
#     "amountLimit": 3600000
#   }'
