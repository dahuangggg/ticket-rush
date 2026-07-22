# 本地运行、测试与压测

## 前置条件

- JDK 17
- OrbStack 或其他支持 Docker Compose 的运行时
- Node.js `^20.19.0` 或 `>=22.12.0`，以及 npm
- `curl`、`jq`、OpenSSL
- 压测额外需要 `wrk`、`k6` 和 Python 3

检查版本：

```bash
java -version
docker compose version
node --version
npm --version
jq --version
openssl version
wrk --version
k6 version
```

## 启动基础设施

推荐开发模式是 MySQL、Redis、Kafka 在容器里，后端由 Maven 在宿主机运行：

```bash
export JWT_SECRET="$(openssl rand -base64 48)"
export BUSINESS_ZONE=Asia/Shanghai

docker compose up -d --wait mysql redis kafka
docker compose ps
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

默认宿主机端口：

| 服务 | 宿主机端口 | 容器端口 |
|---|---:|---:|
| Backend | 8081 | 8081 |
| MySQL | 13306 | 3306 |
| Redis | 16379 | 6379 |
| Kafka | 9092 | 9092 |

也可以把后端一起放进 Compose：

```bash
export JWT_SECRET="$(openssl rand -base64 48)"
docker compose --profile full up --build -d
docker compose ps
```

不要同时启动 Compose `app` 和本地 Maven 后端，它们会争用 8081。

### 可选 AI 配置

票务主链路不依赖 AI。只有需要实际调用 AI 对话时才配置兼容 OpenAI 的凭据：

```bash
export OPENAI_API_KEY=replace_me
export OPENAI_BASE_URL=https://api.openai.com/v1
export OPENAI_MODEL=replace_with_supported_model
```

不要把真实凭据写进仓库或共享终端记录。AI 只注册活动、票档与当前用户订单查询工具，没有抢票、支付、取消、库存、管理员或提醒写工具。

## Flyway 注意事项

空数据库会按 V1、V2、V3、V4 迁移，开发 profile 再加载相对时间种子。`baseline-on-migrate=false`，旧 Docker SQL 创建的非空数据库不会被自动认领。

V4 是停止写入后执行的协议升级，不支持 V3/V4 混合滚动发布。包含真实数据的旧库必须先备份、排空旧消费者、旧 PENDING 订单和 V1–V3 的 legacy rollback work，再在副本上演练。这里的 rollback work 是升级前遗留状态，不是 V4 当前运行时仍在创建的任务。不要通过修改既有迁移、开启 `baseline-on-migrate` 或删除不明 volume 来“解决”迁移错误。

## 前端

```bash
cd frontend
npm ci
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`，Vite 将 API 代理到后端。

## 最小端到端 Smoke

先确认公开读路径和 readiness：

```bash
curl -s http://127.0.0.1:8081/actuator/health/readiness | jq
curl -s http://127.0.0.1:8081/api/events | jq
curl -s http://127.0.0.1:8081/api/events/2001/skus | jq
```

开发种子中的 `13800000001` 是 admin。请求验证码后，本地教学环境可从 Redis 读取；真实环境绝不能这样暴露验证码：

```bash
PHONE=13800000001

curl -s -X POST http://127.0.0.1:8081/api/auth/sms-code \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\"}"

CODE=$(docker compose exec -T redis redis-cli GET "auth:sms-code:{$PHONE}" \
  | tr -d '\r' | cut -d'|' -f1)

TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\",\"code\":\"$CODE\"}" \
  | jq -r '.accessToken')
```

选择一个当前 `status=1` 且在售卖窗口内的 SKU，再初始化并提交 Rush Request：

```bash
EVENT_ID=2001
SKU_ID=3001
IDEMPOTENCY_KEY=$(openssl rand -hex 16)

curl -i -X POST "http://127.0.0.1:8081/api/admin/skus/$SKU_ID/init-stock" \
  -H "Authorization: Bearer $TOKEN"

RUSH_RESPONSE=$(curl -s -X POST http://127.0.0.1:8081/api/ticket-rush/requests \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"eventId\":$EVENT_ID,\"skuId\":$SKU_ID,\"quantity\":1}")

printf '%s\n' "$RUSH_RESPONSE" | jq
RESERVATION_ID=$(printf '%s\n' "$RUSH_RESPONSE" | jq -r '.reservationId')

curl -s "http://127.0.0.1:8081/api/ticket-rush/reservations/$RESERVATION_ID" \
  -H "Authorization: Bearer $TOKEN" | jq

curl -s http://127.0.0.1:8081/api/orders/me \
  -H "Authorization: Bearer $TOKEN" | jq
```

HTTP 202 后短时间 `orderId=null` 是正常异步窗口。响应不确定时复用同一个 `Idempotency-Key`，不要生成新键。

## 快速测试通道

```bash
./mvnw -B test
```

这个命令默认排除 JUnit `integration` 标签。它适合快速验证 Controller、纯状态、Fake Adapter 和不依赖真实服务的契约，但不能证明真实 MySQL、Redis 或 Kafka 行为。

聚焦单类：

```bash
./mvnw -Dtest=TicketRushControllerTest test
./mvnw -Dtest=EventCacheManagerTest test
./mvnw -Dtest=MicrometerTicketRushMetricsTest test
```

## 真实 MySQL/Redis 集成通道

```bash
docker compose up -d --wait mysql redis kafka

# 每次选择一个新的、明确可删除的测试库名；Maven/Flyway 不会创建 MySQL database。
INTEGRATION_DB=ticket_rush_it_local_20260722_1200
docker compose exec -T mysql mysql -uroot -p123456 \
  -e "CREATE DATABASE ${INTEGRATION_DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"

MYSQL_DATABASE="$INTEGRATION_DB" ./mvnw -B -Pintegration verify
```

聚焦一个带 `integration` 标签的类：

```bash
MYSQL_DATABASE="$INTEGRATION_DB" ./mvnw -Pintegration -Dit.test=TicketRushServiceTest verify
```

所有计划中的集成测试结束后，只删除刚才明确创建且已经确认可丢弃的数据库：

```bash
case "$INTEGRATION_DB" in
  ticket_rush_it_local_*)
    docker compose exec -T mysql mysql -uroot -p123456 -e "DROP DATABASE ${INTEGRATION_DB}"
    ;;
  *)
    printf '拒绝删除意外的数据库名：%s\n' "$INTEGRATION_DB" >&2
    ;;
esac
unset INTEGRATION_DB
```

这个 profile 使用真实 MySQL、Redis 并执行 Flyway。必须先创建明确的独立空数据库，不要把集成测试指向共享或有价值的数据；Flyway 负责表迁移，不负责创建 database。Kafka 客户端可连接，但 Listener 和定时 Worker 被刻意关闭，以保持服务级测试确定性，因此它不是 Kafka 端到端证明。

本轮第一次集成命令暴露了 Flyway starter 缺失并失败；修复后使用独立空库复测成功。完整失败、修复和复测证据见[验证报告](verification-report.md)，旧 `ticket_rush` 库没有被写成“已通过”。

## 前端验证

```bash
cd frontend
npm ci
npm audit --audit-level=high
npm run build
```

分别记录依赖安装、漏洞审计和生产构建结果。`npm run build` 成功不能代替浏览器端交互 E2E。

## 压测的三个边界

| 场景 | 命令 | 测量内容 | 不包含 |
|---|---|---|---|
| MySQL baseline | `bash bench/run.sh mysql` | 关闭 Redis/Caffeine 的活动详情 HTTP | Reservation、Kafka、订单 |
| Redis Lua | `bash bench/run.sh lua` | Redis 原子库存扣减与唯一用户记录 | HTTP、JWT、MySQL、Kafka |
| Full async | `bash bench/run.sh async` | k6 测 HTTP 202 Reservation 接受阶段；随后等待 Relay、Kafka 与 MySQL Order Intake 收敛并做守恒门禁 | 订单提交吞吐、真实支付和生产级多副本拓扑 |

全量执行：

```bash
bash bench/run.sh all
```

常用参数：

```bash
BENCH_DURATION=30s BENCH_CONNECTIONS=200 bash bench/run.sh mysql
BENCH_LUA_REQUESTS=100000 BENCH_LUA_CLIENTS=100 bash bench/run.sh lua
BENCH_ASYNC_USERS=5000 BENCH_ASYNC_VUS=200 bash bench/run.sh async
```

脚本会创建每轮独立数据库、ID 范围、Redis 命名空间和 Kafka group，并把原始证据保存在 `bench/results/<timestamp>-<run-id>-<scenario>/`。不要把它指向共享或生产基础设施。

## 压测正确性门禁

Lua 场景至少必须满足：

```text
final stock = initial stock - successful reservations
buyers cardinality = successful reservations
```

异步场景至少必须满足：

```text
orders created = HTTP accepted
final Redis stock = initial stock - accepted
Redis buyers = accepted
durable held + active order quantity = accepted
final Redis stock + Redis buyers = configured stock
Kafka lag = 0
```

任一门禁失败，本轮压测结论就是失败；不能只截取一个漂亮 QPS。

## 如何记录性能结果

每轮至少记录：

- Git commit 和 dirty worktree 状态；
- CPU、内存、JVM、容器镜像版本；
- 初始数据量与库存；
- 并发、持续时间、预热；
- p50、p95、p99、max、吞吐与错误分类；
- MySQL 连接等待、Redis Lua 延迟、Relay 与 Kafka lag；
- 最终库存守恒检查；
- 结果目录。

不同边界的 QPS 不可直接比较。至少重复多轮并报告波动；单机一次结果只能说明该次环境，不是生产容量承诺。

## 停止服务

保留数据卷：

```bash
docker compose down
```

`docker compose down -v` 会删除项目 MySQL、Redis、Kafka 数据卷。只有确认这些数据是可丢弃的教学数据时才能执行。

## 本轮证据

本轮实际命令、测试数、失败项和 `bench/results` 目录统一维护在[验证报告](verification-report.md)，本页只描述稳定运行方法，不复制易漂移的结果数字。
