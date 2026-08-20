#!/usr/bin/env bash
# DELETE /api/v1/categories/{id} — soft-delete a category (USER/ADMIN): status ->
# DELETED, the row is kept. Transactions already filed under it keep pointing at it,
# so past months still report under its name; it just leaves every picker, cannot be
# chosen for a new transaction/recurring/budget, cannot be edited, and frees its name
# for reuse. Still refused (400) while a live sub-category or a live budget would be
# left dangling. Pass the id as arg 1 or set CATEGORY_ID.
source define-envars.sh;

CATEGORY_ID="${1:-${CATEGORY_ID:-}}"
if [ -z "$CATEGORY_ID" ]; then
  echo "Usage: $0 <categoryId>   (or export CATEGORY_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/categories/$CATEGORY_ID"
