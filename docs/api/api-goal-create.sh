#!/usr/bin/env bash
# POST /api/v1/goals — create a savings goal (USER/ADMIN).
#   accountId    : the real account that holds the earmarked money (must be ACTIVE)
#   targetAmount : integer MINOR units (500000 = $5,000.00), positive
#   targetDate   : optional soft deadline, epoch millis
# currency is taken from the backing account — not sent here.
# Progress (saved/remaining) is derived from contributions, never stored.
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/goals" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "<savings-account-id>",
    "name": "Japan Trip",
    "targetAmount": 500000,
    "icon": "airplane"
  }'
