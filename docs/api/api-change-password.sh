#!/usr/bin/env bash
# POST /api/v1/change-password — authenticated (USER/ADMIN). Verifies
# currentPassword against the caller's own hash before updating it; the
# account must use password auth (OAuth accounts have nothing to change).
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/change-password" --data-raw '{
    "currentPassword": "ChangeMe123!",
    "newPassword": "ZenzTest123!"
}'
