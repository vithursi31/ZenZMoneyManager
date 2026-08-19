#!/usr/bin/env bash
# PUT /api/v1/transactions/{id} — FULL REPLACE (USER/ADMIN), same body shape as
# create. Every field is re-specified: omitting note, payeeName, or tags CLEARS
# them rather than leaving them alone, so send the whole object back.
#
# Moving txnDate across a month boundary is allowed — it simply re-slices which
# month the row counts in. Nothing is recomputed eagerly, but two months'
# positions change, so re-read the summary afterwards.
#
# Pass the id as arg 1 or set TXN_ID.
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
    "categoryId": "<expense-category-id>",
    "amount": 1800,
    "payeeName": "Keells",
    "note": "tea + snacks",
    "tags": []
  }'
