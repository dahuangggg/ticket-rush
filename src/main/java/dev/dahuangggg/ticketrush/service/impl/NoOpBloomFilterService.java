package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.service.BloomFilterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 布隆过滤器无操作实现。
 *
 * 当 ticket-rush.redisson.enabled=false（或未配置）时自动注册，
 * 代替 BloomFilterServiceImpl 以保证 Spring 上下文正常启动。
 *
 * mightExist 始终返回 true（不拦截任何请求），
 * 穿透防护由后续的空值缓存（event:null:{id}）承担。
 */
@Service
@ConditionalOnProperty(name = "ticket-rush.redisson.enabled", matchIfMissing = true, havingValue = "false")
public class NoOpBloomFilterService implements BloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(NoOpBloomFilterService.class);

    public NoOpBloomFilterService() {
        log.info("BloomFilterService: using no-op implementation (Redisson disabled)");
    }

    @Override
    public boolean mightExist(Long eventId) {
        // Redisson 未启用时，不做布隆过滤拦截，允许所有请求通过
        return true;
    }

    @Override
    public void add(Long eventId) {
        // 无操作
    }
}
