#!/usr/bin/env bash
# GET /api/v1/summary/breakdown — income and expenses over a period, split by
# category. The detail behind the monthly figure: one grouped aggregate over the
# same rows the transaction list returns, so a report can't drift from the ledger.
#
# Required:
#   START_DATE  yyyy-MM-dd, inclusive, in the caller's timezone
#   END_DATE    yyyy-MM-dd, inclusive, in the caller's timezone
# Optional:
#   ACCOUNT_ID  restrict to one account; omit to span every account they hold
#
#   START_DATE=2026-08-01 END_DATE=2026-08-31 ./api-summary-breakdown.sh
#   START_DATE=2026-01-01 END_DATE=2026-12-31 ACCOUNT_ID=<id> ./api-summary-breakdown.sh
#
# A calendar month is simply its first and last day — there is no month shorthand.
source define-envars.sh;

START_DATE="${START_DATE:-}"
END_DATE="${END_DATE:-}"
if [ -z "$START_DATE" ] || [ -z "$END_DATE" ]; then
  echo "Usage: START_DATE=yyyy-MM-dd END_DATE=yyyy-MM-dd $0   (both required)" >&2
  exit 1
fi

QS="startDate=$START_DATE&endDate=$END_DATE"
[ -n "$ACCOUNT_ID" ] && QS="${QS}&accountId=$ACCOUNT_ID"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/summary/breakdown?$QS"
