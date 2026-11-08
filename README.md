# Ledger API

A double-entry ledger for a payments platform. Money is held in accounts (EUR, USD,
JPY) and moves only through transactions: two or more entries that always balance.
A recorded transaction is never changed; a mistake is corrected by a reversal, which
is a new transaction. Transactions arrive through a REST API and from
`payment-settled` messages on Kafka, and every recorded transaction is announced to
other services. The service runs as several instances against one MySQL.

Stack: Java 17, Spring Boot 4.1, MySQL 8.4, Apache Kafka 4.3, Docker.

Contents: [Running](#running) · [Sample requests](#sample-requests) ·
[Sending a payment-settled message](#sending-a-payment-settled-message) ·
[Design](#design) · [What a consumer can rely on](#what-a-consumer-of-announcements-can-rely-on) ·
[Tests](#tests) · [Assumptions](#assumptions) · [Improvements](#improvements) ·
[AI usage](AI_USAGE.md)

## Running

Needs only Docker (with Compose v2), Git and a JDK 17+ on the host. Maven is fetched
by the wrapper; everything else runs in containers.

```bash
./run.sh
```

This builds the service, runs the unit tests and the integration tests (against a real
MySQL and Kafka started by Testcontainers), builds the Docker image, starts the stack
below, waits for it to be healthy, and runs a last set of checks through the gateway
against the two running instances. It takes a few minutes the first time.

| Component | Where | Notes |
|---|---|---|
| Gateway (nginx) | `http://localhost:8080` | round-robins over `ledger-1` and `ledger-2`; use this |
| `ledger-1`, `ledger-2` | `http://127.0.0.1:8081`, `:8082` | the two instances, for poking at one directly |
| MySQL 8.4 | `127.0.0.1:3306` | `ledger` / `ledger`, database `ledger` |
| Kafka 4.3 (KRaft) | `127.0.0.1:29092` | topics `payments.settled`, `payments.settled.dlt`, `ledger.transaction-recorded.v1` |

Other modes:

```bash
./run.sh --skip-tests   # build and start only
./run.sh --down         # stop everything and remove volumes
./mvnw -B test          # unit tests only, no Docker needed
./mvnw -B verify        # unit + integration tests (Docker needed, nothing else)
```

Health: `GET /actuator/health` (readiness includes the database and the broker).
Metrics: `GET /actuator/prometheus` (`ledger.outbox.published`, `ledger.outbox.unpublished`).

`run.sh` exports `DOCKER_HOST` from the active docker context and sets the
Testcontainers socket override, so colima, Rancher Desktop and remote daemons work
without configuration. It uses `docker compose` or, failing that, `docker-compose`.

## Sample requests

The API contract is [`src/main/resources/openapi/ledger-v1.yaml`](src/main/resources/openapi/ledger-v1.yaml).
Amounts are decimal strings. Every `POST` needs an `Idempotency-Key`. Errors are
RFC 9457 problems (`application/problem+json`).

The three settlement accounts exist from the start, one per currency, with fixed ids
ending in the currency's ISO 4217 numeric code:

```bash
curl -s localhost:8080/v1/settlement-accounts
# EUR 00000000-0000-7000-8000-000000000978
# USD 00000000-0000-7000-8000-000000000840
# JPY 00000000-0000-7000-8000-000000000392
```

Open accounts (the challenge's Merchant 42, Payouts EUR, Fees EUR):

```bash
curl -s -X POST localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"currency":"EUR"}'
```

```json
{"id":"01a1bb6f-786f-700c-87c1-dd26bab0c329","currency":"EUR","kind":"REGULAR",
 "overdraftLimit":"0.00","balance":"0.00","availableBalance":"0.00","openedAt":"2026-11-08T13:53:27.163688Z"}
```

Add `"overdraftLimit":"30.00"` to allow the balance to go down to −30.00. Repeat the
request with the same `Idempotency-Key` and you get the same answer; with the same
key but a different body you get `409 IDEMPOTENCY_KEY_REUSED`.

Record a transaction (example 2: Merchant 42 paid out 98.50 with a 1.50 fee):

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

```json
{"id":"01a1bb6f-7a2e-7d40-9d26-1c9d3f1d2a11","description":"payout","currency":"EUR",
 "recordedAt":"2026-11-08T13:53:27.611102Z",
 "entries":[{"accountId":"<merchant42>","direction":"DEBIT","amount":"100.00","balanceAfter":"25.50"},
            {"accountId":"<payouts>","direction":"CREDIT","amount":"98.50","balanceAfter":"98.50"},
            {"accountId":"<fees>","direction":"CREDIT","amount":"1.50","balanceAfter":"1.50"}]}
```

A refused transaction records nothing and says which rule failed and where
(example 5, Merchant 42 would go to −24.50):

```json
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json
{"type":"urn:ledger:problem:refused","title":"Refused by a ledger rule","status":422,
 "detail":"account <merchant42> would go from 25.50 to -24.50, past its overdraft limit of 0.00",
 "instance":"/v1/transactions",
 "errors":[{"code":"OVERDRAFT_EXCEEDED","detail":"account <merchant42> would go from 25.50 to -24.50, past its overdraft limit of 0.00",
            "pointer":"/entries/0/accountId"}]}
```

`pointer` is a JSON Pointer into the request body. All independent faults are
reported at once (a bad amount in entry 1 and an unknown account in entry 2 come back
together). Codes: `TOO_FEW_ENTRIES`, `NON_POSITIVE_AMOUNT`, `SCALE_EXCEEDED`,
`UNKNOWN_ACCOUNT`, `CURRENCY_MISMATCH`, `UNBALANCED`, `OVERDRAFT_EXCEEDED`,
`INVALID_CURRENCY_CODE`, `UNSUPPORTED_CURRENCY`, `INVALID_AMOUNT` (422);
`ALREADY_REVERSED`, `CANNOT_REVERSE_REVERSAL`, `IDEMPOTENCY_KEY_REUSED`,
`IDEMPOTENCY_KEY_IN_PROGRESS` (409); `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_MISSING`
(400); `NOT_FOUND` (404).

Reverse, read, and browse:

```bash
# Reversal: a new transaction with every entry flipped, linked to the original. Once only.
curl -s -X POST localhost:8080/v1/transactions/<txId>/reversals -H "Idempotency-Key: $(uuidgen)"

# A transaction, with reverses / reversedBy links
curl -s localhost:8080/v1/transactions/<txId>

# An account with its balance; its entries newest first, each with the balance after it
curl -s localhost:8080/v1/accounts/<id>
curl -s "localhost:8080/v1/accounts/<id>/entries?limit=20"          # follow nextCursor for older pages
curl -s "localhost:8080/v1/accounts/<id>/balance?at=2026-11-08T13:53:27Z"
```

Every response carries `X-Trace-Id` (the trace id you also find on each log line and
on the announcement) and `X-Instance-Id` (which instance answered). Send your own
W3C `traceparent` header and the ledger continues that trace.

## Sending a payment-settled message

```bash
scripts/send-payment.sh <merchantAccountId> 125.50 EUR          # records example 1
scripts/send-payment.sh <merchantAccountId> 125.50 EUR pay_42   # same paymentId again: recorded once
scripts/send-payment.sh --malformed                             # unparseable: goes to the dead-letter topic
scripts/read-dead-letters.sh                                    # shows DLT messages with their reason headers
```

The message is JSON on topic `payments.settled`, keyed by `paymentId`
([schema](src/main/resources/schemas/payment-settled.v1.schema.json)):

```json
{"eventId":"evt_7f3a","paymentId":"pay_42","merchantAccountId":"<merchant>",
 "amount":"125.50","currency":"EUR","occurredAt":"2026-11-08T13:50:00Z"}
```

Each message records one transaction that debits the settlement account of that
currency and credits the merchant. A message the ledger can never record (malformed,
unknown account, wrong currency, bad amount) is published to `payments.settled.dlt`
with `ledger-error-code` and `ledger-error-detail` headers and its original payload,
and the partition moves on. Transient failures (database unavailable) are retried
with exponential backoff while the partition waits; after ten attempts the record is
dead-lettered too. Nothing is dropped without a trace.

## Design

The design was written down and agreed before the code, and the commit history
follows its phases. This section is the result.

### Structure

Hexagonal, one deployable, package by layer:

```
com.toniflorea.ledger
├── domain/         pure Java, no framework: Money, Currency, Account, Transaction,
│                   Posting (the rules), Reversal, LedgerError/LedgerException
├── application/    use cases (ports in), ports out, services with transaction
│                   boundaries, IdempotentExecutor, the announced event
├── adapter/
│   ├── inbound/rest        controllers implementing the generated API, problem handler
│   ├── inbound/messaging   payment-settled listener and poison classification
│   ├── outbound/persistence JdbcClient repositories, outbox table, lease, Flyway SQL
│   └── outbound/messaging  the outbox relay that publishes to Kafka
└── infrastructure/ Spring wiring, Kafka error handling, tracing, UUIDv7, properties
```

ArchUnit tests enforce the direction: the domain depends on nothing, the application
layer knows no adapter, adapters do not know each other. The domain has 68 unit tests
that run without Docker; everything that touches MySQL or Kafka is an integration test
against the real thing.

Why one service and not several: the invariant "balances, entries, outbox row and
idempotency key commit together" needs one database transaction, so one service owns
one schema. It is modular enough to split later.

### Money is exact

`Money` is a `long` in minor units plus a `Currency` enum carrying the ISO 4217 scale
(EUR 2, USD 2, JPY 0). Amounts enter as `DecimalAmount`, a validated decimal string,
and become `Money` only once the account's currency is known, so `100.5` on a JPY
account is refused as `SCALE_EXCEEDED` instead of rounded. There is no floating point
and no `BigDecimal` arithmetic anywhere; 0.10 + 0.20 is 30 minor units. JSON carries
amounts as strings so no client can send a float by accident.

### Recording a transaction

`PostingService` is the one path every transaction takes, from the API, from a
reversal, or from a payment message:

1. Lock the accounts of the draft with `SELECT ... FOR UPDATE`, in ascending id order.
2. Read the database clock.
3. Validate in the domain (`Posting.post`): at least two entries, positive amounts
   within scale, known accounts in one currency, debits equal credits, no account past
   its limit. Every independent violation is collected with a JSON Pointer.
4. Insert the transaction and its entries (each with `balance_after`), update the
   balances, append the outbox row. Commit.

Sorted lock acquisition is what makes A→B and B→A at the same moment deadlock-free:
both lock min(A, B) first and queue in the same direction. Balances are materialised on
the account row and re-derivable from the entries; the tests recompute them. The
database clock, read while the lock is held, is one clock across instances, so an
account's entries are monotonic in time and "balance as at" is well defined.
Optimistic locking was rejected because the settlement accounts are hot (every
payment touches one) and would thrash.

Immutability is enforced twice: nothing in the code updates or deletes transactions
or entries, and `BEFORE UPDATE` / `BEFORE DELETE` triggers refuse it at the database.
A `UNIQUE` on `reverses_id` makes "reversed once" a database fact as well; reversals
also lock the original row first, so concurrent reversal attempts serialise.

### Idempotency

Every `POST` takes an `Idempotency-Key`, scoped per operation. `IdempotentExecutor`
inserts the key row first, in the same database transaction as the operation, runs
the operation in a nested transaction (a savepoint), stores the rendered response on
the key row and commits all of it at once:

- A crash halfway leaves no key and no effect; the retry re-executes.
- A success is replayed byte for byte (status, body, `Location`) for 24 hours, from
  any instance, because the row is in the shared database.
- A refusal rolls back to the savepoint, which undoes the posting but keeps the key
  row, and the refusal is stored as the response. A retry of a refused request gets
  the same refusal even after the account was funded.
- A concurrent retry blocks on InnoDB's unique index until the first attempt commits,
  then replays. MySQL gives "wait for the in-flight twin" for free.
- The request is fingerprinted (SHA-256 of method, path and canonical JSON body); the
  same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`, so a different
  request is never mistaken for a retry.

### Settled payments

The consumer deduplicates on `paymentId`, never on `eventId`, because the payment
system may re-send under a new event id. The `processed_payments` row is written in
the posting's transaction, so a redelivery either finds it or collides with it on the
primary key; either way nothing is recorded twice. Poison messages and ledger
refusals are classified as non-retryable and dead-lettered with the reason; anything
else is treated as transient and retried with backoff, holding the partition on
purpose, because losing a payment is worse than delaying the ones behind it.

### Announcing recorded transactions

Transactional outbox. The outbox row is written in the posting transaction; a relay
publishes rows to `ledger.transaction-recorded.v1` in id order, keyed by currency,
each acknowledged by the broker (`acks=all`, idempotent producer) before the next, then
marks them published. One instance relays at a time: it holds a lease row in the
database and renews it on every tick; another instance takes over when the lease
expires. The payload is the JSON in
[`transaction-recorded.v1.schema.json`](src/main/resources/schemas/transaction-recorded.v1.schema.json);
headers carry `event-id`, `event-type`, `transaction-id` and `traceparent`.

Rejected: publishing after commit (a dual write that can lose events), publishing
before commit (phantom events), and CDC with Debezium (correct but heavy here).

### Broker and API choices

Kafka over RabbitMQ for partition ordering, retention and replay, and a standard
dead-letter story. Topics are declared by the application and created at startup.

REST, versioned in the path (`/v1`), resources `accounts`, `transactions` and
`transactions/{id}/reversals` (a reversal is a resource you create, not an action you
call). Problems carry machine-readable `errors[].code` and `errors[].pointer` so a
client can show the right field. The contract is written first in OpenAPI and the
controller interfaces are generated from it at build time, so a change the code does
not follow fails compilation. Evolution: additive fields in responses are
backwards-compatible; a breaking change gets `/v2` beside `/v1`.

### Observability

Micrometer Tracing with the OpenTelemetry bridge, W3C propagation. The trace that
records a transaction is stored with its outbox row and restored by the relay as the
parent of its publish span, so one trace covers the HTTP request or the payment
message, every log line in between, and the announcement. Logs are ECS JSON in the
containers with `traceId`, `spanId` and, on the consumer path, `paymentId` and
`eventId`. Readiness includes the database and the broker.

## What a consumer of announcements can rely on

- **Never misses a transaction.** The outbox row is committed with the transaction;
  the relay publishes everything in the table.
- **May see a transaction twice.** A crash between publish and mark republishes the
  row. Deduplicate on `eventId` (one per transaction) or on `transaction.id`.
- **Order.** Within one currency, in recording order, on one partition. No order across
  currencies. Duplicates caused by a relay failover may arrive after later events.
- **If the service crashes right after recording**, the row is already in the outbox and
  is published when a relay resumes. Nothing is lost; it is late.
- **Payload** follows the committed schema; the test suite validates every published
  record against it.

## Tests

`./mvnw -B test` runs 68 unit tests. `./mvnw -B verify` adds 41 integration tests on
Testcontainers, pinned to the same MySQL and Kafka images as `docker-compose.yml`.
`./run.sh` adds 5 checks against the running stack through the gateway.

| Guarantee | Where it is proven |
|---|---|
| Rules and refusals, with codes and pointers | `PostingTest`, `ReversalTest`, `LedgerApiIT` (every example and refusal in the challenge) |
| Exact money | `MoneyTest`, `DecimalAmountTest`, `CurrencyTest` |
| Atomic: a refused transaction leaves nothing | `AtomicityIT` (row counts, balances and outbox unchanged) |
| Concurrency: A→B and B→A, hot account, overdraft race | `ConcurrencyIT` (16 threads both ways, 100 parallel settlements, 20 parallel debits record exactly one), `StackE2EIT` across two JVMs |
| Idempotency: at most once, same answer, different request refused, crash halfway | `IdempotencyIT` (incl. 20 concurrent identical requests, and 20 concurrent identical refused ones), `IdempotentExecutorIT`, `StackE2EIT` across instances |
| Redelivery: one transaction per payment, new event id or not | `SettlementIT`, `StackE2EIT` |
| Poison messages do not block and are not lost | `SettlementIT` (five bad messages dead-lettered with reasons, the good one behind them recorded) |
| Announcements: none missed, duplicates possible, ordered per currency, schema | `AnnounceIT` |
| Integrity: per-currency sum is zero, balance equals its entries | asserted after every integration test class that moves money |
| Immutability at the database | `ImmutabilityIT` |
| Layering | `ArchitectureTest` (ArchUnit) |
| History and balance as at | `AccountHistoryIT` |
| Tracing end to end | `TraceIT` |

## Assumptions

Things the challenge left open, and what was decided:

- Account and transaction ids are server-generated UUIDv7 (time-ordered, good for
  InnoDB). Settlement account ids are fixed by migration and listed by the API.
- `Idempotency-Key` is mandatory on every `POST` (400 without it), up to 255 characters,
  unique per operation, remembered for 24 hours.
- No authentication or tenancy; keys and accounts are global. Production would scope
  both by API client.
- Currency codes are matched exactly (`EUR`, not `eur`). "Not ISO 4217" and "ISO 4217
  but unsupported" are distinct errors, as in the challenge's examples.
- An amount with more decimals than the currency allows is refused even when the extra
  decimals are zeros (`100.500` EUR).
- An account may appear in several entries of one transaction; `balanceAfter`
  accumulates in entry order and the limit is checked on the final balance.
- A reversal's description defaults to `Reversal of <id>`. An original cannot be
  reversed twice and a reversal cannot be reversed.
- Settlement accounts have no overdraft limit (`overdraftLimit` and `availableBalance`
  are absent for them); all other accounts have the limit given at opening, zero by
  default.
- The payment-settled message is JSON with `eventId`, `paymentId`, `merchantAccountId`,
  `amount`, `currency`, `occurredAt`, keyed by `paymentId`. A message naming an account
  of another currency is refused as `CURRENCY_MISMATCH` and dead-lettered.
- Balance as at `t` is the balance after the last entry recorded at or before `t`;
  zero before the first entry.

## Improvements

Before production:

- **Security**: authentication (mTLS or OAuth2 client credentials), per-client scoping
  of accounts and idempotency keys, and a separate migration user; the application
  user should have no DDL and MySQL should run with binary logging on (it is off here
  only because trigger creation needs SUPER otherwise).
- **Hot accounts**: the settlement accounts serialise every payment in their currency
  through one row lock. Accounts without a limit do not need the pre-check, so they
  could take an atomic `UPDATE balance = balance + ?` instead of a lock, or be sharded
  into sub-accounts that are summed on read.
- **Relay throughput**: the relay sends one record at a time to keep per-currency
  order simple. Pipelining within a partition with the idempotent producer's ordering
  guarantee, or one relay per partition key, would raise throughput.
- **Exporting traces and metrics**: an OTLP exporter and a collector; today traces are
  created and propagated but not exported anywhere.
- **Retention**: idempotency keys are swept after 24 hours; the outbox keeps published
  rows and would need archiving or deletion after a retention period.
- **Contract testing**: validate live responses against the OpenAPI document in the
  integration tests, in addition to generating the interfaces from it.
- **Dead-letter operations**: a tool to inspect and replay dead letters once the cause
  (for example, a merchant account opened late) is fixed.
- **Reconciliation job**: a periodic check that every balance equals the sum of its
  entries and that each currency sums to zero, alerting on drift. The tests do this;
  production should too.

Skipped, and why:

- Multi-currency transactions and FX: out of scope by the challenge's rules (one
  currency per transaction).
- Account closing, updating limits: not asked; both would be new, small use cases on
  the same posting path.
- Debezium/CDC for the outbox: correct but a second moving part to run; the polling
  relay is enough at this scale and keeps the guarantees explicit.
- Multi-module Maven build: the package structure and ArchUnit give the boundaries
  without the build overhead for a service this size.

## How it was built

The design was settled before any code, and the commits follow its ten phases, each
one green before the next. AI assistance and how its output was checked are described
in [AI_USAGE.md](AI_USAGE.md).
