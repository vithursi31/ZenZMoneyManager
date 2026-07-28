#!/usr/bin/env bash
# POST /api/v1/goals/{id}/contributions — record a funding event toward a goal
# (USER/ADMIN). Re-derives the goal's saved/remaining and flips it to ACHIEVED once
# the target is met.
#   amount        : integer MINOR units, positive
#   transactionId : optional; the real transfer into the backing account. When set,
#                   its amount and currency must match (null = manual adjustment).
#   contributedAt : optional epoch millis; defaults to now
# Pass the goal id as arg 1 or set GOAL_ID.
source define-envars.sh;

GOAL_ID="${1:-${GOAL_ID:-}}"
if [ -z "$GOAL_ID" ]; then
  echo "Usage: $0 <goalId>   (or export GOAL_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/goals/$GOAL_ID/contributions" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 50000,
    "note": "monthly top-up"
  }'
