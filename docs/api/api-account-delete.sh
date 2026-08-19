#!/usr/bin/env bash
# DELETE /api/v1/account/{id} — SOFT delete one of the caller's own accounts
# (USER/ADMIN): sets status to DELETED and keeps the row (ledger rows and
# budgets still reference it). Refused with 400 if it is the caller's last
# ACTIVE account, or if it's already deleted. Pass the id as arg 1 or set
# ACCOUNT_ID.
source define-envars.sh;

ACCOUNT_ID="${1:-${ACCOUNT_ID:-}}"
if [ -z "$ACCOUNT_ID" ]; then
  echo "Usage: $0 <accountId>   (or export ACCOUNT_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/account/$ACCOUNT_ID"
