#!/usr/bin/env bash
# POST /api/v1/budgets — create a spending cap (USER/ADMIN).
#   categoryId  : optional; omit/null for an OVERALL budget, else an EXPENSE category
#   period      : WEEKLY | MONTHLY | YEARLY
#   amountLimit : integer MINOR units (50000 = $500.00), positive
#   startDate   : optional epoch millis anchoring the cycle; defaults to now
#   rollover    : carry unused amount into the next period (default false)
# currency is taken from the user's active currency — not sent here.
# At most one ACTIVE budget per (category, period).
source define-envars.sh;

# --- category budget example ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/budgets" \
  -H "Content-Type: application/json" \
  -d '{
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
#     "period": "MONTHLY",
#     "amountLimit": 300000
#   }'
