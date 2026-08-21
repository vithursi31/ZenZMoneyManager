#!/usr/bin/env bash
# GET /api/v1/recurring/{id} — one recurring template, scoped to the caller.
# Pass the id as arg 1 or set RECURRING_ID; an upcoming entry's recurringId is one.
# Someone else's id answers 404, not 403 — the query is scoped to the caller first.
source define-envars.sh;

RECURRING_ID="${1:-${RECURRING_ID:-}}"
if [ -z "$RECURRING_ID" ]; then
  echo "Usage: $0 <recurringId>   (or export RECURRING_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/recurring/$RECURRING_ID"
