#!/usr/bin/env bash
# GET /api/v1/recurring/upcoming — bills, renewals and salary falling due soon
# (USER/ADMIN). This is what feeds an "upcoming payments" strip.
#
# These are PROJECTIONS off the recurring templates, not ledger rows: nothing is
# written, they carry no id, and NO total counts them — not the monthly position, not
# a budget, not the category breakdown — until the generation job posts them on the
# due date. That is what keeps the monthly figure a record of what happened.
#
#   withinDays : 1-90, default 3. The window runs to the END of the target day in the
#                caller's timezone, so a renewal on the 24th shows on the 21st at
#                withinDays=3 whatever time of day it falls at. Outside 1-90 -> 400 E1013.
#
# Each entry carries recurringId (the template to open/edit/pause — act through it),
# dueDate, due (its date has passed and it has not been posted yet; the job picks it up
# within ~15 min), paymentMethod, and trialEnding (the free trial ends inside the window).
#
#   ./api-recurring-upcoming.sh          # default 3 days
#   WITHIN_DAYS=30 ./api-recurring-upcoming.sh
source define-envars.sh;

QS=""
[ -n "$WITHIN_DAYS" ] && QS="?withinDays=$WITHIN_DAYS"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/recurring/upcoming${QS}"
