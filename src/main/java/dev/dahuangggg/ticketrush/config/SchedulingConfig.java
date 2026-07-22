package dev.dahuangggg.ticketrush.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 启用 Spring 定时任务支持（@Scheduled 依赖此注解才会生效）。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public Clock clock(@Value("${ticket-rush.business-zone:Asia/Shanghai}") String businessZone) {
        return Clock.system(ZoneId.of(businessZone));
    }
}

/** Fast tests use the test profile and do not start background jobs. */
@Configuration
@ConditionalOnProperty(name = "ticket-rush.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class ScheduledTasksConfiguration {
}
