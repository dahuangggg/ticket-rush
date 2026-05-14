#!/usr/bin/env bash
# 压测入口脚本
# 用法：bash bench/run.sh [db|redis|full|all]
set -euo pipefail

# ── 修改为实际的 eventId ──────────────────────────────────────────────────────
EVENT_ID=1000000000001
# ─────────────────────────────────────────────────────────────────────────────

BASE_URL="http://127.0.0.1:8081"
ENDPOINT="$BASE_URL/api/events/$EVENT_ID"
THREADS=4
CONNECTIONS=200
DURATION=30s

SCENARIO=${1:-full}

run_bench() {
    local name=$1
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  场景：$name"
    echo "  URL ：$ENDPOINT"
    echo "  并发：$CONNECTIONS 连接 / $THREADS 线程 / $DURATION"
    echo "════════════════════════════════════════════════════════"

    # 预热：先跑 5 秒，让 JIT 编译完成、缓存填充好
    echo "[预热] 运行 5s 预热请求..."
    wrk -t"$THREADS" -c"$CONNECTIONS" -d5s "$ENDPOINT" > /dev/null 2>&1

    echo "[压测] 正式开始..."
    wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency "$ENDPOINT"

    echo ""
    echo "压测完成，结果见上方输出。"
    echo "────────────────────────────────────────────────────────"
}

flush_redis_event_cache() {
    echo "[准备] 清除 Redis 中该活动的所有缓存 key..."
    # 根据你的 Redis 端口调整（-p 16379 是 dev 环境的宿主机映射端口）
    docker exec redis redis-cli -p 6379 DEL \
        "event:detail:$EVENT_ID" \
        "event:detail:hot:$EVENT_ID" \
        "event:null:$EVENT_ID" \
        "event:lock:$EVENT_ID" \
        "event:lock:hot:$EVENT_ID" \
        "event:access:count:$EVENT_ID" > /dev/null 2>&1 || true
    echo "[准备] Redis 缓存已清除。"
}

check_server() {
    echo "[检查] 等待服务就绪..."
    for i in $(seq 1 20); do
        if curl -sf "$BASE_URL/api/events/$EVENT_ID" > /dev/null 2>&1; then
            echo "[检查] 服务已就绪。"
            return 0
        fi
        sleep 1
    done
    echo "[错误] 服务未在 20 秒内响应，请确认应用已启动。"
    exit 1
}

case "$SCENARIO" in
    db)
        echo ">>> 场景一：纯 MySQL（请确认已用 bench-db profile 启动服务）"
        check_server
        run_bench "MySQL only"
        ;;
    redis)
        echo ">>> 场景二：MySQL + Redis（请确认已用 bench-redis profile 启动服务）"
        check_server
        flush_redis_event_cache
        # 触发一次请求让数据进入 Redis，之后的并发才能命中 Redis 缓存
        echo "[准备] 触发首次请求以预填 Redis 缓存..."
        curl -sf "$ENDPOINT" > /dev/null
        run_bench "MySQL + Redis"
        ;;
    full)
        echo ">>> 场景三：MySQL + Redis + Caffeine（请确认已用 bench-full profile 启动服务）"
        check_server
        flush_redis_event_cache
        echo "[准备] 触发首次请求以预填 Redis 和 Caffeine 缓存..."
        curl -sf "$ENDPOINT" > /dev/null
        run_bench "MySQL + Redis + Caffeine"
        ;;
    all)
        echo ">>> 依次运行三个场景"
        echo ""
        echo "!!! 注意：all 模式需要你在每个场景之间手动重启服务并切换 profile !!!"
        echo ""
        echo "请先启动 bench-db profile 的服务，然后按 Enter 继续..."
        read -r
        check_server
        run_bench "MySQL only"

        echo ""
        echo "请切换到 bench-redis profile 重启服务，然后按 Enter 继续..."
        read -r
        check_server
        flush_redis_event_cache
        curl -sf "$ENDPOINT" > /dev/null
        run_bench "MySQL + Redis"

        echo ""
        echo "请切换到 bench-full profile 重启服务，然后按 Enter 继续..."
        read -r
        check_server
        flush_redis_event_cache
        curl -sf "$ENDPOINT" > /dev/null
        run_bench "MySQL + Redis + Caffeine"
        ;;
    *)
        echo "用法：bash bench/run.sh [db|redis|full|all]"
        exit 1
        ;;
esac
