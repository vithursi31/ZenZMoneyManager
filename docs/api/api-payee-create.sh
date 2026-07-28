#!/usr/bin/env bash
# POST /api/v1/payees — create a payee (USER/ADMIN). Idempotent by normalized name:
# posting "Keells" then "keells" returns the same payee. Payees are also
# auto-created when a transaction names one, so you rarely create them by hand.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/payees" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Keells",
    "color": "#6d4c41",
    "icon": "store"
  }'
