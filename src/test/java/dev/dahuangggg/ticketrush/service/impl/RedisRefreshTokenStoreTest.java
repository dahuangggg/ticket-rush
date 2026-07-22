package dev.dahuangggg.ticketrush.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisRefreshTokenStoreTest {

    @Test
    void issueStoresOnlyTokenDigestWithFixedTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisRefreshTokenStore store = new RedisRefreshTokenStore(redis, Duration.ofDays(14));

        String token = store.issue(7L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), eq("7"), eq(Duration.ofDays(14)));
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(key.getValue())
                .startsWith("auth:refresh-token:")
                .doesNotContain(token);
        assertThat(key.getValue().substring("auth:refresh-token:".length()))
                .matches("[0-9a-f]{64}");
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void lookupAndDeleteUseSameDigestKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisRefreshTokenStore store = new RedisRefreshTokenStore(redis, Duration.ofDays(14));

        String token = store.issue(7L);
        ArgumentCaptor<String> issuedKey = ArgumentCaptor.forClass(String.class);
        verify(values).set(issuedKey.capture(), eq("7"), any(Duration.class));
        when(values.get(issuedKey.getValue())).thenReturn("7");

        assertThat(store.getUserId(token)).isEqualTo(7L);
        store.delete(token);

        verify(values).get(issuedKey.getValue());
        verify(redis).delete(issuedKey.getValue());
    }
}
