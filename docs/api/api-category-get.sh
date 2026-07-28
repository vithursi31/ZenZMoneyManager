#!/usr/bin/env bash
# GET /api/v1/categories/{id} — one category, scoped to the caller (404 if not
# owned). Pass the id as arg 1 or set CATEGORY_ID.
source define-envars.sh;

CATEGORY_ID="${1:-${CATEGORY_ID:-}}"
if [ -z "$CATEGORY_ID" ]; then
  echo "Usage: $0 <categoryId>   (or export CATEGORY_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/categories/$CATEGORY_ID"
