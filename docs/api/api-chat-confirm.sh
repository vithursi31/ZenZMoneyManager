#!/usr/bin/env bash
# POST /api/v1/chat/confirm — write a draft the model was unsure of (USER/ADMIN).
# NOT the ordinary path: a complete message is already written by the time
# api-chat-send.sh returns. This is only for a turn that came back PARSED — a suspected
# duplicate, or a delete waiting to be confirmed.
# Pass the assistant turn's id as arg 1 or set MESSAGE_ID (from api-chat-send.sh's
# data.messageId).
#
# Returns the chat reply, with results[] carrying the ids that were written — through
# the normal transaction path, so the same validation as a manually entered row.
# Undo it with api-chat-undo.sh.
# 400 if the draft is incomplete, already written, or the turn has no draft.
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
