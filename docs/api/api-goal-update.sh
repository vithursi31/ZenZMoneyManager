#!/usr/bin/env bash
# PUT /api/v1/goals/{id} — partial update (USER/ADMIN). Editable: name, targetAmount,
# targetDate, color, icon; a null field is left unchanged. The backing account is
# fixed. Changing targetAmount re-evaluates ACHIEVED status. Pass the id as arg 1 or
# set GOAL_ID.
source define-envars.sh;

GOAL_ID="${1:-${GOAL_ID:-}}"
if [ -z "$GOAL_ID" ]; then
  echo "Usage: $0 <goalId>   (or export GOAL_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/goals/$GOAL_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Japan Trip 2026",
    "targetAmount": 650000
  }'
