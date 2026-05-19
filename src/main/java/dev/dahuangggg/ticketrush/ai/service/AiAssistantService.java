package dev.dahuangggg.ticketrush.ai.service;

import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.service.TokenStream;
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
    public String chat(Long userId, String sessionId, String message) {
        return assistant.chat(memoryId(userId, sessionId), message);
    }

    /** 流式调用。LangChain4j 负责 tool-call 循环，前端通过 SSE 逐段接收最终回答。 */
    public TokenStream stream(Long userId, String sessionId, String message) {
        return assistant.stream(memoryId(userId, sessionId), message);
    }

    /** 读会话历史（直接读 Redis）。 */
    public List<ChatMessage> history(Long userId, String sessionId) {
        String json = redis.opsForValue().get(RedisKeyRegistry.aiChatMemoryKey(memoryId(userId, sessionId)));
        if (json == null || json.isBlank()) return List.of();
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    public void clear(Long userId, String sessionId) {
        redis.delete(RedisKeyRegistry.aiChatMemoryKey(memoryId(userId, sessionId)));
    }

    /**
     * Namespace the user-supplied sessionId by userId so one user cannot read or wipe
     * another user's chat memory by guessing/colliding the sessionId.
     */
    private static String memoryId(Long userId, String sessionId) {
        return "u" + userId + ":" + sessionId;
    }
}
