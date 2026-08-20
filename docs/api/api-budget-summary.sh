#!/usr/bin/env bash
# GET /api/v1/budgets/summary — one month's plan against its outcome (USER/ADMIN):
# the caps set for that month, the spend against each, and the month's total
# expenses whether budgeted or not.
#   ./api-budget-summary.sh              # the caller's current month
#   ./api-budget-summary.sh 2026-07      # a specific month (yyyy-MM)
# totalLimit/totalSpent cover CATEGORY budgets only — an overall budget's spend
# already contains every category's, so summing both would double-count. Only
# MONTHLY budgets appear here; a yearly cap is read via api-budget-get.sh.
source define-envars.sh;

MONTH="${1:-${MONTH:-}}"
QS=""
[ -n "$MONTH" ] && QS="?month=$MONTH"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/budgets/summary${QS}"
