package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.RedisRollbackService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LuaRedisRollbackService implements RedisRollbackService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rollbackScript;

    public LuaRedisRollbackService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rollbackScript = new DefaultRedisScript<>();
        this.rollbackScript.setLocation(new ClassPathResource("lua/ticket_rollback.lua"));
        this.rollbackScript.setResultType(Long.class);
    }

    @Override
    public void rollback(Long skuId, Long userId) {
        List<String> keys = List.of(
                RedisKeyRegistry.stockKey(skuId),
                RedisKeyRegistry.orderUserKey(skuId)
        );
        Long result = redisTemplate.execute(rollbackScript, keys, String.valueOf(userId));
        if (result == null) {
            throw new IllegalStateException(
                    "Redis rollback returned null (possible connection issue) skuId=" + skuId + " userId=" + userId);
        }
    }
}
