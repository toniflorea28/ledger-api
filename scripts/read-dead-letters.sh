#!/usr/bin/env bash
# Prints every message on payments.settled.dlt with its headers (the ledger-error-code and
# ledger-error-detail headers say why the ledger refused it). Ctrl-C to stop.
set -euo pipefail
cd "$(dirname "$0")/.."
if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose); else COMPOSE=(docker-compose); fi
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payments.settled.dlt --from-beginning \
  --property print.key=true --property print.headers=true --timeout-ms "${1:-5000}" 2>/dev/null || true
