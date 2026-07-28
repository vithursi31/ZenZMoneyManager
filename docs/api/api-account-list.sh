#!/usr/bin/env bash
# GET /api/v1/accounts — list the caller's accounts (USER/ADMIN), sorted by
# sortOrder then name. Archived accounts are hidden unless includeArchived=true.
source define-envars.sh;

INCLUDE_ARCHIVED="${1:-${INCLUDE_ARCHIVED:-false}}"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/accounts?includeArchived=$INCLUDE_ARCHIVED"
