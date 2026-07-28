#!/usr/bin/env bash
# GET /api/v1/transactions — list the caller's transactions, newest first.
# Optional filters (query params): accountId, from, to (epoch millis).
#   ./api-transaction-list.sh                       # all
#   ACCOUNT_ID=<id> ./api-transaction-list.sh        # by account
#   FROM=1700000000000 TO=1710000000000 ./api-transaction-list.sh   # date range
source define-envars.sh;

QS=""
[ -n "$ACCOUNT_ID" ] && QS="${QS}&accountId=$ACCOUNT_ID"
[ -n "$FROM" ]       && QS="${QS}&from=$FROM"
[ -n "$TO" ]         && QS="${QS}&to=$TO"
QS="${QS#&}"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/transactions${QS:+?$QS}"
