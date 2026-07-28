#!/usr/bin/env bash
# PUT /api/v1/recurring/{id} — partial update (USER/ADMIN). Editable: amount,
# nextRunDate (reschedules and re-anchors the day-of-month), endDate, active
# (false pauses / true resumes), payeeName, note. Type, account, category, and
# cadence are the template's identity — recreate to change them. A null field is
# left unchanged. Pass the id as arg 1 or set RECURRING_ID.
source define-envars.sh;

RECURRING_ID="${1:-${RECURRING_ID:-}}"
if [ -z "$RECURRING_ID" ]; then
  echo "Usage: $0 <recurringId>   (or export RECURRING_ID)" >&2
  exit 1
fi

# Example: bump the amount (rent increase) and pause generation.
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/recurring/$RECURRING_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 110000,
    "active": false
  }'
