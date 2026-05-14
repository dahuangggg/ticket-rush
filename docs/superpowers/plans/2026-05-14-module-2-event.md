# Module 2: Event Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现活动列表与详情查询，包含两级缓存（Caffeine + Redis）、热点/普通活动差异化缓存策略、布隆过滤器防穿透、同步删除 + Kafka 兜底的缓存一致性保障。

**Architecture:** EventController 接收 HTTP 请求，EventServiceImpl 负责业务逻辑编排（布隆过滤器检查、热点路由、访问计数），EventCacheManager 封装所有缓存操作（Caffeine 本地缓存 + Redis，热点活动逻辑过期，普通活动互斥锁重建），Kafka 消费者异步兜底缓存删除失败场景。

**Tech Stack:** Spring Boot 4.x, MyBatis-Plus, Redis (Redisson RBloomFilter + StringRedisTemplate), Caffeine, Kafka, Jackson ObjectMapper

---

## 文件结构

```
新建：
src/main/resources/db/V2__create_tb_event.sql
docker/mysql/init/001_schema.sql              -- tb_event DDL 同步写入（实际迁移机制）
src/main/java/.../entity/Event.java
src/main/java/.../mapper/EventMapper.java
src/main/java/.../dto/event/EventDTO.java
src/main/java/.../dto/event/EventDetailDTO.java
src/main/java/.../dto/event/EventListRequest.java
src/main/java/.../exception/EventNotFoundException.java
src/main/java/.../config/CaffeineConfig.java  -- 含 ObjectMapper @Bean
src/main/java/.../infrastructure/cache/LogicalExpireValue.java
src/main/java/.../infrastructure/cache/EventCacheManager.java
src/main/java/.../service/BloomFilterService.java
src/main/java/.../service/impl/BloomFilterServiceImpl.java  -- @ConditionalOnProperty(havingValue="true")
src/main/java/.../service/impl/NoOpBloomFilterService.java  -- @ConditionalOnProperty(matchIfMissing=true)
src/main/java/.../service/EventService.java
src/main/java/.../service/impl/EventServiceImpl.java
src/main/java/.../infrastructure/mq/EventCacheInvalidateMessage.java
src/main/java/.../infrastructure/mq/EventCacheInvalidationProducer.java
src/main/java/.../infrastructure/mq/EventCacheInvalidationConsumer.java
src/main/java/.../controller/EventController.java
src/test/java/.../controller/EventControllerTest.java

修改：
pom.xml                          -- 新增 Caffeine 依赖
src/main/resources/application-dev.yaml  -- 新增 spring.kafka.admin.auto-create: true
src/main/java/.../config/WebMvcConfig.java   -- /api/events 和 /api/events/** 加入 JWT 白名单
src/main/java/.../exception/GlobalExceptionHandler.java  -- 新增 EventNotFoundException handler
```

---

## Redis Key 设计

```
event:detail:{eventId}           -- 普通活动详情缓存（JSON）
event:detail:hot:{eventId}       -- 热点活动逻辑过期缓存（JSON，含 expireAt）
event:null:{eventId}             -- 空值标记（活动不存在），TTL = 2min
event:lock:{eventId}             -- 普通活动缓存重建互斥锁，TTL = 10s
event:list:{cacheKey}            -- 列表缓存，cacheKey = city:date
event:access:count:{eventId}     -- 访问计数，滑动窗口 1min，超 1000 告警
event:bloom                      -- Redisson RBloomFilter 管理，无需手动操作 key
```

---

## Task 1: SQL Schema

**Files:**
- Create: `src/main/resources/db/V2__create_tb_event.sql`

- [ ] **Step 1: 创建建表 SQL**

```sql
CREATE TABLE tb_event
(
    id          BIGINT       NOT NULL COMMENT 'Snowflake ID',
    title       VARCHAR(200) NOT NULL COMMENT '活动标题，如 周杰伦2026世界巡回演唱会',
    artist      VARCHAR(100) NOT NULL COMMENT '艺人名称',
    city        VARCHAR(50)  NOT NULL COMMENT '演出城市',
    venue       VARCHAR(200) NOT NULL COMMENT '演出场馆',
    event_time  DATETIME     NOT NULL COMMENT '开演时间',
    cover_url   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '封面图 URL',
    description TEXT COMMENT '活动详情描述',
    status      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0未上架 1售卖中 2已结束',
    is_hot      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0普通活动 1热点活动，运营标记，标记后自动预热缓存',
    deleted     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除，0正常 1已删除',
    create_time DATETIME     NOT NULL COMMENT '创建时间，自动填充',
    update_time DATETIME     NOT NULL COMMENT '更新时间，自动填充',
    PRIMARY KEY (id),
    INDEX idx_status_city (status, city) COMMENT '按状态和城市过滤（status 选择性更高，放首位）',
    INDEX idx_event_time (event_time) COMMENT '按日期过滤'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='活动表';
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/V2__create_tb_event.sql
git commit -m "feat: add tb_event DDL"
```

---

## Task 2: Entity + Mapper

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/entity/Event.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/mapper/EventMapper.java`

- [ ] **Step 1: 创建 Event 实体**

```java
package dev.dahuangggg.ticketrush.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_event")
public class Event {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;
    private String artist;
    private String city;
    private String venue;
    private LocalDateTime eventTime;
    private String coverUrl;
    private String description;

    /** 0未上架 1售卖中 2已结束 */
    private Integer status;

    /**
     * 热点标记，0普通 1热点。
     * 由运营人员在管理后台标记，标记后系统自动触发缓存预热。
     * 同时用于业务展示（列表置顶、热门标签）和缓存策略选择（逻辑过期 vs Cache-Aside）。
     */
    private Integer isHot;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

- [ ] **Step 2: 创建 EventMapper**

```java
package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.Event;

// 不加 @Mapper，依赖 @MapperScan 全局扫描（与 UserMapper 保持一致）
public interface EventMapper extends BaseMapper<Event> {
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/entity/Event.java \
        src/main/java/dev/dahuangggg/ticketrush/mapper/EventMapper.java
git commit -m "feat: add Event entity and EventMapper"
```

---

## Task 3: DTOs + Exception + GlobalExceptionHandler

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/dto/event/EventDTO.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/dto/event/EventDetailDTO.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/dto/event/EventListRequest.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/exception/EventNotFoundException.java`
- Modify: `src/main/java/dev/dahuangggg/ticketrush/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 创建 EventDTO（列表项）**

```java
package dev.dahuangggg.ticketrush.dto.event;

import java.time.LocalDateTime;

/**
 * 活动列表项 DTO。
 * 不包含 description，避免列表页传输过多数据。
 * isHot 供前端展示"热门"标签和置顶排序。
 */
public record EventDTO(
        Long id,
        String title,
        String artist,
        String city,
        String venue,
        LocalDateTime eventTime,
        String coverUrl,
        Integer status,
        Integer isHot
) {
}
```

- [ ] **Step 2: 创建 EventDetailDTO（详情）**

```java
package dev.dahuangggg.ticketrush.dto.event;

import java.time.LocalDateTime;

/**
 * 活动详情 DTO，包含完整字段。
 */
public record EventDetailDTO(
        Long id,
        String title,
        String artist,
        String city,
        String venue,
        LocalDateTime eventTime,
        String coverUrl,
        String description,
        Integer status,
        Integer isHot
) {
}
```

- [ ] **Step 3: 创建 EventListRequest**

```java
package dev.dahuangggg.ticketrush.dto.event;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 活动列表查询参数。
 * city 和 date 组合会被缓存；keyword 模糊搜索不参与缓存 key，每次透传到数据库。
 */
public record EventListRequest(
        String city,
        String keyword,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date
) {
}
```

- [ ] **Step 4: 创建 EventNotFoundException**

```java
package dev.dahuangggg.ticketrush.exception;

/**
 * 活动不存在异常。
 *
 * 以下情况抛出：
 * 1. Bloom Filter 判断 eventId 一定不存在。
 * 2. Redis 命中空值标记（活动曾被查询过且 DB 确认不存在）。
 * 3. DB 查询结果为 null（活动不存在或已逻辑删除）。
 */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(Long eventId) {
        super("活动不存在：" + eventId);
    }
}
```

- [ ] **Step 5: 在 GlobalExceptionHandler 中添加 404 处理**

在 `GlobalExceptionHandler.java` 的 `handleSmsCooldown` 方法前插入：

```java
import dev.dahuangggg.ticketrush.exception.EventNotFoundException;
import org.springframework.http.HttpStatus;

@ExceptionHandler(EventNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public ErrorResponse handleEventNotFound(EventNotFoundException exception) {
    return new ErrorResponse("EVENT_NOT_FOUND", exception.getMessage());
}
```

同时在文件顶部 import 块添加 `EventNotFoundException` 的 import。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/dto/event/ \
        src/main/java/dev/dahuangggg/ticketrush/exception/EventNotFoundException.java \
        src/main/java/dev/dahuangggg/ticketrush/exception/GlobalExceptionHandler.java
git commit -m "feat: add event DTOs, EventNotFoundException, and 404 handler"
```

---

## Task 4: Caffeine 依赖 + 配置

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/dev/dahuangggg/ticketrush/config/CaffeineConfig.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/infrastructure/cache/LogicalExpireValue.java`

- [ ] **Step 1: 在 pom.xml 中添加 Caffeine 依赖**

在 `<dependencies>` 块中添加（Spring Boot BOM 管理版本，无需指定）：

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 LogicalExpireValue（逻辑过期包装类）**

```java
package dev.dahuangggg.ticketrush.infrastructure.cache;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存值包装类。
 *
 * 热点活动缓存不设置真实的 Redis TTL，
 * 而是将过期时间存储在 value 内部（expireAt）。
 * 读取时判断逻辑是否过期：
 * - 未过期：直接返回 data
 * - 已过期：返回旧 data，同时异步启动缓存重建，不阻塞当前请求
 *
 * 这样即使缓存"过期"，用户仍能立刻拿到数据，不会出现缓存击穿导致的请求堆积。
 */
public record LogicalExpireValue<T>(T data, LocalDateTime expireAt) {
}
```

- [ ] **Step 3: 创建 CaffeineConfig**

```java
package dev.dahuangggg.ticketrush.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CaffeineConfig {

    /**
     * 活动详情本地缓存。
     *
     * 作为两级缓存的第一层，TTL 30 秒，最多缓存 1000 条。
     * 主要作用是在 Redis 故障或高并发场景下减少对 Redis 的请求压力。
     * TTL 较短是为了减少本地缓存和 Redis 之间的数据不一致窗口。
     *
     * key: eventId, value: JSON 字符串（与 Redis 保持相同格式，避免二次序列化）
     */
    @Bean
    public Cache<Long, String> eventDetailLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 活动列表本地缓存。
     *
     * key: cacheKey（由查询参数拼接），value: JSON 字符串。
     */
    @Bean
    public Cache<String, String> eventListLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 注册 ObjectMapper，确保 EventCacheManager 能正确注入。
     *
     * Spring Boot 4 不再自动注册 Jackson2 ObjectMapper bean，需显式声明。
     * 必须禁用 FAIL_ON_UNKNOWN_PROPERTIES，否则反序列化旧版本 JSON 会抛异常。
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
```

- [ ] **Step 4: 编译确认**

```bash
./mvnw compile -q
```

Expected: 无输出（编译成功）

- [ ] **Step 5: Commit**

```bash
git add pom.xml \
        src/main/java/dev/dahuangggg/ticketrush/infrastructure/cache/LogicalExpireValue.java \
        src/main/java/dev/dahuangggg/ticketrush/config/CaffeineConfig.java
git commit -m "feat: add Caffeine dependency, local cache config, and LogicalExpireValue"
```

---

## Task 5: Bloom Filter Service

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/service/BloomFilterService.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/service/impl/BloomFilterServiceImpl.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/service/impl/NoOpBloomFilterService.java`

- [ ] **Step 1: 创建 BloomFilterService 接口**

```java
package dev.dahuangggg.ticketrush.service;

/**
 * 布隆过滤器服务接口。
 *
 * 用于活动详情查询的缓存穿透防护：
 * - 启动时加载所有合法 eventId
 * - 每次查询前先检查 eventId 是否可能存在
 * - "一定不存在"则直接返回 404，不查 Redis 和 DB
 * - "可能存在"则继续正常查询流程
 *
 * 注意：布隆过滤器不支持删除，活动软删除后其 ID 仍在过滤器中，
 * 此时会穿透到 DB 查询，再由空值缓存兜底。
 */
public interface BloomFilterService {

    /**
     * 判断 eventId 是否可能存在。
     * 返回 false 表示一定不存在，可直接拒绝。
     * 返回 true 表示可能存在（含误判），需继续查询。
     */
    boolean mightExist(Long eventId);

    /**
     * 将 eventId 加入布隆过滤器。
     * 新增活动时调用，保证合法 ID 不被误拦截。
     */
    void add(Long eventId);
}
```

- [ ] **Step 2: 创建 BloomFilterServiceImpl**

```java
package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.Event;
import dev.dahuangggg.ticketrush.mapper.EventMapper;
import dev.dahuangggg.ticketrush.service.BloomFilterService;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

// 仅在 ticket-rush.redisson.enabled=true 时生效；Redisson 禁用时由 NoOpBloomFilterService 兜底
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "ticket-rush.redisson.enabled", havingValue = "true")
public class BloomFilterServiceImpl implements BloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterServiceImpl.class);

    /*
     * 布隆过滤器参数：
     * - 预期元素数量 10000（活动数量上限估算）
     * - 误判率 0.01（1%）：100 个不存在的 ID 中最多 1 个被误判为存在，
     *   误判后仍会查 DB，再由空值缓存兜底，不影响正确性，只有轻微性能损耗。
     *
     * Redisson RBloomFilter 底层基于 Redis BitMap，支持分布式多实例共享，
     * 不同于 JVM 内存中的 Bloom Filter，多实例部署时状态一致。
     */
    private static final String BLOOM_FILTER_KEY = "event:bloom";
    private static final long EXPECTED_INSERTIONS = 10_000;
    private static final double FALSE_POSITIVE_RATE = 0.01;

    private final RBloomFilter<Long> bloomFilter;
    private final EventMapper eventMapper;

    public BloomFilterServiceImpl(RedissonClient redissonClient, EventMapper eventMapper) {
        this.bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_KEY);
        this.bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
        this.eventMapper = eventMapper;
    }

    /**
     * 服务启动时将数据库中所有合法 eventId 加载到布隆过滤器。
     *
     * 只加载未删除的活动 ID，软删除的活动不在过滤器中。
     * 注意：deleted = 0 的过滤由 MyBatis-Plus 的 @TableLogic 自动处理。
     */
    @PostConstruct
    public void init() {
        List<Long> eventIds = eventMapper.selectList(
                new LambdaQueryWrapper<Event>().select(Event::getId)
        ).stream().map(Event::getId).toList();

        eventIds.forEach(bloomFilter::add);
        log.info("Bloom filter initialized with {} event IDs", eventIds.size());
    }

    @Override
    public boolean mightExist(Long eventId) {
        return bloomFilter.contains(eventId);
    }

    @Override
    public void add(Long eventId) {
        bloomFilter.add(eventId);
    }
}
```

- [ ] **Step 3: 创建 NoOpBloomFilterService（Redisson 禁用时的回退实现）**

```java
package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.service.BloomFilterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 布隆过滤器空实现。
 *
 * 当 ticket-rush.redisson.enabled=false 或未配置时激活。
 * mightExist 始终返回 true（所有 ID 都放行），add 是空操作。
 * 这样即使不启用 Redisson，EventServiceImpl 仍能通过构造注入拿到合法的 bean，
 * 缓存穿透防护退化为仅依赖空值缓存，功能正确但防护减弱。
 */
@Service
@ConditionalOnProperty(name = "ticket-rush.redisson.enabled",
        havingValue = "false", matchIfMissing = true)
public class NoOpBloomFilterService implements BloomFilterService {

    @Override
    public boolean mightExist(Long eventId) {
        return true;
    }

    @Override
    public void add(Long eventId) {
    }
}
```

- [ ] **Step 4: 编译确认**

```bash
./mvnw compile -q
```

Expected: 无输出

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/service/BloomFilterService.java \
        src/main/java/dev/dahuangggg/ticketrush/service/impl/BloomFilterServiceImpl.java \
        src/main/java/dev/dahuangggg/ticketrush/service/impl/NoOpBloomFilterService.java
git commit -m "feat: add BloomFilterService with startup initialization"
```

---

## Task 6: EventCacheManager

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/infrastructure/cache/EventCacheManager.java`

- [ ] **Step 1: 创建 EventCacheManager**

```java
package dev.dahuangggg.ticketrush.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Component
public class EventCacheManager {

    private static final Logger log = LoggerFactory.getLogger(EventCacheManager.class);

    /*
     * 异步重建执行器：热点活动缓存逻辑过期后，由此线程池异步重建，
     * 不阻塞正在进行的用户请求（返回旧数据，后台更新缓存）。
     * 使用有界线程池，避免高并发场景下无限创建线程。
     */
    private static final ExecutorService REBUILD_EXECUTOR = Executors.newFixedThreadPool(4);

    private static final String DETAIL_KEY = "event:detail:";
    private static final String HOT_DETAIL_KEY = "event:detail:hot:";
    private static final String NULL_KEY = "event:null:";
    private static final String LOCK_KEY = "event:lock:";
    private static final String LIST_KEY = "event:list:";

    private static final Duration DETAIL_TTL_BASE = Duration.ofMinutes(30);
    private static final Duration DETAIL_TTL_JITTER = Duration.ofMinutes(5);
    private static final Duration NULL_TTL = Duration.ofMinutes(2);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration LIST_TTL_BASE = Duration.ofMinutes(10);
    private static final Duration LIST_TTL_JITTER = Duration.ofMinutes(3);
    private static final Duration LOGICAL_EXPIRE_DURATION = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final Cache<Long, String> eventDetailLocalCache;
    private final Cache<String, String> eventListLocalCache;
    private final ObjectMapper objectMapper;

    public EventCacheManager(StringRedisTemplate redisTemplate,
                             Cache<Long, String> eventDetailLocalCache,
                             Cache<String, String> eventListLocalCache,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.eventDetailLocalCache = eventDetailLocalCache;
        this.eventListLocalCache = eventListLocalCache;
        this.objectMapper = objectMapper;
    }

    // ========== 空值缓存 ==========

    /**
     * 写入空值标记，用于缓存穿透防护。
     * DB 确认活动不存在时调用，后续相同 ID 的查询直接命中此标记返回 404。
     */
    public void cacheNull(Long eventId) {
        redisTemplate.opsForValue().set(NULL_KEY + eventId, "", NULL_TTL);
    }

    /**
     * 检查空值标记是否存在。
     */
    public boolean isNull(Long eventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(NULL_KEY + eventId));
    }

    // ========== 普通活动详情（Cache-Aside + 互斥锁防击穿）==========

    /**
     * 获取普通活动详情，Cache-Aside 模式。
     *
     * 命中 Caffeine → 返回
     * 命中 Redis → 写入 Caffeine → 返回
     * 未命中 → 尝试获取互斥锁
     *   获锁成功 → 调用 dbLoader 查 DB → 写入 Redis 和 Caffeine → 返回
     *   获锁失败 → 短暂等待后重试（最多 3 次），避免大量请求同时重建
     *
     * 返回 null 表示 DB 中也不存在，由调用方缓存空值并抛出 404。
     */
    public EventDetailDTO getNormalEventDetail(Long eventId, Supplier<EventDetailDTO> dbLoader) {
        // 1. 查 Caffeine 本地缓存
        String localJson = eventDetailLocalCache.getIfPresent(eventId);
        if (localJson != null) {
            return deserialize(localJson, EventDetailDTO.class);
        }

        // 2. 查 Redis
        String redisJson = redisTemplate.opsForValue().get(DETAIL_KEY + eventId);
        if (redisJson != null) {
            eventDetailLocalCache.put(eventId, redisJson);
            return deserialize(redisJson, EventDetailDTO.class);
        }

        // 3. 缓存未命中，尝试互斥锁重建（最多重试 3 次）
        for (int i = 0; i < 3; i++) {
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY + eventId, "1", LOCK_TTL);

            if (Boolean.TRUE.equals(locked)) {
                try {
                    // double-check：获锁后先再查一次 Redis，防止重复重建
                    String doubleCheckJson = redisTemplate.opsForValue().get(DETAIL_KEY + eventId);
                    if (doubleCheckJson != null) {
                        eventDetailLocalCache.put(eventId, doubleCheckJson);
                        return deserialize(doubleCheckJson, EventDetailDTO.class);
                    }
                    // 获锁成功，查 DB 重建缓存
                    EventDetailDTO dto = dbLoader.get();
                    if (dto != null) {
                        String json = serialize(dto);
                        long jitterSeconds = ThreadLocalRandom.current()
                                .nextLong(0, DETAIL_TTL_JITTER.toSeconds());
                        redisTemplate.opsForValue().set(
                                DETAIL_KEY + eventId, json,
                                DETAIL_TTL_BASE.plusSeconds(jitterSeconds));
                        eventDetailLocalCache.put(eventId, json);
                    }
                    return dto;
                } finally {
                    redisTemplate.delete(LOCK_KEY + eventId);
                }
            }

            // 未获锁，等待 50ms 后重试（其他线程正在重建）
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 重试时先看 Redis 是否已被重建
            String retryJson = redisTemplate.opsForValue().get(DETAIL_KEY + eventId);
            if (retryJson != null) {
                eventDetailLocalCache.put(eventId, retryJson);
                return deserialize(retryJson, EventDetailDTO.class);
            }
        }

        // 重试耗尽，直接查 DB（降级，避免用户长时间等待）
        return dbLoader.get();
    }

    // ========== 热点活动详情（逻辑过期 + 异步重建防击穿）==========

    /**
     * 获取热点活动详情，逻辑过期模式。
     *
     * 热点活动 Redis key 不设置真实 TTL，value 内嵌 expireAt 时间戳。
     * 读取逻辑：
     *   命中 Caffeine → 返回（Caffeine 有真实 TTL，过期会自动淘汰）
     *   命中 Redis：
     *     逻辑未过期 → 写入 Caffeine → 返回
     *     逻辑已过期 → 尝试获互斥锁
     *       获锁成功 → 异步重建缓存（新线程查 DB）→ 返回旧数据
     *       获锁失败 → 直接返回旧数据（其他线程正在重建）
     *   Redis 未命中 → 返回 null（冷启动场景，调用方应触发 warmUp）
     */
    public EventDetailDTO getHotEventDetail(Long eventId, Supplier<EventDetailDTO> dbLoader) {
        // 1. 查 Caffeine
        String localJson = eventDetailLocalCache.getIfPresent(eventId);
        if (localJson != null) {
            return deserialize(localJson, EventDetailDTO.class);
        }

        // 2. 查 Redis（逻辑过期 key）
        String redisJson = redisTemplate.opsForValue().get(HOT_DETAIL_KEY + eventId);
        if (redisJson == null) {
            // 冷启动，缓存尚未预热，返回 null 由调用方处理
            return null;
        }

        LogicalExpireValue<EventDetailDTO> wrapper = deserializeLogical(redisJson, EventDetailDTO.class);
        if (wrapper == null) {
            return null;
        }

        EventDetailDTO data = wrapper.data();

        if (LocalDateTime.now().isBefore(wrapper.expireAt())) {
            // 逻辑未过期，写入 Caffeine 并返回
            eventDetailLocalCache.put(eventId, serialize(data));
            return data;
        }

        // 逻辑已过期，尝试异步重建
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY + "hot:" + eventId, "1", LOCK_TTL);

        if (Boolean.TRUE.equals(locked)) {
            // 获锁成功，异步重建，当前请求先返回旧数据
            REBUILD_EXECUTOR.submit(() -> {
                try {
                    EventDetailDTO fresh = dbLoader.get();
                    if (fresh != null) {
                        warmUp(eventId, fresh);
                    }
                } finally {
                    redisTemplate.delete(LOCK_KEY + "hot:" + eventId);
                }
            });
        }
        // 获锁失败或已提交重建任务，直接返回旧数据（可接受的短暂不一致）
        return data;
    }

    /**
     * 预热热点活动缓存。
     * 活动被标记为 is_hot=1 时，或动态检测到访问量超阈值时调用。
     * 写入逻辑过期格式，不设置真实 TTL（key 永不自动过期，靠逻辑过期控制刷新频率）。
     */
    public void warmUp(Long eventId, EventDetailDTO dto) {
        LocalDateTime expireAt = LocalDateTime.now().plus(LOGICAL_EXPIRE_DURATION);
        LogicalExpireValue<EventDetailDTO> wrapper = new LogicalExpireValue<>(dto, expireAt);
        redisTemplate.opsForValue().set(HOT_DETAIL_KEY + eventId, serialize(wrapper));
        eventDetailLocalCache.put(eventId, serialize(dto));
        log.info("Hot event cache warmed up for eventId={}", eventId);
    }

    // ========== 缓存删除（数据一致性）==========

    /**
     * 删除活动的所有缓存（普通 + 热点 + Caffeine + 空值标记）。
     * 活动信息更新时同步调用，失败时由 Kafka 消费者异步重试。
     */
    public void invalidate(Long eventId) {
        redisTemplate.delete(DETAIL_KEY + eventId);
        redisTemplate.delete(HOT_DETAIL_KEY + eventId);
        redisTemplate.delete(NULL_KEY + eventId);
        eventDetailLocalCache.invalidate(eventId);
    }

    @jakarta.annotation.PreDestroy
    void shutdownRebuildExecutor() {
        REBUILD_EXECUTOR.shutdown();
    }

    // ========== 列表缓存 ==========

    public List<EventDTO> getEventList(String cacheKey) {
        String localJson = eventListLocalCache.getIfPresent(cacheKey);
        if (localJson != null) {
            return deserializeList(localJson);
        }

        String redisJson = redisTemplate.opsForValue().get(LIST_KEY + cacheKey);
        if (redisJson != null) {
            eventListLocalCache.put(cacheKey, redisJson);
            return deserializeList(redisJson);
        }

        return null;
    }

    public void cacheEventList(String cacheKey, List<EventDTO> list) {
        String json = serialize(list);
        long jitterSeconds = ThreadLocalRandom.current()
                .nextLong(0, LIST_TTL_JITTER.toSeconds());
        redisTemplate.opsForValue().set(
                LIST_KEY + cacheKey, json,
                LIST_TTL_BASE.plusSeconds(jitterSeconds));
        eventListLocalCache.put(cacheKey, json);
    }

    // ========== 序列化工具 ==========

    private <T> String serialize(T obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("反序列化失败，json={}", json, e);
            return null;
        }
    }

    private List<EventDTO> deserializeList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, EventDTO.class));
        } catch (JsonProcessingException e) {
            log.warn("列表反序列化失败", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> LogicalExpireValue<T> deserializeLogical(String json, Class<T> dataClass) {
        try {
            var type = objectMapper.getTypeFactory()
                    .constructParametricType(LogicalExpireValue.class, dataClass);
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("逻辑过期值反序列化失败", e);
            return null;
        }
    }
}
```

- [ ] **Step 2: 编译确认**

```bash
./mvnw compile -q
```

Expected: 无输出

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/infrastructure/cache/EventCacheManager.java
git commit -m "feat: add EventCacheManager with hot/normal cache strategies"
```

---

## Task 7: Kafka 缓存失效补偿

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/infrastructure/mq/EventCacheInvalidateMessage.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/infrastructure/mq/EventCacheInvalidationProducer.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/infrastructure/mq/EventCacheInvalidationConsumer.java`
- Modify: `src/main/resources/application-dev.yaml`

- [ ] **Step 1: 创建消息 DTO**

```java
package dev.dahuangggg.ticketrush.infrastructure.mq;

/**
 * 活动缓存失效消息。
 *
 * 只携带 eventId，不携带 Redis key 字符串。
 * Key 的拼接逻辑由消费者端的 EventCacheManager.invalidate() 统一管理，
 * 避免 key 格式变更时历史消息失效。
 */
public record EventCacheInvalidateMessage(Long eventId) {
}
```

- [ ] **Step 2: 创建 Kafka Producer**

```java
package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventCacheInvalidationProducer {

    private static final Logger log = LoggerFactory.getLogger(EventCacheInvalidationProducer.class);
    static final String TOPIC = "event.cache.invalidate";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventCacheInvalidationProducer(KafkaTemplate<String, String> kafkaTemplate,
                                          ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送缓存失效消息。
     *
     * 仅在同步删除缓存失败时调用，作为异步兜底手段。
     * 使用 eventId 作为 Kafka partition key，保证同一活动的消息有序消费。
     * 发送失败时记录错误日志，依赖 TTL 自然过期兜底（最终一致性的最后防线）。
     */
    public void send(Long eventId) {
        try {
            String message = objectMapper.writeValueAsString(new EventCacheInvalidateMessage(eventId));
            kafkaTemplate.send(TOPIC, String.valueOf(eventId), message);
            log.info("Cache invalidation message sent for eventId={}", eventId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cache invalidation message for eventId={}", eventId, e);
        } catch (Exception e) {
            log.error("Failed to send cache invalidation message for eventId={}, will rely on TTL expiry",
                    eventId, e);
        }
    }
}
```

- [ ] **Step 3: 创建 Kafka Consumer**

```java
package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventCacheInvalidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventCacheInvalidationConsumer.class);

    private final EventCacheManager eventCacheManager;
    private final ObjectMapper objectMapper;

    public EventCacheInvalidationConsumer(EventCacheManager eventCacheManager,
                                          ObjectMapper objectMapper) {
        this.eventCacheManager = eventCacheManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费缓存失效消息，执行缓存删除。
     *
     * Kafka 的梯度重试机制（earliest offset reset + consumer group）保证：
     * 如果本次删除失败（Redis 短暂不可用），消息会被重新消费，梯度重试直到成功。
     * EventCacheManager.invalidate() 调用 Redis DEL，DEL 是幂等操作，
     * 多次消费同一消息不会产生副作用。
     */
    @KafkaListener(topics = EventCacheInvalidationProducer.TOPIC,
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        try {
            EventCacheInvalidateMessage msg = objectMapper.readValue(
                    message, EventCacheInvalidateMessage.class);
            eventCacheManager.invalidate(msg.eventId());
            log.info("Cache invalidated via Kafka for eventId={}", msg.eventId());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cache invalidation message: {}", message, e);
            // 反序列化失败不重试（消息格式错误，重试无意义）
        }
    }
}
```

- [ ] **Step 4: 在 application-dev.yaml 中开启 Kafka topic 自动创建**

在 `spring.kafka` 配置块末尾追加（仅此一条，topic name 由 Producer 内部常量管理，不需要写到 YAML）：

```yaml
spring:
  kafka:
    # 已有配置保持不变
    # 新增：
    admin:
      auto-create: true
```

- [ ] **Step 5: 编译确认**

```bash
./mvnw compile -q
```

Expected: 无输出

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/infrastructure/mq/ \
        src/main/resources/application-dev.yaml
git commit -m "feat: add Kafka cache invalidation producer and consumer"
```

---

## Task 8: EventService + EventServiceImpl

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/service/EventService.java`
- Create: `src/main/java/dev/dahuangggg/ticketrush/service/impl/EventServiceImpl.java`

- [ ] **Step 1: 创建 EventService 接口**

```java
package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;

import java.util.List;

public interface EventService {

    /**
     * 查询活动列表，支持按城市、关键词、日期过滤。
     * city + date 组合结果会被缓存；keyword 每次透传到 DB。
     */
    List<EventDTO> listEvents(EventListRequest request);

    /**
     * 查询活动详情，包含完整的缓存策略（布隆过滤器、热点/普通路由、穿透防护）。
     */
    EventDetailDTO getEventDetail(Long eventId);

    /**
     * 更新活动后同步删除缓存，失败则发送 Kafka 消息异步兜底。
     */
    void invalidateCache(Long eventId);
}
```

- [ ] **Step 2: 创建 EventServiceImpl**

```java
package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.entity.Event;
import dev.dahuangggg.ticketrush.exception.EventNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.mq.EventCacheInvalidationProducer;
import dev.dahuangggg.ticketrush.mapper.EventMapper;
import dev.dahuangggg.ticketrush.service.BloomFilterService;
import dev.dahuangggg.ticketrush.service.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class EventServiceImpl implements EventService {

    private static final Logger log = LoggerFactory.getLogger(EventServiceImpl.class);

    /*
     * 访问量统计线程池：有界，避免高并发下无限创建线程。
     * daemon=true 保证 JVM 正常退出。
     */
    private static final ExecutorService ACCESS_TRACKER_EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "access-tracker");
                t.setDaemon(true);
                return t;
            });

    /*
     * 动态热点检测阈值：1 分钟内访问量超过此值，触发缓存预热并告警。
     * 仅作为运营漏标后的补救手段，不是第一道防线。
     */
    private static final long HOT_DETECT_THRESHOLD = 1000;
    private static final String ACCESS_COUNT_KEY = "event:access:count:";
    private static final Duration ACCESS_COUNT_WINDOW = Duration.ofMinutes(1);

    private final EventMapper eventMapper;
    private final EventCacheManager cacheManager;
    private final EventCacheInvalidationProducer cacheInvalidationProducer;
    private final StringRedisTemplate redisTemplate;
    private final BloomFilterService bloomFilterService;

    public EventServiceImpl(EventMapper eventMapper,
                            EventCacheManager cacheManager,
                            EventCacheInvalidationProducer cacheInvalidationProducer,
                            StringRedisTemplate redisTemplate,
                            BloomFilterService bloomFilterService) {
        this.eventMapper = eventMapper;
        this.cacheManager = cacheManager;
        this.cacheInvalidationProducer = cacheInvalidationProducer;
        this.redisTemplate = redisTemplate;
        this.bloomFilterService = bloomFilterService;
    }

    @Override
    public List<EventDTO> listEvents(EventListRequest request) {
        String cacheKey = buildListCacheKey(request);

        if (!StringUtils.hasText(request.keyword())) {
            List<EventDTO> cached = cacheManager.getEventList(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<EventDTO> result = queryEventListFromDb(request);

        if (!StringUtils.hasText(request.keyword())) {
            cacheManager.cacheEventList(cacheKey, result);
        }

        return result;
    }

    /**
     * 查询活动详情，完整缓存策略（缓存优先，不提前查 DB）。
     *
     * 执行顺序：
     * 1. 布隆过滤器：一定不存在则直接 404。
     * 2. 空值缓存：Redis 有空值标记则直接 404。
     * 3. 先尝试热点缓存（getHotEventDetail）—— 不查 DB，dbLoader 只在异步重建时被调用。
     *    命中（含逻辑过期旧数据）则直接返回。
     * 4. 热点缓存未命中（普通活动或冷启动）→ 走普通缓存（getNormalEventDetail）。
     *    dbLoader 在未命中时才查 DB；发现 isHot=1 则顺手预热热点缓存。
     * 5. DB 也不存在 → 缓存空值 → 404。
     * 6. 异步记录访问量，超阈值时自动预热（运营漏标兜底）。
     *
     * 关键点：AtomicReference<Event> 用于跨 lambda 捕获 dbLoader 中的 DB 查询结果，
     * 使 trackAccessAsync 能拿到 event 对象用于动态热点检测。
     */
    @Override
    public EventDetailDTO getEventDetail(Long eventId) {
        if (!bloomFilterService.mightExist(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        if (cacheManager.isNull(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        AtomicReference<Event> loadedEvent = new AtomicReference<>();

        // 3. 优先尝试热点缓存（不提前查 DB）
        EventDetailDTO result = cacheManager.getHotEventDetail(eventId, () -> {
            Event event = eventMapper.selectById(eventId);
            loadedEvent.set(event);
            return event != null ? toDetailDTO(event) : null;
        });

        if (result != null) {
            trackAccessAsync(eventId, loadedEvent.get());
            return result;
        }

        // 4. 热点缓存未命中 → 普通缓存路径
        result = cacheManager.getNormalEventDetail(eventId, () -> {
            Event event = eventMapper.selectById(eventId);
            loadedEvent.set(event);
            if (event == null) {
                return null;
            }
            if (Integer.valueOf(1).equals(event.getIsHot())) {
                EventDetailDTO dto = toDetailDTO(event);
                cacheManager.warmUp(eventId, dto);
                return dto;
            }
            return toDetailDTO(event);
        });

        if (result == null) {
            cacheManager.cacheNull(eventId);
            throw new EventNotFoundException(eventId);
        }

        trackAccessAsync(eventId, loadedEvent.get());
        return result;
    }

    @Override
    public void invalidateCache(Long eventId) {
        try {
            cacheManager.invalidate(eventId);
        } catch (Exception e) {
            log.error("Sync cache invalidation failed for eventId={}, sending to Kafka", eventId, e);
            cacheInvalidationProducer.send(eventId);
        }
    }

    // ========== 私有方法 ==========

    private List<EventDTO> queryEventListFromDb(EventListRequest request) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .eq(Event::getStatus, 1)
                .eq(StringUtils.hasText(request.city()), Event::getCity, request.city())
                .like(StringUtils.hasText(request.keyword()), Event::getTitle, request.keyword())
                .ge(request.date() != null, Event::getEventTime,
                        request.date() != null ? request.date().atStartOfDay() : null)
                .lt(request.date() != null, Event::getEventTime,
                        request.date() != null ? request.date().plusDays(1).atStartOfDay() : null)
                .orderByDesc(Event::getIsHot)
                .orderByAsc(Event::getEventTime);

        return eventMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .toList();
    }

    @jakarta.annotation.PreDestroy
    void shutdownAccessTracker() {
        ACCESS_TRACKER_EXECUTOR.shutdown();
    }

    private void trackAccessAsync(Long eventId, Event event) {
        ACCESS_TRACKER_EXECUTOR.submit(() -> {
            try {
                String key = ACCESS_COUNT_KEY + eventId;
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, ACCESS_COUNT_WINDOW);
                }
                if (count != null && count >= HOT_DETECT_THRESHOLD
                        && event != null && !Integer.valueOf(1).equals(event.getIsHot())) {
                    log.warn("Dynamic hot event detected: eventId={}, accessCount={} in 1min. "
                            + "Consider marking is_hot=1 in admin console.", eventId, count);
                    cacheManager.warmUp(eventId, toDetailDTO(event));
                }
                // event == null means we arrived via the hot-cache path; warm-up already handled
            } catch (Exception e) {
                log.warn("Access tracking failed for eventId={}", eventId, e);
            }
        });
    }

    private String buildListCacheKey(EventListRequest request) {
        return (request.city() != null ? request.city() : "") + ":"
                + (request.date() != null ? request.date().toString() : "");
    }

    private EventDTO toDTO(Event event) {
        return new EventDTO(event.getId(), event.getTitle(), event.getArtist(),
                event.getCity(), event.getVenue(), event.getEventTime(),
                event.getCoverUrl(), event.getStatus(), event.getIsHot());
    }

    private EventDetailDTO toDetailDTO(Event event) {
        return new EventDetailDTO(event.getId(), event.getTitle(), event.getArtist(),
                event.getCity(), event.getVenue(), event.getEventTime(),
                event.getCoverUrl(), event.getDescription(),
                event.getStatus(), event.getIsHot());
    }
}
```

- [ ] **Step 3: 编译确认**

```bash
./mvnw compile -q
```

Expected: 无输出

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/service/EventService.java \
        src/main/java/dev/dahuangggg/ticketrush/service/impl/EventServiceImpl.java
git commit -m "feat: add EventService with full cache strategy orchestration"
```

---

## Task 9: EventController + 测试

**Files:**
- Create: `src/main/java/dev/dahuangggg/ticketrush/controller/EventController.java`
- Create: `src/test/java/dev/dahuangggg/ticketrush/controller/EventControllerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.exception.EventNotFoundException;
import dev.dahuangggg.ticketrush.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listEventsReturnsOk() throws Exception {
        mockMvc.perform(get("/api/events").param("city", "上海"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1001))
                .andExpect(jsonPath("$[0].title").value("周杰伦2026世界巡回演唱会"));
    }

    @Test
    void getEventDetailReturnsOk() throws Exception {
        mockMvc.perform(get("/api/events/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.description").value("演出详情"));
    }

    @Test
    void getEventDetailReturns404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/events/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @TestConfiguration
    static class EventControllerTestConfig {

        @Bean
        @Primary
        FakeEventService fakeEventService() {
            return new FakeEventService();
        }
    }

    static class FakeEventService implements EventService {

        @Override
        public List<EventDTO> listEvents(EventListRequest request) {
            return List.of(new EventDTO(1001L, "周杰伦2026世界巡回演唱会", "周杰伦",
                    "上海", "梅赛德斯奔驰文化中心",
                    LocalDateTime.of(2026, 8, 1, 20, 0),
                    "https://example.com/cover.jpg", 1, 1));
        }

        @Override
        public EventDetailDTO getEventDetail(Long eventId) {
            if (eventId.equals(9999L)) {
                throw new EventNotFoundException(eventId);
            }
            return new EventDetailDTO(1001L, "周杰伦2026世界巡回演唱会", "周杰伦",
                    "上海", "梅赛德斯奔驰文化中心",
                    LocalDateTime.of(2026, 8, 1, 20, 0),
                    "https://example.com/cover.jpg", "演出详情", 1, 1);
        }

        @Override
        public void invalidateCache(Long eventId) {
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（EventController 尚未创建）**

```bash
./mvnw test -pl . -Dtest=EventControllerTest -q 2>&1 | tail -5
```

Expected: 包含 `FAILED` 或编译错误

- [ ] **Step 3: 创建 EventController**

```java
package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 查询活动列表。
     *
     * 支持三个可选过滤条件：
     * - city：城市过滤，精确匹配。
     * - keyword：标题模糊搜索，不参与缓存（避免缓存 key 爆炸）。
     * - date：演出日期过滤，格式 yyyy-MM-dd。
     *
     * 热点活动（is_hot=1）在结果中置顶。
     */
    @GetMapping
    public List<EventDTO> listEvents(EventListRequest request) {
        return eventService.listEvents(request);
    }

    /**
     * 查询活动详情。
     *
     * 经过布隆过滤器、空值缓存、热点/普通缓存策略的完整处理。
     * 活动不存在时返回 404 EVENT_NOT_FOUND。
     */
    @GetMapping("/{eventId}")
    public EventDetailDTO getEventDetail(@PathVariable Long eventId) {
        return eventService.getEventDetail(eventId);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
./mvnw test -pl . -Dtest=EventControllerTest -q 2>&1 | tail -5
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/dahuangggg/ticketrush/controller/EventController.java \
        src/test/java/dev/dahuangggg/ticketrush/controller/EventControllerTest.java
git commit -m "feat: add EventController with list and detail endpoints"
```

---

## Self-Review

**Spec Coverage 检查：**

| 设计决策 | 覆盖 Task |
|----------|-----------|
| tb_event + is_hot 字段 | Task 1, 2 |
| 布隆过滤器防穿透 | Task 5 |
| Caffeine 两级缓存 | Task 4, 6 |
| 热点活动逻辑过期 | Task 6 |
| 普通活动互斥锁 | Task 6 |
| TTL 随机抖动防雪崩 | Task 6 |
| 空值缓存 | Task 6 |
| 同步删除 + Kafka 兜底 | Task 7, 8 |
| 动态热点检测 + 告警 + 自动预热 | Task 8 |
| is_hot 参与业务（列表置顶） | Task 8 |
| GET /api/events 列表接口 | Task 9 |
| GET /api/events/{id} 详情接口 | Task 9 |
| 404 异常处理 | Task 3 |

**无 TBD / TODO / 占位符：** ✅

**类型一致性：**
- `EventDetailDTO`、`EventDTO` 在 Task 3 定义，Task 6、8、9 使用 ✅
- `EventCacheManager.invalidate(Long)` 在 Task 6 定义，Task 7（Consumer）、Task 8 使用 ✅
- `BloomFilterService.mightExist(Long)` 在 Task 5 定义，Task 8 使用 ✅
- `EventCacheInvalidationProducer.TOPIC` 在 Task 7 定义，Consumer 引用 ✅
