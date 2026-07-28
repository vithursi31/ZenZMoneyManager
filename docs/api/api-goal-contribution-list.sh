#!/usr/bin/env bash
# GET /api/v1/goals/{id}/contributions — list a goal's funding events, newest first.
# Pass the goal id as arg 1 or set GOAL_ID.
source define-envars.sh;

GOAL_ID="${1:-${GOAL_ID:-}}"
if [ -z "$GOAL_ID" ]; then
  echo "Usage: $0 <goalId>   (or export GOAL_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/goals/$GOAL_ID/contributions"
