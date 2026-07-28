#!/usr/bin/env bash
# GET /api/v1/goals — list the caller's savings goals, each with derived progress
# (saved, remaining). status flips to ACHIEVED once saved >= targetAmount.
#   ./api-goal-list.sh                        # active + achieved
#   INCLUDE_ARCHIVED=true ./api-goal-list.sh   # include archived
source define-envars.sh;

QS=""
[ -n "$INCLUDE_ARCHIVED" ] && QS="?includeArchived=$INCLUDE_ARCHIVED"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/goals${QS}"
