#!/usr/bin/env bash
# POST /api/v1/verify-email — public. Confirms the registration OTP, activates the
# account, and returns { accessToken, refreshToken }. Put the code from the
# registration email / app log below.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/verify-email" --data-raw '{
    "email": "vithursiha@gmail.com",
    "code": "070465"
}'
