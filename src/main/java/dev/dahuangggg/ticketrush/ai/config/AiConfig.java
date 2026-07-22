package dev.dahuangggg.ticketrush.ai.config;

import dev.dahuangggg.ticketrush.ai.memory.RedisChatMemoryStore;
import dev.dahuangggg.ticketrush.ai.service.ChatAssistant;
import dev.dahuangggg.ticketrush.ai.tools.EventQueryTools;
import dev.dahuangggg.ticketrush.ai.tools.OrderQueryTools;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.http.HttpClient;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    public ChatModel chatModel(AiProperties props) {
        // 强制走 HTTP/1.1：JDK 自带 HttpClient 的 HTTP/2 实现和部分 OpenAI 兼容代理握手时会被 RST_STREAM。
        JdkHttpClientBuilder httpBuilder = new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1));
        return OpenAiChatModel.builder()
                .httpClientBuilder(httpBuilder)
                .baseUrl(props.getOpenai().getBaseUrl())
                .apiKey(props.getOpenai().getApiKey())
                .modelName(props.getOpenai().getModel())
                .timeout(props.getOpenai().getTimeout())
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(AiProperties props) {
        JdkHttpClientBuilder httpBuilder = new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1));
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(httpBuilder)
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

    /**
     * Production AI tool allowlist. Keep this collection explicit: prompt text is not an
     * authorization boundary, and no mutating business tool belongs here.
     */
    @Bean
    public ReadOnlyToolAllowlist aiReadOnlyTools(EventQueryTools eventTools, OrderQueryTools orderTools) {
        return new ReadOnlyToolAllowlist(List.of(eventTools, orderTools));
    }

    @Bean
    public ChatAssistant chatAssistant(ChatModel model,
                                       StreamingChatModel streamingModel,
                                       ChatMemoryStore store,
                                       AiProperties props,
                                       ReadOnlyToolAllowlist readOnlyTools) {
        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(props.getChat().getMemoryMaxMessages())
                .chatMemoryStore(store)
                .build();
        return AiServices.builder(ChatAssistant.class)
                .chatModel(model)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(provider)
                // 只注册查询工具。提醒、支付、取消和抢票都必须由用户在普通业务 API 中
                // 明确操作，避免模型把自然语言误判成有副作用的命令。
                .tools(readOnlyTools.tools())
                .build();
    }

    public record ReadOnlyToolAllowlist(List<Object> tools) {
        public ReadOnlyToolAllowlist {
            tools = List.copyOf(tools);
        }
    }
}
