# ticket-rush contribution guide

## Purpose

ticket-rush is a teaching project for high-concurrency ticket sales. Optimize for explicit invariants, recoverable failure handling, readable call chains, and tests that demonstrate why a design is correct.

Start with:

1. [CONTEXT.md](CONTEXT.md) for domain vocabulary.
2. [Architecture overview](docs/architecture/overview.md) for module ownership.
3. [Current versus target](docs/architecture/current-vs-target.md) before assuming an ADR is implemented.
4. The relevant record under [docs/adr](docs/adr/README.md) before changing a cross-system consistency choice.

## Architecture rules

- Redis Lua is the Reservation linearization point. Any keys used by one Lua script must share a Redis Cluster hash tag.
- An accepted Rush Request must have a stable reservation ID and a recoverable Reservation Journal entry.
- Kafka delivery is at least once. Order Intake must be idempotent.
- Message idempotency and order creation must commit in the same MySQL transaction, or use an explicitly documented resumable state machine.
- Cancel and timeout must commit the order transition and Release Intent in the same MySQL transaction.
- Redis release must be idempotent. Replaying the same Release Intent must never increment inventory twice.
- Inventory conservation is the primary invariant:

  available + held + active orders = configured inventory

- Do not describe a direct Redis-to-Kafka send or an inline Redis rollback as crash-safe.
- Use Kafka for asynchronous order intake. Do not add a second message broker without a new ADR.

## AI boundary

AI is read-only.

- Allowed: event search, event detail, SKU query, and current-user order query.
- Forbidden: rush, create order, pay, cancel, stock mutation, admin operations, and reminder mutation.
- There is no AI rush-ticket tool.
- Tool registration is an allowlist; prompt text is not a security control.
- AI must derive user identity from the authenticated context and must never accept an arbitrary user ID.

Any change that gives AI a write capability contradicts [ADR-0003](docs/adr/0003-ai-read-only.md) and requires an explicit replacement ADR.

## Data and schema

- Flyway is the single source of schema truth. Put ordered migrations under src/main/resources/db/migration.
- Do not maintain competing table definitions in Docker bootstrap SQL, ORM auto-DDL, README snippets, or manual setup notes.
- Existing migrations are immutable after release; add a new migration.
- IDs use MyBatis-Plus ASSIGN_ID unless a migration and domain reason state otherwise.
- Money is integer cents, never float or double.
- Time is LocalDateTime in Java and DATETIME in MySQL unless an ADR introduces a different clock model.
- All business tables use soft delete and audit timestamps.
- Active-order uniqueness must treat pending and paid as the same active class; canceled and timed-out orders must not block a new Reservation.

## Java conventions

- Constructor injection only; no field injection.
- Controllers translate HTTP and delegate. Business invariants belong in a domain Module.
- DTOs are Java records.
- Entities use the established Lombok and MyBatis-Plus annotations.
- Use named status constants or enums; no magic status numbers in flow code.
- Business exceptions extend RuntimeException and are mapped centrally.
- Do not catch and swallow failures that should trigger retry, DLT handling, or compensation.
- Keep infrastructure-specific code in an Adapter at a clear Seam.

## Testing

- Controller integration tests use SpringBootTest and MockMvc.
- Prefer Fake Adapters through TestConfiguration and Primary over Mockito-based implementation tests.
- The Interface is the test surface. Test observable invariants, not private method choreography.
- Every consistency change needs happy-path, duplicate, concurrency, and fault-injection coverage.
- Required failure points include:
  - after Redis reservation and before relay publication;
  - broker accepted but producer acknowledgement was lost;
  - after message claim and before order commit;
  - after Redis release and before Release Intent completion;
  - duplicate Kafka delivery and duplicate Release Intent execution;
  - Redis restart or missing inventory key.
- Do not say tests passed unless the command was actually run. Report unavailable dependencies separately.
- `./mvnw test` is the fast lane and excludes the `integration` tag. Use `./mvnw -Pintegration verify` with MySQL, Redis, and Kafka for the real-service lane.

## Documentation

- Mark statements as Current implementation or Target architecture when they differ.
- Do not use milestone checklists as architecture documentation.
- Reuse terms from CONTEXT.md.
- Record durable tradeoffs as ADRs; do not hide them in code comments.
- Mermaid diagrams must use quoted labels when punctuation is present and must render without external assets.
- Keep relative links valid from the file that contains them.

## Change discipline

- Preserve unrelated user changes in a dirty worktree.
- Keep commits scoped to the requested area.
- Do not silently change the inventory source of truth, queue technology, AI trust model, or schema owner.
- For destructive local setup commands, identify the exact disposable target and warn that data will be lost.
- A completed change includes implementation, migration when needed, focused tests, and updated documentation.
