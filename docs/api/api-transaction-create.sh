#!/usr/bin/env bash
# POST /api/v1/transactions — record a transaction (USER/ADMIN). Balances re-derive.
#   type   : INCOME | EXPENSE | TRANSFER
#   amount : integer MINOR units (2000 = $20.00), always positive
#   INCOME/EXPENSE  -> categoryId required (kind must match type), no transferAccountId
#   TRANSFER        -> transferAccountId required (≠ accountId), no categoryId
#   payeeName       -> optional; resolved to a Payee row (deduped)
#   txnDate         -> optional epoch millis; defaults to now
# currency is taken from the account (single active currency) — not sent here.
source define-envars.sh;

# --- EXPENSE example ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/transactions" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "INCOME",
    "accountId": "2089780641691471872",
    "categoryId": "2089780641733414912",
    "amount": 5000,
    "payeeName": "",
    "note": "Salary",
    "tags": ["salary"]
  }'

# --- TRANSFER example (uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/transactions" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "type": "TRANSFER",
#     "accountId": "<bank-account-id>",
#     "transferAccountId": "<cash-account-id>",
#     "amount": 50000
#   }'
