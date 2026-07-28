#!/usr/bin/env bash
# PUT /api/v1/budgets/{id} — partial update (USER/ADMIN). Only amountLimit,
# startDate, and rollover are editable; a null field is left unchanged. Category
# and period are the budget's identity — recreate the budget to change them.
# Pass the id as arg 1 or set BUDGET_ID.
source define-envars.sh;

BUDGET_ID="${1:-${BUDGET_ID:-}}"
if [ -z "$BUDGET_ID" ]; then
  echo "Usage: $0 <budgetId>   (or export BUDGET_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/budgets/$BUDGET_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "amountLimit": 60000,
    "rollover": true
  }'
