# ADR-0004: Flyway is the single schema truth

- Status: Accepted and implemented; blank-database migration verified, guarded legacy upgrade pending
- Date: 2026-07-21

## Context

At decision time, the baseline repository defined the base schema in Docker initialization SQL while also carrying partial Flyway migrations. That produced different histories for:

- a fresh Docker volume;
- a developer database;
- a test database;
- an upgraded existing installation.

Consistency features depend on exact generated columns, unique indexes, and intent tables. Schema ambiguity invalidates their tests.

## Decision

Flyway migration files under src/main/resources/db/migration are the only ordered table-definition history.

- V1 creates the historical base schema; V2, V3, and V4 evolve it in order.
- Every schema change is a new versioned migration.
- Released migrations are immutable.
- Docker creates MySQL infrastructure and the database, but does not maintain competing table DDL.
- ORM auto-DDL is not used as a schema owner.
- Documentation links to migrations instead of copying authoritative DDL.

Seed data, if retained, is explicitly separated from schema evolution and is safe to rerun only in development.

## Consequences

Positive:

- fresh install, test, and upgrade use one history;
- unique constraints and generated columns are reproducible;
- integration tests exercise the same schema as the application;
- release and rollback planning become reviewable.

Costs:

- the existing Docker-created schema needs a deliberate adoption path;
- adopting the decision required writing and reviewing a historical baseline and follow-up migrations;
- incompatible history cannot be fixed by editing an old released migration;
- local reusable volumes may require backup and controlled migration.

## Alternatives

### Docker init SQL as the schema truth

Rejected because Docker entrypoint scripts run only for a fresh data directory and do not provide ordered upgrades.

### Manual SQL instructions

Rejected because developer environments drift and automated tests cannot prove the same history.

### ORM auto-DDL

Rejected because generated production DDL and upgrade intent are too implicit for this teaching project.

## Implementation status

The current source implements the decision as one ordered history:

- [V1 baseline](../../src/main/resources/db/migration/V1__baseline_schema.sql) creates the
  pre-role, pre-reminder base schema and retains legacy tables required by that historical contract;
- immutable [V2](../../src/main/resources/db/migration/V2__add_user_role.sql) adds the user role;
- immutable [V3](../../src/main/resources/db/migration/V3__add_rush_reminder.sql) adds Rush Reminder;
- [V4](../../src/main/resources/db/migration/V4__harden_reservation_and_release_flow.sql) adds the
  Reservation Ledger, inbox/order reservation links, Release Intent, corrected active-order
  uniqueness, and the durable first-stock-opening marker;
- [development seed](../../src/main/resources/db/dev/R__demo_seed.sql) is separate, repeatable, and uses relative dates;
- Compose provisions MySQL but mounts no table-definition SQL;
- Spring Boot's dedicated Flyway starter activates migration auto-configuration;
- development and integration profiles enable Flyway;
- `baseline-on-migrate` is false and Flyway clean is disabled.

V4 is also a stop-the-world protocol upgrade. Pre-existing SKUs are conservatively marked as
previously opened so loss of Redis cannot reset them to Configured Inventory. Before changing durable
tables, V4 requires all legacy rollback tasks to be successful, requires every legacy PENDING order to
be resolved under V3, and rejects the legacy one-pending-plus-one-paid state for the same user/SKU.
Any of those database-visible conditions makes the migration fail closed before MySQL commits
persistent DDL. Completed legacy evidence is archived, because old tasks and orders lack the
Reservation hash required by the new idempotent release protocol and must not be relabeled as new
Release Intents.

The SQL guards are necessary but not sufficient: they are point-in-time reads and cannot stop a V3
node from writing immediately afterward. Deployment must first reject new Rush Requests while V3
consumers and lifecycle workers drain the main rush topic, pending orders, and rollback tasks. After
consumer lag reaches zero and the database guards are satisfied, stop every old application node and
rollback worker, recheck the gates, and keep old nodes stopped throughout migration and V4 startup.
V4 is not safe as a rolling mixed-version deployment.

The protocol barrier also covers Redis schema. For SKU 3001, V1 through V3 used
`ticket:stock:3001` and `ticket:order:user:3001`; V4 uses the co-slotted
`ticket:{3001}:stock` and `ticket:{3001}:buyers` keys with literal hash-tag braces, plus metadata, Reservation, idempotency, and
journal keys. Flyway cannot migrate that external state. For an exact V1-to-V3 installation, the
approved offline path freezes writers, audits legacy Available Inventory and buyer identities against
all PAID orders, copies the two legacy values without overwriting V4 targets, and only then runs V4
stock init to register the SKU and populate metadata. Differently slotted keys require a
Cluster-aware client, not one Lua script or `RENAME`.

The copy is admissible only when legacy Available Inventory plus paid quantity equals Configured
Inventory and the buyer membership exactly represents those paid users. Missing, expired,
wrong-typed, or contradictory evidence leaves the SKU unavailable for a reviewed reconstruction.
V4's `stock_initialized=1` marker is intentionally fail-closed; it is not permission for ordinary
init to replace missing legacy state with Configured Inventory. The operational details and acceptance
checks are documented in [running locally](../getting-started/running.md#v3-redis-namespace-handoff).

On 2026-07-22, an independent blank database executed V1, V2, V3, V4, and the repeatable development
seed successfully, produced 12 base tables, and passed 106 Surefire tests plus 27 Failsafe integration
tests. The exact command and evidence are in the
[verification report](../zh-CN/verification-report.md#真实服务通道独立空库复测). This does not prove
the guarded V3-to-V4 legacy upgrade or recovery from interrupted MySQL DDL; those exercises remain
pending.

There is intentionally no silent adoption path for a non-empty database created by the old Docker
SQL. Back up valuable data, identify its exact schema and Flyway history, drain or explicitly resolve
legacy rollback work, and test the selected migration or baselining procedure on a copy. Do not
baseline a current-looking schema as V1, and do not delete an existing database volume as an
undocumented migration strategy.
