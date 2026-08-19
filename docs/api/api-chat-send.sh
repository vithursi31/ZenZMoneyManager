#!/usr/bin/env bash
# POST /api/v1/chat — read a typed message into a DRAFT (USER/ADMIN).
# Never writes to the ledger: confirm is a separate call (api-chat-confirm.sh).
#   message   : max 500 chars, non-blank (the cap bounds model compute)
#   sessionId : optional; omit to start a conversation, pass it back to continue one
#
# The reply carries {messageId, sessionId, status, reply, draft, prompt}:
#   PARSED              -> draft is complete + confident; confirm it
#   NEEDS_CLARIFICATION -> prompt.field says what is still open and prompt.options
#                          what to offer for it; answer with api-chat-draft.sh, or
#                          just send another message on the same sessionId
#   ANSWERED            -> the message was a question ("how can I reduce my
#                          expenses?"), answered from the user's own aggregates;
#                          `insight` carries the figures, `draft` is null, and any
#                          capture in progress is left untouched
#   FAILED              -> the model was unreachable or unreadable; nothing stored,
#                          and any draft already in progress stays live
#
# Pass sessionId to continue a conversation: a follow-up folds into the draft the
# earlier turn built rather than starting a new one, so "I spent 20" then "food"
# is one transaction, not two halves of nothing.
# Money in the draft is minor units + currency; the client formats it.
#
# Requires the optional Ollama service:
#   docker compose --profile llm up -d
#   docker compose exec ollama ollama pull qwen2.5:1.5b-instruct
# Without it every message comes back FAILED (by design — chat degrades, not 500s).
# Rate limited per user: 10/min, 100/hour, 500/day, fail-closed (429 + Retry-After).
source define-envars.sh;

MESSAGE="${1:-${MESSAGE:-spent 1500 on lunch at Keells}}"

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/chat" \
  -H "Content-Type: application/json" \
  -d "{
    \"message\": \"$MESSAGE\"${SESSION_ID:+,
    \"sessionId\": \"$SESSION_ID\"}
  }"
