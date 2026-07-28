#!/usr/bin/env bash
# GET /api/v1/transactions/{id} — one transaction, scoped to the caller (404 if
# not owned). Pass the id as arg 1 or set TXN_ID.
source define-envars.sh;

TXN_ID="${1:-${TXN_ID:-}}"
if [ -z "$TXN_ID" ]; then
  echo "Usage: $0 <transactionId>   (or export TXN_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/transactions/$TXN_ID"
