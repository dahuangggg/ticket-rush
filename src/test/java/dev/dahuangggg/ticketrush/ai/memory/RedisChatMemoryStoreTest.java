package dev.dahuangggg.ticketrush.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisChatMemoryStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisChatMemoryStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new RedisChatMemoryStore(redis, Duration.ofDays(7));
    }

    @Test
    void getMessages_returnsEmptyListWhenAbsent() {
        when(ops.get("ai:chat:s1")).thenReturn(null);
        assertThat(store.getMessages("s1")).isEmpty();
    }

    @Test
    void updateMessages_serializesAndSetsTtl() {
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from("hello"));
        store.updateMessages("s1", msgs);
        verify(ops).set(eq("ai:chat:s1"), any(String.class), eq(Duration.ofDays(7)));
    }

    @Test
    void getMessages_roundtripsThroughUpdate() {
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"), AiMessage.from("hello"));
        store.updateMessages("s1", msgs);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("ai:chat:s1"), cap.capture(), any(Duration.class));
        when(ops.get("ai:chat:s1")).thenReturn(cap.getValue());

        List<ChatMessage> read = store.getMessages("s1");
        assertThat(read).hasSize(2);
        assertThat(read.get(0)).isInstanceOf(UserMessage.class);
        assertThat(read.get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void deleteMessages_deletesKey() {
        store.deleteMessages("s1");
        verify(redis).delete("ai:chat:s1");
    }
}
