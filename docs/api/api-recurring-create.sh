#!/usr/bin/env bash
# POST /api/v1/recurring — create a recurring template: salary, rent, or a
# subscription (USER/ADMIN). A scheduler turns it into real transactions on each due
# date, with catch-up for downtime.
#   type          : INCOME | EXPENSE  (same field rules as a transaction; no TRANSFER)
#   categoryId    : required; its kind must match type
#   amount        : integer MINOR units, positive
#   cadence       : DAILY | WEEKLY | MONTHLY | YEARLY  (the billing cycle; there is no
#                   ONE_TIME — a one-off is api-transaction-create.sh instead)
#   nextRunDate   : epoch millis of the FIRST run; its day-of-month anchors MONTHLY/
#                   YEARLY cycles (a 31st template clamps to a short month's last day,
#                   then returns to the 31st), read in the CALLER'S timezone
#   trialEndDate  : optional epoch millis; a subscription's free-trial end
#   endDate       : optional epoch millis; generation stops once the next run passes it
#   payeeName     : optional; resolved to a Payee and copied onto generated rows
#   paymentMethod : optional CASH | CARD | BANK_TRANSFER | WALLET | OTHER; copied onto
#                   every generated row
#
# Neither accountId nor currency is sent — both are resolved server-side, exactly as
# for a transaction. The template stores no currency of its own: it is read off the
# account on every response, so it can never disagree with it.
#
# RESPONSE is {template, posted}. "posted" is null for a template scheduled in the
# future; when nextRunDate has ALREADY arrived the server posts that one occurrence in
# this request and returns the transaction there — so if it is non-null, real money
# moved and the ledger/monthly summary need a refresh.
#
# Runs as-is: nextRunDate defaults to tomorrow 09:00 local and the category to the
# caller's first EXPENSE one. Override either:
#   CATEGORY_ID=<id> NEXT_RUN_DATE=<epoch-millis> ./api-recurring-create.sh
# To watch the immediate-post path, set a nextRunDate in the past:
#   NEXT_RUN_DATE=$(date -d 'today 06:00' +%s%3N) ./api-recurring-create.sh
source define-envars.sh;

NEXT_RUN_DATE="${NEXT_RUN_DATE:-$(date -d 'tomorrow 09:00' +%s%3N)}"
if [ -z "$CATEGORY_ID" ]; then
  CATEGORY_ID="$(curl -ks -H "Authorization:Bearer $ACCESS_TOKEN" "$HOST/api/v1/categories" \
    | tr '}' '\n' | grep '"kind":"EXPENSE"' | head -1 \
    | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
fi
echo "using categoryId=$CATEGORY_ID nextRunDate=$NEXT_RUN_DATE ($(date -d "@$((NEXT_RUN_DATE/1000))"))" >&2

# --- Spotify subscription: a recurring EXPENSE billed to a card ---
curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/recurring" \
  -H "Content-Type: application/json" \
  -d "{
    \"type\": \"EXPENSE\",
    \"categoryId\": \"2090737474375651329\",
    \"amount\": 999,
    \"cadence\": \"MONTHLY\",
    \"nextRunDate\": 1787356800000,
    \"payeeName\": \"Spotify\",
    \"note\": \"Spotify Premium\",
    \"paymentMethod\": \"CARD\"
  }"

# --- the same with a 14-day free trial (uncomment) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/recurring" \
#   -H "Content-Type: application/json" \
#   -d "{
#     \"type\": \"EXPENSE\",
#     \"categoryId\": \"$CATEGORY_ID\",
#     \"amount\": 999,
#     \"cadence\": \"MONTHLY\",
#     \"nextRunDate\": $(date -d '+14 days 09:00' +%s%3N),
#     \"trialEndDate\": $(date -d '+14 days 09:00' +%s%3N),
#     \"payeeName\": \"Netflix\",
#     \"paymentMethod\": \"WALLET\"
#   }"

# --- monthly rent (uncomment; pick a housing category) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/recurring" \
#   -H "Content-Type: application/json" \
#   -d "{
#     \"type\": \"EXPENSE\",
#     \"categoryId\": \"<housing-category-id>\",
#     \"amount\": 100000,
#     \"cadence\": \"MONTHLY\",
#     \"nextRunDate\": $NEXT_RUN_DATE,
#     \"payeeName\": \"Landlord\",
#     \"note\": \"Monthly rent\",
#     \"paymentMethod\": \"BANK_TRANSFER\"
#   }"

# --- monthly salary (INCOME, uncomment; pick an INCOME category) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/recurring" \
#   -H "Content-Type: application/json" \
#   -d "{
#     \"type\": \"INCOME\",
#     \"categoryId\": \"<salary-category-id>\",
#     \"amount\": 300000,
#     \"cadence\": \"MONTHLY\",
#     \"nextRunDate\": $NEXT_RUN_DATE
#   }"
