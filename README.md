# Ledger API

A double-entry ledger service for a payments platform: accounts hold money in
EUR, USD or JPY; money moves only through balanced, immutable transactions;
settled payments arrive from a message broker; every recorded transaction is
announced to other services.

Stack: Java 17, Spring Boot 4, MySQL 8.4, Apache Kafka, Docker.

> Work in progress. The design is described in [docs/PLAN.md](docs/PLAN.md); this
> README is filled in as the phases in that plan land.

## Running

Requirements on the host: Docker (with Compose v2), Git, a JDK 17+.

```bash
./run.sh
```

builds the service, runs unit and integration tests (the latter against real MySQL
and Kafka via Testcontainers), starts the stack and smoke-checks it. The API is then
served at `http://localhost:8080` by a gateway that round-robins over two ledger
instances (`ledger-1`, `ledger-2`).

Other modes:

```bash
./run.sh --skip-tests   # build and start only
./run.sh --down         # stop and remove volumes
```

### Notes for non-Docker-Desktop setups

`run.sh` exports `DOCKER_HOST` from the active `docker context` and sets
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, so Testcontainers works
with colima, Rancher Desktop or a remote daemon without extra configuration. If
`docker compose` is not available as a plugin, the standalone `docker-compose` (v2+)
binary is used.

## Status

| Phase (docs/PLAN.md §7) | State |
|---|---|
| 1. Scaffold: build, compose stack, run script, wiring test | done |
| 2. API contract: OpenAPI spec, generated interfaces, message schemas | done |
| 3. Domain model: money, accounts, posting rules, reversal, unit tests | done |
| 4. Persistence and posting: schema, ordered row locks, REST, problem details, atomicity/concurrency tests | done |
| 5. Idempotency: key stored in the same transaction, refusals remembered, concurrent retries | done |
| 6. Settlement consumer | pending |
| 7. Outbox relay | pending |
| 8. Nice-to-haves | pending |
| 9. Packaging and e2e | pending |
| 10. Documentation | pending |
