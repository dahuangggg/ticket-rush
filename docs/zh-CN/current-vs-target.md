# 当前实现与目标架构

快照日期：2026-07-22。

这里区分“源码已实现”“本轮真实验证”和“仍是目标”。测试数值以[本轮验证报告](verification-report.md)为准。

| 主题 | 当前实现 | 仍需证明或建设 |
|---|---|---|
| Reservation | Lua 同时写库存、buyer、幂等映射、Reservation 和 Stream Journal；真实 Redis 集成已覆盖接受、幂等重试、重复用户、售罄、毒 Journal 与缺失 stock 时失败关闭 | Redis Cluster、重启恢复、并发与进程故障矩阵、生命周期 GC |
| Redis 到 Kafka | Relay 至少一次发布；稳定 `reservationId`；MySQL Ledger 可作为 Stream 丢失后的二次发布来源；SKU 注册表可重建；本轮 full async 已覆盖一次真实 Kafka 成功路径 | ack-loss、Relay 重启、多实例、Broker 故障、全 Redis 丢失前置窗口 |
| Order Intake | inbox、订单和 Ledger 状态在同一 MySQL 事务；真实 MySQL 集成已覆盖顺序重复投递、错误身份回滚、活动订单唯一性与终态后重试；本轮 full async 创建 1,000 个订单并通过最终 Ledger 门禁 | 并发消费者和 claim 后、事务提交前的进程故障注入 |
| Release | 订单终态和 Release Intent 同事务；真实 MySQL/Redis 集成已覆盖 intent 创建、重复释放、缺失 stock 时失败关闭与重试耗尽；失败 intent 可显式 requeue | Redis 成功后 Worker 死亡与真实人工 re-drive；每行 lease 与时间退避 |
| 超时订单 | keyset 扫描；每笔 `REQUIRES_NEW`；CAS 失败 no-op | 大量毒数据、多个实例与长时间堆积压测 |
| 库存恢复 | `NEW -> OPENING -> OPENED`；仅 stock key 丢失时检查 buyer 与未结 Intent 后恢复 | 完整 Redis state 重建、SKU pause/audit/resume、审计记录 |
| HTTP 幂等 | `Idempotency-Key` 必填；服务端摘要；202 返回稳定 Reservation；前端复用键并轮询 | 浏览器刷新后的持久化策略与客户端崩溃策略 |
| Schema | Flyway V1–V4 是唯一事实；V4 带旧协议升级保护；本轮最终 gate 在独立空库真实执行 V1–V4 与 repeatable demo seed，history 全部成功并生成 12 张 base table；`TicketRushApplicationTests` 的 V4 bean/version 断言已纳入 Failsafe | 旧 `ticket_rush` 库没有被声明通过；V3 到 V4 guard 与 MySQL DDL 中途失败恢复仍需演练 |
| AI | 只注册活动搜索、活动详情、票档和当前用户订单查询工具；提醒写入走普通认证 API | 真实模型对抗性 Prompt 测试；未来 RAG 仍须保持只读 |
| Security | 验证码原子消费与失败次数限制；refreshToken 只存摘要且固定 TTL；Admin 403；异步上下文清理 | TLS、外部限流、密钥轮换、多实例滥用测试 |
| Observability | readiness、Prometheus、低基数 Rush/Kafka/Order/Release/Cache 指标、只读库存检查；最终 full async 已抓取真实指标并验证 Kafka lag 为 0 | 完整 Dashboard、告警阈值、Broker outage 与长时间运行演练 |
| Benchmark | `bash bench/run.sh all` 本轮退出 0；MySQL、Lua、full async 三个边界均保存结果并通过各自门禁 | 多轮重复方差、不同硬件和生产拓扑容量结论 |

## 不能从实现直接推导的结论

- 有测试类不等于该测试本轮执行过。
- Compose 服务健康不等于 Kafka 端到端已经验证。
- HTTP 202 QPS 不等于 MySQL 订单提交吞吐。
- 单机 Redis Lua 正确不等于 Redis Cluster 的多 Key 脚本可执行。
- `balanced=true` 只表示当前计数解释一致，不表示丢失的全部 Redis Reservation Hash 都可重建。
- Redis 开启 AOF `everysec` 不等于没有最多约一秒的持久化窗口。

## 目标完成门槛

1. 空 MySQL 数据库已真实执行 V1–V4，且 V4 bean/version 回归已纳入测试 gate；V3 升级 guard 仍需经过正反例。
2. 真实 Redis 成功路径与 Lua 压测已执行；仍需 Redis Cluster、复制故障和完整灾备证据。
3. 真实 Kafka 成功路径已由 full async 覆盖；仍需 ack 丢失、重复投递、DLT 和进程退出恢复。
4. 每个一致性故障点恢复后库存守恒。
5. 压测在最终 Ledger 审计通过后才输出性能结论。
6. 文档记录精确命令、版本、退出码、测试数和结果目录。
