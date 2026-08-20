#!/usr/bin/env bash
# DELETE /api/v1/budgets/{id} — soft-delete a budget (USER/ADMIN): status -> DELETED,
# the row is kept. It drops out of every listing and summary (even with
# includeArchived=true) and can no longer be edited or archived, but stays readable by
# id, and its (account, category, period, periodKey) slot is freed. Transactions are
# untouched. Use archive instead when the user may want to see it again.
# Pass the id as arg 1 or set BUDGET_ID.
source define-envars.sh;

BUDGET_ID="${1:-${BUDGET_ID:-}}"
if [ -z "$BUDGET_ID" ]; then
  echo "Usage: $0 <budgetId>   (or export BUDGET_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/budgets/$BUDGET_ID"
