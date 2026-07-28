#!/usr/bin/env bash
# GET /api/v1/categories — list the caller's categories (USER/ADMIN), ordered by
# kind, then sortOrder, then name.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/categories"
