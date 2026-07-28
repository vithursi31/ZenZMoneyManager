#!/usr/bin/env bash
# PUT /api/v1/transactions/{id} — full replace (USER/ADMIN). Send the complete
# transaction (same shape as create); affected account balances re-derive, and if
# the account changed, both old and new balances update. Pass the id as arg 1 or
# set TXN_ID.
source define-envars.sh;

TXN_ID="${1:-${TXN_ID:-}}"
if [ -z "$TXN_ID" ]; then
  echo "Usage: $0 <transactionId>   (or export TXN_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/transactions/$TXN_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "EXPENSE",
    "accountId": "<account-id>",
    "categoryId": "<expense-category-id>",
    "amount": 1800,
    "payeeName": "Keells",
    "note": "tea + snacks"
  }'
