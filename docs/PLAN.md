# Ledger API — Service Structure Plan

Status: proposal for review, no code written yet.
Source: "Backend Code Challenge — Ledger API" (PDF), re-read in full.

---

## 1. What we are building, in one paragraph

A double-entry ledger service for a payments platform. Money sits in accounts
(EUR/USD/JPY, exact minor units, optional overdraft limit) and moves only through
immutable, balanced transactions of two or more entries. Transactions arrive via a
REST API and via `payment-settled` messages from a broker; every recorded transaction
(reversals included) is announced to other services. The service runs as several
instances against one MySQL and must stay exact under concurrency, client retries and
message redelivery. One script builds, starts and tests everything in Docker.

## 2. Requirements distilled (what the evaluators check)

| Area | Hard requirement | Consequence for design |
|---|---|---|
| Integrity | Per-currency balances always sum to zero; all-or-nothing posting; never update/delete | Balance mutation, entry insert and outbox insert in **one DB transaction**; DB-level immutability guard |
| Validation | ≥2 entries, positive amounts, scale ≤ currency, same currency, accounts exist, debits = credits, overdraft never exceeded; caller told **which part** failed | Domain rule engine producing error codes + JSON pointers; RFC 9457 problem responses |
| Reversal | New transaction with flipped entries, linked; only once; reversal of reversal refused; obeys overdraft | `reverses_id` unique column enforces "once" at the DB; domain rule for "not a reversal" |
| Settlement feed | Message = eventId, paymentId, merchant account, amount, currency; bad message must not block or vanish | Inbox table keyed by **paymentId**; dead-letter topic + structured log for poison messages |
| Announce | Every transaction announced; README states loss / duplicate / ordering / crash semantics | **Transactional outbox** → Kafka; at-least-once, ordered per currency |
| Concurrency | Multi-instance; A→B with B→A simultaneously; no lost update, no deadlock | Row locks (`SELECT … FOR UPDATE`) acquired in **sorted account-id order** |
| Idempotency | Open account, record, reverse: at most once; same answer on retry on any instance; never confuse a different request with a retry | `Idempotency-Key` header + request fingerprint; key row stored in the **same DB tx** as the effect |
| Redelivery | Same payment recorded once even under a new eventId | Dedupe on `paymentId`, not `eventId` |
| Money | Never approximated | `long` minor units + currency scale; amounts travel as **decimal strings** in JSON |
| Stack | Java 17, Spring Boot 4, MySQL, REST, broker of choice, all in Docker, one script | Maven wrapper, Testcontainers, docker compose with 2 app replicas |
| Nice to have | API-first; paged entries with balance-after; balance as at instant; traceability | OpenAPI first + contract check; `balance_after` on every entry; W3C trace context through Kafka headers |

## 3. Key design decisions (with the alternative we rejected)

### 3.1 Architecture: hexagonal, single deployable
Ports & adapters, package-by-layer inside one Spring Boot module. The domain is plain
Java (no Spring, no JDBC). Application services orchestrate use cases and own
transaction boundaries. Adapters: REST in, Kafka in, MySQL out, Kafka out.
ArchUnit tests enforce the dependency direction.
*Rejected:* microservice split (accounts / transactions / announcer): the invariant
"balance + entries + outbox commit together" needs one database transaction, so one
service owning one schema is the honest design. The code is still modular enough to
split later.

### 3.2 Money: `long` minor units, never floating point
`Money(long minorUnits, Currency currency)`; `Currency` is a closed enum
(EUR=2, USD=2, JPY=0). Parsing from a decimal string rejects more decimals than the
currency allows (`¥100.5` → `SCALE_EXCEEDED`). DB column `BIGINT`. JSON carries
amounts as strings (`"125.50"`, `"15000"`), so no client can accidentally send a float.
*Rejected:* `BigDecimal` everywhere — correct but easy to misuse (scale drift,
`equals` vs `compareTo`), and `DECIMAL` columns invite arithmetic in SQL.

### 3.3 Concurrency: pessimistic row locks in canonical order
Posting a transaction:
1. Collect distinct account ids, **sort them**, `SELECT … FOR UPDATE` in that order.
2. Validate currencies, compute new balances, check overdraft.
3. Insert transaction, entries (each with `balance_after`), outbox event; update account balances.
4. Commit.

Sorted lock acquisition makes A→B / B→A deadlock-free (both lock min(A,B) first).
Balance is materialised on the account row and re-derivable from entries (an
integrity test recomputes it). Isolation `READ COMMITTED` on the posting path to avoid
InnoDB gap-lock surprises on concurrent inserts.
*Rejected:* optimistic locking with a version column. Settlement accounts are hot
(every payment touches them); optimistic retries would thrash exactly there.
Lock-free `UPDATE balance = balance + ?` for limitless accounts is noted as a
production improvement.

### 3.4 Idempotency: header + fingerprint, stored in the same transaction
Every `POST` requires `Idempotency-Key` (client-generated, ≤255 chars). Table
`idempotency_keys(scope, key) PK, request_fingerprint, status, body`, where scope is
the operation (`open-account`, `record-transaction`, `reverse:{txId}`).

Flow (success path): begin tx → `INSERT` key row → run use case (ids generated
up-front as UUIDv7 so the stored response is byte-identical to the live one) → store
response on the key row → commit. Business effect and key commit atomically, so a
crash halfway leaves no key and no effect, and the retry re-executes cleanly.

Concurrent duplicate: the second `INSERT` **blocks on InnoDB's unique-index lock** until
the first commits or rolls back. Then it either hits a duplicate-key error (read and
return the stored response) or succeeds (the first rolled back; proceed). MySQL gives
us "wait for the in-flight twin" for free, across instances.

Refusals (4xx) are also remembered. The operation runs in a nested transaction (a
savepoint) inside the key's transaction: a refusal rolls back to the savepoint, which
undoes the effects but keeps the key row and its lock, and the refusal is stored as the
response before the same commit. Waiting retries therefore never see the lock released
and simply replay. (An earlier draft used a second transaction for refusals; releasing
the lock made twenty concurrent retries race each other.)

Different request, same key: fingerprint (SHA-256 of method + path + canonical body)
mismatch → `409 IDEMPOTENCY_KEY_REUSED`. Keys expire (e.g. 24 h) via a sweeper.
*Rejected:* letting the client choose the transaction id as the only idempotency
mechanism — works for transactions, not for "open account" or for remembering refusals.

### 3.5 Redelivery: inbox keyed by paymentId
`processed_payments(payment_id PK, event_id, transaction_id)` inserted in the **same
DB tx** as the ledger transaction. A redelivered payment, with the same or a new
`eventId`, hits the PK and is acknowledged without effect. The settlement account per
currency is seeded by migration (`kind = SETTLEMENT`, unique per currency).

Poison messages (malformed JSON, unknown account, currency mismatch, scale violation):
not retried (not transient), published to `payments.settled.dlt` with error-code
headers and the original payload, logged with trace id, offset committed → the
partition moves on. Transient failures (DB unavailable) retry with backoff and block
the partition on purpose: losing data is worse than lag.

### 3.6 Announcing: transactional outbox → Kafka
`outbox_events` row written in the posting transaction. A relay publishes rows in id
order to topic `ledger.transaction-recorded.v1`, keyed by **currency**, marks them
published after the broker acks (`acks=all`, idempotent producer). One active relay at a
time via a DB lease row (short TTL, any instance can take over), so partition order
equals commit order within a currency.

What a consumer can rely on (goes into the README verbatim):
- **Never misses** a transaction: the event is committed with the transaction.
- **May see one twice**: crash between publish and mark-published → republish. Dedupe on `transactionId`.
- **Order**: per currency, in recording order. No order across currencies.
- **Crash right after recording**: the event is already in the outbox; it is published when the relay resumes.

*Rejected:* publishing from the service after commit (dual write → can lose events);
Debezium CDC (correct but heavy for this scope); publishing before commit (phantom events).
Kafka over RabbitMQ: partition ordering, retention/replay, and a standard DLT story
with spring-kafka.

### 3.7 API: resources, errors, versioning
Prefix `/v1`. Amounts are strings. Errors are RFC 9457 `application/problem+json`
with `type`, `title`, `status`, `detail`, and an `errors[]` array of
`{ code, detail, pointer }` where `pointer` is a JSON Pointer into the request
(`/entries/1/amount`). Status mapping: 400 malformed, 404 unknown resource in the
path, 409 state conflict (`ALREADY_REVERSED`, `CANNOT_REVERSE_REVERSAL`,
`IDEMPOTENCY_KEY_REUSED`), 422 domain refusal (`UNBALANCED`, `OVERDRAFT_EXCEEDED`,
`SCALE_EXCEEDED`, `CURRENCY_MISMATCH`, `TOO_FEW_ENTRIES`, `UNKNOWN_ACCOUNT`,
`UNSUPPORTED_CURRENCY`, `INVALID_CURRENCY_CODE`, `NON_POSITIVE_AMOUNT`).

| Method & path | Purpose | Notes |
|---|---|---|
| `POST /v1/accounts` | Open account | body `{currency, overdraftLimit?}`; `Idempotency-Key` required; 201 + `Location` |
| `GET /v1/accounts/{id}` | Account with balance | `balance`, `availableBalance` (= balance + overdraft limit) |
| `GET /v1/accounts/{id}/entries` | Entries newest first, paged | cursor pagination (opaque cursor = entry id); each with `balanceAfter` |
| `GET /v1/accounts/{id}/balance?at=` | Balance as at instant | from `balance_after` of the last entry ≤ `at` (DB clock) |
| `POST /v1/transactions` | Record transaction | `{description, entries:[{accountId, direction, amount}]}`; 201 |
| `GET /v1/transactions/{id}` | Read transaction | includes `reverses` / `reversedBy` links |
| `POST /v1/transactions/{id}/reversals` | Reverse | 201 with the new transaction; `Idempotency-Key` required |
| `GET /v1/settlement-accounts` | Discover seeded settlement accounts | convenience for clients/tests |

API-first: `src/main/resources/openapi/ledger-v1.yaml` is written first;
`openapi-generator` produces the controller interfaces (`interfaceOnly`) so the code
cannot drift from the contract, and a test validates live responses against the spec
(swagger-request-validator). Fallback if the generator misbehaves with Boot 4:
hand-written controllers + the validator test only.

### 3.8 Persistence: Flyway + `JdbcClient`, no JPA
Explicit SQL in repository adapters (`JdbcClient`, Spring 6.1+). A ledger has a handful
of queries and every one of them matters (`FOR UPDATE`, ordered inserts, paging);
JPA would hide the exact statements we need to reason about.
Immutability is enforced in the DB too: `BEFORE UPDATE` / `BEFORE DELETE` triggers on
`transactions` and `entries` raise `SIGNAL SQLSTATE '45000'`.

Schema (minor units everywhere, ids `BINARY(16)` UUIDv7):

```
accounts            id PK, currency CHAR(3), kind ENUM(REGULAR, SETTLEMENT),
                    overdraft_limit_minor BIGINT, balance_minor BIGINT,
                    created_at TIMESTAMP(6);  UNIQUE(kind, currency) for SETTLEMENT
transactions        id PK, currency, description, recorded_at TIMESTAMP(6) (DB clock),
                    reverses_id NULL UNIQUE → transactions.id
entries             id BIGINT AUTO_INCREMENT PK, transaction_id FK, account_id FK,
                    position SMALLINT, direction ENUM(DEBIT, CREDIT),
                    amount_minor BIGINT, balance_after_minor BIGINT, recorded_at
                    INDEX(account_id, id DESC), INDEX(account_id, recorded_at)
idempotency_keys    scope, idem_key PK(scope, idem_key), request_fingerprint BINARY(32),
                    response_status SMALLINT, response_body JSON, created_at, expires_at
processed_payments  payment_id PK, event_id, transaction_id, processed_at
outbox_events       id BIGINT AUTO_INCREMENT PK, event_type, aggregate_id,
                    partition_key, payload JSON, trace_headers JSON,
                    created_at, published_at NULL;  INDEX(published_at, id)
relay_lease         name PK, owner, expires_at
```

`recorded_at` comes from the **database clock** inside the lock, so "balance as at" is
monotonic per account even with skewed instance clocks.

### 3.9 Observability
Micrometer Tracing (OpenTelemetry bridge). `traceparent` is accepted on HTTP, stored
with the outbox row and set as a Kafka header on publish; the consumer continues the
trace from the incoming message. Structured JSON logs (Boot's `logging.structured`)
with `traceId`, `paymentId`, `transactionId`, `idempotencyKey` in MDC. Actuator
health (DB + Kafka in readiness), Prometheus metrics, graceful shutdown.

## 4. Package structure

```
com.<org>.ledger
├── domain/                     pure Java, no framework
│   ├── money/                  Money, Currency (EUR, USD, JPY), MoneyParser
│   ├── account/                Account, AccountId, AccountKind, OverdraftLimit
│   ├── transaction/            Transaction, Entry, Direction, TransactionId,
│   │                           PostingRules (balance, scale, currency, overdraft)
│   └── error/                  LedgerError(code, pointer, detail), LedgerException
├── application/
│   ├── usecase/                inbound ports: OpenAccount, RecordTransaction,
│   │                           ReverseTransaction, RecordSettledPayment, queries
│   ├── port/                   outbound ports: AccountRepository, TransactionRepository,
│   │                           OutboxRepository, IdempotencyStore, ProcessedPaymentStore,
│   │                           IdGenerator, DbClock
│   ├── service/                use-case implementations, @Transactional boundaries
│   └── idempotency/            IdempotentExecutor (the §3.4 flow)
├── adapter/                    (`inbound`/`outbound`: `in` is a Java keyword)
│   ├── inbound/rest/              generated API interfaces, controllers, DTO mappers,
│   │                           ProblemDetailsHandler, IdempotencyKey resolver
│   ├── inbound/messaging/          PaymentSettledListener, message schema, DLT config
│   ├── outbound/persistence/       JdbcClient repositories, row mappers
│   └── outbound/messaging/         OutboxRelay (scheduled, leased), TransactionRecorded event
└── infrastructure/             Spring config: Kafka, Jackson, tracing, scheduling,
                                clock, leader lease, Flyway
src/main/resources/
├── openapi/ledger-v1.yaml      the contract (written first)
├── db/migration/               V1__schema.sql, V2__settlement_accounts.sql, V3__triggers.sql
└── application*.yaml
```

## 5. Repository layout

```
.
├── run.sh                      build → test (unit + Testcontainers) → compose up → e2e smoke
├── docker-compose.yml          mysql, kafka (KRaft), ledger-1, ledger-2, nginx (round-robin)
├── Dockerfile                  multi-stage, JRE 17 runtime
├── pom.xml, mvnw               Maven wrapper (needs only a JDK on the host)
├── scripts/send-payment.sh     publishes a sample payment-settled message via the kafka container
├── docs/PLAN.md                this file (later folded into README design sections)
├── README.md                   run, samples, design, guarantees, improvements
├── AI_USAGE.md                 how AI was used and how its output was checked
├── CLAUDE.md                   instruction file given to the AI (committed, as required)
└── src/main, src/test
```

## 6. Test strategy (proofs, not coverage)

| Guarantee | Test (real MySQL + Kafka via Testcontainers unless noted) |
|---|---|
| Rules | Unit tests on `PostingRules` for every row of the PDF's "refused" table, asserting code + pointer |
| Atomic refusal | Refuse an overdraft on entry 3 of 3 → row counts of accounts/transactions/entries/outbox unchanged |
| Concurrency | 2 accounts, N threads alternating A→B and B→A plus random amounts; never past overdraft, final balances exact, no lock timeout; hot settlement account with 200 parallel settlements |
| Idempotency | Same key twice → identical body and status, one transaction; concurrent same key from 20 threads → one effect; same key different body → 409; refusal replayed on retry |
| Redelivery | Same paymentId delivered 3× under 3 eventIds → one transaction, one announcement |
| Poison message | Malformed, then valid → valid processed; malformed on DLT with reason header |
| Announce | Every recorded tx appears on the topic; kill relay between publish and mark → duplicate observed, nothing missing; per-currency order equals recording order |
| Integrity | Random workload, then per-currency sum of balances = 0 and balance = Σ entries |
| Immutability | `UPDATE entries …` rejected by trigger |
| Architecture | ArchUnit: domain depends on nothing; adapters never call each other |
| Multi-instance (e2e) | `docker compose` with 2 replicas behind nginx; smoke suite replays idempotency and concurrency scenarios through the load balancer |

## 7. Implementation phases

1. **Scaffold**: Maven, Boot 4, Flyway, Testcontainers wiring, compose with MySQL + Kafka, `run.sh` skeleton, CLAUDE.md.
2. **Contract**: write `ledger-v1.yaml`, generate interfaces, problem-details format, message schemas.
3. **Domain**: Money, Currency, Account, Transaction, PostingRules, reversal rules — unit tests from the PDF examples.
4. **Persistence + posting**: schema, repositories, locked posting path, reversal, seeded settlement accounts, triggers; atomicity + concurrency ITs.
5. **Idempotency**: IdempotentExecutor, header handling, fingerprinting, sweeper; ITs.
6. **Settlement consumer**: listener, inbox dedupe, DLT, `send-payment.sh`; redelivery + poison ITs.
7. **Outbox relay**: lease, publisher, event schema, tracing headers; announce ITs.
8. **Nice-to-haves**: paged entries with balance-after, balance-at, trace propagation end to end.
9. **Packaging**: Dockerfile, 2-replica compose, e2e smoke, `run.sh` end to end.
10. **Docs**: README (run, samples, decisions, guarantees, improvements), AI_USAGE.md.

Each phase lands as its own commit(s) so the history reads as the design unfolding.

## 8. Assumptions to record in the README

- Account ids and transaction ids are server-generated UUIDv7; settlement account ids are fixed by migration and discoverable via the API.
- `Idempotency-Key` is mandatory on all `POST`s (400 if missing). Keys live 24 h.
- No authentication/tenancy: keys and accounts are global. A production version scopes both by API client.
- Overdraft limit is non-negative, in the account's currency, fixed at opening.
- A refused transaction is remembered under its idempotency key like a success is.
- `description` max 255 chars; optional on reversals (defaults to `Reversal of <id>`).
- Payment-settled message is JSON `{eventId, paymentId, merchantAccountId, amount, currency, occurredAt}` on topic `payments.settled`, keyed by `paymentId`.

## 9. Open questions for you

1. **Base package / org name** for the code (`com.<org>.ledger`)? I will default to `com.toniflorea.ledger` if you don't say.
2. **Build tool**: Maven wrapper (my default, boring and reliable) or Gradle?
3. **Kafka** confirmed as the broker? RabbitMQ is viable but weakens the ordering story.
4. **Account ids**: server-generated only, or also accept a client-supplied id on open (handy for merchants-by-name demos, adds a 409 path)?
5. **Scope of nice-to-haves**: do all three (paged entries, balance-at, tracing) or time-box?

## 10. Local prerequisites (your machine, checked today)

- Docker 29 is installed, but `docker compose` is not: install the Compose plugin (or Docker Desktop).
- No JDK found: install Temurin 17 (`brew install --cask temurin@17`) or via SDKMAN.
- Maven/Gradle not needed on the host; the wrapper downloads them.
