#!/usr/bin/env bash
# GET /api/v1/admin/ping — ADMIN-only smoke check. Returns 403 for a plain USER.
# The .env.local account must have ROLE_ADMIN for this to succeed.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/admin/ping"
