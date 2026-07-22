# ticket-rush 中文教学文档

这组文档面向第一次阅读项目、准备面试讲解，以及需要实际启动和验证系统的开发者。

文档使用两种明确标签：

- **当前实现**：可以从当前源码、配置或本轮实际命令中找到证据。
- **目标架构**：已经记录方向，但仍缺实现或真实依赖验证，不能当成已完成能力。

本地且被 Git 忽略的 `dev-docs/` 中有 11 篇只读中文原稿，它们不会随 GitHub 仓库发布。本目录保留同名文档，但正文已经按当前源码重写；被替换的做法只留在明确标注为“历史方案（已废弃，不要照用）”的段落中。

## 推荐阅读顺序

### 第一次了解项目

1. [领域与当前架构](architecture-and-domain.md)
2. [抢票一致性链路](rush-consistency-flow.md)
3. [当前实现与目标架构](current-vs-target.md)
4. [本地运行、测试与压测](running-testing-and-benchmarking.md)
5. [本轮验证报告](verification-report.md)

### 按模块读源码

1. [用户登录与 JWT 鉴权](module-1-user-auth.md)
2. [活动列表、详情与缓存](module-2-event.md)
3. [票档查询](module-3-ticket-sku.md)
4. [Redis 库存初始化与恢复](module-4-stock-init.md)
5. [Redis Lua Reservation](module-5-lua-rush.md)
6. [Kafka Order Intake](module-6-order.md)
7. [支付、取消、超时与 Release Intent](module-7-payment.md)

### 面试复盘

- [开发流程复盘](development-process-record.md)
- [缓存模块面试话术](cache-interview-script.md)
- [秒杀链路面试话术](seckill-interview-script.md)
- [订单超时与支付并发面试话术](order-timeout-payment-interview-script.md)

## 先记住四条主线

1. Redis Lua 是 Reservation 的线性化点，同时写库存、买家集合、幂等映射、Reservation 和 Redis Stream Journal。
2. Relay 到 Kafka 是至少一次投递；稳定的 `reservationId` 贯穿 HTTP 重试、Relay 重试、Kafka 重投和订单查询。
3. Order Intake 在同一个 MySQL 事务中提交消息幂等、订单和 Reservation Ledger 状态。
4. 取消或超时在同一个 MySQL 事务中提交订单终态和 Release Intent；Redis 释放脚本可安全重放。

库存守恒是最重要的业务不变量：

```text
Configured Inventory
  = Available Inventory
  + 尚未交接给活跃订单的 held Reservations
  + active order quantity
```

同一个库存单位从 Reservation 交接给活跃订单后不能重复计数。

## 文档证据规则

- 源码存在只证明“已实现”，不自动证明真实 MySQL、Redis、Kafka 下可工作。
- `./mvnw test` 是快速测试通道，默认排除 `integration` 标签。
- `./mvnw -Pintegration verify` 才运行真实 MySQL/Redis 集成通道；该通道配置 Kafka，但关闭 Listener 和定时任务，不等价于 Kafka 端到端测试。
- `bench/run.sh` 的 `mysql`、`lua`、`async` 测量边界不同，QPS 不能直接横向比较。
- 只有本轮实际执行且保留了命令、退出码和结果目录的数据，才能写进[验证报告](verification-report.md)。

## 与英文架构文档的关系

中文文档用于具体教学，英文文档仍是当前架构和 ADR 的完整审阅入口：

- [领域词汇](../../CONTEXT.md)
- [架构总览](../architecture/overview.md)
- [当前实现与目标架构](../architecture/current-vs-target.md)
- [一致性案例](../architecture/consistency.md)
- [故障手册](../failures/failure-playbook.md)
- [ADR 索引](../adr/README.md)
