#!/usr/bin/env bash
# GET /api/v1/budgets/{id} — one budget with derived usage for the current period
# window (spent/remaining). Pass the id as arg 1 or set BUDGET_ID.
source define-envars.sh;

BUDGET_ID="${1:-${BUDGET_ID:-}}"
if [ -z "$BUDGET_ID" ]; then
  echo "Usage: $0 <budgetId>   (or export BUDGET_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/budgets/$BUDGET_ID"
