# Running locally

## Prerequisites

- JDK 17
- Docker with Compose
- Node.js `^20.19.0` or `>=22.12.0`, plus npm, for the frontend
- curl, OpenSSL, and jq
- an OpenAI-compatible API key only when exercising AI

Check:

```bash
java -version
docker compose version
node --version
npm --version
jq --version
openssl version
```

Generate a local JWT signing secret in the current terminal before starting either backend mode:

```bash
export JWT_SECRET="$(openssl rand -base64 48)"
```

Do not reuse this development secret in a deployed environment.

Business timestamps use one explicit zone. Keep the default, or set the same value for every
application instance and database connection:

```bash
export BUSINESS_ZONE=Asia/Shanghai
```

## Choose one backend mode

Do not start both the Compose app service and a local Maven process on port 8081.

### Mode A: infrastructure in Docker, backend in Maven

This is the best development mode:

```bash
docker compose up -d mysql redis kafka
docker compose ps
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### Mode B: backend and infrastructure in Docker

```bash
docker compose --profile full up --build -d
docker compose ps
```

The app is exposed on port 8081. Nginx proxies the backend on port 8080; the current Dockerfile does not package the Vue frontend.

## Flyway and existing databases

Flyway is the only schema owner. On an empty `ticket_rush` database, the backend applies the ordered
[V1](../../src/main/resources/db/migration/V1__baseline_schema.sql),
[V2](../../src/main/resources/db/migration/V2__add_user_role.sql),
[V3](../../src/main/resources/db/migration/V3__add_rush_reminder.sql), and
[V4](../../src/main/resources/db/migration/V4__harden_reservation_and_release_flow.sql) history. The
development profile then applies the separate
[relative-time seed](../../src/main/resources/db/dev/R__demo_seed.sql). Compose no longer mounts
table-definition SQL.

Read [implementation status](../architecture/current-vs-target.md) before reusing an existing MySQL volume. `baseline-on-migrate` is false, so a non-empty database created by the old Docker SQL fails closed rather than being silently claimed by Flyway.

- If a Flyway-managed database is at V3, treat V4 as a stop-the-world protocol upgrade, not a rolling
  schema change. Rehearse this sequence on a copy first:
  1. reject new Rush Requests at ingress while keeping the V3 consumers and lifecycle workers running;
  2. drain `ticket.rush.requests`, including records using the legacy payload, and verify consumer lag
     is zero;
  3. let every legacy PENDING order become PAID or be canceled/timed out by V3, then drain every
     resulting rollback task and verify all legacy rollback tasks are successful;
  4. resolve any user/SKU pair that still has more than one PENDING-or-PAID order;
  5. stop every V3 application instance and legacy rollback worker, recheck steps 2 through 4, then run
     V4; do not allow a V3 node to restart after migration begins.

  V4 checks the database-visible conditions in steps 3 and 4 before persistent DDL. SQL cannot prove
  that old nodes are stopped or Kafka lag is zero, so those are deployment gates. Do not mark
  unfinished work successful merely to bypass a guard.
- If valuable data comes from the old Docker SQL without an exact Flyway history, back it up and
  design a reviewed mapping to the correct migration state. Do not baseline it as V1 merely because
  several table names match. This repository does not automate that adoption.
- If the data is disposable teaching data, use a new empty project database or an explicitly identified disposable volume.

Do not disable Flyway to make a schema error disappear, and never delete a MySQL volume unless you have confirmed its exact contents are disposable.

MySQL DDL commits implicitly. The V4 preflight guard runs before persistent DDL and is safely
rerunnable, but a failure after `ALTER TABLE tb_ticket_sku` may leave a partially upgraded schema.
Keep the stop-the-world barrier in place and do not blindly run `flyway repair` followed by V4 again.
Prefer restoring the rehearsed pre-V4 snapshot. If restoration is impossible, inventory the exact
completed statements through `information_schema` and Flyway history, compare with a clean V1-to-V4
reference schema, and use a separately reviewed one-off forward-repair script. Mark the migration
successful only after the resulting schema and data invariants match that reference; never edit V4
in place or baseline the half-migrated database.

### V3 Redis namespace handoff

For SKU 3001, the V1-to-V3 runtime used `ticket:stock:3001` for Available Inventory and
`ticket:order:user:3001` for the buyer set. V4 uses the Cluster-compatible
`ticket:{3001}:stock` and `ticket:{3001}:buyers` namespace; the braces are now a literal Redis hash
tag. V4 also requires per-SKU metadata. A
successful MySQL V4 migration does not move these Redis keys. This handoff applies only when
`flyway_schema_history` proves the exact successful V1, V2, and V3 history from this repository; an
old Docker-created schema still needs its own reviewed adoption plan.

Perform the Redis handoff offline after the stop-the-world gates above and before reopening Rush
traffic:

1. Keep every V3 node stopped and preserve a Redis snapshot. For each SKU, require the legacy stock
   key to be a numeric string, the legacy buyer key to be a set or absent, and both V4 target keys to
   be absent.
2. Audit the frozen values against MySQL, including soft-deleted evidence: legacy Available Inventory
   plus the quantity of every PAID order must equal Configured Inventory. The legacy buyer members
   must exactly equal the distinct users with PAID orders, and because V3 only sold quantity one, its
   cardinality must equal the paid quantity. A missing buyer key is acceptable only when there are no
   paid units.
3. With no writers, copy the legacy stock string unchanged into the corresponding
   `ticket:{skuId}:stock` key (where the braces are literal) using
   create-only semantics, and copy the legacy buyer members unchanged into
   `ticket:{skuId}:buyers`. Do this through a Cluster-aware client; Redis `COPY`, `RENAME`, or one Lua
   script cannot safely move these differently slotted keys. Abort rather than overwrite a target
   that appeared unexpectedly.
4. Start only the V4 runtime while ingress remains blocked, then invoke the normal admin stock-init
   endpoint for each transferred SKU. Because the new stock key already exists, `SET NX` preserves
   the copied Available Inventory and init only registers the SKU and refreshes its V4 metadata; it
   must not recreate Configured Inventory. Do not copy V3's rolling one-hour TTL: the V4 buyer set,
   Reservation, idempotency evidence, and active journal remain persistent until a future
   lifecycle-aware GC proves each record terminal from the durable ledger.
5. Run the read-only inventory check and compare the copied stock and buyer membership with the
   frozen audit before reopening traffic. Keep the old keys and snapshot as rollback evidence until
   acceptance is complete, but never restart a V3 writer after V4 migration begins.

This repository deliberately does not automate the cross-slot copy: topology, snapshots, and the
operator's evidence source are deployment-specific. If the legacy stock key is missing, a type is
wrong, the buyer set has expired, an invariant does not match, or a target key already exists, leave
that SKU unavailable and perform a reviewed reconstruction. Do not call ordinary V4 init first in
the hope that it will reopen the SKU from Configured Inventory.

## AI configuration

The AI assistant is optional to the ticketing lesson. The current application registers its AI
beans and read-only tools at startup; provide the remote-provider values when exercising the AI
endpoints:

```bash
export OPENAI_API_KEY=your_key
export OPENAI_BASE_URL=https://api.openai.com/v1
export OPENAI_MODEL=your_supported_model
```

Do not use production credentials in a shared development environment.

AI tools are read-only and there is no AI rush-ticket tool. See [AI boundary](../ai/read-only-boundary.md).

## Start the frontend

```bash
cd frontend
npm ci
npm run dev
```

Open http://127.0.0.1:5173. Vite proxies API requests to the backend.

## Smoke test public reads

```bash
curl -s http://127.0.0.1:8081/api/events | jq
curl -s http://127.0.0.1:8081/api/events/2001/skus | jq
```

The development seed uses relative time: SKUs 3001 through 3003 are on sale, while SKU 3004 demonstrates a future sale. Query the current state instead of assuming seeded data exists in non-development profiles.

## Development login

Request a development SMS code:

```bash
PHONE=13800000001
curl -s -X POST http://127.0.0.1:8081/api/auth/sms-code \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\"}"
```

The code is stored in Redis. In a local disposable environment:

```bash
CODE=$(docker compose exec -T redis redis-cli GET "auth:sms-code:{$PHONE}" \
  | tr -d '\r' | cut -d'|' -f1)
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"code\":\"$CODE\"}" | jq -r '.accessToken')
```

The seeded 13800000001 account is an admin in the Flyway development seed. Do not use this workflow outside development.

Refresh tokens use a fixed TTL and Redis stores only a SHA-256 digest. Tokens created by an older checkout that used raw token keys are intentionally not migrated; sign in again after upgrading.

## Initialize a SKU and submit a Rush Request

Choose a currently on-sale SKU from the public query, then:

```bash
EVENT_ID=replace_me
SKU_ID=replace_me
IDEMPOTENCY_KEY=$(openssl rand -hex 16)

curl -i -X POST "http://127.0.0.1:8081/api/admin/skus/$SKU_ID/init-stock" \
  -H "Authorization: Bearer $TOKEN"

curl -i -X POST http://127.0.0.1:8081/api/ticket-rush/requests \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"eventId\":$EVENT_ID,\"skuId\":$SKU_ID,\"quantity\":1}"

curl -s http://127.0.0.1:8081/api/orders/me \
  -H "Authorization: Bearer $TOKEN" | jq
```

The response includes a stable reservation ID and Reservation state. Reuse the same Idempotency-Key when retrying an uncertain client request, then query:

```bash
RESERVATION_ID=replace_from_response
curl -s "http://127.0.0.1:8081/api/ticket-rush/reservations/$RESERVATION_ID" \
  -H "Authorization: Bearer $TOKEN" | jq
```

First stock initialization moves through durable `NEW`, retryable `OPENING`, and `OPENED` states;
Redis is prepared as unavailable before the real sale status is activated. If a later Redis loss
removes only the stock key, recovery takes the Release Worker's per-SKU mutex and derives the
remaining value only when buyer evidence remains and every Release Intent is settled. Missing
evidence or an unsettled intent returns a recovery-required error and keeps the SKU unavailable. Do
not work around that protection by writing Configured Inventory into Redis.

## Test

The fast lane excludes JUnit tests tagged `integration` and does not require Docker:

```bash
./mvnw test
```

Focused fast examples:

```bash
./mvnw -Dtest=TicketRushControllerTest test
./mvnw -Dtest=EventCacheManagerTest test
./mvnw -Dtest=MicrometerTicketRushMetricsTest test
```

The integration-tagged lane uses real MySQL and Redis and runs Flyway against them. Kafka is
configured and reachable, but `application-integration.yaml` deliberately disables Kafka listener
auto-start and scheduled workers so service tests remain deterministic; this lane is not a Kafka
end-to-end proof:

```bash
docker compose up -d --wait mysql redis kafka

# Choose a new, explicitly disposable database. Maven and Flyway do not create the database itself.
INTEGRATION_DB=ticket_rush_it_local_20260722_1200
docker compose exec -T mysql mysql -uroot -p123456 \
  -e "CREATE DATABASE ${INTEGRATION_DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"

MYSQL_DATABASE="$INTEGRATION_DB" ./mvnw -Pintegration verify
```

To focus one integration-tagged class, use the Failsafe property:

```bash
MYSQL_DATABASE="$INTEGRATION_DB" ./mvnw -Pintegration -Dit.test=TicketRushServiceTest verify
```

After every intended integration run is finished, remove only the exact database you created and
already confirmed is disposable:

```bash
case "$INTEGRATION_DB" in
  ticket_rush_it_local_*)
    docker compose exec -T mysql mysql -uroot -p123456 -e "DROP DATABASE ${INTEGRATION_DB}"
    ;;
  *)
    printf 'Refusing to drop unexpected database: %s\n' "$INTEGRATION_DB" >&2
    ;;
esac
unset INTEGRATION_DB
```

The integration suite writes fixture data to the configured services. Always create a dedicated
empty database first; do not point it at a shared, development, or production database. The latest
command-by-command evidence, including anything not run, is recorded
in [implementation status](../architecture/current-vs-target.md) and the
[full verification report](../zh-CN/verification-report.md). On 2026-07-22, the final gate against an
independent empty database passed 106 Surefire tests and 27 Failsafe integration tests with zero
failures, errors, or skips. Flyway applied V1 through V4 plus the repeatable development seed. The
integration profile still disabled Kafka listeners and schedulers, so this command is not Kafka
end-to-end evidence.

## Benchmark

The harness separates three boundaries:

```bash
bash bench/run.sh mysql
bash bench/run.sh lua
bash bench/run.sh async
bash bench/run.sh all
```

Read [bench/README.md](../../bench/README.md) first. The MySQL baseline measures event reads, the
Lua run measures only the atomic Redis primitive, and the async run reports HTTP 202 Reservation
acceptance before waiting for relay, Kafka, and Order Intake to converge. Its QPS is not order-commit
throughput, and the three scenarios' rates are not interchangeable.

## Troubleshooting

### Docker is unavailable

```bash
docker compose ps
```

If this cannot connect to the daemon, start Docker or the configured container runtime before claiming integration tests passed.

### Port 8081 is already in use

Check whether the Compose app service and local Maven process are both running. Stop the one you do not intend to use.

### Database migration error

Check `flyway_schema_history` and determine whether the database has no Flyway history or is at V1,
V2, V3, or V4. A V4 failure may be an intentional legacy rollback or duplicate-active-order guard; inspect and
finish that durable work before retrying. Do not paper over a non-empty schema with ad hoc ALTER
statements or `baseline-on-migrate=true`. Follow ADR-0004 and migrate or baseline deliberately.

### Rush returns SOLD_OUT

Confirm:

- the Redis stock key was initialized;
- the SKU is currently on sale;
- event ID and SKU relation are correct;
- Available Inventory is greater than zero.

### Request is queued but no order appears

Inspect readiness, Reservation Journal age, relay logs, Kafka consumer lag, inbox state, and DLT records. See [failure playbook](../failures/failure-playbook.md).

### AI startup or request fails

Verify API key, base URL, model compatibility, and network access. AI failure must not prevent normal authenticated ticketing paths in the hardened design.
