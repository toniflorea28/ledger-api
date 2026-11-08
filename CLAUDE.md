# Ledger API — instructions for AI assistants

This file is committed as the challenge requires ("commit any instruction files you
gave it"). It is the standing brief for any AI session working on this repository.

## What this is
A double-entry ledger service for a payments platform: Java 17, Spring Boot 4, MySQL,
Kafka, REST. The requirements are in the challenge PDF; the design is in
`docs/PLAN.md`. Read the plan before changing anything structural.

## Non-negotiable invariants
- Money is exact: `long` minor units + currency scale. Never `double`/`float`, never
  `BigDecimal` arithmetic in the domain, never JSON numbers for amounts (strings only).
- A transaction is recorded all-or-nothing: entries, balance updates, outbox row and
  idempotency row commit in one database transaction.
- Transactions and entries are immutable: no UPDATE/DELETE paths in code, triggers in DB.
- Balances of all accounts in one currency always sum to zero.
- Account row locks are taken with `SELECT ... FOR UPDATE` in sorted account-id order.
- Settlement dedupe is on `paymentId`, never on `eventId`.
- Every announced event comes from the outbox table, never from a direct publish.

## Code layout (hexagonal)
`domain` (pure Java) ← `application` (use cases, ports, transactions) ← `adapter`
(`inbound/rest`, `inbound/messaging`, `outbound/persistence`, `outbound/messaging`) and `infrastructure`
(Spring config). ArchUnit tests enforce this; do not add Spring or JDBC to `domain`.

## Conventions
- Persistence is plain SQL through `JdbcClient` + Flyway migrations. No JPA.
- API first: change `src/main/resources/openapi/ledger-v1.yaml` before the controller.
- Errors are RFC 9457 problem details with `errors[].code` and `errors[].pointer`.
- Unit tests are `*Test.java` (surefire, no Docker). Integration tests are `*IT.java`
  (failsafe, Testcontainers with the same image versions as docker-compose).
- Every guarantee from the challenge's section 6 has a test against real MySQL/Kafka.
- Unspecified behaviour: make a reasonable call and record it in README "Assumptions".

## Commands
- Build + all tests: `./run.sh` (or `./mvnw -B verify`)
- Unit tests only: `./mvnw -B test`
- Start stack only: `./run.sh --skip-tests`; stop: `./run.sh --down`
- JDK on this machine: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`

## Working agreements
- One phase of `docs/PLAN.md` §7 per commit; describe the "why" in the commit body.
- Commit messages carry no AI attribution of any kind (no Co-Authored-By, no
  "generated with"). AI usage is documented in AI_USAGE.md, nowhere else.
- Comments only where they add real value: a non-obvious why, a constraint, a trap.
  No section banners, no restating the code, no javadoc that repeats the name.
- Keep README and AI_USAGE.md in step with what was actually done.
