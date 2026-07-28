#!/usr/bin/env bash
# Shared config for the token-protected endpoint scripts. It is the first line of
# each: `source define-envars.sh;`. It loads .env.local (via load-env.sh) and
# ensures ACCESS_TOKEN / REFRESH_TOKEN are set — logging in with
# API_EMAIL / API_PASSWORD when a token isn't already pinned in .env.local.
#
# Responses are wrapped in the ApiResponse envelope ({status,data,message,
# errorCode}); the token lives at data.accessToken. The sed extraction below
# finds it regardless of nesting.

_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-env.sh
source "$_API_DIR/load-env.sh"

if [ -z "$ACCESS_TOKEN" ]; then
  if [ -z "$API_EMAIL" ] || [ -z "$API_PASSWORD" ]; then
    echo "ERROR: ACCESS_TOKEN is empty and API_EMAIL/API_PASSWORD are not set in .env.local." >&2
    return 1 2>/dev/null || exit 1
  fi
  _AUTH_RESP="$(curl -ks -H "Content-Type: application/json" -X POST "$HOST/api/v1/authenticate" \
    --data-raw "{\"email\": \"$API_EMAIL\", \"password\": \"$API_PASSWORD\"}")"
  ACCESS_TOKEN="$(printf '%s' "$_AUTH_RESP" | sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  REFRESH_TOKEN="$(printf '%s' "$_AUTH_RESP" | sed -n 's/.*"refreshToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  if [ -z "$ACCESS_TOKEN" ]; then
    echo "ERROR: authentication against $HOST failed; could not parse accessToken from response:" >&2
    echo "$_AUTH_RESP" >&2
    return 1 2>/dev/null || exit 1
  fi
  export ACCESS_TOKEN REFRESH_TOKEN
fi
