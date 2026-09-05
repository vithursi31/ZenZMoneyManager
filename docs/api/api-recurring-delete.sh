#!/usr/bin/env bash
# DELETE /api/v1/recurring/{id} — delete a recurring template (USER/ADMIN).
#
# A HARD delete, unlike an account or a category: the template row is gone. The
# transactions it already generated are real money and are kept untouched — their
# recurringId becomes a historical label pointing at nothing.
#
# To stop future generation while keeping the history readable, PUT active=false
# instead (api-recurring-update.sh). Pass the id as arg 1 or set RECURRING_ID.
#
# NOTE: chat cannot reach this. "Cancel my Netflix subscription" is declined on
# purpose — removing a template changes every future month, and picking the right
# one out of language is too risky. Undoing a chat-created template only
# DEACTIVATES it (active=false), which stops generation without destroying it.
source define-envars.sh;

RECURRING_ID="${1:-${RECURRING_ID:-}}"
if [ -z "$RECURRING_ID" ]; then
  echo "Usage: $0 <recurringId>   (or export RECURRING_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/recurring/$RECURRING_ID"
