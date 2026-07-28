#!/usr/bin/env bash
# POST /api/v1/chat/confirm — commit a draft to the ledger (USER/ADMIN).
# The only path from chat to a transaction: the model proposes, the user commits.
# Pass the assistant turn's id as arg 1 or set MESSAGE_ID (from api-chat-send.sh's
# data.messageId).
#
# Returns the created transaction, written through the normal transaction path —
# same validation and balance re-derivation as a manually entered row.
# 400 if the draft is incomplete, already confirmed, or the turn has no draft.
source define-envars.sh;

MESSAGE_ID="${1:-${MESSAGE_ID:-}}"
if [ -z "$MESSAGE_ID" ]; then
  echo "Usage: $0 <messageId>   (or export MESSAGE_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/chat/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"messageId\": \"$MESSAGE_ID\"}"
