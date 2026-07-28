#!/usr/bin/env bash
# GET /api/v1/payees — list the caller's payees (USER/ADMIN), alphabetical.
# Backs payee autocomplete and "total spent at X" reporting.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/payees"
