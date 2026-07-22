# ADR-0002: MySQL transactional Release Intent

- Status: Accepted and implemented; real-service transaction/replay tests passed, crash injection pending
- Date: 2026-07-21

## Context

Cancel and timeout change an order in MySQL and return held inventory in Redis.

Calling Redis inline after a database compare-and-set creates two crash windows:

- MySQL commits but Redis does not run, leaking Available Inventory;
- Redis releases but MySQL rolls back, leaving an active order without a held unit.

Retrying an unconditional Redis INCR can also inflate inventory when the first execution succeeded but its response or worker completion update was lost.

## Decision

The Order Lifecycle Module will commit these in one MySQL transaction:

- PENDING to CANCELED or TIMEOUT compare-and-set;
- one Release Intent keyed by reservation ID.

A Release Worker will scan pending intents and invoke an idempotent Redis state transition. The
current implementation serializes each scan with an optional distributed lock and retries on the
next fixed-delay scan; per-row leasing and time-based backoff remain future hardening.

After the durable Ledger confirms `RELEASE_PENDING`, Redis increments Available Inventory only when
the Reservation moves from one of the script's allowed releasable states (`RESERVED`, `QUEUED`,
`ORDER_CREATED`, `REJECTED`, or `RELEASE_PENDING`) to `RELEASED`. Replays return
`ALREADY_RELEASED` and do not increment.

The worker marks the Release Intent complete after either RELEASED or ALREADY_RELEASED.

Payment transitions PENDING to PAID and creates no Release Intent.

## Consequences

Positive:

- every committed terminal order has durable release work;
- process death delays release but does not lose it;
- replay is safe without relying on a distributed lock;
- release backlog, retry count, and age are queryable;
- timeout scheduling and manual cancel share one consistency Module.

Costs:

- inventory return is eventually consistent;
- a new table, worker, retry policy, and alerts are required;
- Reservation state must remain available or reconstructable until release;
- operators need a permanent-failure runbook.

## Alternatives

### Inline Redis rollback with compensation only on exception

Rejected because process death and ambiguous Redis responses are not catchable exceptions.

### Redis first, then database

Rejected because it reverses rather than removes the split-brain window.

### Distributed lock around the retry worker

Useful for load control, but rejected as the correctness mechanism. A crash after Redis and before task completion still replays the side effect.

### Periodic scan of terminal orders

Retained as reconciliation, not the primary release obligation. Without an intent it is difficult to distinguish work never attempted from work already applied.

## Implementation status

The current worktree has the Release Intent entity, Flyway table and uniqueness constraint, fixed-delay scanner, worker, manual failed-intent requeue endpoint, and stateful Redis release script. Cancel and timeout schedule the intent inside the order transaction. It does not yet have a per-row claim lease or delayed next-attempt column.

The 2026-07-22 real-service lane passed the MySQL terminal-transition-plus-intent test, timeout
isolation, retry exhaustion, and Redis duplicate-release test as part of 27 integration tests. See
the [recorded evidence](../zh-CN/verification-report.md#真实服务通道独立空库复测). These tests do not
simulate process death between the Redis side effect and the MySQL completion update.

Production-style verification still requires:

- the current fixed-delay retry and permanent failure remain visible, and any future claim/backoff policy is verified;
- worker-death-after-Redis tests pass;
- reconciliation verifies all terminal orders.
