#!/usr/bin/env bash
# GET /api/v1/account — the caller's primary account: the oldest ACTIVE one,
# and the account new transactions land in by default.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/account"
