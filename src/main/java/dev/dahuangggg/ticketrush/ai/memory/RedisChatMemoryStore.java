package dev.dahuangggg.ticketrush.ai.memory;

import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisChatMemoryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = RedisKeyRegistry.aiChatMemoryKey(memoryId.toString());
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) return List.of();
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = RedisKeyRegistry.aiChatMemoryKey(memoryId.toString());
        String json = ChatMessageSerializer.messagesToJson(messages);
        redis.opsForValue().set(key, json, ttl);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(RedisKeyRegistry.aiChatMemoryKey(memoryId.toString()));
    }
}
