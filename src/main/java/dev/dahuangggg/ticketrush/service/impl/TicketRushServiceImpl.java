package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushProducer;
import dev.dahuangggg.ticketrush.service.TicketRushService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TicketRushServiceImpl implements TicketRushService {

    // Redis key 前缀，与 StockInitServiceImpl.STOCK_KEY 和 AGENTS.md 保持一致
    static final String STOCK_KEY_PREFIX      = "ticket:stock:";
    static final String ORDER_USER_KEY_PREFIX = "ticket:order:user:";

    private final StringRedisTemplate redisTemplate;
    private final TicketRushProducer producer;
    private final DefaultRedisScript<Long> rushScript;

    public TicketRushServiceImpl(StringRedisTemplate redisTemplate,
                                 TicketRushProducer producer) {
        this.redisTemplate = redisTemplate;
        this.producer = producer;
        // DefaultRedisScript 在首次 execute 时发送 EVAL 并由 Spring Data Redis 缓存 SHA，
        // 后续调用自动切换为 EVALSHA，不需要手动管理脚本 SHA。
        this.rushScript = new DefaultRedisScript<>();
        this.rushScript.setLocation(new ClassPathResource("lua/ticket_rush.lua"));
        this.rushScript.setResultType(Long.class);
    }

    @Override
    public TicketRushResponse rush(Long userId, TicketRushRequest request) {
        Long skuId = request.skuId();
        List<String> keys = List.of(
                STOCK_KEY_PREFIX + skuId,
                ORDER_USER_KEY_PREFIX + skuId
        );

        Long result = redisTemplate.execute(rushScript, keys, String.valueOf(userId));

        if (Long.valueOf(1L).equals(result)) {
            throw new SoldOutException(skuId);
        }
        if (Long.valueOf(2L).equals(result)) {
            throw new DuplicateOrderException(skuId);
        }

        // Lua 返回 0：扣库存成功，发送 Kafka 消息触发异步创单
        producer.send(new TicketRushMessage(
                UUID.randomUUID().toString(),
                userId,
                request.eventId(),
                skuId,
                request.quantity()
        ));

        return new TicketRushResponse("QUEUED");
    }
}
