#!/usr/bin/env bash
# PUT /api/v1/me — authenticated (USER/ADMIN). Updates the caller's own
# firstName/lastName; either can be omitted to leave it unchanged.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X PUT "$HOST/api/v1/me" --data-raw '{
    "firstName": "Ada",
    "lastName": "Lovelace"
}'
