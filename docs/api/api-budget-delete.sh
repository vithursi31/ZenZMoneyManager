#!/usr/bin/env bash
# DELETE /api/v1/budgets/{id} — permanently delete a budget (USER/ADMIN). Nothing
# references a budget, so this is a hard delete (transactions are untouched). To
# keep it for history instead, archive it. Pass the id as arg 1 or set BUDGET_ID.
source define-envars.sh;

BUDGET_ID="${1:-${BUDGET_ID:-}}"
if [ -z "$BUDGET_ID" ]; then
  echo "Usage: $0 <budgetId>   (or export BUDGET_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/budgets/$BUDGET_ID"
