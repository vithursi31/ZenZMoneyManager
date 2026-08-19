#!/usr/bin/env bash
# PUT /api/v1/account/{id}/name — rename one of the caller's own accounts
# (USER/ADMIN). name is required and non-blank. Pass the id as arg 1 or set
# ACCOUNT_ID.
source define-envars.sh;

ACCOUNT_ID="${1:-${ACCOUNT_ID:-}}"
if [ -z "$ACCOUNT_ID" ]; then
  echo "Usage: $0 <accountId>   (or export ACCOUNT_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/account/$ACCOUNT_ID/name" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Everyday Checking"
  }'
