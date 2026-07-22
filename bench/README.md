# Reproducible performance experiments

This harness separates three different questions. Their throughput numbers are not directly
comparable because each scenario measures a different boundary.

| Scenario | Command | Measured boundary |
|---|---|---|
| MySQL baseline | `bash bench/run.sh mysql` | HTTP event-detail path with Redis and Caffeine disabled |
| Redis Lua | `bash bench/run.sh lua` | Atomic stock deduction and unique-user recording in Redis only |
| Full async | `bash bench/run.sh async` | HTTP 202 Reservation-acceptance burst, then post-burst convergence through relay, Kafka, MySQL validation, and order creation |

Run every scenario with `bash bench/run.sh all`.

The latest recorded green run is `1784692033_4123146903ca9a80` from 2026-07-22. It passed all
three scenario-specific correctness gates. See the
[verification report](../docs/zh-CN/verification-report.md#压测总览) for the exact workload,
latencies, throughput, cleanup state, and limitations; it is a single-machine observation rather
than a committed capacity baseline. Raw bundles live under the locally ignored `bench/results/`
directory and are not published to GitHub; the tracked verification report is the durable summary.

## Prerequisites

- Docker Compose and Java 17
- `wrk` for the MySQL baseline
- `k6` and `jq` for the full async scenario
- Python 3 and OpenSSL for fixture generation and result normalization

On macOS:

```bash
brew install wrk k6 jq
```

The script starts the required MySQL, Redis, and Kafka services, waits for their health checks,
creates an exact per-run MySQL database named `ticket_rush_bench_<run-id>`, starts the application
on port `18081`, warms the measured path, and preserves raw evidence under
`bench/results/<timestamp>-<run-id>-<scenario>/`. The cleanup trap drops the disposable database on
normal exit, ordinary command failure, and catchable `INT` or `TERM`. It cannot run after `SIGKILL`,
host loss, or container-runtime failure. Set `BENCH_KEEP_DATABASE=true` only for debugging; that
retains the explicitly named benchmark database and makes its later deletion your responsibility.

The full-async scenario also claims Redis logical database 15 with a run-owned sentinel. It refuses
to run unless that database is empty. Cleanup never calls `FLUSHDB`: it scans and unlinks only the
run-specific `ticket:{<sku-id>}:*` namespace, deletes the fixed `ticket:rush:outbox:skus` registry
that this exclusively claimed database created, and deletes the sentinel only when its value still
equals the generated run ID. Override it with
`BENCH_REDIS_DATABASE=<0..15>` only when the selected database is reserved for benchmarks. The
Redis-only scenario likewise uses run-specific `bench:ticket:{...}` keys and removes only those
exact keys.

Common workload controls can be overridden without editing the script:

```bash
BENCH_DURATION=30s BENCH_CONNECTIONS=200 bash bench/run.sh mysql
BENCH_LUA_REQUESTS=100000 BENCH_LUA_CLIENTS=100 bash bench/run.sh lua
BENCH_ASYNC_USERS=5000 BENCH_ASYNC_VUS=200 bash bench/run.sh async
```

The harness generates a non-overridable run ID from the start time plus 64 bits of randomness. The
MySQL database, event ID, SKU ID, Redis key namespace, and Kafka group are all derived from that
identity. Database, event, SKU, and user fixtures use plain `INSERT`; there is no upsert path. The
harness checks the event, SKU, and entire generated user-ID range before insertion and fails
instead of modifying an existing row.

A host-local atomic lock directory rejects concurrent benchmark runs before the empty-broker
ownership check. This closes the check/start race for the intended local Docker workflow. An
unclean `SIGKILL` may leave more than the lock: the run-owned MySQL database, Redis sentinel and
namespace, Kafka topics or group, port `18081`, and an application child process can also survive.
Use the run ID and result metadata to inspect every one of those exact resources, confirm no process
still owns the dedicated containers, and clean only the matched run-owned resources before removing
the stale lock or starting another benchmark.

Application-backed scenarios pin the application to the Compose endpoints (`127.0.0.1:13306`,
`127.0.0.1:16379`, and `127.0.0.1:9092`) and the disposable database. Ambient Spring connection
overrides are not inherited. The Kafka topic names are currently compile-time constants, so the
harness takes the conservative alternative: before starting an application scenario it requires a
dedicated broker with no non-internal topic and no consumer group. It does not accept a custom
consumer-group name; every group is rooted at `ticket-rush-bench-<run-id>` and uses
`auto-offset-reset=latest`. When the cleanup trap runs, it deletes only those exact groups. It deletes the four topics
created by this application only after proving that no unexpected topic/group appeared and that
the topic record counts equal the benchmark's expected counts. If the ownership proof fails, it
leaves all topics intact and reports the reason. A broker containing development or production
traffic is therefore rejected rather than acknowledged as merely a measurement caveat.

## Correctness gates

Before trusting `wrk`, the MySQL scenario requires a `200` smoke response whose body contains the
benchmark event ID. Its parser fails on any non-2xx/3xx response or socket error.

The Lua scenario fails if the final Redis stock or unique-user set cardinality differs from the
submitted request count. Each full-async run derives fresh event, SKU, and user IDs. k6 thresholds
require every requested iteration to execute exactly once, zero dropped iterations, zero rejected
responses, and complete accepted/rejected response accounting. The harness then waits for Order
Intake and asserts:

The k6 rate and latency fields describe only the HTTP 202 Reservation-acceptance burst. They are not
Order Intake commit throughput; convergence to committed orders is checked afterward.

```text
orders created == HTTP requests accepted
final Redis stock == initial stock - accepted requests
Redis buyers == accepted requests
durable held reservations + active order quantity == accepted requests
final Redis stock + Redis buyers == configured stock
Kafka lag for the run-specific consumer group == 0
```

HTTP errors, readiness failures, missing tools, and infrastructure commands are not hidden. A
failed gate exits non-zero instead of publishing a misleading QPS number.

## Result bundle

Every run records:

- UTC time, run ID, Git commit and dirty-file count;
- host and Java information plus container image IDs;
- exact non-secret fixture IDs, disposable database disposition, Redis database, and Kafka group;
- workload parameters and raw `wrk`, `redis-benchmark`, or `k6` output;
- normalized latency, throughput, executed-iteration, dropped-iteration, and response-accounting
  summaries.

Artifact availability depends on the measured boundary:

| Artifact | MySQL baseline | Redis Lua | Full async |
|---|---:|---:|---:|
| Application log and Prometheus snapshot | yes | no application is started | yes |
| Raw load-generator output and normalized state/summary | yes | yes | yes |
| Kafka lag and final MySQL/Redis invariant counts | no | Redis counts only | yes |

Generated JWTs and user SQL are held in a temporary directory and deleted on exit; they are never
part of the evidence bundle. `metadata.env` records no password, JWT secret, or token.

Repeat measurements and report variability. Keep commit, hardware, JVM, dataset, warm-up, and
topology constant within a comparison. These experiments provide evidence for a particular setup;
they are not production-capacity claims or proof of a causal optimization by themselves.

## Test lanes

Fast tests exclude the JUnit `integration` tag and start neither Kafka listeners nor scheduled
jobs:

```bash
./mvnw test
```

The real-service integration lane is executable against the Compose dependencies:

```bash
docker compose up -d mysql redis kafka
./mvnw -Pintegration verify
```

The integration profile configures Kafka but deliberately disables listeners and scheduled workers.
It validates real MySQL, Redis, and Flyway behavior, not Kafka end-to-end delivery. The command
intentionally fails when its infrastructure is unavailable; it should not be silently treated as a
skipped green build.

The 2026-07-22 blank-database gate actually executed 106 Surefire tests and 27 Failsafe integration
tests successfully. Consult the [backend test evidence](../docs/zh-CN/verification-report.md#后端测试)
instead of inferring a current pass from this command example.
