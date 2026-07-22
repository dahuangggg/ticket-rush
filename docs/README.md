# Documentation map

The documentation separates two kinds of statements:

- **Current implementation** describes behavior visible in the source today.
- **Target architecture** describes an accepted design that may still require implementation.

Never infer completion from an ADR alone. Check [current versus target](architecture/current-vs-target.md) and the source.

## Languages and document status

- This directory is the maintained English architecture and engineering guide.
- [The maintained Chinese teaching guide](zh-CN/README.md) contains the expanded, module-by-module
  learning path and the latest local verification report.
- `dev-docs/` is the ignored, read-only local historical Chinese source set. It is not part of the
  published documentation and must not be treated as the current implementation description.

## Suggested reading paths

### First-time learner

1. [Domain context](../CONTEXT.md)
2. [Architecture overview](architecture/overview.md)
3. [Core rush flow](architecture/rush-flow.md)
4. [Technology comparison](comparisons/data-paths.md)
5. [Learning roadmap](learning/roadmap.md)

### Consistency reviewer

1. [Current versus target](architecture/current-vs-target.md)
2. [Consistency case study](architecture/consistency.md)
3. [Failure playbook](failures/failure-playbook.md)
4. [Testing and benchmarking](testing/testing-and-benchmarking.md)
5. [Decision records](adr/README.md)

### Operator

1. [Running locally](getting-started/running.md)
2. [Observability](operations/observability.md)
3. [Failure playbook](failures/failure-playbook.md)
4. [Security](security/security.md)

### AI reviewer

1. [AI read-only boundary](ai/read-only-boundary.md)
2. [ADR-0003](adr/0003-ai-read-only.md)
3. [Security](security/security.md)

## Architecture

- [Overview](architecture/overview.md)
- [Current versus target](architecture/current-vs-target.md)
- [Core rush flow](architecture/rush-flow.md)
- [Consistency case study](architecture/consistency.md)

## Engineering practice

- [MySQL, Redis Lua, Kafka, and relay comparison](comparisons/data-paths.md)
- [Failure playbook](failures/failure-playbook.md)
- [Testing and benchmarking](testing/testing-and-benchmarking.md)
- [Latest local verification record](testing/testing-and-benchmarking.md#current-verification-record)
- [Observability](operations/observability.md)
- [Security](security/security.md)
- [AI read-only boundary](ai/read-only-boundary.md)

## Learning and setup

- [Learning roadmap](learning/roadmap.md)
- [Running locally](getting-started/running.md)
- [Benchmark harness](../bench/README.md)

## Decisions

- [ADR index](adr/README.md)
- [ADR-0001: Redis Reservation Journal and Kafka relay](adr/0001-redis-reservation-journal-relay.md)
- [ADR-0002: MySQL Release Intent](adr/0002-mysql-release-intent.md)
- [ADR-0003: read-only AI](adr/0003-ai-read-only.md)
- [ADR-0004: Flyway as the schema truth](adr/0004-flyway-schema-truth.md)

## Historical source notes and maintained teaching guides

The original step-by-step Chinese notes remain unchanged in the ignored local `dev-docs/` source
set. Their expanded, source-checked successors are maintained under
[`docs/zh-CN`](zh-CN/README.md). Durable cross-system decisions live in ADRs, while current behavior
and executed verification evidence live in the source-linked English and Chinese guides.
