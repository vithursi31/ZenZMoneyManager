#!/usr/bin/env bash
# GET /api/v1/account/active — every ACTIVE account the caller holds.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/account/active"
