# Implementation status against the target architecture

This page distinguishes implemented structure, evidence executed on the current local worktree, and
external-system or fault-injection proof that still has not run.

Snapshot date: 2026-07-22. This snapshot follows the completed hardening pass described below.

## Status legend

- **Implemented:** the production call path and required schema exist in source.
- **Fast verified:** isolated tests passed without starting external services.
- **Locally verified:** the stated path ran against the local Compose services or benchmark harness.
- **External or chaos proof pending:** cluster, failover, acknowledgement-loss, process-death, or
  deployment-rehearsal evidence is still required where explicitly listed.

## Consistency path

| Concern | Implemented structure | Executed evidence and remaining proof |
|---|---|---|
| Reservation | Lua validates eligibility, decrements inventory, writes stable Reservation state, idempotency mapping, and Redis Stream journal | real standalone Redis integration covers accepted, retry, duplicate, sold-out, poison-journal, and fail-closed release cases; a 100,000-request stock-and-dedup primitive run conserved inventory. Redis Cluster, restart recovery, and lifecycle-aware GC remain unproved |
| Redis to Kafka | scheduled relay reads journal entries and republishes the same reservation ID; MySQL `RESERVED` rows provide a second relay source after ledger persistence, while a durable opened-SKU scan repairs a lost Redis discovery registry | the 1,000-request full-async run reached 1,000 committed orders with Kafka lag zero. Ack-loss, process restart, registry-loss timing, multi-instance behavior, and the pre-ledger total-Redis-loss gap remain unproved |
| Order Intake | inbox, order, and Reservation Ledger update share one MySQL transaction | real MySQL integration covers atomic creation, sequential duplicate delivery, mismatch rollback, active-order uniqueness, and terminal-order reuse; full async created 1,000 orders. Concurrent duplicate consumers and crash-after-claim injection remain unproved |
| Release | terminal order transition and MySQL Release Intent share one transaction; a fixed-delay worker optionally locks the batch and uses stateful idempotent Redis release; an admin endpoint can requeue an exhausted intent | real MySQL/Redis integration covers intent creation, idempotent release, missing-stock fail-closed behavior, and retry exhaustion. Worker death after Redis success and a live manual re-drive remain unproved; per-row lease and time-based backoff remain Target architecture |
| Reconciliation | admin-only inspection separates inventory conservation from ledger catch-up; first opening uses a retryable state machine, stock-key-only recovery is serialized against Release Workers and refuses unsettled intents, and full Redis loss fails closed | the full-async final gate reconciled 1,000 buyers/orders with zero remaining stock and zero durable holds. Operator pause/audit/resume controls and buyer/Reservation rebuild after full Redis loss remain Target architecture |
| Cluster keys | all per-SKU Lua keys use one literal Redis hash tag | standalone Redis passed; Redis Cluster, replication, and failover have not run |
| HTTP idempotency | controller accepts `Idempotency-Key`; the frontend reuses one key after an uncertain in-page retry and polls reservation status | fast/controller and real Redis tests cover stable-key retry. Page reload or browser restart persistence remains a product decision and has not been browser-E2E tested |
| AI | only EventQueryTools and OrderQueryTools are registered; bounded request and ownership tests exist | exercise adversarial requests against a live model without granting mutation tools |
| Security | atomic OTP verify-and-consume, attempt limit, fixed-TTL hashed refresh token, admin 403, and async context cleanup are implemented | deployment secrets, TLS, external rate limiting, and multi-instance abuse tests |
| Operations | explicit topics and DLTs, identity-checked DLT recovery with non-exhausting infrastructure retry, readiness health, low-cardinality Micrometer metrics, and three benchmark families exist | the final benchmark captured live Prometheus data and Kafka lag and passed all three local correctness gates. Broker outage, manual poison-record review, long-running capacity, and multi-instance exercises remain unproved |
| Schema | immutable V1 through V4 Flyway history builds the current schema; V4 guards the legacy release-protocol upgrade; relative-time seed is isolated under `db/dev`; Docker DDL is removed | a fresh database migrated through V1–V4 plus the repeatable seed and produced 12 base tables. Guarded V3-to-V4 fixtures, mid-DDL restore/forward-repair rehearsal, and valuable pre-Flyway volume adoption remain unproved |

## Source anchors

- [ticket_rush.lua](../../src/main/resources/lua/ticket_rush.lua)
- [ticket_rollback.lua](../../src/main/resources/lua/ticket_rollback.lua)
- [RedisRushReservationAdapter](../../src/main/java/dev/dahuangggg/ticketrush/infrastructure/redis/RedisRushReservationAdapter.java)
- [TicketRushServiceImpl](../../src/main/java/dev/dahuangggg/ticketrush/service/impl/TicketRushServiceImpl.java)
- [ReservationOutboxRelay](../../src/main/java/dev/dahuangggg/ticketrush/infrastructure/mq/ReservationOutboxRelay.java)
- [TicketRushDltRecoveryConsumer](../../src/main/java/dev/dahuangggg/ticketrush/infrastructure/mq/TicketRushDltRecoveryConsumer.java)
- [OrderServiceImpl](../../src/main/java/dev/dahuangggg/ticketrush/service/impl/OrderServiceImpl.java)
- [InventoryReleaseServiceImpl](../../src/main/java/dev/dahuangggg/ticketrush/service/impl/InventoryReleaseServiceImpl.java)
- [PaymentServiceImpl](../../src/main/java/dev/dahuangggg/ticketrush/service/impl/PaymentServiceImpl.java)
- [InventoryReconciliationServiceImpl](../../src/main/java/dev/dahuangggg/ticketrush/service/impl/InventoryReconciliationServiceImpl.java)

## Reconciliation response semantics

The admin `inventory-check` response describes one point-in-time observation. Its fields are not
interchangeable:

| Field | Meaning |
|---|---|
| `initialStock` | Configured capacity stored in MySQL; the name does not mean current availability |
| `availableStock` | Current Redis stock value, or null when the stock key is absent |
| `heldReservations` | Durable Reservation quantity in RESERVED, QUEUED, or RELEASE_PENDING |
| `activeOrderQuantity` | Durable Order quantity in PENDING or PAID |
| `redisConsumedReservations` | Redis buyer-set cardinality, including the brief pre-ledger window after Lua acceptance |
| `expectedAvailableStock` | `max(0, initialStock - max(heldReservations + activeOrderQuantity, redisConsumedReservations))` |
| `inventoryConserved` | True only when `availableStock` exists and configured capacity equals available plus the larger consumed count |
| `ledgerCaughtUp` | True when durable consumed quantity equals Redis buyer count at this instant |
| `balanced` | True only when both `inventoryConserved` and `ledgerCaughtUp` are true |

`ledgerCaughtUp=false` may be a short normal relay window; alerts must also consider Reservation age.
Conversely, `balanced=true` does not prove that all Redis Reservation hashes can be reconstructed.

Stock Initialization uses a durable `NEW(0) -> OPENING(2) -> OPENED(1)` state machine. `OPENING` is
retryable after a process or Redis failure: Redis is first prepared with unavailable metadata, MySQL
then moves to `OPENED`, and only then is the real sale status activated. For an already-opened SKU,
stock-key-only recovery shares a per-SKU mutex with the Release Worker and proceeds only when buyer
evidence survives and no PENDING or FAILED Release Intent exists. Missing evidence or an unsettled
intent raises a recovery-required error; configured capacity is never silently reopened.
The mutex is distributed when Redisson is enabled. Its in-process fallback is intentionally limited
to single-instance teaching runs and is not a multi-node recovery protocol.

## AI

[AiConfig](../../src/main/java/dev/dahuangggg/ticketrush/ai/config/AiConfig.java) registers only:

- [EventQueryTools](../../src/main/java/dev/dahuangggg/ticketrush/ai/tools/EventQueryTools.java)
- [OrderQueryTools](../../src/main/java/dev/dahuangggg/ticketrush/ai/tools/OrderQueryTools.java)

There is no AI rush-ticket or reminder-mutation tool. Reminder creation is the normal authenticated
`POST /api/reminders` business endpoint with `ReminderCreationRequest`, outside the AI registry. The
backend endpoint exists; this page does not claim matching frontend reminder UI coverage.

## Schema adoption

The current schema is the result of an ordered, immutable history:

1. [V1](../../src/main/resources/db/migration/V1__baseline_schema.sql) creates the pre-role,
   pre-reminder base schema retained for migration-history compatibility.
2. [V2](../../src/main/resources/db/migration/V2__add_user_role.sql) adds the user role.
3. [V3](../../src/main/resources/db/migration/V3__add_rush_reminder.sql) adds Rush Reminder.
4. [V4](../../src/main/resources/db/migration/V4__harden_reservation_and_release_flow.sql) adds the
   durable Reservation Ledger, inbox/order reservation links, Release Intent, corrected active-order
   uniqueness, and the `stock_initialized` recovery marker.

V4 conservatively marks pre-existing SKUs as previously opened. It refuses the protocol upgrade while
a legacy rollback task is unresolved, while a legacy PENDING order still depends on the old release
protocol, or while the old schema contains more than one active order for a user/SKU. Completed legacy
evidence is archived rather than replayed through a new idempotency protocol it cannot satisfy.

Those SQL guards run only after an external protocol barrier: reject new Rush Requests while V3
consumers and lifecycle workers drain the main rush topic, pending orders, and rollback tasks. Once
consumer lag is zero and the data gates are satisfied, stop all V3 nodes and rollback workers, recheck
the gates, and keep old nodes stopped through V4 startup. SQL cannot prove those deployment facts, so
the guard is a prerequisite, not permission to perform a rolling upgrade or discard unfinished work.

V3 Redis state is also a separate migration prerequisite. The old
`ticket:stock:3001` / `ticket:order:user:3001` keys (using SKU 3001 as an example) are not visible
through V4's literal-hash-tag `ticket:{3001}:stock` / `ticket:{3001}:buyers` namespace, and Flyway
cannot copy them. The supported
offline handoff first proves the exact V1-to-V3 history, freezes every writer, validates old stock and
buyer membership against paid-order evidence, copies those values without overwriting the new keys,
and invokes V4 init only to register the SKU and populate metadata. There is no automatic cross-slot
migration. Any missing or contradictory evidence leaves the SKU fail-closed for manual review; see
the [V3 Redis namespace handoff](../getting-started/running.md#v3-redis-namespace-handoff).

[The local fixture seed](../../src/main/resources/db/dev/R__demo_seed.sql) is separate and uses
relative dates so SKUs 3001 through 3003 are on sale when loaded by the development or integration
profile.

The current schema path has:

- one ordered V1 through V4 history, with released V2 and V3 left unchanged;
- no Docker-owned table definitions;
- Flyway enabled in the development and integration profiles;
- a generated active-order guard that maps PENDING and PAID to one uniqueness class;
- repeatable local fixture data used by the development and integration profiles.

`baseline-on-migrate` is deliberately false. A non-empty schema created by the old Docker SQL is not
silently adopted, and it must not be labeled “V1” merely because several table names match. Valuable
data needs a reviewed mapping to the exact migration state and a tested upgrade plan; disposable
teaching data can use a new empty database.

## Verification record

For this 2026-07-22 snapshot:

| Command | Result | What it proves |
|---|---|---|
| `./mvnw -B test` | 106 fast tests passed; zero failures, errors, or skips | fast contracts pass; Maven also compiled the excluded integration-tagged sources |
| `MYSQL_DATABASE=ticket_rush_it_gate_20260722_1145 ./mvnw -B -Pintegration clean verify` | 106 Surefire and 27 Failsafe tests passed; zero failures, errors, or skips | an independent empty database applied Flyway V1–V4 plus the repeatable seed; the five Failsafe classes exercised real MySQL and Redis. Kafka listeners and schedulers were disabled in this lane |
| `bash bench/run.sh all` | all three scenarios exited successfully under run ID `1784692033_4123146903ca9a80` | observed 22,959.58 req/s for the MySQL event-read boundary, 132,450.33 req/s for the Redis stock-and-dedup primitive, and 2,792.37 req/s for the full-async HTTP 202 acceptance phase; the harness then waited for all 1,000 orders and each scenario passed its own correctness gate |

The integration classes cover the normal Reservation, idempotent release, transactional Order Intake,
Release Intent, and stock initialization paths. The full-async benchmark additionally exercised the
actual relay, Kafka, and Order Intake success path. It did not inject acknowledgement loss or process
death. These single-machine observations are not production capacity claims. See the
[full command and environment record](../zh-CN/verification-report.md).

## Proof still required before “complete”

1. A V3 fixture proves that V4 rejects unresolved legacy rollback work, legacy PENDING orders, and
   duplicate active-order groups before durable schema changes, then succeeds after they are resolved.
2. A documented choice is made for valuable pre-Flyway data: map and migrate it, baseline an exactly
   matching version, or keep it outside this development history.
3. Lua tests run against Redis Cluster, including replication/failover behavior; the current proof is
   standalone Redis only.
4. Ack-loss and process-death tests preserve inventory conservation.
5. Full Redis-loss repair and mid-DDL recovery are rehearsed with recorded operator evidence.
6. Browser E2E, live-model adversarial requests, and multi-instance abuse/capacity tests run where
   those surfaces are claimed.

An implementation class and migration are evidence of a coherent build. Passing real-service failure-path tests is evidence of a working recovery system.
