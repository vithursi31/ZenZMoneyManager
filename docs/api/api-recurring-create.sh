#!/usr/bin/env bash
# POST /api/v1/recurring — create a recurring template (USER/ADMIN). A scheduler
# generates real transactions from it on each due date (with catch-up for downtime).
#   type        : INCOME | EXPENSE | TRANSFER  (same field rules as a transaction)
#   amount      : integer MINOR units, positive
#   cadence     : DAILY | WEEKLY | MONTHLY | YEARLY
#   nextRunDate : epoch millis of the FIRST run; its day-of-month anchors MONTHLY/
#                 YEARLY cycles (a 31st template clamps to a short month's last day,
#                 then returns to the 31st)
#   endDate     : optional epoch millis; generation stops once the next run passes it
#   payeeName   : optional; resolved to a Payee and copied onto generated rows
# currency is taken from the account — not sent here.
source define-envars.sh;

# --- monthly rent example (runs on the 1st; set nextRunDate to your first run) ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/recurring" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "EXPENSE",
    "accountId": "<account-id>",
    "categoryId": "<housing-category-id>",
    "amount": 100000,
    "cadence": "MONTHLY",
    "nextRunDate": 1677628800000,
    "payeeName": "Landlord",
    "note": "Monthly rent"
  }'

# --- monthly salary example (INCOME, uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/recurring" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "type": "INCOME",
#     "accountId": "<account-id>",
#     "categoryId": "<salary-category-id>",
#     "amount": 300000,
#     "cadence": "MONTHLY",
#     "nextRunDate": 1677628800000
#   }'
