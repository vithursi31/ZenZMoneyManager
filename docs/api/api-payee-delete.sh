#!/usr/bin/env bash
# DELETE /api/v1/payees/{id} — delete a payee (USER/ADMIN). Allowed only when no
# transaction or recurring template references it; otherwise it returns 400 (leave
# it unused, or merge in a later phase). Pass the id as arg 1 or set PAYEE_ID.
source define-envars.sh;

PAYEE_ID="${1:-${PAYEE_ID:-}}"
if [ -z "$PAYEE_ID" ]; then
  echo "Usage: $0 <payeeId>   (or export PAYEE_ID)" >&2
  exit 1
fi

curl -kv -H "Authorization:Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -X DELETE "$HOST/api/v1/payees/$PAYEE_ID"
