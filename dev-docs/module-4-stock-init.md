# 模块四：Redis 库存初始化

## 模块目标

模块四是高并发抢票流程的前置准备步骤，负责将 MySQL 中的账面库存同步到 Redis，建立 `ticket:stock:{skuId}` 实时计数器，供模块五 Lua 脚本做原子扣减。

核心设计决策有两个：

1. **库存分层**：`tb_ticket_sku.stock` 是账面初始库存，只在模块四初始化时读取一次；之后所有扣减操作都在 Redis 计数器上进行，MySQL stock 不再实时更新（由 Kafka 消费者在模块六异步回写最终状态）。
2. **SET NX 幂等**：使用 `setIfAbsent`（Redis SET NX）写入计数器，保证多次调用不会覆盖已在售中的计数器——开售后如果服务重启再次调用初始化，不会把已被扣减的库存重置回初始值。

同时，本模块将 `TicketSkuServiceImpl.toDTO()` 切换为优先读 Redis 实时库存，未初始化时回退 MySQL，使得票档查询接口在开售前后都能返回正确的库存值。

## 完成范围

| 能力 | 状态 | 说明 |
|------|------|------|
| Redis 库存计数器初始化 | 已完成 | SET NX，从 MySQL stock 写入，幂等 |
| 初始化幂等保障 | 已完成 | 已存在不覆盖，返回 `initialized=false` |
| SKU 不存在时 404 | 已完成 | `TicketSkuNotFoundException`，`GlobalExceptionHandler` 处理 |
| 票档接口切换 Redis 库存 | 已完成 | `toDTO()` 优先读 Redis，null 时回退 MySQL |
| 管理接口 `/api/admin/**` 放行 | 已完成 | `WebMvcConfig` 白名单，暂无 Admin 鉴权 |
| 集成测试 | 已完成 | `@SpringBootTest` + 真实 Redis + 真实 MySQL |
| 控制器测试 | 已完成 | `FakeStockInitService` + MockMvc |

## MVC 分层结构

```text
dev.dahuangggg.ticketrush
├── controller/
│   └── StockInitController          -- POST /api/admin/skus/{skuId}/init-stock
├── dto/sku/
│   └── StockInitResultDTO           -- record { boolean initialized }
├── service/
│   ├── StockInitService             -- 接口：initStock / getAvailableStock
│   └── impl/
│       └── StockInitServiceImpl     -- Redis SET NX + GET；依赖 TicketSkuMapper
└── service/impl/
    └── TicketSkuServiceImpl         -- toDTO() 切换为读 Redis 库存（本模块修改）
```

## 核心接口

| 接口 | 方法 | 是否需要登录 | 作用 |
|------|------|--------------|------|
| `/api/admin/skus/{skuId}/init-stock` | `POST` | 否（暂时放行） | 初始化指定票档的 Redis 库存计数器 |

响应示例：

**新建计数器（首次调用）：**

```http
POST http://127.0.0.1:8081/api/admin/skus/3001/init-stock
```

```json
{ "initialized": true }
```

**已存在，未覆盖（重复调用）：**

```json
{ "initialized": false }
```

**SKU 不存在：**

```json
{
  "code": "TICKET_SKU_NOT_FOUND",
  "message": "票档不存在：9999"
}
```

## Redis Key 设计

```text
ticket:stock:{skuId}    -- 字符串类型，值为十进制整数（如 "180"）
```

- 无 TTL：开售期间计数器持续有效，不需要过期。
- 格式与 `DECR` 兼容：模块五 Lua 脚本直接对此 key 执行 `DECR`，无需格式转换。
- 写入值来源：`tb_ticket_sku.stock`（账面库存），由 `TicketSkuMapper.selectById` 读取。

## 关键代码说明

### StockInitServiceImpl

**`initStock`** 的两个非显然点：

1. **SET NX 语义**：`setIfAbsent` 在 key 已存在时不写入，返回 `false`（或集群 pipeline 场景下返回 `null`）。用 `Boolean.TRUE.equals(set)` 做空安全判断，避免 NPE。
2. **直接依赖 `TicketSkuMapper`，而非 `TicketSkuService`**：`TicketSkuServiceImpl` 依赖 `StockInitService`，若 `StockInitServiceImpl` 反过来依赖 `TicketSkuService`，Spring 会报循环依赖。绕开方式是直接注入 Mapper，跳过 Service 层。

```java
// SET NX：key 不存在时才写入，防止抢票进行中被误覆盖
Boolean set = redisTemplate.opsForValue()
        .setIfAbsent(STOCK_KEY + skuId, String.valueOf(sku.getStock()));
// setIfAbsent 在集群 pipeline 场景下可能返回 null，用 TRUE.equals 做空安全判断
return Boolean.TRUE.equals(set);
```

**`getAvailableStock`** 返回 `null`（而非 `0`）表示计数器未初始化——`toDTO()` 用此区分"未初始化"和"库存为零"两种情况。

### TicketSkuServiceImpl.toDTO()

```java
// 优先返回 Redis 实时库存；计数器未初始化时回退到 MySQL 账面库存
Integer redisStock = stockInitService.getAvailableStock(sku.getId());
int stock = redisStock != null ? redisStock : sku.getStock();
```

开售前 Redis 计数器尚未初始化，`getAvailableStock` 返回 `null`，接口返回 MySQL 账面库存；初始化后返回 Redis 实时值，随抢票进度实时减少。

### WebMvcConfig

```java
"/api/admin/**"         // 管理接口，暂时放行（后续接入 Admin 鉴权）
```

`/api/admin/**` 被添加到 JWT 放行白名单。当前阶段无 Admin 权限体系，实际生产需在后续模块补充鉴权。

## 错误码

| HTTP 状态 | code | 触发场景 |
|-----------|------|----------|
| 404 | `TICKET_SKU_NOT_FOUND` | 票档不存在或已软删除 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端异常 |

## 验证方式

### 1. 运行集成测试（需要 Docker MySQL + Redis 启动）

```bash
./mvnw test -Dtest=StockInitServiceTest
```

覆盖：初始化新建返回 true、重复初始化返回 false、不存在 SKU 抛 404、读取实时库存、计数器未初始化返回 null。

### 2. 运行控制器测试

```bash
./mvnw test -Dtest=StockInitControllerTest
```

覆盖：`initialized=true`、`initialized=false`、404 错误响应。

### 3. 全量测试（确保无回归）

```bash
./mvnw test
```

### 4. 手动验证（依赖 Docker 服务）

```bash
# 初始化票档 3001（应返回 initialized=true）
curl -X POST http://127.0.0.1:8081/api/admin/skus/3001/init-stock

# 重复初始化（应返回 initialized=false）
curl -X POST http://127.0.0.1:8081/api/admin/skus/3001/init-stock

# 查票档库存，此时应返回 Redis 实时库存 180
curl http://127.0.0.1:8081/api/ticket-skus/3001

# 初始化不存在的 SKU（应返回 404）
curl -X POST http://127.0.0.1:8081/api/admin/skus/9999/init-stock
```

## 当前边界

- **管理接口无鉴权**：`/api/admin/**` 暂时完全放行，后续需在 Admin JWT 模块中补充鉴权。
- **无 TTL**：计数器永不过期；若需要下架活动并重置库存，需手动 DEL key。
- **无批量初始化**：每次只能初始化单个 SKU；批量初始化可在需要时封装为循环调用。
- **MySQL stock 不回写**：扣减只在 Redis 发生，MySQL 账面库存在整个售卖周期内不变，仅供初始化读取使用。
