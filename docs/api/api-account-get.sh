#!/usr/bin/env bash
# GET /api/v1/accounts/{id} — one account, scoped to the caller (404 if not owned).
# Pass the id as arg 1 or set ACCOUNT_ID.
source define-envars.sh;

ACCOUNT_ID="${1:-${ACCOUNT_ID:-}}"
if [ -z "$ACCOUNT_ID" ]; then
  echo "Usage: $0 <accountId>   (or export ACCOUNT_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/accounts/$ACCOUNT_ID"
