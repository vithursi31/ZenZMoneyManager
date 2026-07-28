#!/usr/bin/env bash
# GET /api/v1/me — the current user (roles USER/ADMIN). Authenticated with the
# access token from define-envars.sh.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/me"
