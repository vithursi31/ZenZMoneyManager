#!/usr/bin/env bash
# POST /api/v1/account — add another account for the caller (USER/ADMIN).
#   name : optional label; omit for an unnamed account.
# Currency is not sent — every account is denominated in the caller's active
# currency. No opening balance: accounts hold no balance at all.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/account" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Savings"
  }'
