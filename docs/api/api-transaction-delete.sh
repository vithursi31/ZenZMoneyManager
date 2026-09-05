#!/usr/bin/env bash
# DELETE /api/v1/transactions/{id} — delete a transaction (USER/ADMIN).
#
# SOFT since 2026-09-01 (V12): the row stays and its status flips to DELETED, the
# same way a category does. Nothing counts it afterwards — every aggregate filters
# status = ACTIVE — and every read path answers 404 for it, so it is gone as far as
# the API is concerned. It survives because a chat turn and a goal contribution both
# record the transaction they refer to.
#
# Deleting twice answers 404, not 500. There is no restore endpoint: the only way
# back is undoing the chat turn that removed it (api-chat-undo.sh).
# Pass the id as arg 1 or set TXN_ID.
source define-envars.sh;

TXN_ID="${1:-${TXN_ID:-}}"
if [ -z "$TXN_ID" ]; then
  echo "Usage: $0 <transactionId>   (or export TXN_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/transactions/$TXN_ID"
