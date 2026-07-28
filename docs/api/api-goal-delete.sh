#!/usr/bin/env bash
# DELETE /api/v1/goals/{id} — delete a goal (USER/ADMIN). Allowed only when the goal
# has no contributions; a funded goal carries ledger history and must be archived
# instead. Pass the id as arg 1 or set GOAL_ID.
source define-envars.sh;

GOAL_ID="${1:-${GOAL_ID:-}}"
if [ -z "$GOAL_ID" ]; then
  echo "Usage: $0 <goalId>   (or export GOAL_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/goals/$GOAL_ID"
