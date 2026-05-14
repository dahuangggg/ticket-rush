package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import dev.dahuangggg.ticketrush.exception.TicketSkuUnavailableException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushProducer;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.RedisRollbackService;
import dev.dahuangggg.ticketrush.service.TicketRushService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TicketRushServiceImpl implements TicketRushService {

    // Redis key 前缀，与 StockInitServiceImpl.STOCK_KEY 保持一致
    static final String STOCK_KEY_PREFIX      = "ticket:stock:";
    static final String ORDER_USER_KEY_PREFIX = "ticket:order:user:";

    private final StringRedisTemplate redisTemplate;
    private final TicketRushProducer producer;
    private final TicketSkuMapper ticketSkuMapper;
    private final RedisRollbackService redisRollbackService;
    private final DefaultRedisScript<Long> rushScript;

    public TicketRushServiceImpl(StringRedisTemplate redisTemplate,
                                 TicketRushProducer producer,
                                 TicketSkuMapper ticketSkuMapper,
                                 RedisRollbackService redisRollbackService) {
        this.redisTemplate = redisTemplate;
        this.producer = producer;
        this.ticketSkuMapper = ticketSkuMapper;
        this.redisRollbackService = redisRollbackService;
        // DefaultRedisScript 在首次 execute 时发送 EVAL 并由 Spring Data Redis 缓存 SHA，
        // 后续调用自动切换为 EVALSHA，不需要手动管理脚本 SHA。
        this.rushScript = new DefaultRedisScript<>();
        this.rushScript.setLocation(new ClassPathResource("lua/ticket_rush.lua"));
        this.rushScript.setResultType(Long.class);
    }

    @Override
    public TicketRushResponse rush(Long userId, TicketRushRequest request) {
        Long skuId = request.skuId();
        validateSkuAvailable(request);

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
        try {
            producer.send(new TicketRushMessage(
                    UUID.randomUUID().toString(),
                    userId,
                    request.eventId(),
                    skuId,
                    request.quantity()
            ));
        } catch (RuntimeException e) {
            redisRollbackService.rollback(skuId, userId);
            throw e;
        }

        return new TicketRushResponse("QUEUED");
    }

    private void validateSkuAvailable(TicketRushRequest request) {
        Long skuId = request.skuId();
        TicketSku sku = ticketSkuMapper.selectById(skuId);
        if (sku == null
                || !request.eventId().equals(sku.getEventId())
                || !Integer.valueOf(TicketSku.STATUS_ON_SALE).equals(sku.getStatus())) {
            throw new TicketSkuUnavailableException(skuId);
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(sku.getSaleStartTime()) || now.isAfter(sku.getSaleEndTime())) {
            throw new TicketSkuUnavailableException(skuId);
        }
    }
}
