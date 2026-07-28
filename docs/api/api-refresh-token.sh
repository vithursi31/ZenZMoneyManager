#!/usr/bin/env bash
# POST /api/v1/refresh-token — sends the REFRESH token in the Authorization header
# and gets back a new { accessToken }. define-envars.sh captured REFRESH_TOKEN at
# login; a refresh token used as an access token is rejected.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $REFRESH_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/refresh-token"
