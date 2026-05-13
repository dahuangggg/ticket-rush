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
}
