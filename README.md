# ticket-rush

[中文](#中文) | [English](#english)

---

## 中文

高并发演唱会抢票教学项目：用 Spring Boot、MySQL、Redis Lua 和 Kafka 演示秒杀系统中的线性化点、幂等、异步创单、补偿与库存守恒。

### 状态

早期项目，不再维护。代码与文档保留作学习参考，不保证可用于生产。

### 功能

- 短信验证码登录，JWT access token + Redis 存储的 refresh token
- 演出、票档（SKU）查询，Caffeine + Redis 多级缓存
- 抢票：Redis Lua 原子预占库存，Redis Stream 预占日志 + 中继投递 Kafka
- 异步创单：Kafka 消费幂等，订单与消息去重同一 MySQL 事务提交
- 模拟支付、取消、超时关单；Release Intent 保证库存回补可恢复且幂等
- 库存对账（available + held + active orders = 配置库存）
- 管理端：库存初始化、Release Intent 重试
- 抢票提醒（`/api/reminders`）
- 只读 AI 助手（LangChain4j）：仅能查询演出、票档和当前用户订单，没有任何写操作工具
- Vue 3 前端：演出列表、演出详情/抢票、我的订单、AI 对话
- 压测脚本（`bench/`）与 Prometheus 指标

### 技术栈

| 方面 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 4、MyBatis-Plus |
| 存储 | MySQL 8.4，Flyway 管理表结构 |
| 缓存 / 预占 | Redis 7.2、Lua、Redisson、Caffeine |
| 消息 | Kafka 3.7 |
| AI | LangChain4j（只读工具白名单） |
| 前端 | Vue 3、Vue Router、Vite、Axios |
| 构建 / 部署 | Maven Wrapper、Docker Compose |

### 本地运行

前置：JDK 17、Docker、Node.js 22。

```bash
# 1. 启动依赖：MySQL :13306 (root/123456)、Redis :16379、Kafka :9092
docker compose up -d mysql redis kafka

# 2. 启动后端（http://127.0.0.1:8081），OPENAI_API_KEY 仅 AI 功能需要
export JWT_SECRET="$(openssl rand -base64 48)"
export OPENAI_API_KEY=your_key_if_ai_is_enabled
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. 启动前端（http://127.0.0.1:5173）
cd frontend && npm ci && npm run dev
```

测试：

```bash
./mvnw test                                                  # 快速测试，不依赖外部服务
docker compose up -d mysql redis kafka && ./mvnw -Pintegration verify   # 真实服务集成测试
```

Flyway 会自动执行 V1–V4 迁移，dev profile 额外导入开发种子数据。复用旧数据库前请先阅读 [运行指南](docs/getting-started/running.md)。

### 目录结构

```text
src/main/java/dev/dahuangggg/ticketrush/
  controller/  service/  domain/  ai/  infrastructure/  security/  ...
src/main/resources/
  db/migration/   Flyway 迁移
  lua/            Redis Lua 脚本
src/test/         单元与集成测试
frontend/         Vue 3 前端
bench/            压测脚本
docker/           MySQL / Nginx 配置
docs/             架构、ADR、测试与中文教学文档
compose.yaml      本地依赖服务
```

### 文档

- [完整中文教学文档](docs/zh-CN/README.md)、[原中文说明](README.zh-CN.md)
- [文档地图](docs/README.md)、[领域术语](CONTEXT.md)、[架构概览](docs/architecture/overview.md)、[ADR](docs/adr/README.md)
- [AI 只读边界](docs/ai/read-only-boundary.md)、[测试与压测](docs/testing/testing-and-benchmarking.md)、[贡献规范](AGENTS.md)

---

## English

A teaching project for high-concurrency concert ticket sales. It uses Spring Boot, MySQL, Redis Lua, and Kafka to demonstrate linearization, idempotency, asynchronous order intake, compensation, and inventory conservation in a flash-sale system.

### Status

Early project, no longer actively maintained. Code and docs are kept for learning reference only; not production-ready.

### Features

- SMS-code login with JWT access tokens and Redis-backed refresh tokens
- Event and ticket SKU queries with Caffeine + Redis multi-level caching
- Rush: atomic Redis Lua inventory reservation, Redis Stream reservation journal, relay to Kafka
- Async order intake: idempotent Kafka consumer; order and message dedup commit in one MySQL transaction
- Simulated payment, cancel, and order timeout; Release Intent makes inventory release recoverable and idempotent
- Inventory reconciliation (available + held + active orders = configured inventory)
- Admin endpoints: stock initialization and Release Intent retry
- Rush reminders (`/api/reminders`)
- Read-only AI assistant (LangChain4j): can only query events, SKUs, and the current user's orders; no mutation tools
- Vue 3 frontend: event list, event detail / rush, my orders, AI chat
- Benchmark harness (`bench/`) and Prometheus metrics

### Tech stack

| Concern | Choice |
|---|---|
| Backend | Java 17, Spring Boot 4, MyBatis-Plus |
| Storage | MySQL 8.4, schema owned by Flyway |
| Cache / reservation | Redis 7.2, Lua, Redisson, Caffeine |
| Messaging | Kafka 3.7 |
| AI | LangChain4j (read-only tool allowlist) |
| Frontend | Vue 3, Vue Router, Vite, Axios |
| Build / deploy | Maven Wrapper, Docker Compose |

### Getting started

Prerequisites: JDK 17, Docker, Node.js 22.

```bash
# 1. Start dependencies: MySQL :13306 (root/123456), Redis :16379, Kafka :9092
docker compose up -d mysql redis kafka

# 2. Start the backend (http://127.0.0.1:8081); OPENAI_API_KEY is only needed for AI
export JWT_SECRET="$(openssl rand -base64 48)"
export OPENAI_API_KEY=your_key_if_ai_is_enabled
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Start the frontend (http://127.0.0.1:5173)
cd frontend && npm ci && npm run dev
```

Tests:

```bash
./mvnw test                                                  # fast lane, no external services
docker compose up -d mysql redis kafka && ./mvnw -Pintegration verify   # real-service integration lane
```

Flyway applies migrations V1–V4 automatically; the dev profile also loads a development seed. Read the [run guide](docs/getting-started/running.md) before reusing an old database.

### Directory structure

```text
src/main/java/dev/dahuangggg/ticketrush/
  controller/  service/  domain/  ai/  infrastructure/  security/  ...
src/main/resources/
  db/migration/   Flyway migrations
  lua/            Redis Lua scripts
src/test/         Unit and integration tests
frontend/         Vue 3 frontend
bench/            Benchmark scripts
docker/           MySQL / Nginx config
docs/             Architecture, ADRs, testing, Chinese teaching docs
compose.yaml      Local dependency services
```

### Documentation

- [Documentation map](docs/README.md), [Domain context](CONTEXT.md), [Architecture overview](docs/architecture/overview.md), [ADRs](docs/adr/README.md)
- [AI read-only boundary](docs/ai/read-only-boundary.md), [Testing and benchmarking](docs/testing/testing-and-benchmarking.md), [Contribution guide](AGENTS.md)
