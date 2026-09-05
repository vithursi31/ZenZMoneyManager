#!/usr/bin/env bash
# POST /api/v1/chat/reject — discard a draft (USER/ADMIN). Writes nothing to the
# ledger; the turn stays in history marked REJECTED.
# Pass the assistant turn's id as arg 1 or set MESSAGE_ID.
# 400 once the draft has been written — a written entry is taken back with
# api-chat-undo.sh, not by rejecting the chat turn.
# Returns an empty success envelope: {"status":"success","data":null}.
source define-envars.sh;

MESSAGE_ID="${1:-${MESSAGE_ID:-}}"
if [ -z "$MESSAGE_ID" ]; then
  echo "Usage: $0 <messageId>   (or export MESSAGE_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/chat/reject" \
  -H "Content-Type: application/json" \
  -d "{\"messageId\": \"$MESSAGE_ID\"}"
