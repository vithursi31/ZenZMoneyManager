#!/usr/bin/env bash
# GET /api/v1/chat?sessionId=<id> — replay one conversation, oldest turn first
# (USER/ADMIN), scoped to the caller.
# Pass the session id as arg 1 or set SESSION_ID. Without one the API returns an
# empty list: history is per-conversation, never "everything this user ever said".
source define-envars.sh;

SESSION_ID="${1:-${SESSION_ID:-}}"
if [ -z "$SESSION_ID" ]; then
  echo "Usage: $0 <sessionId>   (or export SESSION_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X GET "$HOST/api/v1/chat?sessionId=$SESSION_ID"
