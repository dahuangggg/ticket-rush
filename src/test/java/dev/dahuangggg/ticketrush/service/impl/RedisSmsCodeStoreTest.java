package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.exception.SmsCooldownException;
import dev.dahuangggg.ticketrush.service.SmsCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RedisSmsCodeStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisSmsCodeStore store = new RedisSmsCodeStore(redis, Duration.ofMinutes(5), 5);

    @Test
    void saveStoresCodeAndAttemptCounterWithTtl() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        store.save("13800138000", "123456");

        verify(redis).execute(
                any(RedisScript.class),
                eq(List.of(
                        RedisKeyRegistry.smsCodeKey("13800138000"),
                        RedisKeyRegistry.smsCooldownKey("13800138000"))),
                eq("123456|0"),
                eq("300000"),
                eq("60000"));
    }

    @Test
    void saveRejectsCooldownAtomically() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store.save("13800138000", "123456"))
                .isInstanceOf(SmsCooldownException.class);
    }

    @Test
    void verifyAndConsumeMapsAtomicScriptResult() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L, 1L, 2L, null);

        assertThat(store.verifyAndConsume("13800138000", "123456"))
                .isEqualTo(SmsCodeStore.VerificationResult.VERIFIED);
        assertThat(store.verifyAndConsume("13800138000", "000000"))
                .isEqualTo(SmsCodeStore.VerificationResult.INVALID);
        assertThat(store.verifyAndConsume("13800138000", "000000"))
                .isEqualTo(SmsCodeStore.VerificationResult.TOO_MANY_ATTEMPTS);
        assertThat(store.verifyAndConsume("13800138000", "123456"))
                .isEqualTo(SmsCodeStore.VerificationResult.INVALID);

        verify(redis, times(4)).execute(
                any(RedisScript.class),
                eq(List.of(RedisKeyRegistry.smsCodeKey("13800138000"))),
                anyString(),
                eq("5"));
    }
}
