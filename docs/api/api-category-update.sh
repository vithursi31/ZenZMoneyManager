#!/usr/bin/env bash
# PUT /api/v1/categories/{id} — partial update (USER/ADMIN). Only name/color/icon/
# sortOrder are editable; kind and parent are fixed at creation. A null field is
# left unchanged. Renaming onto another live category's name in the same kind is
# refused (case-insensitively); recasing the category's own name is fine. A deleted
# category cannot be edited. Pass the id as arg 1 or set CATEGORY_ID.
source define-envars.sh;

CATEGORY_ID="${1:-${CATEGORY_ID:-}}"
if [ -z "$CATEGORY_ID" ]; then
  echo "Usage: $0 <categoryId>   (or export CATEGORY_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X PUT "$HOST/api/v1/categories/$CATEGORY_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Dining Out",
    "color": "#e53935",
    "icon": "restaurant",
    "sortOrder": 1
  }'
