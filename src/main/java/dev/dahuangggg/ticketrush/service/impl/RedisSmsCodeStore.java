package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.exception.SmsCooldownException;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.SmsCodeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisSmsCodeStore implements SmsCodeStore {

    private static final Duration COOLDOWN_DURATION = Duration.ofSeconds(60);
    private static final DefaultRedisScript<Long> SAVE_SCRIPT = saveScript();
    private static final DefaultRedisScript<Long> VERIFY_AND_CONSUME_SCRIPT = verificationScript();

    private final StringRedisTemplate redisTemplate;
    private final Duration codeTtl;
    private final int maxAttempts;

    public RedisSmsCodeStore(StringRedisTemplate redisTemplate,
                             @Value("${ticket-rush.auth.sms-code-ttl:5m}") Duration codeTtl,
                             @Value("${ticket-rush.auth.sms-code-max-attempts:5}") int maxAttempts) {
        this.redisTemplate = redisTemplate;
        this.codeTtl = codeTtl;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("sms-code-max-attempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * 把验证码写入 Redis，并同时设置 TTL。
     *
     * 同一手机号 60s 内只允许发送一次，超频抛 SmsCooldownException。
     */
    @Override
    public void save(String phone, String code) {
        Long saved = redisTemplate.execute(
                SAVE_SCRIPT,
                java.util.List.of(
                        RedisKeyRegistry.smsCodeKey(phone),
                        RedisKeyRegistry.smsCooldownKey(phone)),
                code + "|0",
                String.valueOf(codeTtl.toMillis()),
                String.valueOf(COOLDOWN_DURATION.toMillis()));
        if (Long.valueOf(0L).equals(saved)) {
            throw new SmsCooldownException();
        }
        if (!Long.valueOf(1L).equals(saved)) {
            throw new IllegalStateException("SMS code store returned no result");
        }
    }

    /**
     * 由 Lua 原子完成比较、失败次数增加和一次性消费。
     * 返回值：0=验证成功并删除，1=无效，2=达到失败次数上限并删除。
     */
    @Override
    public VerificationResult verifyAndConsume(String phone, String submittedCode) {
        Long result = redisTemplate.execute(
                VERIFY_AND_CONSUME_SCRIPT,
                java.util.List.of(RedisKeyRegistry.smsCodeKey(phone)),
                submittedCode,
                String.valueOf(maxAttempts));
        if (Long.valueOf(0L).equals(result)) {
            return VerificationResult.VERIFIED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return VerificationResult.TOO_MANY_ATTEMPTS;
        }
        // Redis pipeline/cluster 异常时 execute 可能返回 null。认证逻辑必须 fail closed，
        // 不能把“无法确认”误判为验证码正确。
        return VerificationResult.INVALID;
    }

    private static DefaultRedisScript<Long> verificationScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local state = redis.call('GET', KEYS[1])
                if not state then
                    return 1
                end

                local delimiter = string.find(state, '|', 1, true)
                local storedCode = state
                local attempts = 0
                if delimiter then
                    storedCode = string.sub(state, 1, delimiter - 1)
                    attempts = tonumber(string.sub(state, delimiter + 1)) or 0
                end

                local maxAttempts = tonumber(ARGV[2])
                if attempts >= maxAttempts then
                    redis.call('DEL', KEYS[1])
                    return 2
                end

                if storedCode == ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    return 0
                end

                attempts = attempts + 1
                if attempts >= maxAttempts then
                    redis.call('DEL', KEYS[1])
                    return 2
                end

                local ttl = redis.call('PTTL', KEYS[1])
                if ttl > 0 then
                    redis.call('SET', KEYS[1], storedCode .. '|' .. attempts, 'PX', ttl)
                else
                    redis.call('DEL', KEYS[1])
                end
                return 1
                """);
        return script;
    }

    private static DefaultRedisScript<Long> saveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('EXISTS', KEYS[2]) == 1 then
                    return 0
                end
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
                return 1
                """);
        return script;
    }
}
