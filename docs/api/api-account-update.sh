#!/usr/bin/env bash
# PUT /api/v1/accounts/{id} — partial update (USER/ADMIN). Only name/color/icon/
# sortOrder are editable; a null field is left unchanged. Currency, type and
# opening balance are intentionally not editable here. Pass the id as arg 1 or set
# ACCOUNT_ID.
source define-envars.sh;

ACCOUNT_ID="${1:-${ACCOUNT_ID:-}}"
if [ -z "$ACCOUNT_ID" ]; then
  echo "Usage: $0 <accountId>   (or export ACCOUNT_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/accounts/$ACCOUNT_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Chase Everyday Checking",
    "color": "#43a047",
    "icon": "wallet",
    "sortOrder": 1
  }'
