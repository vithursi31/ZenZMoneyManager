#!/usr/bin/env bash
# DELETE /api/v1/goals/{id}/contributions/{contributionId} — remove a funding event
# (USER/ADMIN). Re-derives the goal's saved/remaining and can drop it back from
# ACHIEVED to ACTIVE. Pass GOAL_ID as arg 1 and CONTRIBUTION_ID as arg 2 (or export).
source define-envars.sh;

GOAL_ID="${1:-${GOAL_ID:-}}"
CONTRIBUTION_ID="${2:-${CONTRIBUTION_ID:-}}"
if [ -z "$GOAL_ID" ] || [ -z "$CONTRIBUTION_ID" ]; then
  echo "Usage: $0 <goalId> <contributionId>   (or export GOAL_ID / CONTRIBUTION_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/goals/$GOAL_ID/contributions/$CONTRIBUTION_ID"
