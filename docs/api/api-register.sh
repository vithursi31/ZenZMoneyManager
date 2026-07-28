#!/usr/bin/env bash
# POST /api/v1/register — public. Creates a user (status "pending") and emails a
# 6-digit verification OTP. With SMTP unset the code is logged to the app console
# ([DEV FALLBACK]). Then run api-verify-email.sh with that code.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/register" --data-raw '{
    "email": "newuser@example.com",
    "password": "ChangeMe123!",
    "displayName": "New User"
}'
