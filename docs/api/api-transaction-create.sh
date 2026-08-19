#!/usr/bin/env bash
# POST /api/v1/transactions — record a transaction (USER/ADMIN).
#   type       : INCOME | EXPENSE   (no TRANSFER — see the note below)
#   categoryId : required; its kind must match type (INCOME→INCOME, EXPENSE→EXPENSE)
#   amount     : integer MINOR units (2000 = $20.00), always POSITIVE —
#                direction comes from type, never from the sign
#   txnDate    : optional epoch millis; defaults to now
#   payeeName  : optional, max 300; resolved to a Payee row (deduped, created on first use)
#   note       : optional, max 500
#   tags       : optional array of strings
#
# Neither accountId nor currency is sent. The server stamps the caller's active
# currency and resolves the row to their primary account (the oldest ACTIVE one);
# an accountId in the body is ignored. There is no TRANSFER type and no stored
# balance — the monthly figure is summed from these rows by
# GET /api/v1/summary/monthly.
source define-envars.sh;

# --- INCOME example ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/transactions" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "INCOME",
    "categoryId": "<income-category-id>",
    "amount": 500000,
    "payeeName": "Acme Ltd",
    "note": "Salary",
    "tags": ["salary"]
  }'

# --- EXPENSE example (uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/transactions" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "type": "EXPENSE",
#     "categoryId": "<expense-category-id>",
#     "amount": 1050,
#     "payeeName": "Corner Cafe",
#     "note": "burger",
#     "tags": ["lunch"]
#   }'
