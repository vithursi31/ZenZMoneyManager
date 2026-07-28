#!/usr/bin/env bash
# POST /api/v1/authenticate/google — public. Exchanges a Google credential for app
# tokens (find-or-create user). `type` is one of: IdToken (mobile SDK id_token),
# AuthCode (web OAuth code), AccessToken. `value` is that credential.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/authenticate/google" --data-raw '{
    "value": "<google-id-token-or-auth-code>",
    "type": "IdToken"
}'
