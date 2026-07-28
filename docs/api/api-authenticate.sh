#!/usr/bin/env bash
# POST /api/v1/authenticate — public. Email/password login; returns
# { accessToken, refreshToken } inside the ApiResponse envelope. Handy for
# inspecting the token response — the protected scripts auto-authenticate via
# define-envars.sh, so you don't normally call this by hand. Uses the credentials
# from .env.local.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/authenticate" --data-raw "{
    \"email\": \"$API_EMAIL\",
    \"password\": \"$API_PASSWORD\"
}"
