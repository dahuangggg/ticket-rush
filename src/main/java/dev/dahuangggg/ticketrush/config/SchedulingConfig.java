package dev.dahuangggg.ticketrush.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 定时任务支持（@Scheduled 依赖此注解才会生效）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
