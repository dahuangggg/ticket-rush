# 本轮验证报告

快照日期：2026-07-22。

本页只记录本轮实际执行证据。本地、被 Git 忽略的历史 `dev-docs` 中的 QPS 不作为本轮结果。命令失败、修复后复测、未提供脚本的检查，都单独说明。

## 结论摘要

- 三个 Compose 依赖服务健康；后端 106 个快速测试全部通过。
- 第一次真实服务测试失败并暴露 Flyway 依赖缺口；修复测试匹配规则后，在独立空数据库执行最终 gate，106 个 Surefire 测试和 27 个 Failsafe 集成测试全部通过，真实执行 V1–V4 与 repeatable seed。
- 前端依赖安装、漏洞审计和生产构建通过；项目没有 `test`、`lint`、`typecheck` 脚本，因此没有声称这些检查通过。
- `bash bench/run.sh all` 最终退出 0，同一 run ID 下的 MySQL、Redis Lua 和 full async 三个场景均通过各自正确性门禁。

这些结果是本机单次观测，不是生产容量承诺。

## 环境

| 项目 | 本轮值 |
|---|---|
| 容器运行时 | OrbStack，Docker daemon 可用 |
| Java / Maven | Java 17 / Maven 3.9.15 |
| MySQL | 8.4.9，宿主机端口 13306 |
| Redis | 7.2.14，宿主机端口 16379 |
| Kafka | Apache Kafka image 3.7.1，宿主机端口 9092 |
| 前端 | Node 26.5.0 / npm 11.17.0 |
| Git 状态 | 本轮在有未提交改动的工作区执行；结果只对应该工作区快照 |

## 服务启动与健康

实际命令：

```bash
BUSINESS_ZONE=Asia/Shanghai docker compose up -d --wait mysql redis kafka
```

结果：退出 0，`mysql`、`redis`、`kafka` 三个容器均为 `healthy`。镜像和端口见上表。

## 后端测试

### 快速通道

```bash
./mvnw -B test
```

结果：

```text
Tests run: 106
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Total time: 7.729 s
```

该通道排除了 `integration` 标签。

### 真实服务通道：首次失败

首次执行：

```bash
./mvnw -B -Pintegration verify
```

该次连接已有的 `ticket_rush` 数据库，结果为 26 个 error。根因不是可以忽略的环境跳过：Spring Boot 4 下仅有 Flyway core 依赖不会启用预期的自动配置，项目缺少 `spring-boot-starter-flyway`，导致测试上下文未完成数据库迁移。

处理：加入 Spring Boot Flyway starter，并补充 Flyway V4 集成回归。随后还发现 Failsafe 原先只匹配单数 `*Test`，没有执行复数类名 `TicketRushApplicationTests`；最终增加 `**/*Tests.java` 匹配规则后重新执行完整 gate。这个失败运行不能证明旧 `ticket_rush` 数据库已经通过迁移，也没有把旧库标记为通过。

### 真实服务通道：独立空库复测

```bash
MYSQL_DATABASE=ticket_rush_it_gate_20260722_1145 ./mvnw -B -Pintegration clean verify
```

结果：

```text
Surefire: 31 reports, 106 / 106 passed
Failsafe: 5 reports, 27 / 27 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Total time: 17.774 s
```

Failsafe 的 5 份报告包括 `TicketRushApplicationTests` 和 4 个真实服务测试类；前者包含 Flyway V4 bean/version 断言。独立数据库的 Flyway history 精确记录 V1、V2、V3、V4 和 repeatable demo seed，全部 `success=1`，并生成 12 张 base table。该证据证明空库迁移和当前真实 MySQL/Redis 集成测试通过；integration profile 仍关闭 Kafka Listener 与 scheduled Worker，所以它不是 Kafka 端到端证明。

## 前端

在 `frontend/` 下实际执行：

| 命令 | 结果 |
|---|---|
| `npm ci` | 成功安装 65 个 package；0 vulnerabilities |
| `npm audit --audit-level=high` | 0 vulnerabilities，退出 0 |
| `npm run build` | Vite 7.3.6；91 modules；393 ms；`dist` 约 168K |

当前 `package.json` 没有 `test`、`lint` 或 `typecheck` 脚本，这三类检查未运行。构建成功不能代替浏览器交互 E2E。

## 压测总览

最终执行：

```bash
bash bench/run.sh all
```

结果：退出 0。最终 run ID：

```text
1784692033_4123146903ca9a80
```

三个场景测量边界不同，下面的吞吐值不能当成同一接口的横向优化对比。
下列 `bench/results/...` 是本机原始证据路径；该目录被 Git 忽略，不随 GitHub 仓库发布。
仓库保留的是本页汇总，其他机器可运行 harness 生成自己的原始 bundle。

### MySQL 活动详情基线

结果目录：

```text
bench/results/20260722T034714Z-1784692033_4123146903ca9a80-mysql-baseline
```

| 指标 | 结果 |
|---|---:|
| 请求数 | 690,221 |
| 持续时间 | 30.06 s |
| 吞吐 | 22,959.58 req/s |
| P50 | 8.42 ms |
| P75 | 10.23 ms |
| P90 | 17.48 ms |
| P99 | 94.20 ms |
| errors | 0 |

这个场景测量关闭 Redis/Caffeine 后的活动详情 HTTP 路径，不是 Reservation 吞吐。

### Redis Lua 原语

结果目录：

```text
bench/results/20260722T034756Z-1784692033_4123146903ca9a80-redis-lua
```

| 指标 | 结果 |
|---|---:|
| 请求数 | 100,000 |
| 吞吐 | 132,450.33 req/s |
| 平均延迟 | 1.352 ms |
| 最小延迟 | 0.240 ms |
| P50 | 1.319 ms |
| P95 | 2.039 ms |
| P99 | 2.655 ms |
| 最大延迟 | 4.615 ms |

正确性门禁：

```text
finalStock = 0
uniqueReservations = 100000
inventoryConserved = true
```

`uniqueReservations` 是 harness 已保存的历史字段名；这个 Redis-only 脚本实际只执行库存扣减
与 synthetic unique buyer 去重，不创建生产 Reservation Hash 或 Stream Journal。

这个场景只测 Redis Lua，不包含 HTTP、JWT、Relay、Kafka 或 MySQL Order Intake。

### Full async

结果目录：

```text
bench/results/20260722T034757Z-1784692033_4123146903ca9a80-full-async
```

| 指标 | 结果 |
|---|---:|
| requested / executed / HTTP responses | 1,000 / 1,000 / 1,000 |
| accepted / accounted | 1,000 / 1,000 |
| dropped / rejected | 0 / 0 |
| responses conserved | `true` |
| HTTP 202 接受吞吐 | 2,792.3679 req/s |
| k6 HTTP burst duration | 358.119 ms |
| 平均延迟 | 34.9328 ms |
| P50 | 14.8895 ms |
| P90 | 111.5998 ms |
| P95 | 171.20725 ms |
| P99 | 205.514 ms |
| 最大延迟 | 207.358 ms |

最终正确性门禁：

```text
configured = 1000
orders = 1000
buyers = 1000
durableHeld = 0
activeOrderQuantity = 1000
finalStock = 0
expectedAvailable = 0
kafkaLag = 0
responsesConserved = true
```

这个场景覆盖真实 HTTP/JWT、Redis Reservation、Relay、Kafka 和 MySQL Order Intake。上表的
吞吐与延迟来自 k6 的 HTTP 202 接受阶段；请求结束后 harness 继续等待 1,000 笔订单提交并执行
最终守恒门禁，因此 2,792.3679 req/s 不是订单提交吞吐。它仍不模拟 producer ack 丢失或进程强杀。

### 压测收尾状态

最终 `all` 退出后再次检查：Redis DB 0 与 DB 15 的 key 数均为 0；Kafka 只剩内部 topic `__consumer_offsets`，没有 consumer group；压测锁已释放，端口 18081 空闲；MySQL、Redis、Kafka 容器仍为 `healthy`。这说明结果不是依赖残留业务数据得到的，且 harness 没有留下应用进程或锁。

## 2026-09-02 缓存 A/B 补充验证

新增 `bash bench/run.sh cache`，使用同一份 k6 脚本依次验证热点活动详情的纯 MySQL、
MySQL + Redis、MySQL + Redis + Caffeine 三条读取路径。最终绿色 run ID 为
`1788334710_abd5af944e0a2596`，命令使用 100 VUs、5 秒预热和 30 秒正式测试；每次只运行
一个 Spring Boot 实例。由于宿主机默认端口被另一项目占用，本轮使用 MySQL `23306` 与
Redis `26379`，容器内部拓扑未变。Kafka Listener 和 Topic 自动创建已关闭，不属于测量边界。

| 场景 | 请求数 | QPS | P99 | HTTP 错误 |
|---|---:|---:|---:|---:|
| 纯 MySQL | 472,741 | 15,756.45 | 30.42 ms | 0 |
| MySQL + Redis | 579,803 | 19,325.01 | 14.50 ms | 0 |
| MySQL + Redis + Caffeine | 727,165 | 24,236.35 | 13.84 ms | 0 |

本轮 Caffeine + Redis 相对纯 MySQL 的吞吐为约 `1.54x`，P99 降低约 `54.5%`。这是同机
单轮观测，不是生产容量结论；当前实现也没有暴露 Caffeine 命中计数，因此不能报告精确缓存
命中率。原始 bundle 位于本机 Git 忽略目录 `bench/results/`，三个结果目录后缀分别为
`cache-mysql`、`cache-redis` 和 `cache-caffeine`。

随后发现活动详情在 Caffeine 前先执行 Redis 空值检查，使 L1 命中仍承担 Redis RTT。增加
`EventServiceLocalCacheTest` 复现后，将本地正缓存移到 Bloom Filter 和空值缓存之前，并用
相同参数复测。修复后绿色 run ID 为 `1788335860_8f63bd1ab1226cc3`：

| 场景 | 请求数 | QPS | 平均延迟 | P99 | HTTP 错误 |
|---|---:|---:|---:|---:|---:|
| 纯 MySQL | 471,601 | 15,718.02 | 5.54 ms | 33.15 ms | 0 |
| MySQL + Redis | 685,151 | 22,828.83 | 4.09 ms | 14.04 ms | 0 |
| MySQL + Redis + Caffeine | 808,252 | 26,940.00 | 3.11 ms | 18.96 ms | 0 |

修复后 Caffeine 场景相对修复前单轮吞吐提高约 `11.2%`，平均延迟下降约 `16.8%`；相对同轮
纯 MySQL 吞吐约为 `1.71x`。但 Redis 对照组在两轮之间也有明显波动，而且修复后单轮 P99
高于修复前，因此不能把全部差值或尾延迟变化归因于本次修改。

为减少单轮和执行顺序偏差，又执行 5 轮交错复测。run ID 为
`1788336729_0ac5706105ae76f0`；每轮仍为 100 VUs、5 秒预热和 30 秒正式测试，顺序按
`MySQL → Redis → Caffeine`、`Redis → Caffeine → MySQL`、
`Caffeine → MySQL → Redis` 循环。每个场景启动前清理本轮独占 Redis 库中的事件缓存，
再独立预热；代表值采用 5 轮中位数，不选择最好的一轮。

| 场景 | QPS 中位数 | QPS 范围 | QPS CV | P99 中位数 | P99 范围 | P99 CV | 总错误 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 纯 MySQL | 14,671.33 | 14,356.07～15,986.53 | 5.18% | 35.71 ms | 33.10～38.19 ms | 5.61% | 0 |
| MySQL + Redis | 22,009.46 | 21,127.03～23,530.50 | 4.05% | 14.16 ms | 13.06～16.17 ms | 8.24% | 0 |
| MySQL + Redis + Caffeine | 38,785.62 | 38,003.34～42,214.95 | 4.68% | 11.03 ms | 9.99～11.36 ms | 5.52% | 0 |

以中位数比较，Caffeine 场景相对纯 MySQL 吞吐约为 `2.64x`，P99 下降约 `69.1%`。
各组 QPS CV 为 `4.05%～5.18%`，证明本机仍存在可见轮次波动；但 Caffeine 的 5 轮 P99
集中在 `9.99～11.36 ms`，此前 `18.96 ms` 属于本组条件下未复现的单轮高值，不能作为
代表值。Prometheus 快照也没有提供足够的时序证据把该尖峰唯一归因于 GC、JIT 或调度中的
某一项，因此这里只把它判断为本机同机压测噪声与运行状态共同造成的离群观测。

本轮 15 个正式测量共处理 11,549,891 个请求，HTTP 错误为 0。结束后确认临时 MySQL
数据库为 0 个、Redis DB 15 为空、压测锁已释放且端口 18081 无监听。原始汇总位于本机
Git 忽略目录的 `...-1788336729_0ac5706105ae76f0-cache-summary/cache-aggregate.json`。

## 压测过程中发现并修复的问题

最终绿色 run 之前，压测实际暴露了六个 harness 缺陷：

| 缺陷 | 影响 | 修复方向 |
|---|---|---|
| Lua 文本换行被压平，`--` 注释吞掉后续脚本 | Lua 场景执行内容不完整 | 保留原始换行，通过本轮专属脚本文件执行 |
| Maven 子进程没有被完整停止 | 后续场景残留应用与端口占用 | 跟踪并清理实际子进程生命周期 |
| 18 位测试 ID 拼接成手机号超过 `VARCHAR(20)` | full async fixture 插入失败 | 使用长度受控的合成手机号 |
| JavaScript `Number` 舍入 18 位 ID | HTTP、Redis、MySQL 使用了不同身份 | 将大整数 ID 作为字符串传递，避免 IEEE-754 精度损失 |
| k6 默认中位数字段名是 `med`，脚本却读取 `p(50)` | P50 被错误报告为 `null` | 显式设置 `summaryTrendStats`，并统一输出 avg、P50、P90、P95、P99 和 max |
| 多轮缓存测试固定执行顺序且沿用上一场景的事件缓存 | 顺序和缓存状态可能污染后续结果 | 三种顺序循环轮换，每场景清理事件缓存后独立预热，并汇总中位数、范围和 CV |

另外加固了 Kafka consumer group 查询重试，以及仅清理由本轮独占的 Redis outbox SKU registry 数据。调试失败目录不是性能结论；上文只采用最终退出 0 的同一 run ID。

## 非阻塞警告与兼容性边界

以下信息没有让本轮命令失败，也不改变上文的通过结论，但后续升级时需要跟踪：

- Flyway 11.14.1 连接 MySQL 8.4.9 时提示数据库版本比其已测试范围更新；警告中给出的最高已验证版本是 MySQL 8.1。本轮迁移与回归实际通过，但这不是 Flyway 对该版本组合的官方兼容性保证。
- repeatable demo seed 的 `ON DUPLICATE KEY UPDATE` 使用了 MySQL 已标记弃用的 `VALUES()` 写法；当前仍能执行，后续应迁移到行别名写法。
- `npm ci` 输出了依赖脚本 `allow-scripts` 提示；本轮 `npm audit --audit-level=high` 为 0 vulnerabilities，Vite 生产构建也成功。该提示不是漏洞扫描失败。

## 静态检查

以下检查均实际通过：

- `bash -n bench/run.sh`；
- `shellcheck bench/run.sh`；
- `docker compose config --quiet`；
- 7 个 YAML 文件解析；
- `python3 -m py_compile bench/generate_fixtures.py bench/parse_wrk.py`；
- `git diff --check`；
- 发布前全仓文档复核（排除 `dev-docs/`）：40 个 Markdown 文件、160 个 fenced code block（320 条围栏标记）、51 个 Mermaid block、230 个 Markdown 链接；没有 `~~~`、不平衡围栏、断链或无效锚点；
- 51 个 Mermaid block 使用 Mermaid CLI 11.16.0 与本机 Chrome 实际渲染，51 / 51 成功。

## 仍需单独验证的能力

即使本轮上述命令通过，以下能力也不能自动宣称完成：

- Redis Cluster 的 CROSSSLOT、复制和 failover；
- Kafka Broker 已接收但 producer ack 丢失；
- Relay 或 Release Worker 在指定持久化点被强杀后的恢复；
- 完整 V3 到 V4 升级 guard 和 MySQL DDL 中途失败恢复演练；
- 完整 Redis 丢失后的 buyer/Reservation 全状态重建；
- 多实例下的限流、滥用和长时间堆积；
- 真实支付回调、短信供应商和生产级安全；
- 真实模型 Prompt Injection 对抗测试。
