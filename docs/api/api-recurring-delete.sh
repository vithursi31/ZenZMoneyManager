#!/usr/bin/env bash
# DELETE /api/v1/recurring/{id} — delete a recurring template (USER/ADMIN). Already-
# generated transactions are real ledger rows and are kept untouched. To stop future
# generation without deleting, PUT active=false instead. Pass the id as arg 1 or set
# RECURRING_ID.
source define-envars.sh;

RECURRING_ID="${1:-${RECURRING_ID:-}}"
if [ -z "$RECURRING_ID" ]; then
  echo "Usage: $0 <recurringId>   (or export RECURRING_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/recurring/$RECURRING_ID"
