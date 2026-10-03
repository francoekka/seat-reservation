#!/bin/bash
# Usage: ./burst.sh <showId> <seatNumber>
# Example: ./burst.sh 123e4567-e89b-12d3-a456 A1

SHOW_ID=$1
SEAT=$2
URL="http://localhost:8080/shows/$SHOW_ID/reserve"

echo "Launching hot-seat storm for seat $SEAT..."

# Launch 200 parallel requests
for i in $(seq 1 200); do
  IDKEY="burst-$i"
  TOKEN="Bearer user-$i-token"
  BODY="{\"seats\":[\"$SEAT\"]}"

  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST "$URL" \
    -H "Authorization: $TOKEN" \
    -H "Idempotency-Key: $IDKEY" \
    -H "Content-Type: application/json" \
    -d "$BODY" &
done

wait
echo "Storm complete."

# Verify reconciliation invariant
echo "Checking show state..."
curl -s "http://localhost:8080/shows/$SHOW_ID" | jq .
