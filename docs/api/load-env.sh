#!/usr/bin/env bash
# Loads docs/api/.env.local (HOST, API_EMAIL, API_PASSWORD, optional ACCESS_TOKEN)
# and exports HOST with a localhost default. Sourced by the public endpoint
# scripts (register / authenticate / password-reset), which need no token.
# Token-protected scripts source define-envars.sh instead (it sources this, then
# logs in to obtain a JWT).

_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ENV_FILE="$_API_DIR/.env.local"
if [ ! -f "$_ENV_FILE" ]; then
  echo "ERROR: $_ENV_FILE not found. Copy docs/api/.env.local.example to docs/api/.env.local and fill it in." >&2
  return 1 2>/dev/null || exit 1
fi

set -a
# shellcheck disable=SC1090
. "$_ENV_FILE"
set +a

export HOST="${HOST:-http://localhost:8080}"
