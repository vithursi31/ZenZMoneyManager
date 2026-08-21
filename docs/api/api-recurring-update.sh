#!/usr/bin/env bash
# PUT /api/v1/recurring/{id} — PATCH semantics (USER/ADMIN): a field you omit is left
# unchanged. Editable: amount, nextRunDate (reschedules AND re-anchors the day-of-month,
# read in the caller's timezone), trialEndDate, endDate, active (false pauses / true
# resumes), payeeName, note, paymentMethod.
#
# type, categoryId and cadence are the template's identity — they are what its
# already-generated rows were filed under — so recreate the template to change one.
#
# To stop a subscription prefer active=false over DELETE: it keeps the history readable
# and the rows' recurringId still resolves.
#
# Pass the id as arg 1 or set RECURRING_ID.
source define-envars.sh;

RECURRING_ID="${1:-${RECURRING_ID:-}}"
if [ -z "$RECURRING_ID" ]; then
  echo "Usage: $0 <recurringId>   (or export RECURRING_ID)" >&2
  exit 1
fi

# Example: the subscription price went up, and it is now billed by bank transfer.
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/recurring/$RECURRING_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 1299,
    "paymentMethod": "BANK_TRANSFER"
  }'

# --- pause it (the cancel button; uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X PUT "$HOST/api/v1/recurring/$RECURRING_ID" \
#   -H "Content-Type: application/json" \
#   -d '{"active": false}'

# --- move the billing day, re-anchoring the cycle (uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X PUT "$HOST/api/v1/recurring/$RECURRING_ID" \
#   -H "Content-Type: application/json" \
#   -d "{\"nextRunDate\": $(date -d 'tomorrow 09:00' +%s%3N)}"
