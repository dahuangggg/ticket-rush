package dev.dahuangggg.ticketrush.ai.service;

import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiAssistantService {

    private final ChatAssistant assistant;
    private final StringRedisTemplate redis;

    public AiAssistantService(ChatAssistant assistant, StringRedisTemplate redis) {
        this.assistant = assistant;
        this.redis = redis;
    }

    /** 非流式调用。LangChain4j 内部驱动 tool-call 循环并返回最终文本。 */
    public String chat(String sessionId, String message) {
        return assistant.chat(sessionId, message);
    }

    /** 读会话历史（直接读 Redis）。 */
    public List<ChatMessage> history(String sessionId) {
        String json = redis.opsForValue().get(RedisKeyRegistry.aiChatMemoryKey(sessionId));
        if (json == null || json.isBlank()) return List.of();
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    public void clear(String sessionId) {
        redis.delete(RedisKeyRegistry.aiChatMemoryKey(sessionId));
    }
}
