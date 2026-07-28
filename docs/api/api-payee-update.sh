#!/usr/bin/env bash
# PUT /api/v1/payees/{id} — partial update (USER/ADMIN). Renaming re-derives the
# normalized dedup key; renaming onto an existing payee's name returns 400. A null
# field is left unchanged. Pass the id as arg 1 or set PAYEE_ID.
source define-envars.sh;

PAYEE_ID="${1:-${PAYEE_ID:-}}"
if [ -z "$PAYEE_ID" ]; then
  echo "Usage: $0 <payeeId>   (or export PAYEE_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/payees/$PAYEE_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Keells Super",
    "color": "#5d4037",
    "icon": "storefront"
  }'
