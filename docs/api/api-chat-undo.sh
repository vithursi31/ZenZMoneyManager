#!/usr/bin/env bash
# POST /api/v1/chat/undo — delete what a chat turn wrote (USER/ADMIN).
# The way back that makes writing without asking safe: a chat message the model read
# completely and confidently is already in the ledger by the time the reply arrives.
# Pass the assistant turn's id as arg 1 or set MESSAGE_ID (from api-chat-send.sh's
# data.results[].messageId).
#
# Deletes the transaction the turn created, and — for a repeating entry — the template
# too, along with the occurrence it posted on creation.
# 400 if the turn never wrote anything, or was already undone. 404 if it is not yours.
source define-envars.sh;

MESSAGE_ID="${1:-${MESSAGE_ID:-}}"
if [ -z "$MESSAGE_ID" ]; then
  echo "Usage: $0 <messageId>   (or export MESSAGE_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/chat/undo" \
  -H "Content-Type: application/json" \
  -d "{\"messageId\": \"$MESSAGE_ID\"}"
