#!/usr/bin/env bash
# POST /api/v1/categories — create a category (USER/ADMIN).
#   kind     : INCOME | EXPENSE
#   parentId : optional; makes this a sub-category. One level deep, and the
#              sub-category's kind must match its parent's. Must not be deleted.
#   name     : must be free within this kind, compared case-insensitively — Food,
#              food and FOOD are one category, so a second one returns 400. The same
#              name in the other kind is fine ("Gifts" received vs given).
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/categories" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Food & Drinks",
    "kind": "EXPENSE",
    "parentId": null,
    "color": "#fb8c00",
    "icon": "food",
    "sortOrder": 0
  }'
