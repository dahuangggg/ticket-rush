package dev.dahuangggg.ticketrush.ai.config;

import dev.dahuangggg.ticketrush.ai.memory.RedisChatMemoryStore;
import dev.dahuangggg.ticketrush.ai.service.ChatAssistant;
import dev.dahuangggg.ticketrush.ai.tools.EventQueryTools;
import dev.dahuangggg.ticketrush.ai.tools.OrderQueryTools;
import dev.dahuangggg.ticketrush.ai.tools.ReminderTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    public ChatModel chatModel(AiProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.getOpenai().getBaseUrl())
                .apiKey(props.getOpenai().getApiKey())
                .modelName(props.getOpenai().getModel())
                .timeout(props.getOpenai().getTimeout())
                .build();
    }

    @Bean
    public ChatMemoryStore chatMemoryStore(StringRedisTemplate redis, AiProperties props) {
        return new RedisChatMemoryStore(redis, props.getChat().getMemoryTtl());
    }

    @Bean
    public ChatAssistant chatAssistant(ChatModel model,
                                       ChatMemoryStore store,
                                       AiProperties props,
                                       EventQueryTools eventTools,
                                       OrderQueryTools orderTools,
                                       ReminderTools reminderTools) {
        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(props.getChat().getMemoryMaxMessages())
                .chatMemoryStore(store)
                .build();
        return AiServices.builder(ChatAssistant.class)
                .chatModel(model)
                .chatMemoryProvider(provider)
                .tools(eventTools, orderTools, reminderTools)
                .build();
    }
}
