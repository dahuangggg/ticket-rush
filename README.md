# ticket-rush

高并发演唱会抢票平台后端，基于 Redis Lua 原子扣库存 + Kafka 异步创单实现核心抢票链路。

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 4.0.x |
| Java | 17 |
| MySQL + MyBatis-Plus | 3.5.x |
| Redis + Lua | — |
| Kafka | — |
| JWT (jjwt) | 0.13.x |
| Caffeine | 本地缓存 L1 |

## 核心抢票链路

```
用户点击抢票
  → Redis Lua 原子校验（库存 + 一人一单）
  → 扣库存 / 记录用户
  → 发送 Kafka 消息
  → 立即返回 QUEUED
  → Kafka 消费者异步创建待支付订单
  → 用户支付 / 取消 / 超时自动取消
```

## 模块进度

| 模块 | 说明 | 状态 |
|------|------|------|
| 1 | 短信验证码登录 + JWT | ✅ |
| 2 | 演出活动列表 + 详情（多级缓存） | ✅ |
| 3 | 票档 SKU 查询 | ✅ |
| 4 | Redis 库存初始化 | ✅ |
| 5 | Lua 抢票校验 + Kafka 投递 | ✅ |
| 6 | Kafka 消费者异步创单（幂等） | ✅ |
| 7 | 模拟支付 / 用户取消 / 超时扫描 | ✅ |
| 8 | AI function calling | 🔲 |
| 9 | RAG 知识库 | 🔲 |

## 快速启动

**前置依赖**（通过 Docker Compose 一键启动）：

```bash
docker compose up -d
```

启动 MySQL、Redis、Kafka。

**运行服务**：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

默认端口 `8081`。

**初始化库存（示例 SKU 3001）**：

```bash
# 需要 admin 角色的 JWT
curl -X POST http://localhost:8081/api/admin/skus/3001/init-stock \
  -H "Authorization: Bearer $TOKEN"
```

**端到端抢票**：

```bash
# 1. 登录
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","code":"123456"}' | jq -r '.accessToken')

# 2. 抢票
curl -X POST http://localhost:8081/api/ticket-rush/requests \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"eventId":2001,"skuId":3001,"quantity":1}'
# → {"status":"QUEUED"}

# 3. 查询订单
curl http://localhost:8081/api/orders/me -H "Authorization: Bearer $TOKEN"
```

## 主要 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/sms-code` | 发送短信验证码 |
| POST | `/api/auth/login` | 登录，返回 JWT |
| GET  | `/api/events` | 演出列表（支持城市/关键词/日期筛选） |
| GET  | `/api/events/{id}/skus` | 票档列表 |
| POST | `/api/admin/skus/{id}/init-stock` | 初始化 Redis 库存（需 admin 角色） |
| POST | `/api/ticket-rush/requests` | 抢票（需登录） |
| GET  | `/api/orders/me` | 我的订单列表（需登录） |
| POST | `/api/orders/{id}/pay` | 模拟支付（需登录） |
| POST | `/api/orders/{id}/cancel` | 取消订单（需登录） |

## 压测

三种场景配置（需先启动 Docker 依赖）：

```bash
# 纯 MySQL，无缓存
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,bench-db

# MySQL + Redis
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,bench-redis

# MySQL + Redis + Caffeine 两级缓存
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,bench-full
```

详见 `bench/README.md`。

## 运行测试

```bash
./mvnw test
```

需要 Docker 中的 MySQL、Redis、Kafka 处于运行状态。
