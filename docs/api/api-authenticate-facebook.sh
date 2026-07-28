#!/usr/bin/env bash
# POST /api/v1/authenticate/facebook — public. Exchanges a Facebook credential for
# app tokens (find-or-create user). `type` is AccessToken or AuthCode; `value` is
# that credential. redirectUri is required only for the AuthCode exchange.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/authenticate/facebook" --data-raw '{
    "value": "<facebook-access-token-or-code>",
    "type": "AccessToken",
    "redirectUri": ""
}'
