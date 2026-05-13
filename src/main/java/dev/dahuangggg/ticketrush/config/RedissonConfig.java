package dev.dahuangggg.ticketrush.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(name = "ticket-rush.redisson.enabled", havingValue = "true")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(Environment environment) {
        String host = environment.getProperty("spring.data.redis.host", "127.0.0.1");
        int port = environment.getProperty("spring.data.redis.port", Integer.class, 6379);
        int database = environment.getProperty("spring.data.redis.database", Integer.class, 0);
        String password = environment.getProperty("spring.data.redis.password", "");

        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(16);

        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }

        return Redisson.create(config);
    }
}
