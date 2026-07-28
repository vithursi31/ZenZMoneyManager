#!/usr/bin/env bash
# GET /api/v1/payees/{id} — one payee, scoped to the caller (404 if not owned).
# Pass the id as arg 1 or set PAYEE_ID.
source define-envars.sh;

PAYEE_ID="${1:-${PAYEE_ID:-}}"
if [ -z "$PAYEE_ID" ]; then
  echo "Usage: $0 <payeeId>   (or export PAYEE_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/payees/$PAYEE_ID"
