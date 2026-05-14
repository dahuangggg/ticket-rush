# 模块三：票档查询

## 模块目标

模块三完成票务平台的票档展示层，负责把演出活动下挂的各票档信息（价格、库存、售卖时间、每人限购）呈现给用户。

本模块是一个**薄 CRUD**，没有复杂的缓存逻辑。设计上刻意保持简单：票档数据量少、变更频率低，查询走 MySQL 即可满足需求。真正的高并发挑战来自模块四（Redis 库存初始化）和模块五（Lua 原子抢票），模块三为它们打好数据模型基础。

一个关键的设计决策是：**`stock` 字段语义的分层**。`tb_ticket_sku.stock` 是账面库存，记录的是初始发售数量；模块四会在 Redis 中维护一个独立的实时计数器 `ticket:stock:{skuId}`，用于高并发抢票时的原子扣减。两套数据职责不同，本模块接口返回的是 MySQL 库存，模块四上线后将切换为 Redis 实时库存（改动点已在 `TicketSkuServiceImpl.toDTO()` 中标注）。

## 完成范围

| 能力 | 状态 | 说明 |
|------|------|------|
| 按活动查询票档列表 | 已完成 | 返回指定活动下所有票档，按价格升序排列 |
| 按 ID 查询单个票档 | 已完成 | 不存在或已软删除时返回 404 |
| 404 错误处理 | 已完成 | `TICKET_SKU_NOT_FOUND`，由 `GlobalExceptionHandler` 统一处理 |
| 公开访问（无需登录） | 已完成 | 两个接口均在 `WebMvcConfig` 白名单中放行 |

## MVC 分层结构

```text
dev.dahuangggg.ticketrush
├── controller/
│   └── TicketSkuController        -- GET /api/events/{id}/skus, GET /api/ticket-skus/{id}
├── dto/sku/
│   └── TicketSkuDTO               -- 列表和详情共用同一个 record（字段已足够完整）
├── entity/
│   └── TicketSku                  -- 映射 tb_ticket_sku 表
├── mapper/
│   └── TicketSkuMapper            -- 继承 BaseMapper<TicketSku>
├── service/
│   ├── TicketSkuService           -- 接口，listByEvent / getSkuDetail
│   └── impl/
│       └── TicketSkuServiceImpl   -- 调用 Mapper 查询，实体转 DTO
└── exception/
    └── TicketSkuNotFoundException -- 票档不存在，映射到 404
```

## 核心接口

| 接口 | 方法 | 是否需要登录 | 作用 |
|------|------|--------------|------|
| `/api/events/{eventId}/skus` | `GET` | 否 | 查询活动下所有票档，按价格升序，无票档时返回空数组 |
| `/api/ticket-skus/{skuId}` | `GET` | 否 | 查询单个票档详情，不存在时返回 404 |

两个接口均不需要 JWT 鉴权：
- `/api/events/{eventId}/skus` 被 `WebMvcConfig` 中已有的 `/api/events/**` 白名单覆盖
- `/api/ticket-skus/**` 作为新增白名单单独放行

## 请求示例

**查询活动 2001 下的所有票档：**

```http
GET http://127.0.0.1:8081/api/events/2001/skus
```

响应（数组，按 price 升序）：

```json
[
  {
    "id": 3001,
    "eventId": 2001,
    "name": "看台票 380",
    "price": 38000,
    "stock": 180,
    "saleStartTime": "2026-05-20T12:00:00",
    "saleEndTime": "2026-07-18T18:00:00",
    "limitPerUser": 1,
    "status": 1
  },
  {
    "id": 3002,
    "eventId": 2001,
    "name": "看台票 580",
    "price": 58000,
    "stock": 120,
    "saleStartTime": "2026-05-20T12:00:00",
    "saleEndTime": "2026-07-18T18:00:00",
    "limitPerUser": 1,
    "status": 1
  }
]
```

**活动无票档或活动不存在时（返回空数组，不是 404）：**

```http
GET http://127.0.0.1:8081/api/events/9999/skus
```

响应：

```json
[]
```

**查询单个票档：**

```http
GET http://127.0.0.1:8081/api/ticket-skus/3001
```

响应：

```json
{
  "id": 3001,
  "eventId": 2001,
  "name": "看台票 380",
  "price": 38000,
  "stock": 180,
  "saleStartTime": "2026-05-20T12:00:00",
  "saleEndTime": "2026-07-18T18:00:00",
  "limitPerUser": 1,
  "status": 1
}
```

**票档不存在时：**

```http
GET http://127.0.0.1:8081/api/ticket-skus/9999
```

响应 `404`：

```json
{
  "code": "TICKET_SKU_NOT_FOUND",
  "message": "票档不存在：9999"
}
```

## 数据模型

### MySQL：tb_ticket_sku

```sql
tb_ticket_sku
- id               BIGINT       -- MyBatis-Plus 雪花 ID
- event_id         BIGINT       -- 所属活动 ID，对应 tb_event.id
- name             VARCHAR(64)  -- 票档名称，如"看台票 380"、"内场票 VIP"
- price            BIGINT       -- 票价，单位分（e.g. 38000 = ¥380）
- stock            INT          -- 账面库存；模块四会另行维护 Redis 实时计数器
- sale_start_time  DATETIME     -- 开售时间
- sale_end_time    DATETIME     -- 截止购票时间
- limit_per_user   INT          -- 每人限购数量（一人一单场景设为 1）
- status           TINYINT(1)   -- 0 未开售，1 售卖中，2 售罄；售罄状态由模块五 Kafka 消费者异步回写
- deleted          TINYINT(1)   -- 逻辑删除，MyBatis-Plus @TableLogic 自动过滤
- create_time      DATETIME     -- 创建时间，插入时自动填充
- update_time      DATETIME     -- 更新时间，插入和更新时自动填充

索引：
- idx_ticket_sku_event(event_id)                                  -- 按活动查票档
- idx_ticket_sku_status_sale_time(status, sale_start_time, ...)   -- 售卖状态 + 时间范围筛选
```

### 关于 price 字段类型

AGENTS.md 的表结构定义中 `price` 标注为 `INT`，但 `001_schema.sql` 实际建表使用 `BIGINT`（遵循"金额用整数"的规范）。Java 实体中对应 `Long`。

### status 字段的存/算决策

`status` 采用**存储**而非实时计算方式：

- `0 未开售` / `2 售罄` 无法仅从时间推断
- `status=1 售卖中` 理论上可由 `sale_start_time ≤ now ≤ sale_end_time` 计算，但存储避免每次都做时间比较
- `status=2 售罄` 由模块五 Kafka 消费者在库存归零时异步回写，存在短暂延迟

接口同时返回 `saleStartTime` / `saleEndTime`，前端可据此在客户端做精确的倒计时展示，无需额外查询。

## 关键代码说明

### TicketSkuController

两个 URL 前缀不同（`/api/events/...` 和 `/api/ticket-skus/...`），无法共用类级别的 `@RequestMapping`，因此使用完整路径挂在 `@GetMapping` 上：

```java
@RestController
public class TicketSkuController {

    @GetMapping("/api/events/{eventId}/skus")
    public List<TicketSkuDTO> listByEvent(@PathVariable Long eventId) { ... }

    @GetMapping("/api/ticket-skus/{skuId}")
    public TicketSkuDTO getSkuDetail(@PathVariable Long skuId) { ... }
}
```

### TicketSkuServiceImpl

`listByEvent` 使用 `LambdaQueryWrapper` 按 `eventId` 过滤并 `orderByAsc(price)`，排序在数据库层完成；`@TableLogic` 自动附加 `AND deleted = 0`，无需手动过滤。

`getSkuDetail` 调用 `selectById`，返回 null 时抛 `TicketSkuNotFoundException`，`GlobalExceptionHandler` 将其转为 404。

`toDTO()` 是模块四的切换点：当 Redis 实时库存上线后，只需在此处把 `sku.getStock()` 替换为从 Redis 读取实时计数器，其他代码不需要改动。

## 错误码

| HTTP 状态 | code | 触发场景 |
|-----------|------|----------|
| 404 | `TICKET_SKU_NOT_FOUND` | 票档不存在或已软删除 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端异常 |

## 验证方式

### 1. 运行集成测试

```bash
./mvnw test -Dtest=TicketSkuControllerTest
```

覆盖：按活动列出票档（2 条）、无票档活动返回空数组、按 ID 查票档、不存在返回 404。

### 2. 全量测试（确保无回归）

```bash
./mvnw test
```

### 3. 手动验证（依赖 Docker MySQL 中的种子数据）

```bash
# 查询活动 2001（周杰伦上海站）的所有票档（应返回 3 条，按价格升序）
curl "http://127.0.0.1:8081/api/events/2001/skus"

# 查询活动 9999（不存在）的票档（应返回 []，不是 404）
curl "http://127.0.0.1:8081/api/events/9999/skus"

# 查询单个票档
curl "http://127.0.0.1:8081/api/ticket-skus/3001"

# 查询不存在的票档（应返回 404 + TICKET_SKU_NOT_FOUND）
curl "http://127.0.0.1:8081/api/ticket-skus/9999"
```

种子数据（来自 `docker/mysql/init/002_seed_data.sql`）：

| id | event_id | name | price | stock | status |
|----|----------|------|-------|-------|--------|
| 3001 | 2001 | 看台票 380 | 38000 | 180 | 1 |
| 3002 | 2001 | 看台票 580 | 58000 | 120 | 1 |
| 3003 | 2001 | 内场票 1280 | 128000 | 60 | 1 |
| 3004 | 2002 | 看台票 480 | 48000 | 150 | 1 |
| 3005 | 2002 | 内场票 1080 | 108000 | 80 | 1 |
| 3006 | 2003 | 预售看台票 580 | 58000 | 200 | 0 |

## 当前边界

- **`stock` 返回 MySQL 账面库存**：模块四上线后应切换为 Redis 实时计数器，当前值在抢票开始后即失去参考意义。
- **`status=2 售罄` 存在延迟**：Redis 实时库存归零到 MySQL `status` 字段被 Kafka 消费者回写之间存在短暂不一致（秒级），前端可同时参考 `stock=0` 做本地判断。
- **不验证 `eventId` 是否存在**：`listByEvent` 不检查活动是否存在，无票档时直接返回空数组。如需严格校验，需额外查 `EventMapper`。
- **无缓存**：票档数据量小、变更频率低，当前不做缓存。若未来票档查询成为热点，可在模块四之后按需加 Caffeine 本地缓存。
- **无分页**：一场活动的票档数量通常在 10 个以内，不需要分页。
