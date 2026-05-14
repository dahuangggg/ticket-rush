# 压测：热点活动详情查询

对比三种缓存方案在高并发下的吞吐量和延迟。

## 前置准备

### 1. 安装 wrk

```bash
brew install wrk
```

### 2. 写入测试数据

向数据库插入一条热点活动（`is_hot=1`），记录下它的 `id`。

```sql
INSERT INTO tb_event (id, title, artist, city, venue, event_time, cover_url, description, is_hot, status, deleted, create_time, update_time)
VALUES (1000000000001, '周杰伦2026世界巡回演唱会', '周杰伦', '上海', '梅赛德斯奔驰文化中心',
        '2026-08-01 20:00:00', 'https://example.com/cover.jpg', '演出详情', 1, 1, 0, NOW(), NOW());
```

把 `bench/run.sh` 中的 `EVENT_ID` 改为实际插入的 ID。

### 3. 三个场景分别启动服务

每次切换场景都需要重启服务：

```bash
# 场景一：纯 MySQL
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,bench-db

# 场景二：MySQL + Redis
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,bench-redis

# 场景三：MySQL + Redis + Caffeine（默认）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,bench-full
```

### 4. 执行压测

```bash
# 单独跑某个场景（等服务启动后执行）
bash bench/run.sh db
bash bench/run.sh redis
bash bench/run.sh full

# 或者三个场景依次执行（需要在每次之间手动重启服务）
bash bench/run.sh all
```

## 参数说明

| 参数 | 值 | 含义 |
|------|----|------|
| `-t` | 4 | 4 个 wrk 工作线程（建议 = CPU 核数 / 2） |
| `-c` | 200 | 保持 200 个并发连接 |
| `-d` | 30s | 持续压测 30 秒 |
| `--latency` | — | 输出延迟百分位分布（P50 / P75 / P90 / P99） |

## 输出

```
Running 30s test @ http://127.0.0.1:8081/api/events/1000000000001
  4 threads and 200 connections

  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    1.23ms    0.85ms  15.23ms   88.12%
    Req/Sec   42.30k     3.12k   48.50k    72.00%

  Latency Distribution
     50%    0.98ms
     75%    1.45ms
     90%    2.13ms
     99%    4.82ms

  5,032,145 requests in 30.10s, 1.23GB read
Requests/sec: 167,181.20
Transfer/sec: 41.86MB
```

- **Latency Avg**：平均响应时间，越低越好
- **P99**：99% 的请求在这个时间内完成，反映尾延迟，是线上体验的关键指标
- **Requests/sec**：QPS，越高越好
- **Stdev**：标准差，越小说明延迟越稳定

## 实测结果（2026-05-14）

### 测试环境

| 机器 | Apple M3 Pro (11) @ 4.06 GHz |
|------|------|
| 项目 | 配置 |
| JVM | JDK 17，`-XX:TieredStopAtLevel=1`（IntelliJ 默认，未优化） |
| MySQL | Docker，映射到本机 13306 |
| Redis | Docker，映射到本机 16379 |
| wrk | 4 线程 / 200 并发连接 / 持续 30 秒 |
| 预热 | 正式测试前先跑 5 秒预热（JIT + 缓存填充） |
| 活动 | `is_hot=1`，热点活动（逻辑过期缓存路径） |

> **注意**：服务以 IntelliJ 默认 JVM flags（`-XX:TieredStopAtLevel=1`）启动，JIT 编译受限，QPS 比生产部署（`-server` + 完整 JIT）会低。关注各场景间的**相对倍数**，而非绝对值。

---

### wrk 原始输出

**场景三：MySQL + Redis + Caffeine（热点命中 Caffeine L1）**

```
Running 30s test @ http://127.0.0.1:8081/api/events/1000000000001
  4 threads and 200 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     6.83ms    8.71ms 135.16ms   93.68%
    Req/Sec     9.38k     2.41k   14.15k    70.67%
  Latency Distribution
     50%    4.81ms
     75%    6.18ms
     90%    9.32ms
     99%   46.21ms
  1120261 requests in 30.02s, 724.55MB read
Requests/sec:  37316.39
Transfer/sec:     24.14MB
```

**场景二：MySQL + Redis（热点命中 Redis L2，Caffeine 禁用）**

```
Running 30s test @ http://127.0.0.1:8082/api/events/1000000000001
  4 threads and 200 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    11.63ms   13.27ms 231.31ms   94.52%
    Req/Sec     5.13k     1.54k   13.72k    68.61%
  Latency Distribution
     50%    8.68ms
     75%   12.00ms
     90%   17.87ms
     99%   79.66ms
  611231 requests in 30.10s, 395.33MB read
Requests/sec:  20307.87
Transfer/sec:     13.13MB
```

**场景一：MySQL only（每次查 DB，缓存全部禁用）**

```
Running 30s test @ http://127.0.0.1:8082/api/events/1000000000001
  4 threads and 200 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    27.78ms   39.24ms 532.51ms   90.68%
    Req/Sec     2.75k     0.99k    7.52k    62.24%
  Latency Distribution
     50%   15.13ms
     75%   32.37ms
     90%   63.86ms
     99%  196.16ms
  325507 requests in 30.07s, 210.53MB read
Requests/sec:  10825.44
Transfer/sec:      7.00MB
```

---

### 核心指标对比

| 场景 | QPS | P50 | P90 | P99 | 相对 MySQL |
|------|----:|----:|----:|----:|------------|
| MySQL only | 10,825 | 15.13ms | 63.86ms | 196.16ms | 基准 1× |
| MySQL + Redis | 20,307 | 8.68ms | 17.87ms | 79.66ms | **1.9×** |
| MySQL + Redis + Caffeine | 37,316 | 4.81ms | 9.32ms | 46.21ms | **3.4×** |

---

### 性能分析

#### 场景一：MySQL only — QPS 10,825 / P99 196ms

每次请求都穿透到数据库，MySQL 连接池（`maximum-pool-size=20`）成为第一瓶颈。在 200 并发下，大量请求排队等待数据库连接，导致 P99 高达 196ms，且 Stdev（39ms）远大于均值（27ms），说明响应时间极不稳定。

这是开票瞬间不加缓存的真实写照：数百万用户同时刷新活动页，数据库连接被打满，响应时间急剧恶化。

#### 场景二：MySQL + Redis — QPS 20,307 / P99 79ms

加上 Redis 后 QPS 提升 1.9 倍，P99 从 196ms 降至 79ms。热点活动走逻辑过期缓存路径，读的是 Redis 的 `event:detail:hot:{id}` key，不查数据库。

瓶颈转移到网络 I/O：每次请求需要一次本机 Docker→Redis 的 TCP 往返（RTT 约 0.3ms），加上 Lettuce 连接池和序列化/反序列化，平均延迟 11.63ms。P99 为 79ms，说明在高并发下 Redis 连接池偶尔也会产生排队。

Stdev（13ms）明显大于均值，延迟分布仍不够稳定。

#### 场景三：MySQL + Redis + Caffeine — QPS 37,316 / P99 46ms

两级缓存下 QPS 达到 3.4 倍于纯 MySQL，P99 降至 46ms。热点活动命中 Caffeine（JVM 本地内存），完全绕过网络，读操作在纳秒到微秒级完成。

Stdev（8.71ms）接近均值（6.83ms），分布相对 Redis 场景更稳定，但仍有毛刺（Max 135ms）。这来自 JIT 未完全启动（`-XX:TieredStopAtLevel=1`）和 GC 停顿。

**实测结论：Caffeine 命中时的吞吐提升来自消除网络往返，而非 Redis 慢。** Redis 本身很快（P50 仅 8ms），但 Docker 网络 RTT + Lettuce 连接池 + 反序列化的累积，在 200 并发下成为瓶颈。

---

### 关键观察

**P99 的意义**

P99 反映的是尾延迟，也是用户实际感知"卡顿"的场景。三个场景的 P99 分别是 196ms / 79ms / 46ms。在开票场景下，P99 196ms 意味着每 100 个用户中就有 1 个等待将近 0.2 秒，这在高频点击场景中是肉眼可感的卡顿。

**Stdev 比 Avg 更能说明稳定性**

MySQL only 的 Stdev（39ms）= 均值（27ms）的 1.4 倍，说明响应时间忽快忽慢。Caffeine 场景的 Stdev（8.71ms）≈ 均值（6.83ms）的 1.3 倍，相对更稳，但还有优化空间。

**当前数据的局限**

- JVM 以 `TieredStopAtLevel=1` 运行（IntelliJ debug/run 默认），C2 JIT 编译器未启动，吞吐量大约只有 `-server` 模式的 60~70%。
- 数据库和 Redis 都在 Docker 内，有额外的虚拟网络开销。生产环境两者都在同一内网时 RTT 会更低。
- 单实例测试，未模拟 HikariCP 连接耗尽、Redis 连接满载等极端情况。

**生产部署估算（`-server` JVM + 容器化部署）**

| 场景 | 预估 QPS |
|------|---------|
| MySQL only | ~15,000 |
| MySQL + Redis | ~30,000 |
| MySQL + Redis + Caffeine | ~60,000+ |
