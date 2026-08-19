#!/usr/bin/env bash
# POST /api/v1/chat/draft — answer a suggestion, or edit a draft, before confirming
# it (USER/ADMIN). Still no ledger write: api-chat-confirm.sh is the only one.
#
# This is the structured half of the conversation. api-chat-send.sh sends language
# and pays for a model call; this sends a value the user already picked, so it costs
# a row update and is not rate limited.
#
#   messageId  : the assistant turn holding the draft (from api-chat-send.sh)
#   txnType    : EXPENSE | INCOME
#   amountMinor: minor units of your active currency (2000 = $20.00), positive
#   categoryId : one of your own categories; its kind must match txnType, and it
#                settles txnType when the draft has none yet
#   txnDate    : epoch millis; omit to keep the draft's date (today by default)
#   note       : description  |  payeeName: merchant or person
#
# Only the fields you send are applied. Flipping txnType drops a category the new
# direction invalidates, and the reply asks for it again.
#
# The reply is the same shape as api-chat-send.sh — {messageId, status, draft, prompt}:
#   PARSED              -> nothing missing; confirm it
#   NEEDS_CLARIFICATION -> prompt.field says what is still open, prompt.options what
#                          to offer for it (amounts come as amountMinor, unformatted)
source define-envars.sh;

MESSAGE_ID="${1:-${MESSAGE_ID:?set MESSAGE_ID to an assistant turn from api-chat-send.sh}}"
CATEGORY_ID="${CATEGORY_ID:-}"
AMOUNT_MINOR="${AMOUNT_MINOR:-}"
TXN_TYPE="${TXN_TYPE:-}"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/chat/draft" \
  -H "Content-Type: application/json" \
  -d "{
    \"messageId\": \"$MESSAGE_ID\"${CATEGORY_ID:+,
    \"categoryId\": \"$CATEGORY_ID\"}${AMOUNT_MINOR:+,
    \"amountMinor\": $AMOUNT_MINOR}${TXN_TYPE:+,
    \"txnType\": \"$TXN_TYPE\"}
  }"
