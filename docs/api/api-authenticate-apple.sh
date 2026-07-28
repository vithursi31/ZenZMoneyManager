#!/usr/bin/env bash
# POST /api/v1/authenticate/apple — public. Verifies the Apple identity token and
# exchanges the authorization code (find-or-create user). email/givenName/
# familyName are only sent by Apple on the first sign-in. Set isMobileApp true for
# the native app, false for the web Service ID.
source load-env.sh;

curl -kv -H "Content-Type: application/json" -X POST "$HOST/api/v1/authenticate/apple" --data-raw '{
    "authorizationCode": "<apple-authorization-code>",
    "identityToken": "<apple-identity-token>",
    "email": "user@privaterelay.appleid.com",
    "givenName": "New",
    "familyName": "User",
    "nonce": "<nonce-if-used>",
    "isMobileApp": true
}'
