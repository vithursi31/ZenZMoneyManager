#!/usr/bin/env bash
# POST /api/v1/goals/{id}/archive — set status ARCHIVED (USER/ADMIN). Hides the goal
# from the default listing while keeping its contributions. Pass the id as arg 1 or
# set GOAL_ID.
source define-envars.sh;

GOAL_ID="${1:-${GOAL_ID:-}}"
if [ -z "$GOAL_ID" ]; then
  echo "Usage: $0 <goalId>   (or export GOAL_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/goals/$GOAL_ID/archive"
