#!/usr/bin/env bash
# GET /api/v1/transactions — list the caller's transactions, newest first.
# Optional filters (query params), any combination:
#   ACCOUNT_ID  an account id; omit to span all of the caller's accounts
#   TYPE        INCOME or EXPENSE
#   START_DATE  yyyy-MM-dd, inclusive, in the caller's timezone
#   END_DATE    yyyy-MM-dd, inclusive, in the caller's timezone
#
#   ./api-transaction-list.sh                                        # all
#   ACCOUNT_ID=<id> ./api-transaction-list.sh                        # by account
#   TYPE=EXPENSE ./api-transaction-list.sh                           # by type
#   START_DATE=2026-08-01 END_DATE=2026-08-31 ./api-transaction-list.sh   # one month
source define-envars.sh;

QS=""
[ -n "$ACCOUNT_ID" ] && QS="${QS}&accountId=$ACCOUNT_ID"
[ -n "$TYPE" ]       && QS="${QS}&type=$TYPE"
[ -n "$START_DATE" ] && QS="${QS}&startDate=$START_DATE"
[ -n "$END_DATE" ]   && QS="${QS}&endDate=$END_DATE"
QS="${QS#&}"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/transactions${QS:+?$QS}"
