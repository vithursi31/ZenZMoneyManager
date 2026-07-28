#!/usr/bin/env bash
# POST /api/v1/forgot-password — public. Emails a password-reset OTP if the account
# exists (the response is the same either way — it never discloses existence).
# With SMTP unset the code is logged to the app console. Then run
# api-reset-password.sh.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/forgot-password" --data-raw '{
    "email": "user@example.com"
}'
