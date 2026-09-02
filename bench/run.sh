#!/usr/bin/env bash
set -euo pipefail

BENCH_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$BENCH_DIR/.." && pwd)
RESULTS_DIR=${BENCH_RESULTS_DIR:-$BENCH_DIR/results}

MYSQL_CONTAINER=${MYSQL_CONTAINER:-ticket-rush-mysql}
REDIS_CONTAINER=${REDIS_CONTAINER:-ticket-rush-redis}
KAFKA_CONTAINER=${KAFKA_CONTAINER:-ticket-rush-kafka}
BENCH_MYSQL_ROOT_PASSWORD=${BENCH_MYSQL_ROOT_PASSWORD:-123456}
BENCH_MYSQL_PORT=${BENCH_MYSQL_PORT:-13306}
BENCH_REDIS_PORT=${BENCH_REDIS_PORT:-16379}

BENCH_PORT=${BENCH_PORT:-18081}
BASE_URL="http://127.0.0.1:${BENCH_PORT}"
RUN_STARTED_EPOCH=$(date +%s)
RUN_NONCE_HEX=""
BENCH_RUN_ID=""
BENCH_DATABASE=""
BENCH_REDIS_DATABASE=${BENCH_REDIS_DATABASE:-15}
KAFKA_GROUP_ID=""
BASE_EVENT_ID=""
BASE_SKU_ID=""
THREADS=${BENCH_THREADS:-4}
CONNECTIONS=${BENCH_CONNECTIONS:-200}
DURATION=${BENCH_DURATION:-30s}
LUA_REQUESTS=${BENCH_LUA_REQUESTS:-100000}
LUA_CLIENTS=${BENCH_LUA_CLIENTS:-$CONNECTIONS}
ASYNC_USERS=${BENCH_ASYNC_USERS:-1000}
ASYNC_VUS=${BENCH_ASYNC_VUS:-100}
ASYNC_TIMEOUT_SECONDS=${BENCH_ASYNC_TIMEOUT_SECONDS:-90}
CACHE_VUS=${BENCH_CACHE_VUS:-100}
CACHE_DURATION=${BENCH_CACHE_DURATION:-30s}
CACHE_WARMUP_DURATION=${BENCH_CACHE_WARMUP_DURATION:-5s}
CACHE_ROUNDS=${BENCH_CACHE_ROUNDS:-1}
BENCH_KEEP_DATABASE=${BENCH_KEEP_DATABASE:-false}

SCENARIO=${1:-all}
APP_PID=""
CURRENT_RESULT_DIR=""
EVENT_ID=""
SKU_ID=""
USER_ID_BASE=""
USER_ID_LAST=""
BENCH_DATABASE_CREATED=false
ASYNC_REDIS_CLAIMED=false
ASYNC_REDIS_PREFIX=""
ASYNC_REDIS_SENTINEL_KEY="bench:ticket-rush:exclusive-owner"
ASYNC_REDIS_SENTINEL_VALUE=""
LUA_REDIS_KEYS_CREATED=false
LUA_STOCK_KEY=""
LUA_BUYERS_KEY=""
LUA_SEQUENCE_KEY=""
LUA_SCRIPT_CONTAINER_PATH=""
LUA_SCRIPT_COPIED=false
FIXTURE_TEMP_DIR=""
KAFKA_GROUP_USED=false
KAFKA_BROKER_CLAIMED=false
EXPECTED_RUSH_RECORDS=0
KAFKA_RUSH_TOPIC="ticket.rush.requests"
KAFKA_RUSH_DLT_TOPIC="ticket.rush.requests.DLT"
KAFKA_CACHE_TOPIC="event.cache.invalidate"
KAFKA_CACHE_DLT_TOPIC="event.cache.invalidate.DLT"
BENCH_PROCESS_LOCK_DIR="${TMPDIR:-/tmp}/ticket-rush-bench.lock"
BENCH_PROCESS_LOCK_ACQUIRED=false

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "missing required command: $1" >&2
        exit 1
    fi
}

require_positive_integer() {
    local name=$1 value=$2
    if [[ ! "$value" =~ ^[0-9]+$ ]] || [[ "$value" -lt 1 ]]; then
        echo "$name must be a positive integer: $value" >&2
        exit 2
    fi
}

require_business_id() {
    local name=$1 value=$2
    require_positive_integer "$name" "$value"
    if [[ ${#value} -gt 18 ]]; then
        echo "$name must fit the benchmark's 18-digit business-ID range: $value" >&2
        exit 2
    fi
}

validate_configuration() {
    local nonce_numeric
    require_command openssl
    require_command pgrep
    RUN_NONCE_HEX=$(openssl rand -hex 8)
    BENCH_RUN_ID="${RUN_STARTED_EPOCH}_${RUN_NONCE_HEX}"
    BENCH_DATABASE="ticket_rush_bench_${BENCH_RUN_ID}"
    KAFKA_GROUP_ID="ticket-rush-bench-${BENCH_RUN_ID}"
    LUA_SCRIPT_CONTAINER_PATH="/tmp/ticket_rush_bench_${BENCH_RUN_ID}.lua"
    ASYNC_REDIS_SENTINEL_VALUE=$BENCH_RUN_ID
    nonce_numeric=$((16#${RUN_NONCE_HEX:0:12}))
    BASE_EVENT_ID=$((900000000000000000 + nonce_numeric * 2))
    require_business_id BENCH_EVENT_ID "$BASE_EVENT_ID"
    BASE_SKU_ID=$((BASE_EVENT_ID + 1))
    require_business_id BENCH_SKU_ID "$BASE_SKU_ID"
    require_positive_integer BENCH_PORT "$BENCH_PORT"
    if [[ "$BENCH_PORT" -gt 65535 ]]; then
        echo "BENCH_PORT must be at most 65535" >&2
        exit 2
    fi
    require_positive_integer BENCH_MYSQL_PORT "$BENCH_MYSQL_PORT"
    require_positive_integer BENCH_REDIS_PORT "$BENCH_REDIS_PORT"
    if [[ "$BENCH_MYSQL_PORT" -gt 65535 || "$BENCH_REDIS_PORT" -gt 65535 ]]; then
        echo "BENCH_MYSQL_PORT and BENCH_REDIS_PORT must be at most 65535" >&2
        exit 2
    fi
    require_positive_integer BENCH_THREADS "$THREADS"
    require_positive_integer BENCH_CONNECTIONS "$CONNECTIONS"
    require_positive_integer BENCH_LUA_REQUESTS "$LUA_REQUESTS"
    require_positive_integer BENCH_LUA_CLIENTS "$LUA_CLIENTS"
    require_positive_integer BENCH_ASYNC_USERS "$ASYNC_USERS"
    if [[ "$ASYNC_USERS" -gt 2147483647 ]]; then
        echo "BENCH_ASYNC_USERS exceeds the Redis Lua integer stock range" >&2
        exit 2
    fi
    require_positive_integer BENCH_ASYNC_VUS "$ASYNC_VUS"
    require_positive_integer BENCH_ASYNC_TIMEOUT_SECONDS "$ASYNC_TIMEOUT_SECONDS"
    require_positive_integer BENCH_CACHE_VUS "$CACHE_VUS"
    require_positive_integer BENCH_CACHE_ROUNDS "$CACHE_ROUNDS"
    if [[ -z "$CACHE_DURATION" || -z "$CACHE_WARMUP_DURATION" ]]; then
        echo "BENCH_CACHE_DURATION and BENCH_CACHE_WARMUP_DURATION must not be empty" >&2
        exit 2
    fi
    if [[ ! "$BENCH_DATABASE" =~ ^ticket_rush_bench_[0-9]+_[a-f0-9]{16}$ ]] \
            || [[ ${#BENCH_DATABASE} -gt 64 ]]; then
        echo "generated BENCH_DATABASE is outside the safe per-run namespace" >&2
        exit 2
    fi
    if [[ "$KAFKA_GROUP_ID" != "ticket-rush-bench-${BENCH_RUN_ID}" ]]; then
        echo "generated Kafka group is outside the safe per-run namespace" >&2
        exit 2
    fi
    if [[ ! "$BENCH_REDIS_DATABASE" =~ ^[0-9]+$ ]] \
            || [[ "$BENCH_REDIS_DATABASE" -lt 0 ]] \
            || [[ "$BENCH_REDIS_DATABASE" -gt 15 ]]; then
        echo "BENCH_REDIS_DATABASE must be an integer from 0 through 15" >&2
        exit 2
    fi
    if [[ "$BENCH_KEEP_DATABASE" != "true" && "$BENCH_KEEP_DATABASE" != "false" ]]; then
        echo "BENCH_KEEP_DATABASE must be true or false" >&2
        exit 2
    fi
}

acquire_process_lock() {
    if ! mkdir "$BENCH_PROCESS_LOCK_DIR" 2>/dev/null; then
        echo "another ticket-rush benchmark may be using the dedicated broker; refusing concurrent start" >&2
        echo "if no run exists, remove the stale lock directory: $BENCH_PROCESS_LOCK_DIR" >&2
        exit 1
    fi
    BENCH_PROCESS_LOCK_ACQUIRED=true
}

set_fixture_ids() {
    local offset=${1:-0}
    EVENT_ID=$((BASE_EVENT_ID + offset))
    SKU_ID=$((BASE_SKU_ID + offset))
    require_business_id EVENT_ID "$EVENT_ID"
    require_business_id SKU_ID "$SKU_ID"
}

wait_healthy() {
    local container=$1
    local attempts=${2:-60}
    for ((i = 1; i <= attempts; i++)); do
        local status
        status=$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)
        if [[ "$status" == "healthy" ]]; then
            return 0
        fi
        sleep 2
    done
    echo "container did not become healthy: $container" >&2
    docker logs "$container" >&2
    exit 1
}

ensure_dependencies() {
    require_command docker
    (cd "$PROJECT_DIR" && \
        BUSINESS_ZONE=Asia/Shanghai \
        MYSQL_HOST_PORT="$BENCH_MYSQL_PORT" \
        REDIS_HOST_PORT="$BENCH_REDIS_PORT" \
        docker compose up -d mysql redis kafka)
    wait_healthy "$MYSQL_CONTAINER"
    wait_healthy "$REDIS_CONTAINER"
    wait_healthy "$KAFKA_CONTAINER"
}

list_non_internal_kafka_topics() {
    docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server 127.0.0.1:9092 --list \
        | awk 'NF && $0 !~ /^__/'
}

list_kafka_consumer_groups() {
    docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server 127.0.0.1:9092 --list \
        | awk 'NF'
}

ensure_kafka_broker_disposable() {
    if [[ "$KAFKA_BROKER_CLAIMED" == "true" ]]; then
        return
    fi
    local topics groups
    topics=$(list_non_internal_kafka_topics)
    groups=$(list_kafka_consumer_groups)
    if [[ -n "$topics" || -n "$groups" ]]; then
        echo "benchmark application scenarios require a dedicated empty Kafka broker" >&2
        if [[ -n "$topics" ]]; then
            printf 'existing non-internal topics:\n%s\n' "$topics" >&2
        fi
        if [[ -n "$groups" ]]; then
            printf 'existing consumer groups:\n%s\n' "$groups" >&2
        fi
        exit 1
    fi
    KAFKA_BROKER_CLAIMED=true
}

mysql_admin_exec() {
    docker exec -i "$MYSQL_CONTAINER" \
        mysql -uroot "-p${BENCH_MYSQL_ROOT_PASSWORD}" --batch --skip-column-names
}

mysql_exec() {
    docker exec -i "$MYSQL_CONTAINER" \
        mysql -uroot "-p${BENCH_MYSQL_ROOT_PASSWORD}" --batch --skip-column-names \
        --database="$BENCH_DATABASE"
}

ensure_benchmark_database() {
    if [[ "$BENCH_DATABASE_CREATED" == "true" ]]; then
        return
    fi
    printf "CREATE DATABASE \`%s\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n" \
        "$BENCH_DATABASE" | mysql_admin_exec
    BENCH_DATABASE_CREATED=true
}

prepare_business_fixture() {
    local existing_rows
    existing_rows=$(printf '%s\n' \
        "SELECT" \
        "  (SELECT COUNT(*) FROM tb_event WHERE id=$EVENT_ID)" \
        "  + (SELECT COUNT(*) FROM tb_ticket_sku WHERE id=$SKU_ID OR event_id=$EVENT_ID)" \
        "  + (SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=$SKU_ID OR event_id=$EVENT_ID)" \
        "  + (SELECT COUNT(*) FROM tb_rush_reservation WHERE sku_id=$SKU_ID OR event_id=$EVENT_ID);" \
        | mysql_exec)
    if [[ "$existing_rows" -ne 0 ]]; then
        echo "benchmark event/SKU IDs already own rows in disposable database $BENCH_DATABASE" >&2
        exit 1
    fi
    {
        printf 'SET @bench_event_id = %s; SET @bench_sku_id = %s; SET @bench_stock = %s;\n' \
            "$EVENT_ID" "$SKU_ID" "$ASYNC_USERS"
        sed -n '1,$p' "$BENCH_DIR/sql/setup.sql"
    } | mysql_exec
}

guard_user_range() {
    local existing_users
    existing_users=$(printf \
        'SELECT COUNT(*) FROM tb_user WHERE id BETWEEN %s AND %s;\n' \
        "$USER_ID_BASE" "$USER_ID_LAST" | mysql_exec)
    if [[ "$existing_users" -ne 0 ]]; then
        echo "benchmark user ID range $USER_ID_BASE..$USER_ID_LAST is not empty" >&2
        exit 1
    fi
}

new_result_dir() {
    local name=$1
    local timestamp
    timestamp=$(date -u +%Y%m%dT%H%M%SZ)
    CURRENT_RESULT_DIR="$RESULTS_DIR/${timestamp}-${BENCH_RUN_ID}-${name}"
    if [[ -e "$CURRENT_RESULT_DIR" ]]; then
        echo "result directory already exists: $CURRENT_RESULT_DIR" >&2
        exit 1
    fi
    mkdir -p "$CURRENT_RESULT_DIR"
}

record_metadata() {
    local scenario=$1 prometheus_artifact=$2 database_used=$3
    local kafka_mode=${4:-owned}
    local recorded_database=not-used
    local recorded_group=not-used
    local database_disposition=not-applicable
    local recorded_redis_database=default
    local kafka_auto_offset_reset=not-used
    local kafka_broker_requirement=not-used
    local kafka_rush_topic=not-used
    if [[ "$database_used" == "true" ]]; then
        recorded_database=$BENCH_DATABASE
        recorded_group=$([[ "$kafka_mode" == "owned" ]] && echo "$KAFKA_GROUP_ID" || echo not-used)
        database_disposition=$([[ "$BENCH_KEEP_DATABASE" == "true" ]] && echo retain || echo drop-on-exit)
        recorded_redis_database=$BENCH_REDIS_DATABASE
        if [[ "$kafka_mode" == "owned" ]]; then
            kafka_auto_offset_reset=latest
            kafka_broker_requirement=dedicated-empty
            kafka_rush_topic=$KAFKA_RUSH_TOPIC
        else
            kafka_broker_requirement=read-only-not-measured
        fi
    fi
    {
        echo "scenario=$scenario"
        echo "benchmark_run_id=$BENCH_RUN_ID"
        echo "recorded_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "git_commit=$(git -C "$PROJECT_DIR" rev-parse HEAD)"
        echo "git_dirty_files=$(git -C "$PROJECT_DIR" status --short | wc -l | tr -d ' ')"
        echo "host=$(uname -a)"
        echo "java=$(java -version 2>&1 | head -1)"
        echo "event_id=$EVENT_ID"
        echo "sku_id=$SKU_ID"
        echo "mysql_database=$recorded_database"
        echo "mysql_database_disposition=$database_disposition"
        echo "redis_database=$recorded_redis_database"
        echo "mysql_host_port=$BENCH_MYSQL_PORT"
        echo "redis_host_port=$BENCH_REDIS_PORT"
        echo "kafka_consumer_group=$recorded_group"
        echo "kafka_auto_offset_reset=$kafka_auto_offset_reset"
        echo "kafka_broker_requirement=$kafka_broker_requirement"
        echo "kafka_rush_topic=$kafka_rush_topic"
        echo "prometheus_artifact=$prometheus_artifact"
        echo "credential_artifacts=not-retained"
        echo "threads=$THREADS"
        echo "connections=$CONNECTIONS"
        echo "duration=$DURATION"
        echo "lua_requests=$LUA_REQUESTS"
        echo "lua_clients=$LUA_CLIENTS"
        echo "async_users=$ASYNC_USERS"
        echo "async_vus=$ASYNC_VUS"
        echo "cache_vus=$CACHE_VUS"
        echo "cache_duration=$CACHE_DURATION"
        echo "cache_warmup_duration=$CACHE_WARMUP_DURATION"
        echo "cache_rounds=$CACHE_ROUNDS"
        echo "mysql_image=$(docker inspect --format '{{.Config.Image}}@{{.Image}}' "$MYSQL_CONTAINER")"
        echo "redis_image=$(docker inspect --format '{{.Config.Image}}@{{.Image}}' "$REDIS_CONTAINER")"
        echo "kafka_image=$(docker inspect --format '{{.Config.Image}}@{{.Image}}' "$KAFKA_CONTAINER")"
    } > "$CURRENT_RESULT_DIR/metadata.env"
}

port_is_listening() {
    python3 - "$BENCH_PORT" <<'PY'
import socket
import sys

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.settimeout(0.5)
    raise SystemExit(0 if sock.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0 else 1)
PY
}

assert_port_available() {
    if port_is_listening; then
        echo "benchmark port $BENCH_PORT already has a listener; refusing to trust another process's readiness" >&2
        exit 1
    fi
}

wait_application() {
    for ((i = 1; i <= 90; i++)); do
        if [[ -z "$APP_PID" ]] || ! kill -0 "$APP_PID" 2>/dev/null; then
            echo "application process exited before readiness" >&2
            tail -100 "$CURRENT_RESULT_DIR/application.log" >&2
            exit 1
        fi
        if curl -fsS "$BASE_URL/actuator/health/readiness" \
                | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 1
    done
    echo "application did not become ready" >&2
    tail -100 "$CURRENT_RESULT_DIR/application.log" >&2
    exit 1
}

start_application() {
    local profiles=$1
    local kafka_mode=${2:-owned}
    if [[ "$kafka_mode" == "owned" ]]; then
        ensure_kafka_broker_disposable
    elif [[ "$kafka_mode" != "read-only" ]]; then
        echo "unknown benchmark Kafka mode: $kafka_mode" >&2
        exit 2
    fi
    assert_port_available
    if [[ -z "${BENCH_JWT_SECRET:-}" ]]; then
        BENCH_JWT_SECRET=$(openssl rand -base64 48)
    fi
    (
        cd "$PROJECT_DIR"
        env -u SPRING_APPLICATION_JSON \
        -u SPRING_CONFIG_LOCATION \
        -u SPRING_CONFIG_ADDITIONAL_LOCATION \
        -u JDK_JAVA_OPTIONS \
        -u _JAVA_OPTIONS \
        SPRING_PROFILES_ACTIVE="$profiles" \
        SERVER_PORT="$BENCH_PORT" \
        BUSINESS_ZONE=Asia/Shanghai \
        JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai \
        MYSQL_HOST=127.0.0.1 \
        MYSQL_PORT="$BENCH_MYSQL_PORT" \
        MYSQL_DATABASE="$BENCH_DATABASE" \
        MYSQL_USERNAME=root \
        MYSQL_PASSWORD="$BENCH_MYSQL_ROOT_PASSWORD" \
        MYSQL_POOL_MAX_SIZE=20 \
        MYSQL_POOL_MIN_IDLE=5 \
        SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${BENCH_MYSQL_PORT}/${BENCH_DATABASE}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8" \
        SPRING_DATASOURCE_USERNAME=root \
        SPRING_DATASOURCE_PASSWORD="$BENCH_MYSQL_ROOT_PASSWORD" \
        REDIS_HOST=127.0.0.1 \
        REDIS_PORT="$BENCH_REDIS_PORT" \
        REDIS_PASSWORD='' \
        SPRING_DATA_REDIS_HOST=127.0.0.1 \
        SPRING_DATA_REDIS_PORT="$BENCH_REDIS_PORT" \
        SPRING_DATA_REDIS_PASSWORD='' \
        SPRING_DATA_REDIS_DATABASE="$BENCH_REDIS_DATABASE" \
        REDISSON_ENABLED=false \
        KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
        KAFKA_CONSUMER_GROUP="$KAFKA_GROUP_ID" \
        SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
        SPRING_KAFKA_CONSUMER_GROUP_ID="$KAFKA_GROUP_ID" \
        SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=latest \
        SPRING_KAFKA_LISTENER_AUTO_STARTUP="$([[ "$kafka_mode" == "owned" ]] && echo true || echo false)" \
        SPRING_KAFKA_ADMIN_AUTO_CREATE="$([[ "$kafka_mode" == "owned" ]] && echo true || echo false)" \
        KAFKA_LISTENER_CONCURRENCY=3 \
        KAFKA_TOPIC_REPLICATION_FACTOR=1 \
        KAFKA_RUSH_TOPIC_PARTITIONS=12 \
        KAFKA_CACHE_TOPIC_PARTITIONS=3 \
        OPENAI_API_KEY='' \
        JWT_SECRET="$BENCH_JWT_SECRET" \
        ./mvnw -q spring-boot:run
    ) > "$CURRENT_RESULT_DIR/application.log" 2>&1 &
    APP_PID=$!
    if [[ "$kafka_mode" == "owned" ]]; then
        KAFKA_GROUP_USED=true
    fi
    wait_application
}

terminate_process_tree() {
    local root_pid=$1 child_pid
    while IFS= read -r child_pid; do
        if [[ -n "$child_pid" ]]; then
            terminate_process_tree "$child_pid"
        fi
    done < <(pgrep -P "$root_pid" 2>/dev/null || true)
    if kill -0 "$root_pid" 2>/dev/null; then
        kill -TERM "$root_pid" 2>/dev/null || true
    fi
}

stop_application() {
    local attempt
    if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        terminate_process_tree "$APP_PID"
        wait "$APP_PID" 2>/dev/null || true
    fi
    APP_PID=""
    # Maven can exit just before the Spring child finishes its graceful shutdown.
    for ((attempt = 1; attempt <= 50; attempt++)); do
        if ! port_is_listening; then
            return
        fi
        sleep 0.1
    done
    echo "warning: benchmark application did not release port $BENCH_PORT after shutdown" >&2
}

capture_prometheus() {
    curl -fsS "$BASE_URL/actuator/prometheus" > "$CURRENT_RESULT_DIR/prometheus.txt"
    echo "prometheus_captured=prometheus.txt" >> "$CURRENT_RESULT_DIR/metadata.env"
}

verify_event_smoke_response() {
    local status
    status=$(curl -sS -o "$CURRENT_RESULT_DIR/smoke-response.json" \
        -w '%{http_code}' "$BASE_URL/api/events/$EVENT_ID")
    if [[ "$status" != "200" ]]; then
        echo "event smoke request returned HTTP $status" >&2
        exit 1
    fi
    python3 - "$CURRENT_RESULT_DIR/smoke-response.json" "$EVENT_ID" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
if int(payload.get("id", -1)) != int(sys.argv[2]):
    raise SystemExit("event smoke response does not contain the benchmark event ID")
PY
}

run_mysql_baseline() {
    local offset=${1:-0}
    require_command wrk
    require_command curl
    require_command openssl
    require_command python3
    ensure_dependencies
    ensure_benchmark_database
    set_fixture_ids "$offset"
    new_result_dir mysql-baseline
    record_metadata mysql-baseline planned true
    start_application "dev,bench-db"
    prepare_business_fixture
    verify_event_smoke_response

    wrk -t"$THREADS" -c"$CONNECTIONS" -d5s \
        "$BASE_URL/api/events/$EVENT_ID" >/dev/null
    wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency \
        "$BASE_URL/api/events/$EVENT_ID" | tee "$CURRENT_RESULT_DIR/wrk.txt"
    python3 "$BENCH_DIR/parse_wrk.py" \
        "$CURRENT_RESULT_DIR/wrk.txt" > "$CURRENT_RESULT_DIR/summary.json"
    capture_prometheus
    stop_application
    echo "mysql baseline results: $CURRENT_RESULT_DIR"
}

clear_event_cache() {
    docker exec \
        -e REDIS_DB="$BENCH_REDIS_DATABASE" \
        "$REDIS_CONTAINER" sh -eu -c '
            redis-cli -n "$REDIS_DB" --scan --pattern "event:*" |
            while IFS= read -r key; do
                redis-cli -n "$REDIS_DB" UNLINK "$key" >/dev/null
            done
        '
}

run_cache_variant() {
    local name=$1 profiles=$2 prepare_fixture=$3 round=$4 position=$5 variant=$6
    new_result_dir "$name"
    record_metadata "$name" planned true read-only
    {
        echo "cache_round=$round"
        echo "cache_position=$position"
        echo "cache_variant=$variant"
    } >> "$CURRENT_RESULT_DIR/metadata.env"
    if [[ "$prepare_fixture" == "true" ]]; then
        start_application "$profiles" read-only
        prepare_business_fixture
        printf 'UPDATE tb_event SET is_hot=1 WHERE id=%s;\n' "$EVENT_ID" | mysql_exec
    else
        clear_event_cache
        start_application "$profiles" read-only
    fi
    verify_event_smoke_response

    k6 run \
        -e BASE_URL="$BASE_URL" \
        -e EVENT_ID="$EVENT_ID" \
        -e VUS="$CACHE_VUS" \
        -e DURATION="$CACHE_WARMUP_DURATION" \
        "$BENCH_DIR/k6/event-detail.js" \
        > "$CURRENT_RESULT_DIR/k6-warmup.txt"
    k6 run \
        -e SUMMARY_FILE="$CURRENT_RESULT_DIR/k6-summary.json" \
        -e BASE_URL="$BASE_URL" \
        -e EVENT_ID="$EVENT_ID" \
        -e VUS="$CACHE_VUS" \
        -e DURATION="$CACHE_DURATION" \
        "$BENCH_DIR/k6/event-detail.js" \
        | tee "$CURRENT_RESULT_DIR/k6.txt"
    capture_prometheus
    stop_application
    CACHE_SUMMARY_INPUTS+=("$variant=$CURRENT_RESULT_DIR/k6-summary.json")
    echo "$name results: $CURRENT_RESULT_DIR"
}

run_cache_comparison() {
    local fixture_offset=${1:-0}
    require_command k6
    require_command curl
    require_command python3
    ensure_dependencies
    ensure_benchmark_database
    set_fixture_ids "$fixture_offset"
    ASYNC_REDIS_PREFIX="event"
    claim_async_redis_database
    CACHE_SUMMARY_INPUTS=()

    local round rotation position variant profiles name prepare_fixture
    local fixture_prepared=false
    local -a order
    for ((round = 1; round <= CACHE_ROUNDS; round++)); do
        rotation=$(((round - 1) % 3))
        case "$rotation" in
            0) order=(mysql redis caffeine) ;;
            1) order=(redis caffeine mysql) ;;
            2) order=(caffeine mysql redis) ;;
        esac
        for position in 1 2 3; do
            variant=${order[$((position - 1))]}
            case "$variant" in
                mysql) profiles="dev,bench-db" ;;
                redis) profiles="dev,bench-redis" ;;
                caffeine) profiles="dev,bench-full" ;;
            esac
            prepare_fixture=false
            if [[ "$fixture_prepared" == "false" ]]; then
                prepare_fixture=true
                fixture_prepared=true
            fi
            if [[ "$CACHE_ROUNDS" -eq 1 ]]; then
                name="cache-$variant"
            else
                name="cache-r${round}-$variant"
            fi
            run_cache_variant "$name" "$profiles" "$prepare_fixture" \
                "$round" "$position" "$variant"
        done
    done

    new_result_dir cache-summary
    python3 "$BENCH_DIR/summarize_cache.py" "${CACHE_SUMMARY_INPUTS[@]}" \
        | tee "$CURRENT_RESULT_DIR/cache-aggregate.json"
    echo "cache aggregate results: $CURRENT_RESULT_DIR"
}

guard_lua_keys() {
    local existing
    LUA_STOCK_KEY="bench:ticket:{$SKU_ID}:stock"
    LUA_BUYERS_KEY="bench:ticket:{$SKU_ID}:buyers"
    LUA_SEQUENCE_KEY="bench:ticket:{$SKU_ID}:sequence"
    existing=$(docker exec "$REDIS_CONTAINER" redis-cli --raw EXISTS \
        "$LUA_STOCK_KEY" "$LUA_BUYERS_KEY" "$LUA_SEQUENCE_KEY")
    if [[ "$existing" -ne 0 ]]; then
        echo "Redis-only benchmark keys already exist for SKU $SKU_ID; refusing to overwrite them" >&2
        exit 1
    fi
    LUA_REDIS_KEYS_CREATED=true
}

run_redis_lua() {
    local offset=${1:-0}
    ensure_dependencies
    set_fixture_ids "$offset"
    new_result_dir redis-lua
    record_metadata redis-lua not-applicable false
    guard_lua_keys

    docker cp "$BENCH_DIR/lua/ticket_rush_bench.lua" \
        "$REDIS_CONTAINER:$LUA_SCRIPT_CONTAINER_PATH"
    LUA_SCRIPT_COPIED=true
    docker exec "$REDIS_CONTAINER" redis-cli SET \
        "$LUA_STOCK_KEY" "$LUA_REQUESTS" >/dev/null

    docker exec "$REDIS_CONTAINER" sh -eu -c '
        script_path=$1
        shift
        # Preserve line breaks so Lua line comments cannot consume the script body.
        script=$(cat "$script_path")
        exec redis-benchmark --csv -n "$1" -c "$2" EVAL "$script" 3 "$3" "$4" "$5"
    ' sh "$LUA_SCRIPT_CONTAINER_PATH" "$LUA_REQUESTS" "$LUA_CLIENTS" \
        "$LUA_STOCK_KEY" "$LUA_BUYERS_KEY" "$LUA_SEQUENCE_KEY" \
        | tee "$CURRENT_RESULT_DIR/redis-benchmark.csv"

    local final_stock members
    final_stock=$(docker exec "$REDIS_CONTAINER" redis-cli --raw GET "$LUA_STOCK_KEY")
    members=$(docker exec "$REDIS_CONTAINER" redis-cli --raw SCARD "$LUA_BUYERS_KEY")
    printf '{"requests":%d,"finalStock":%d,"uniqueReservations":%d,"inventoryConserved":%s}\n' \
        "$LUA_REQUESTS" "$final_stock" "$members" \
        "$([[ $((final_stock + members)) -eq "$LUA_REQUESTS" ]] && echo true || echo false)" \
        > "$CURRENT_RESULT_DIR/state.json"
    if [[ "$final_stock" -ne 0 || "$members" -ne "$LUA_REQUESTS" ]]; then
        echo "Redis Lua invariant failed; see $CURRENT_RESULT_DIR/state.json" >&2
        exit 1
    fi
    echo "redis lua results: $CURRENT_RESULT_DIR"
}

claim_async_redis_database() {
    local ownership_result
    ownership_result=$(docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" --raw EVAL \
        "if redis.call('DBSIZE') ~= 0 then return 0 end redis.call('SET', KEYS[1], ARGV[1]); return 1" \
        1 "$ASYNC_REDIS_SENTINEL_KEY" "$ASYNC_REDIS_SENTINEL_VALUE")
    if [[ "$ownership_result" -ne 1 ]]; then
        echo "Redis database $BENCH_REDIS_DATABASE is not empty; refusing to claim shared state" >&2
        exit 1
    fi
    ASYNC_REDIS_CLAIMED=true
}

capture_kafka_lag() {
    if ! docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server 127.0.0.1:9092 --describe --group "$KAFKA_GROUP_ID" \
            > "$CURRENT_RESULT_DIR/kafka-lag.txt" 2>&1; then
        echo -1
        return
    fi
    awk -v group="$KAFKA_GROUP_ID" '
        $1 == group && $6 ~ /^[0-9]+$/ { found = 1; total += $6 }
        END { print found ? total : -1 }
    ' "$CURRENT_RESULT_DIR/kafka-lag.txt"
}

kafka_topic_is_owned() {
    case "$1" in
        "$KAFKA_RUSH_TOPIC"|"$KAFKA_RUSH_DLT_TOPIC"|"$KAFKA_CACHE_TOPIC"|"$KAFKA_CACHE_DLT_TOPIC")
            return 0
            ;;
        *) return 1 ;;
    esac
}

kafka_group_is_owned() {
    case "$1" in
        "$KAFKA_GROUP_ID"|"${KAFKA_GROUP_ID}-dlt"|"${KAFKA_GROUP_ID}-dlt-recovery")
            return 0
            ;;
        *) return 1 ;;
    esac
}

kafka_topic_record_count() {
    local topic=$1 output
    if ! output=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh \
            --bootstrap-server 127.0.0.1:9092 --topic "$topic" --time -1 2>&1); then
        return 1
    fi
    awk -F: '$3 ~ /^[0-9]+$/ { total += $3 } END { print total + 0 }' <<< "$output"
}

run_full_async() {
    local offset=${1:-0}
    require_command k6
    require_command jq
    require_command python3
    require_command curl
    require_command openssl
    ensure_dependencies
    ensure_benchmark_database
    set_fixture_ids "$offset"
    ASYNC_REDIS_PREFIX="ticket:{$SKU_ID}"

    USER_ID_BASE=${BENCH_USER_ID_BASE:-$((EVENT_ID + 100000))}
    require_business_id BENCH_USER_ID_BASE "$USER_ID_BASE"
    USER_ID_LAST=$((USER_ID_BASE + ASYNC_USERS - 1))
    require_business_id BENCH_USER_ID_LAST "$USER_ID_LAST"

    new_result_dir full-async
    record_metadata full-async planned true
    {
        echo "user_id_base=$USER_ID_BASE"
        echo "user_id_last=$USER_ID_LAST"
    } >> "$CURRENT_RESULT_DIR/metadata.env"
    claim_async_redis_database

    if [[ -z "${BENCH_JWT_SECRET:-}" ]]; then
        BENCH_JWT_SECRET=$(openssl rand -base64 48)
    fi

    start_application "dev,bench-full"
    prepare_business_fixture
    guard_user_range

    FIXTURE_TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ticket-rush-bench.XXXXXX")
    python3 "$BENCH_DIR/generate_fixtures.py" \
        --count "$ASYNC_USERS" \
        --user-id-base "$USER_ID_BASE" \
        --secret "$BENCH_JWT_SECRET" \
        --sql "$FIXTURE_TEMP_DIR/users.sql" \
        --tokens "$FIXTURE_TEMP_DIR/tokens.json"
    mysql_exec < "$FIXTURE_TEMP_DIR/users.sql"

    local now sale_start sale_end cleanup_at
    local rush_prefix stock_key buyers_key metadata_key
    now=$(date +%s)
    sale_start=$((now - 3600))
    sale_end=$((now + 3600))
    cleanup_at=$((sale_end + 604800))
    rush_prefix=$ASYNC_REDIS_PREFIX
    stock_key="$rush_prefix:stock"
    buyers_key="$rush_prefix:buyers"
    metadata_key="$rush_prefix:meta"
    docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" \
        SET "$stock_key" "$ASYNC_USERS" >/dev/null
    docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" HSET "$metadata_key" \
        eventId "$EVENT_ID" status 1 saleStart "$sale_start" saleEnd "$sale_end" \
        unitPrice 10000 cleanupAt "$cleanup_at" >/dev/null
    docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" SADD \
        "ticket:rush:outbox:skus" "$SKU_ID" >/dev/null

    k6 run \
        -e SUMMARY_FILE="$CURRENT_RESULT_DIR/k6-summary.json" \
        -e TOKENS_FILE="$FIXTURE_TEMP_DIR/tokens.json" \
        -e BASE_URL="$BASE_URL" \
        -e EVENT_ID="$EVENT_ID" \
        -e SKU_ID="$SKU_ID" \
        -e RUN_ID="$BENCH_RUN_ID" \
        -e VUS="$ASYNC_VUS" \
        "$BENCH_DIR/k6/full-async.js" \
        | tee "$CURRENT_RESULT_DIR/k6.txt"
    cleanup_fixture_temp

    local requested executed dropped accepted rejected accounted
    requested=$(jq -r '.requestedIterations' "$CURRENT_RESULT_DIR/k6-summary.json")
    executed=$(jq -r '.executedIterations' "$CURRENT_RESULT_DIR/k6-summary.json")
    dropped=$(jq -r '.droppedIterations' "$CURRENT_RESULT_DIR/k6-summary.json")
    accepted=$(jq -r '.accepted' "$CURRENT_RESULT_DIR/k6-summary.json")
    rejected=$(jq -r '.rejected' "$CURRENT_RESULT_DIR/k6-summary.json")
    accounted=$(jq -r '.accountedResponses' "$CURRENT_RESULT_DIR/k6-summary.json")
    if [[ "$requested" -ne "$ASYNC_USERS" \
            || "$executed" -ne "$requested" \
            || "$dropped" -ne 0 \
            || "$accepted" -ne "$requested" \
            || "$rejected" -ne 0 \
            || "$accounted" -ne "$requested" ]]; then
        echo "k6 response-accounting invariant failed; see $CURRENT_RESULT_DIR/k6-summary.json" >&2
        exit 1
    fi
    EXPECTED_RUSH_RECORDS=$accepted

    local configured_stock expected_stock order_count final_stock
    local buyers_count durable_held active_order_quantity kafka_lag
    configured_stock=$(printf \
        'SELECT stock FROM tb_ticket_sku WHERE id=%s;\n' "$SKU_ID" | mysql_exec)
    expected_stock=$((configured_stock - accepted))
    order_count=0
    for ((i = 1; i <= ASYNC_TIMEOUT_SECONDS; i++)); do
        order_count=$(printf \
            'SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=%s AND status IN (0,1) AND deleted=0;\n' \
            "$SKU_ID" | mysql_exec)
        if [[ "$order_count" -ge "$accepted" ]]; then
            break
        fi
        sleep 1
    done
    final_stock=$(docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" \
        --raw GET "$stock_key")
    buyers_count=$(docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" \
        --raw SCARD "$buyers_key")
    durable_held=$(printf \
        'SELECT COALESCE(SUM(quantity),0) FROM tb_rush_reservation WHERE sku_id=%s AND status IN (0,1,4) AND deleted=0;\n' \
        "$SKU_ID" | mysql_exec)
    active_order_quantity=$(printf \
        'SELECT COALESCE(SUM(quantity),0) FROM tb_ticket_order WHERE sku_id=%s AND status IN (0,1) AND deleted=0;\n' \
        "$SKU_ID" | mysql_exec)

    kafka_lag=-1
    for ((i = 1; i <= 30; i++)); do
        kafka_lag=$(capture_kafka_lag)
        if [[ "$kafka_lag" -eq 0 ]]; then
            break
        fi
        sleep 1
    done
    capture_prometheus
    printf '%s\n' \
        "{\"configuredStock\":$configured_stock,\"requested\":$requested,\"executed\":$executed," \
        "\"dropped\":$dropped,\"accepted\":$accepted,\"rejected\":$rejected," \
        "\"ordersCreated\":$order_count,\"buyers\":$buyers_count,\"durableHeld\":$durable_held," \
        "\"activeOrderQuantity\":$active_order_quantity,\"finalStock\":$final_stock," \
        "\"expectedStock\":$expected_stock,\"kafkaLag\":$kafka_lag}" \
        | tr -d '\n' > "$CURRENT_RESULT_DIR/invariants.json"
    printf '\n' >> "$CURRENT_RESULT_DIR/invariants.json"
    stop_application

    if [[ "$order_count" -ne "$accepted" \
            || "$final_stock" -ne "$expected_stock" \
            || "$buyers_count" -ne "$accepted" \
            || $((durable_held + active_order_quantity)) -ne "$accepted" \
            || $((final_stock + buyers_count)) -ne "$configured_stock" \
            || "$kafka_lag" -ne 0 ]]; then
        echo "full async invariant failed; see $CURRENT_RESULT_DIR/invariants.json" >&2
        exit 1
    fi
    echo "full async results: $CURRENT_RESULT_DIR"
}

cleanup_fixture_temp() {
    if [[ -n "$FIXTURE_TEMP_DIR" && -d "$FIXTURE_TEMP_DIR" ]]; then
        rm -f "$FIXTURE_TEMP_DIR/users.sql" "$FIXTURE_TEMP_DIR/tokens.json"
        rmdir "$FIXTURE_TEMP_DIR" 2>/dev/null || true
    fi
    FIXTURE_TEMP_DIR=""
}

cleanup_redis() {
    if [[ "$LUA_SCRIPT_COPIED" == "true" ]]; then
        if ! docker exec "$REDIS_CONTAINER" rm -f -- "$LUA_SCRIPT_CONTAINER_PATH" \
                >/dev/null 2>&1; then
            echo "warning: failed to remove the run-owned Redis benchmark script" >&2
        fi
        LUA_SCRIPT_COPIED=false
    fi
    if [[ "$LUA_REDIS_KEYS_CREATED" == "true" ]]; then
        if ! docker exec "$REDIS_CONTAINER" redis-cli UNLINK \
                "$LUA_STOCK_KEY" "$LUA_BUYERS_KEY" "$LUA_SEQUENCE_KEY" >/dev/null 2>&1; then
            echo "warning: failed to remove exact Redis-only benchmark keys" >&2
        fi
        LUA_REDIS_KEYS_CREATED=false
    fi
    if [[ "$ASYNC_REDIS_CLAIMED" == "true" ]]; then
        if ! docker exec \
                -e REDIS_DB="$BENCH_REDIS_DATABASE" \
                -e KEY_PATTERN="${ASYNC_REDIS_PREFIX}:*" \
                "$REDIS_CONTAINER" sh -eu -c '
                    redis-cli -n "$REDIS_DB" --scan --pattern "$KEY_PATTERN" |
                    while IFS= read -r key; do
                        redis-cli -n "$REDIS_DB" UNLINK "$key" >/dev/null
                    done
                '; then
            echo "warning: failed to remove run-owned Redis SKU keys" >&2
        fi
        if ! docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" UNLINK \
                "ticket:rush:outbox:skus" >/dev/null 2>&1; then
            echo "warning: failed to remove the run-owned Redis outbox registry" >&2
        fi
        if ! docker exec "$REDIS_CONTAINER" redis-cli -n "$BENCH_REDIS_DATABASE" EVAL \
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" \
                1 "$ASYNC_REDIS_SENTINEL_KEY" "$ASYNC_REDIS_SENTINEL_VALUE" \
                >/dev/null 2>&1; then
            echo "warning: failed to release the benchmark Redis ownership sentinel" >&2
        fi
        ASYNC_REDIS_CLAIMED=false
    fi
}

cleanup_kafka_topics() {
    if [[ "$KAFKA_BROKER_CLAIMED" != "true" ]]; then
        return
    fi
    local topics groups topic group count expected
    local safe_to_delete=true
    if ! topics=$(list_non_internal_kafka_topics); then
        echo "warning: could not inspect benchmark Kafka topics; leaving them untouched" >&2
        KAFKA_BROKER_CLAIMED=false
        return
    fi
    if ! groups=$(list_kafka_consumer_groups); then
        echo "warning: could not inspect benchmark Kafka groups; leaving topics untouched" >&2
        KAFKA_BROKER_CLAIMED=false
        return
    fi
    for topic in $topics; do
        if ! kafka_topic_is_owned "$topic"; then
            echo "warning: unexpected Kafka topic appeared during the run: $topic" >&2
            safe_to_delete=false
        fi
    done
    for group in $groups; do
        if ! kafka_group_is_owned "$group"; then
            echo "warning: unexpected Kafka consumer group appeared during the run: $group" >&2
            safe_to_delete=false
        fi
    done
    for topic in "$KAFKA_RUSH_TOPIC" "$KAFKA_RUSH_DLT_TOPIC" \
            "$KAFKA_CACHE_TOPIC" "$KAFKA_CACHE_DLT_TOPIC"; do
        if ! grep -Fxq "$topic" <<< "$topics"; then
            continue
        fi
        expected=0
        if [[ "$topic" == "$KAFKA_RUSH_TOPIC" ]]; then
            expected=$EXPECTED_RUSH_RECORDS
        fi
        if ! count=$(kafka_topic_record_count "$topic"); then
            echo "warning: could not prove ownership of Kafka topic $topic" >&2
            safe_to_delete=false
        elif [[ "$count" -ne "$expected" ]]; then
            echo "warning: Kafka topic $topic has $count records, expected $expected; leaving topics untouched" >&2
            safe_to_delete=false
        fi
    done
    if [[ "$safe_to_delete" == "true" ]]; then
        for topic in $topics; do
            if ! docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
                    --bootstrap-server 127.0.0.1:9092 --delete --topic "$topic" \
                    >/dev/null 2>&1; then
                echo "warning: failed to delete run-owned Kafka topic $topic" >&2
            fi
        done
    else
        echo "warning: Kafka ownership proof failed; no application topic was deleted" >&2
    fi
    KAFKA_BROKER_CLAIMED=false
}

cleanup_kafka_groups() {
    if [[ "$KAFKA_GROUP_USED" != "true" ]]; then
        return
    fi
    local group output attempt deleted
    for group in "$KAFKA_GROUP_ID" "${KAFKA_GROUP_ID}-dlt" "${KAFKA_GROUP_ID}-dlt-recovery"; do
        deleted=false
        output=""
        for ((attempt = 1; attempt <= 20; attempt++)); do
            output=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
                    --bootstrap-server 127.0.0.1:9092 --delete --group "$group" 2>&1 || true)
            if ! list_kafka_consumer_groups | grep -Fxq "$group"; then
                deleted=true
                break
            fi
            # A stopped consumer may remain non-empty until its Kafka session expires.
            sleep 1
        done
        if [[ "$deleted" != "true" ]]; then
            echo "warning: failed to delete benchmark Kafka group $group after retries: $output" >&2
        fi
    done
    KAFKA_GROUP_USED=false
}

drop_benchmark_database() {
    if [[ "$BENCH_DATABASE_CREATED" != "true" || "$BENCH_KEEP_DATABASE" == "true" ]]; then
        return
    fi
    echo "dropping disposable benchmark database: $BENCH_DATABASE"
    if ! printf "DROP DATABASE \`%s\`;\n" "$BENCH_DATABASE" | mysql_admin_exec; then
        echo "warning: failed to drop disposable benchmark database $BENCH_DATABASE" >&2
    fi
    BENCH_DATABASE_CREATED=false
}

cleanup() {
    stop_application
    cleanup_fixture_temp
    cleanup_redis
    cleanup_kafka_groups
    cleanup_kafka_topics
    drop_benchmark_database
    if [[ "$BENCH_PROCESS_LOCK_ACQUIRED" == "true" ]]; then
        if ! rmdir "$BENCH_PROCESS_LOCK_DIR" 2>/dev/null; then
            echo "warning: failed to release benchmark process lock: $BENCH_PROCESS_LOCK_DIR" >&2
        fi
        BENCH_PROCESS_LOCK_ACQUIRED=false
    fi
}

validate_configuration
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
acquire_process_lock

case "$SCENARIO" in
    mysql) run_mysql_baseline 0 ;;
    cache) run_cache_comparison 0 ;;
    lua) run_redis_lua 0 ;;
    async) run_full_async 0 ;;
    all)
        run_mysql_baseline 0
        run_redis_lua 10
        run_full_async 20
        # The async and cache scenarios both require exclusive ownership of the configured Redis
        # database. Release only this run's exact namespace and sentinel before claiming it again.
        cleanup_redis
        run_cache_comparison 30
        ;;
    *)
        echo "usage: bash bench/run.sh [mysql|cache|lua|async|all]" >&2
        exit 2
        ;;
esac
