#!/usr/bin/env bash
# GET /api/v1/onboarding/currencies — authenticated (USER/ADMIN). The currency
# picker's options: every ISO-4217 code the onboarding-complete endpoint accepts.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/onboarding/currencies"
