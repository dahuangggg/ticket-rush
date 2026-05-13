package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.exception.SmsCooldownException;
import dev.dahuangggg.ticketrush.service.SmsCodeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisSmsCodeStore implements SmsCodeStore {

    private static final String CODE_PREFIX = "auth:sms-code:";
    private static final String COOLDOWN_PREFIX = "auth:sms-cooldown:";
    private static final Duration COOLDOWN_DURATION = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    @Value("${ticket-rush.auth.sms-code-ttl:5m}")
    private Duration codeTtl;

    public RedisSmsCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 把验证码写入 Redis，并同时设置 TTL。
     *
     * 同一手机号 60s 内只允许发送一次，超频抛 SmsCooldownException。
     */
    @Override
    public void save(String phone, String code) {
        Boolean notOnCooldown = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey(phone), "1", COOLDOWN_DURATION);
        if (!Boolean.TRUE.equals(notOnCooldown)) {
            throw new SmsCooldownException();
        }
        redisTemplate.opsForValue().set(codeKey(phone), code, codeTtl);
    }

    /**
     * 对比用户提交的验证码和 Redis 中保存的验证码。
     *
     * 如果 Redis 中没有值，说明验证码不存在或已经过期；
     * 此时 storedCode 为 null，code.equals(null) 会返回 false。
     */
    @Override
    public boolean matches(String phone, String code) {
        String storedCode = redisTemplate.opsForValue().get(codeKey(phone));
        return code.equals(storedCode);
    }

    @Override
    public void delete(String phone) {
        redisTemplate.delete(codeKey(phone));
    }

    private String codeKey(String phone) {
        return CODE_PREFIX + phone;
    }

    private String cooldownKey(String phone) {
        return COOLDOWN_PREFIX + phone;
    }
}
