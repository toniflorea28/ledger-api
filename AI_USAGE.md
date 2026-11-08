# AI usage

I used Claude Code (Anthropic's coding assistant) on this project the way I would use
a fast, well-read junior engineer: I decided what to build and how, it produced the
code I asked for, and nothing it produced was accepted until it had been run against
the real database and broker.

## Division of labour

**Mine: the decisions.**

- The framing. I set the brief before any design work: treat this as a service that
  will run as several instances inside a large payment processor, and favour
  multi-instance correctness over simplicity everywhere the two conflict.
- The design. I required a written plan before a line of code
  ([docs/PLAN.md](docs/PLAN.md)): requirements distilled, every design decision with
  the alternative it was chosen over, the schema, the package layout, the test matrix
  and the build order. I reviewed it, settled the open questions (base package, Maven,
  Kafka as the broker, server-generated ids, all three nice-to-haves in scope) and
  approved each decision before the scaffold was started. The choices the README
  defends are the ones I signed off on: minor-unit `long` money, pessimistic row locks
  in sorted order, the idempotency key committed with its effect, deduplication on
  `paymentId`, the transactional outbox keyed by currency.
- The gate between phases. Work was split into ten phases, each ending with the full
  test run against real MySQL and Kafka; I reviewed the result of each and gave the go
  for the next. Nothing was committed red.
- The working rules. No AI attribution in the commit history (AI use is disclosed
  here, where the challenge asks for it) and no comments that merely restate code; the
  rules are in [CLAUDE.md](CLAUDE.md), the committed brief the assistant worked from.

**Delegated: the typing.**

- Project scaffolding: the Maven build, Docker image, compose stack, run script.
- Code generation from the approved design: the domain model, repositories, REST
  adapter, Kafka adapters, the outbox relay.
- Test generation from the plan's test matrix and from the worked examples and
  refusals in the challenge PDF.
- First drafts of the documentation and commit messages.

## How the output was checked

The rule was: run it, do not read it and nod.

- Every phase ended with `./mvnw verify` against MySQL 8.4 and Kafka 4.3 in
  Testcontainers, and the final phases with `./run.sh` against the two-instance Docker
  stack. 68 unit tests, 41 integration tests and 5 stack-level checks pass.
- Failures were taken to their root cause, not patched around. What that caught is
  recorded in the commit messages, for example:
  - the first idempotency implementation stored refusals in a second transaction,
    which released the key's lock; twenty concurrent identical refused requests raced
    each other. The fix is a savepoint inside one transaction;
  - Spring Boot 4 switches OpenTelemetry propagation off together with trace export,
    and its test support disables export, so the tracing tests were silently passing
    on nothing. Production was unaffected; the tests were not a proof until fixed;
  - MySQL's JSON column type rewrites key order and whitespace, so the bytes announced
    to Kafka were not the bytes the ledger serialised;
  - trigger creation needs SUPER while binary logging is on; two compose services
    building the same image tag in parallel collide; and a few test bugs of the
    assistant's own making.
- ArchUnit rules put in place in phase 3 caught a layering violation in phase 4 and
  forced a cleaner split between adapters.
- Framework facts (Spring Boot 4 artifact names, generator options, library APIs)
  were verified against the actual jars and Maven Central, not taken from the
  assistant's memory of a framework newer than its training data.

## In short

The architecture, the trade-offs and the acceptance bar are mine; the assistant made
the implementation fast and the test coverage broad. Any decision in this repository
is one I can explain and would make again.
