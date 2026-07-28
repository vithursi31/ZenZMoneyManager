#!/usr/bin/env bash
# POST /api/v1/reset-password — public. Consumes the reset OTP + a new password and
# returns { accessToken, refreshToken }. The code is bound to the email it was
# issued for.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/reset-password" --data-raw '{
    "email": "user@example.com",
    "code": "123456",
    "newPassword": "NewPassw0rd!"
}'
