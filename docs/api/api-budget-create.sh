#!/usr/bin/env bash
# POST /api/v1/budgets — create a spending cap on a specific account (USER/ADMIN).
#   accountId   : required; one of the caller's own accounts (see api-account-list.sh)
#   categoryId  : optional; omit/null for an OVERALL budget, else an EXPENSE category
#   period      : MONTHLY | YEARLY — the current calendar month/year, in the
#                 caller's timezone; there is no custom start date anymore
#   amountLimit : integer MINOR units (50000 = $500.00), positive
#   rollover    : carry unused amount into the next period (default false)
# Currency is not sent — it's derived from the linked account.
# At most one ACTIVE budget per (account, category, period).
source define-envars.sh;

# --- category budget example ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/budgets" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "<account-id>",
    "categoryId": "<expense-category-id>",
    "period": "MONTHLY",
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
#     "amountLimit": 300000
#   }'
