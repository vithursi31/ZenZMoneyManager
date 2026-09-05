#!/usr/bin/env bash
# POST /api/v1/chat — read a message and record what is complete (USER/ADMIN).
source define-envars.sh;

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
  -X POST "$HOST/api/v1/chat" \
  -H "Content-Type: application/json" \
  --data-raw '{
    "message": "how to cook briyani?",
    "sessionId":"616b2fa8-18e3-4613-b6f4-2a73949ec6da"
  }'

# --- continue a conversation (paste the sessionId you got back) ---
# curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" \
#   -X POST "$HOST/api/v1/chat" \
#   -H "Content-Type: application/json" \
#   --data-raw '{
#     "message": "one ticket 50 for snacks 10",
#     "sessionId": "<sessionId-from-the-previous-response>"
#   }'

# --- missing amount -> one question, nothing written ---
#     "message": "I had lunch at Pizza Hut today"

# --- several amounts in a FRESH message -> several entries ---
#     "message": "I spent 28 on coffee, 350 on groceries and 120 on fuel today"

# --- something that repeats -> a recurring template, not a row ---
#     "message": "Netflix 15 every month"

# --- a question -> answered from your own figures, nothing written ---
#     "message": "how much did I spend on food this month?"

# --- thousands separator and shorthand ---
#     "message": "Spent 2,500 on groceries"
#     "message": "bought a laptop for 250k"

# --- removing something -> finds it and asks first (confirm with api-chat-confirm.sh) ---
#     "message": "remove the 2,500 groceries expense"

# --- cancelling a subscription -> declined by design; use api-recurring-delete.sh ---
#     "message": "cancel my Netflix subscription"
