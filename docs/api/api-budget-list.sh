#!/usr/bin/env bash
# GET /api/v1/budgets — list the caller's budgets, each with derived usage for the
# current period window: spent, remaining, periodStart, periodEnd.
#   ./api-budget-list.sh                       # active only
#   INCLUDE_ARCHIVED=true ./api-budget-list.sh  # include archived
source define-envars.sh;

QS=""
[ -n "$INCLUDE_ARCHIVED" ] && QS="?includeArchived=$INCLUDE_ARCHIVED"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/budgets${QS}"
