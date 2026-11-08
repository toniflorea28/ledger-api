#!/usr/bin/env bash
# Publishes a payment-settled message to the local stack's Kafka (topic payments.settled,
# keyed by paymentId), the way the payment system would.
#
#   scripts/send-payment.sh <merchantAccountId> <amount> <currency> [paymentId] [eventId]
#   scripts/send-payment.sh --malformed        unparseable payload, to watch the dead-letter path
#
# Re-run with the same paymentId (and any eventId) to see redelivery recorded only once.
set -euo pipefail
cd "$(dirname "$0")/.."

if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose); else COMPOSE=(docker-compose); fi
uuid() { uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || cat /proc/sys/kernel/random/uuid; }

if [[ "${1:-}" == "--malformed" ]]; then
  key="malformed-$(uuid)"
  payload='{"eventId": "evt_broken", "paymentId": '
else
  [[ $# -ge 3 ]] || { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
  merchant=$1; amount=$2; currency=$3
  payment=${4:-pay_$(uuid)}; event=${5:-evt_$(uuid)}
  key=$payment
  payload=$(printf '{"eventId":"%s","paymentId":"%s","merchantAccountId":"%s","amount":"%s","currency":"%s","occurredAt":"%s"}' \
    "$event" "$payment" "$merchant" "$amount" "$currency" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
fi

printf '%s|%s\n' "$key" "$payload" | "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic payments.settled \
  --property parse.key=true --property key.separator='|' >/dev/null
echo "sent to payments.settled with key $key:"
echo "  $payload"
