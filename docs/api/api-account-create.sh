#!/usr/bin/env bash
# POST /api/v1/accounts — create an account (USER/ADMIN).
#   type  : CASH | BANK | CARD | SAVINGS | WALLET
#   openingBalance : integer MINOR units (e.g. 500000 = $5000.00); may be negative for a CARD
#   currency : ISO-4217; ignored if the user already has an active currency, else it seeds it
# currentBalance starts equal to openingBalance and is thereafter derived from the ledger.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/accounts" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Chase Checking",
    "type": "BANK",
    "currency": "USD",
    "openingBalance": 500000,
    "color": "#1e88e5",
    "icon": "bank",
    "sortOrder": 0
  }'
