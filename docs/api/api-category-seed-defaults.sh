#!/usr/bin/env bash
# POST /api/v1/categories/seed-defaults — provision the default category set
# (Income: Salary, Business, ...; Expense: Food & Drinks, Groceries, ...) for the
# caller. Idempotent: if the user already has categories, it returns the existing
# set without duplicating. Handy right after registering a fresh user.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X POST "$HOST/api/v1/categories/seed-defaults"
