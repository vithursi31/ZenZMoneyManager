#!/usr/bin/env bash
# DELETE /api/v1/accounts/{id} — SOFT delete an account (USER/ADMIN): sets status
# to DELETED and keeps the row in the DB (audit/recovery); it then disappears from
# listings and reads as 404. Allowed only when the account has no transactions
# (as source or transfer target); otherwise it returns 400 and you must archive it
# instead. Pass the id as arg 1 or set ACCOUNT_ID.
source define-envars.sh;

ACCOUNT_ID="${1:-${ACCOUNT_ID:-}}"
if [ -z "$ACCOUNT_ID" ]; then
  echo "Usage: $0 <accountId>   (or export ACCOUNT_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/accounts/$ACCOUNT_ID"
