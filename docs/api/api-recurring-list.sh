#!/usr/bin/env bash
# GET /api/v1/recurring — list the caller's recurring templates, soonest next-run
# first. Each row includes nextRunDate, anchorDay, cadence, endDate, and active.
#   ./api-recurring-list.sh                       # active only
#   INCLUDE_INACTIVE=true ./api-recurring-list.sh  # include paused/ended
source define-envars.sh;

QS=""
[ -n "$INCLUDE_INACTIVE" ] && QS="?includeInactive=$INCLUDE_INACTIVE"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/recurring${QS}"
