package dev.dahuangggg.ticketrush.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "ticketrush.ai")
public class AiProperties {

    private OpenAi openai = new OpenAi();
    private Chat chat = new Chat();

    @Data
    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model = "gpt-5.4";
        private Duration timeout = Duration.ofSeconds(30);
    }

    @Data
    public static class Chat {
        private int memoryMaxMessages = 20;
        private Duration memoryTtl = Duration.ofDays(7);
    }
}
