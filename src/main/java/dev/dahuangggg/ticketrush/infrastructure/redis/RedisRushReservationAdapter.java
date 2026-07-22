package dev.dahuangggg.ticketrush.infrastructure.redis;

import dev.dahuangggg.ticketrush.domain.rush.ReleaseResult;
import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.service.ReservationOutbox;
import dev.dahuangggg.ticketrush.service.ReservationReleaseStore;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Redis Adapter：把 eligibility、扣库存、用户去重、Reservation Journal 和 Outbox
 * 收敛到同一个 Lua 原子操作中。
 */
@Component
public class RedisRushReservationAdapter
        implements RushReservationStore, ReservationOutbox, ReservationReleaseStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRushReservationAdapter.class);
    private static final long CLEANUP_GRACE_SECONDS = 7 * 24 * 60 * 60L;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> reserveScript;
    private final DefaultRedisScript<Long> transitionScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> quarantineScript;
    private final Clock clock;

    public RedisRushReservationAdapter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.reserveScript = script("lua/ticket_rush.lua", String.class);
        this.transitionScript = script("lua/reservation_transition.lua", Long.class);
        this.releaseScript = script("lua/ticket_rollback.lua", Long.class);
        this.quarantineScript = script("lua/reservation_outbox_quarantine.lua", Long.class);
    }

    @Override
    public boolean initializeSku(TicketSku sku, int availableStock) {
        Long skuId = sku.getId();
        long saleStart = sku.getSaleStartTime().atZone(clock.getZone()).toEpochSecond();
        long saleEnd = sku.getSaleEndTime().atZone(clock.getZone()).toEpochSecond();
        long cleanupAt = saleEnd + CLEANUP_GRACE_SECONDS;

        // 先注册再开放库存，保证 Relay 一定能发现后续产生的 outbox。
        registerSku(skuId);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("eventId", String.valueOf(sku.getEventId()));
        metadata.put("status", String.valueOf(sku.getStatus()));
        metadata.put("saleStart", String.valueOf(saleStart));
        metadata.put("saleEnd", String.valueOf(saleEnd));
        metadata.put("unitPrice", String.valueOf(sku.getPrice()));
        metadata.put("cleanupAt", String.valueOf(cleanupAt));
        String metadataKey = RedisKeyRegistry.skuMetadataKey(skuId);
        redisTemplate.opsForHash().putAll(metadataKey, metadata);

        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(
                RedisKeyRegistry.stockKey(skuId), String.valueOf(availableStock));
        return Boolean.TRUE.equals(initialized);
    }

    @Override
    public Integer getAvailableStock(Long skuId) {
        String value = redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(skuId));
        return value == null ? null : Integer.parseInt(value);
    }

    @Override
    public long getConsumedReservationCount(Long skuId) {
        Long size = redisTemplate.opsForSet().size(RedisKeyRegistry.orderUserKey(skuId));
        return size == null ? 0L : size;
    }

    @Override
    public boolean hasBuyerState(Long skuId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyRegistry.orderUserKey(skuId)));
    }

    @Override
    public ReservationDecision reserve(Long userId, Long eventId, Long skuId, Integer quantity,
                                       String reservationId, String idempotencyHash) {
        List<String> keys = List.of(
                RedisKeyRegistry.stockKey(skuId),
                RedisKeyRegistry.orderUserKey(skuId),
                RedisKeyRegistry.skuMetadataKey(skuId),
                RedisKeyRegistry.reservationKey(skuId, reservationId),
                RedisKeyRegistry.idempotencyKey(skuId, userId, idempotencyHash),
                RedisKeyRegistry.reservationOutboxKey(skuId)
        );
        String raw = redisTemplate.execute(
                reserveScript,
                keys,
                String.valueOf(userId),
                reservationId,
                String.valueOf(eventId),
                String.valueOf(skuId),
                String.valueOf(quantity)
        );
        if (raw == null) {
            throw new IllegalStateException("Reservation script returned null for skuId=" + skuId);
        }
        String[] parts = raw.split("\\|", 2);
        ReservationDecision.Type type = switch (parts[0]) {
            case "RESERVED" -> ReservationDecision.Type.RESERVED;
            case "EXISTING" -> ReservationDecision.Type.EXISTING;
            case "SOLD_OUT" -> ReservationDecision.Type.SOLD_OUT;
            case "DUPLICATE_USER" -> ReservationDecision.Type.DUPLICATE_USER;
            case "SKU_UNAVAILABLE" -> ReservationDecision.Type.SKU_UNAVAILABLE;
            case "CORRUPT_STATE" -> throw new IllegalStateException(
                    "Redis reservation state is corrupt for skuId=" + skuId);
            default -> throw new IllegalStateException("Unknown reservation script result: " + raw);
        };
        return new ReservationDecision(type, parts.length == 2 ? parts[1] : null);
    }

    @Override
    public Optional<ReservationSnapshot> find(String reservationId) {
        Long skuId = skuIdFromReservationId(reservationId);
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(RedisKeyRegistry.reservationKey(skuId, reservationId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReservationSnapshot(
                value(values, "reservationId"),
                longValue(values, "userId"),
                longValue(values, "eventId"),
                longValue(values, "skuId"),
                intValue(values, "quantity"),
                longValue(values, "unitPrice"),
                ReservationStatus.valueOf(value(values, "status")),
                nullableLong(values, "orderId"),
                Instant.ofEpochSecond(longValue(values, "createdAt")),
                nullableValue(values, "reason")
        ));
    }

    @Override
    public void markQueued(String reservationId) {
        transition(reservationId, "RESERVED", ReservationStatus.QUEUED, "", "");
    }

    @Override
    public void markOrderCreated(String reservationId, Long orderId) {
        transition(reservationId, "RESERVED,QUEUED", ReservationStatus.ORDER_CREATED,
                "orderId", String.valueOf(orderId));
    }

    @Override
    public void markPaid(String reservationId) {
        transition(reservationId, "ORDER_CREATED", ReservationStatus.PAID, "", "");
    }

    @Override
    public void markRejected(String reservationId, String reason) {
        transition(reservationId, "RESERVED,QUEUED", ReservationStatus.REJECTED,
                "reason", reason == null ? "" : reason);
    }

    @Override
    public void registerSku(Long skuId) {
        redisTemplate.opsForSet().add(
                RedisKeyRegistry.reservationOutboxSkuRegistryKey(), String.valueOf(skuId));
    }

    @Override
    public Set<Long> registeredSkuIds() {
        Set<String> values = redisTemplate.opsForSet()
                .members(RedisKeyRegistry.reservationOutboxSkuRegistryKey());
        Set<Long> result = new TreeSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            try {
                result.add(Long.valueOf(value));
            } catch (NumberFormatException e) {
                log.warn("Ignoring invalid SKU id in reservation outbox registry: {}", value);
            }
        }
        return result;
    }

    @Override
    public List<ReservationOutboxRecord> pending(Long skuId, int limit) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                RedisKeyRegistry.reservationOutboxKey(skuId),
                Range.unbounded(),
                Limit.limit().count(limit));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<ReservationOutboxRecord> result = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            try {
                Map<Object, Object> values = record.getValue();
                result.add(new ReservationOutboxRecord(
                        record.getId().getValue(),
                        value(values, "reservationId"),
                        longValue(values, "userId"),
                        longValue(values, "eventId"),
                        longValue(values, "skuId"),
                        intValue(values, "quantity"),
                        longValue(values, "unitPrice"),
                        longValue(values, "createdAt")
                ));
            } catch (RuntimeException e) {
                quarantine(skuId, record.getId().getValue(), e);
            }
        }
        return result;
    }

    @Override
    public void acknowledge(Long skuId, String streamId) {
        redisTemplate.opsForStream().delete(
                RedisKeyRegistry.reservationOutboxKey(skuId), RecordId.of(streamId));
    }

    private void quarantine(Long skuId, String streamId, RuntimeException parseFailure) {
        Long moved = redisTemplate.execute(
                quarantineScript,
                List.of(
                        RedisKeyRegistry.reservationOutboxKey(skuId),
                        RedisKeyRegistry.reservationOutboxQuarantineKey(skuId)
                ),
                streamId,
                parseFailure.getClass().getSimpleName()
        );
        if (moved == null) {
            throw new IllegalStateException(
                    "Reservation journal quarantine returned null: skuId=" + skuId
                            + " streamId=" + streamId,
                    parseFailure);
        }
        if (moved < 0) {
            throw new IllegalStateException(
                    "Reservation journal quarantine state is corrupt: skuId=" + skuId
                            + " streamId=" + streamId,
                    parseFailure);
        }
        if (moved == 0) {
            log.debug("Reservation journal entry was already moved by another relay: skuId={} streamId={}",
                    skuId, streamId);
            return;
        }
        log.error("Invalid reservation journal entry moved to quarantine: skuId={} streamId={}",
                skuId, streamId, parseFailure);
    }

    @Override
    public ReleaseResult release(String reservationId, Long skuId, Long userId) {
        Long result = redisTemplate.execute(
                releaseScript,
                List.of(
                        RedisKeyRegistry.stockKey(skuId),
                        RedisKeyRegistry.orderUserKey(skuId),
                        RedisKeyRegistry.reservationKey(skuId, reservationId)
                ),
                String.valueOf(userId)
        );
        if (result == null) {
            throw new IllegalStateException("Reservation release returned null: " + reservationId);
        }
        return switch (result.intValue()) {
            case 0 -> ReleaseResult.RELEASED;
            case 1 -> ReleaseResult.ALREADY_RELEASED;
            case 2 -> ReleaseResult.RESERVATION_NOT_FOUND;
            case 3 -> throw new IllegalStateException("Paid reservation cannot be released: " + reservationId);
            case 4 -> throw new IllegalStateException(
                    "Inventory stock key is missing; refusing an unsafe increment: " + reservationId);
            case 5 -> throw new IllegalStateException(
                    "Redis reservation state is corrupt; refusing a partial release: " + reservationId);
            default -> throw new IllegalStateException("Unknown reservation release result: " + result);
        };
    }

    private void transition(String reservationId, String allowed, ReservationStatus target,
                            String extraField, String extraValue) {
        Long skuId = skuIdFromReservationId(reservationId);
        Long result = redisTemplate.execute(
                transitionScript,
                List.of(RedisKeyRegistry.reservationKey(skuId, reservationId)),
                allowed,
                target.name(),
                extraField,
                extraValue
        );
        if (result == null) {
            throw new IllegalStateException("Reservation transition returned null: " + reservationId);
        }
        if (result == 2L) {
            log.warn("Reservation missing in Redis during transition: id={} target={}", reservationId, target);
        } else if (result == 3L) {
            log.info("Reservation transition ignored: id={} target={}", reservationId, target);
        }
    }

    public static Long skuIdFromReservationId(String reservationId) {
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId must not be null");
        }
        int separator = reservationId.indexOf('-');
        if (separator <= 0) {
            throw new IllegalArgumentException("Invalid reservationId: " + reservationId);
        }
        try {
            return Long.valueOf(reservationId.substring(0, separator));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid reservationId: " + reservationId, e);
        }
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Reservation field missing: " + key);
        }
        return String.valueOf(value);
    }

    private static String nullableValue(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Map<Object, Object> values, String key) {
        return Long.valueOf(value(values, key));
    }

    private static Long nullableLong(Map<Object, Object> values, String key) {
        String value = nullableValue(values, key);
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private static Integer intValue(Map<Object, Object> values, String key) {
        return Integer.valueOf(value(values, key));
    }
}
