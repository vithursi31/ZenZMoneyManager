#!/usr/bin/env bash
# POST /api/v1/accounts/{id}/archive — set status ARCHIVED (USER/ADMIN). Hides the
# account from pickers but preserves its history; archived accounts take no new
# transactions. Pass the id as arg 1 or set ACCOUNT_ID.
source define-envars.sh;

ACCOUNT_ID="${1:-${ACCOUNT_ID:-}}"
if [ -z "$ACCOUNT_ID" ]; then
  echo "Usage: $0 <accountId>   (or export ACCOUNT_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/accounts/$ACCOUNT_ID/archive"
