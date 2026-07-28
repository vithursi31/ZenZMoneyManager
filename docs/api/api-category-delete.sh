#!/usr/bin/env bash
# DELETE /api/v1/categories/{id} — delete a category (USER/ADMIN). Allowed only
# when nothing references it: no sub-categories, no transactions, no budgets;
# otherwise it returns 400 (leave it unused, or merge in a later phase). Pass the
# id as arg 1 or set CATEGORY_ID.
source define-envars.sh;

CATEGORY_ID="${1:-${CATEGORY_ID:-}}"
if [ -z "$CATEGORY_ID" ]; then
  echo "Usage: $0 <categoryId>   (or export CATEGORY_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/categories/$CATEGORY_ID"
