#!/usr/bin/env bash
# GET /api/v1/summary/monthly — income, expenses, and position for one calendar
# month (the home screen's figures). Nothing is stored: both totals are summed
# from the transaction rows on every call.
#
# Optional (query params):
#   MONTH       yyyy-MM. Omit for the caller's current month, in their timezone.
#   ACCOUNT_ID  restrict to one account; omit to span every account they hold.
#
#   ./api-summary-monthly.sh                            # current month, all accounts
#   MONTH=2026-08 ./api-summary-monthly.sh              # one month, all accounts
#   MONTH=2026-08 ACCOUNT_ID=<id> ./api-summary-monthly.sh
source define-envars.sh;

QS=""
[ -n "$MONTH" ]      && QS="${QS}&month=$MONTH"
[ -n "$ACCOUNT_ID" ] && QS="${QS}&accountId=$ACCOUNT_ID"
QS="${QS#&}"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/summary/monthly${QS:+?$QS}"
