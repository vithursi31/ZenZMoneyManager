#!/usr/bin/env bash
# POST /api/v1/budgets/{id}/archive — set status ARCHIVED (USER/ADMIN). Hides the
# budget from the default listing and frees its (account, category, period) slot
# for a new active budget. Pass the id as arg 1 or set BUDGET_ID.
source define-envars.sh;

BUDGET_ID="${1:-${BUDGET_ID:-}}"
if [ -z "$BUDGET_ID" ]; then
  echo "Usage: $0 <budgetId>   (or export BUDGET_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/budgets/$BUDGET_ID/archive"
