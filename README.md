# Ledger API

A double-entry ledger for a payments platform. Money is held in accounts (EUR, USD, JPY)
and moves only through transactions: two or more entries that always balance. A recorded
transaction is never changed; mistakes are corrected by a reversal. Transactions come in
through a REST API and from `payment-settled` messages on Kafka, and every recorded
transaction is announced to other services. Several instances run against one MySQL.

Java 17, Spring Boot 4.1, MySQL 8.4, Kafka 4.3, Docker. AI usage: [AI_USAGE.md](AI_USAGE.md).

## Running

Needs Docker (Compose v2), Git and a JDK 17+. Maven comes from the wrapper.

```bash
./run.sh              # build, unit + integration tests, start the stack, end-to-end checks
./run.sh --skip-tests # build and start only
./run.sh --down       # stop and remove volumes
```

The stack: an nginx gateway on `http://localhost:8080` round-robins over two instances
(`ledger-1` on 8081, `ledger-2` on 8082), MySQL on 3306 (`ledger`/`ledger`), Kafka on
29092. Integration tests use Testcontainers with the same images. Health at
`/actuator/health`, metrics at `/actuator/prometheus`.

## API

Contract: [`src/main/resources/openapi/ledger-v1.yaml`](src/main/resources/openapi/ledger-v1.yaml)
(the controller interfaces are generated from it at build time). Amounts are decimal
strings. Every `POST` needs an `Idempotency-Key`. Errors are RFC 9457 problems.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/accounts` | open an account: `{"currency":"EUR","overdraftLimit":"30.00"}` |
| `GET` | `/v1/accounts/{id}` | account with balance and available balance |
| `GET` | `/v1/accounts/{id}/entries?limit=&cursor=` | entries newest first, each with the balance after it |
| `GET` | `/v1/accounts/{id}/balance?at=` | balance as at an instant |
| `GET` | `/v1/settlement-accounts` | the ledger's own accounts, one per currency |
| `POST` | `/v1/transactions` | record a transaction |
| `GET` | `/v1/transactions/{id}` | read it, with `reverses` / `reversedBy` links |
| `POST` | `/v1/transactions/{id}/reversals` | reverse it (once; a reversal cannot be reversed) |

Record the challenge's payout (Merchant 42 pays out 98.50 with a 1.50 fee):

```bash
curl -s -X POST localhost:8080/v1/transactions -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{
    "description": "payout",
    "entries": [
      {"accountId": "<merchant42>", "direction": "DEBIT",  "amount": "100.00"},
      {"accountId": "<payouts>",    "direction": "CREDIT", "amount": "98.50"},
      {"accountId": "<fees>",       "direction": "CREDIT", "amount": "1.50"}
    ]}'
```

A refusal records nothing and names the rule and the field:

```json
HTTP/1.1 422   Content-Type: application/problem+json
{"type":"urn:ledger:problem:refused","title":"Refused by a ledger rule","status":422,
 "detail":"account <merchant42> would go from 25.50 to -24.50, past its overdraft limit of 0.00",
 "errors":[{"code":"OVERDRAFT_EXCEEDED","detail":"...","pointer":"/entries/0/accountId"}]}
```

Codes: `TOO_FEW_ENTRIES`, `NON_POSITIVE_AMOUNT`, `SCALE_EXCEEDED`, `UNKNOWN_ACCOUNT`,
`CURRENCY_MISMATCH`, `UNBALANCED`, `OVERDRAFT_EXCEEDED`, `INVALID_CURRENCY_CODE`,
`UNSUPPORTED_CURRENCY`, `INVALID_AMOUNT` (422); `ALREADY_REVERSED`,
`CANNOT_REVERSE_REVERSAL`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_KEY_IN_PROGRESS` (409);
`MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_MISSING` (400); `NOT_FOUND` (404). All independent
faults are reported together. Responses carry `X-Trace-Id` and `X-Instance-Id`.

Retrying with the same key and body returns the first answer, success or refusal, from
any instance, for 24 hours. The same key with a different body is refused.

## Payment-settled messages

```bash
scripts/send-payment.sh <merchantAccountId> 125.50 EUR          # records example 1
scripts/send-payment.sh <merchantAccountId> 125.50 EUR pay_42   # same paymentId: recorded once
scripts/send-payment.sh --malformed                             # goes to the dead-letter topic
scripts/read-dead-letters.sh                                    # DLT with reason headers
```

Topic `payments.settled`, keyed by `paymentId`, JSON
([schema](src/main/resources/schemas/payment-settled.v1.schema.json)):
`{"eventId","paymentId","merchantAccountId","amount","currency","occurredAt"}`.
Each message debits the settlement account of its currency and credits the merchant.
Deduplication is on `paymentId`, so a re-send under a new `eventId` is still recorded once.
A message that can never be recorded (malformed, unknown account, wrong currency, bad
amount) goes to `payments.settled.dlt` with `ledger-error-code` and `ledger-error-detail`
headers and the partition moves on; transient failures retry with backoff while the
partition waits, and are dead-lettered after ten attempts. Nothing is lost silently.

## Design

**Structure.** Hexagonal: `domain` (pure Java rules: Money, Account, Posting, Reversal),
`application` (use cases, ports, transaction boundaries, idempotency), `adapter`
(`inbound/rest`, `inbound/messaging`, `outbound/persistence`, `outbound/messaging`),
`infrastructure` (Spring wiring). ArchUnit enforces the direction. One service, because
"balances, entries, outbox row and idempotency key commit together" needs one database
transaction.

**Money.** `long` minor units plus a currency enum carrying the ISO 4217 scale. No
floating point, no `BigDecimal` arithmetic; `100.5` JPY is refused, not rounded.

**Recording.** One path for API, reversals and payments: lock the accounts with
`SELECT ... FOR UPDATE` in ascending id order (so A→B and B→A cannot deadlock), read the
database clock, validate in the domain, insert transaction and entries (each with
`balance_after`), update balances, append the outbox row, commit. Transactions and
entries are immutable in code and by database triggers; a `UNIQUE` on `reverses_id`
makes "reversed once" a database fact. Pessimistic locks were chosen over optimistic
because the settlement accounts are hot.

**Idempotency.** The key row is inserted first, in the same transaction as the effect;
the operation runs in a nested transaction (savepoint); the rendered response is stored
on the row before commit. A crash leaves neither key nor effect. A refusal rolls back to
the savepoint and is stored too. Concurrent retries block on InnoDB's unique index until
the first attempt commits, then replay. The request is fingerprinted (SHA-256 of method,
path, canonical body) so a different request is never taken for a retry.

**Broker.** Kafka, for partition ordering, retention and a standard dead-letter path.
Topics are declared by the application and created at startup.

**Announcing.** Transactional outbox. The row is written in the posting transaction; one
relay at a time (database lease, 15 s) publishes to `ledger.transaction-recorded.v1` in
id order, keyed by currency, each record acknowledged (`acks=all`, idempotent producer)
before the next, then marked published. Payload per
[schema](src/main/resources/schemas/transaction-recorded.v1.schema.json); headers
`event-id`, `event-type`, `transaction-id`, `traceparent`.

**Observability.** W3C tracing through HTTP, database and Kafka: the trace that records a
transaction is stored with its outbox row and becomes the parent of the publish span, so
one trace id covers the request or message, the logs and the announcement. ECS JSON logs
in the containers with `traceId`, `spanId`, `paymentId`, `eventId`. Readiness includes
database and broker.

## What a consumer of announcements can rely on

- **Never misses a transaction**: the outbox row commits with the transaction.
- **May see one twice**: a crash between publish and mark republishes it. Dedupe on
  `eventId` or `transaction.id`.
- **Order**: per currency, in recording order, on one partition. None across currencies.
- **Crash right after recording**: the event is already in the outbox and is published
  when a relay resumes. Late, never lost.

## Tests

`./mvnw -B test`: 68 unit tests, no Docker. `./mvnw -B verify`: plus 41 integration
tests on Testcontainers. `./run.sh`: plus 5 checks through the gateway across two JVMs.

- The challenge's examples and every refusal, with codes and pointers (`PostingTest`, `LedgerApiIT`).
- A refused transaction leaves no row and no balance change (`AtomicityIT`).
- A→B and B→A from 16 threads, 100 parallel settlements on one account, 20 parallel
  debits of a 10.00 balance recording exactly one (`ConcurrencyIT`, `StackE2EIT`).
- Retries replay the first answer, refusals included; 20 concurrent identical requests
  take effect once; a crash after the effect leaves nothing (`IdempotencyIT`, `IdempotentExecutorIT`).
- One transaction per payment under any `eventId`; five poison messages dead-lettered
  with reasons while the good one behind them is recorded (`SettlementIT`).
- Every transaction announced, schema-valid, ordered per currency; a lost mark republishes
  (`AnnounceIT`). Trace ids flow end to end (`TraceIT`). Triggers refuse changes (`ImmutabilityIT`).
- After every test that moves money: per-currency balances sum to zero and each balance
  equals the sum of its entries.

## Assumptions

- Ids are server-generated UUIDv7; settlement accounts have fixed ids ending in the ISO
  numeric code (`...0978` EUR, `...0840` USD, `...0392` JPY).
- `Idempotency-Key` is mandatory on every `POST`, scoped per operation, kept 24 hours.
- No authentication or tenancy; a production version scopes keys and accounts by client.
- Currency codes are matched exactly. Extra decimals are refused even when zero (`100.500` EUR).
- Settlement accounts have no overdraft limit; others have the limit given at opening, zero by default.
- Balance as at `t` is the balance after the last entry recorded at or before `t`; zero before the first.

## Improvements

Before production: authentication and per-client scoping; a privileged migration user so
the app user has no DDL and binary logging can stay on (it is off here because trigger
creation needs SUPER otherwise); lock-free updates or sharding for the hot settlement
accounts; a pipelined relay for throughput; an OTLP exporter for the traces; retention
for published outbox rows; contract tests of live responses against the OpenAPI document;
dead-letter replay tooling; a scheduled reconciliation job (the tests do it, production
should too).

Skipped: multi-currency transactions and FX (out of scope by the rules); account closing
and limit changes (not asked); Debezium for the outbox (a second moving part; the polling
relay keeps the guarantees explicit); a multi-module build (packages plus ArchUnit are
enough at this size).
