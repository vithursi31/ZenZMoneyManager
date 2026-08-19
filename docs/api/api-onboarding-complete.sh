#!/usr/bin/env bash
# POST /api/v1/onboarding — authenticated (USER/ADMIN). First-run setup (F-1.27):
# sets currency (required, ISO-4217) and optional language/timezone, provisions the
# caller's account, and seeds default categories. Idempotent — re-running it updates
# preferences without creating a second account or duplicate categories.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/onboarding" --data-raw '{
    "currency": "USD",
    "language": "en",
    "timezone": "America/New_York"
}'
