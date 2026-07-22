# ADR-0001: Redis Reservation Journal and Kafka relay

- Status: Accepted and implemented; local happy path verified, fault injection pending
- Date: 2026-07-21

## Context

Redis Lua is the hot-path linearization point for inventory. The baseline application executes Lua, then sends Kafka from the request process.

Those operations cannot be atomic. The process may die after inventory is decremented and before Kafka publication. A producer timeout is also ambiguous: the broker may have appended the event even though the application did not receive the acknowledgement. Immediate rollback can therefore create an order after inventory was returned.

## Decision

The Reservation Module will atomically create all of these in one Redis Lua execution:

- Available Inventory decrement;
- active-user and eligibility state;
- stable reservation ID and idempotency mapping;
- Reservation state;
- unpublished Reservation Journal entry.

A relay Adapter will publish unpublished journal entries to Kafka with the reservation ID as deterministic message identity.

Relay delivery is at least once. It retries UNKNOWN acknowledgements with the same identity. Order Intake is idempotent and absorbs duplicates.

HTTP acceptance means the Reservation and journal were atomically accepted by Redis. It does not mean an order exists or that Redis AOF has already survived an fsync boundary.

All Redis keys touched by one Lua script will contain one literal SKU hash tag so they share a Redis Cluster slot.

## Consequences

Positive:

- application-process death no longer erases work that remains in Redis;
- relay backlog is queryable from the journal; dedicated alerting gauges remain future work;
- request latency is not coupled to Kafka tail acknowledgement;
- one identity connects HTTP retry, relay retry, Kafka retry, and order status;
- fault-injection checkpoints become explicit.

Costs:

- journal lifecycle and relay ownership add state;
- duplicate Kafka publication is expected;
- Redis persistence and journal reconciliation become operational requirements;
- AOF `everysec` still has a host-failure loss window before MySQL Ledger persistence;
- cleanup must follow sale and Reservation lifecycle rather than an arbitrary TTL.

## Alternatives

### Direct synchronous Kafka send

Rejected as the target because it leaves a crash gap and treats timeout as certainty.

### Direct asynchronous send

Rejected because lower request latency does not make the accepted request recoverable.

### Redis Stream instead of Kafka

Technically simpler because Lua can reserve and enqueue in one system. Not selected because this project intentionally teaches Kafka Order Intake.

### MySQL transactional outbox for Reservation

Appropriate when MySQL owns the reservation. Not selected for the rush hot path because the decision keeps Redis as the Reservation linearization point.

### Distributed transaction

Rejected because Redis, Kafka, and MySQL business recovery still needs domain states, while two-phase coordination would increase latency and operational complexity.

## Implementation status

The current worktree has stable reservation IDs, Lua journal creation, Redis Stream relay, Redis
Cluster hash tags, and idempotent Order Intake. The immutable Flyway history reaches those durable
fields in [V4](../../src/main/resources/db/migration/V4__harden_reservation_and_release_flow.sql),
which persists the Reservation Ledger and links inbox and order rows to the reservation ID.

On 2026-07-22, a 100,000-request Redis benchmark of the stock-decrement-plus-unique-user primitive
passed its conservation gate. Separately, a 1,000-request run exercised the complete production
Reservation Lua plus HTTP, relay, Kafka, and MySQL Order Intake, creating 1,000 orders with zero
final consumer lag. See the [recorded evidence](../zh-CN/verification-report.md#压测总览). This
verifies one local happy path, not the ambiguous or crash-recovery paths that motivated this ADR.

Production-style verification still requires:

- crash-after-Lua and ack-loss tests pass;
- relay restart and multi-instance tests pass;
- backlog-under-failure and reconciliation metrics are verified.
