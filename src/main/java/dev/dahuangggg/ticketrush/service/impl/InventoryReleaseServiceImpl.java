package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.domain.rush.ReleaseResult;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.InventoryReleaseIntent;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.infrastructure.observability.TicketRushMetrics;
import dev.dahuangggg.ticketrush.mapper.InventoryReleaseIntentMapper;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.ReservationReleaseStore;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.SkuInventoryMutex;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Service
public class InventoryReleaseServiceImpl implements InventoryReleaseService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReleaseServiceImpl.class);
    private static final int MAX_RETRY_COUNT = 10;

    private final InventoryReleaseIntentMapper mapper;
    private final ReservationReleaseStore releaseStore;
    private final RushReservationLedger ledger;
    private final RedissonClient redissonClient;
    private final TicketRushMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final SkuInventoryMutex skuInventoryMutex;

    public InventoryReleaseServiceImpl(InventoryReleaseIntentMapper mapper,
                                       ReservationReleaseStore releaseStore,
                                       RushReservationLedger ledger,
                                       Optional<RedissonClient> redissonClient,
                                       TicketRushMetrics metrics,
                                       PlatformTransactionManager transactionManager,
                                       SkuInventoryMutex skuInventoryMutex) {
        this.mapper = mapper;
        this.releaseStore = releaseStore;
        this.ledger = ledger;
        this.redissonClient = redissonClient.orElse(null);
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.skuInventoryMutex = skuInventoryMutex;
    }

    @Override
    @Transactional
    public boolean scheduleBeforeOrder(
            String reservationId, Long userId, Long skuId, String reason) {
        return schedule(reservationId, null, userId, skuId, reason, true);
    }

    @Override
    @Transactional
    public void scheduleAfterOrder(
            String reservationId, Long orderId, Long userId, Long skuId, String reason) {
        if (!schedule(reservationId, orderId, userId, skuId, reason, false)) {
            throw new IllegalStateException(
                    "Reservation is no longer releasable after order: " + reservationId);
        }
    }

    private boolean schedule(String reservationId, Long orderId, Long userId, Long skuId,
                             String reason, boolean beforeOrder) {
        // 条件 UPDATE 是 DLT 释放与并发创单之间的线性化点。
        // 创单前只能从 RESERVED/QUEUED 转换；取消/超时只能从 ORDER_CREATED 转换。
        boolean transitioned = beforeOrder
                ? ledger.tryMarkReleasePendingBeforeOrder(reservationId, reason)
                : ledger.tryMarkReleasePendingAfterOrder(reservationId, reason);
        if (!transitioned) {
            return hasIntent(reservationId);
        }
        try {
            mapper.insert(InventoryReleaseIntent.builder()
                    .reservationId(reservationId)
                    .orderId(orderId)
                    .userId(userId)
                    .skuId(skuId)
                    .reason(truncate(reason))
                    .status(InventoryReleaseIntent.STATUS_PENDING)
                    .retryCount(0)
                    .build());
        } catch (DuplicateKeyException ignored) {
            // 并发取消/超时只有一个意图需要存活，唯一键是最终保护。
        }
        return true;
    }

    @Override
    @Scheduled(fixedDelayString = "${ticket-rush.release-worker.fixed-delay-ms:1000}")
    public void processPending() {
        if (redissonClient == null) {
            doProcessPending();
            return;
        }
        RLock lock = redissonClient.getLock(RedisKeyRegistry.releaseRetryLock());
        if (!lock.tryLock()) {
            return;
        }
        try {
            doProcessPending();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public boolean requeueFailed(String reservationId) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<InventoryReleaseIntent>()
                .eq(InventoryReleaseIntent::getReservationId, reservationId)
                .eq(InventoryReleaseIntent::getStatus, InventoryReleaseIntent.STATUS_FAILED)
                .set(InventoryReleaseIntent::getStatus, InventoryReleaseIntent.STATUS_PENDING)
                .set(InventoryReleaseIntent::getRetryCount, 0)
                .set(InventoryReleaseIntent::getErrorMessage, null));
        return updated == 1;
    }

    @Override
    public boolean hasUnsettledForSku(Long skuId) {
        return mapper.countUnsettledForSku(skuId) > 0;
    }

    private void doProcessPending() {
        List<InventoryReleaseIntent> intents = mapper.selectList(
                new LambdaQueryWrapper<InventoryReleaseIntent>()
                        .eq(InventoryReleaseIntent::getStatus, InventoryReleaseIntent.STATUS_PENDING)
                        .lt(InventoryReleaseIntent::getRetryCount, MAX_RETRY_COUNT)
                        .orderByAsc(InventoryReleaseIntent::getId)
                        .last("LIMIT 100"));
        for (InventoryReleaseIntent intent : intents) {
            processOne(intent);
        }
    }

    private void processOne(InventoryReleaseIntent intent) {
        try {
            skuInventoryMutex.execute(intent.getSkuId(), () -> {
                releaseAndCommit(intent);
                return null;
            });
        } catch (RuntimeException e) {
            mapper.recordFailure(intent.getId(), truncate(e.getMessage()),
                    MAX_RETRY_COUNT - 1, MAX_RETRY_COUNT);
            InventoryReleaseIntent current = mapper.selectById(intent.getId());
            boolean exhausted = current != null
                    && current.getStatus() == InventoryReleaseIntent.STATUS_FAILED;
            int currentRetry = current == null ? intent.getRetryCount() : current.getRetryCount();
            metrics.recordRelease(exhausted
                    ? TicketRushMetrics.ReleaseOutcome.FAILED
                    : TicketRushMetrics.ReleaseOutcome.RETRY);
            log.warn("Reservation release failed: reservationId={} attempt={}",
                    intent.getReservationId(), currentRetry, e);
        }
    }

    private void releaseAndCommit(InventoryReleaseIntent intent) {
        ReservationStatus durableStatus = ledger.find(intent.getReservationId())
                    .map(reservation -> ReservationStatus.fromCode(reservation.getStatus()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Reservation missing in ledger: " + intent.getReservationId()));
        if (durableStatus != ReservationStatus.RELEASE_PENDING) {
            throw new IllegalStateException(
                    "Reservation is not release-pending: " + intent.getReservationId()
                            + " status=" + durableStatus);
        }
        ReleaseResult result = releaseStore.release(
                intent.getReservationId(), intent.getSkuId(), intent.getUserId());
        if (result == ReleaseResult.RESERVATION_NOT_FOUND) {
            throw new IllegalStateException("Reservation missing in Redis: " + intent.getReservationId());
        }
        // 该 SKU 的恢复锁横跨 Redis 副作用和数据库提交。stock key 丢失时，重建流程要么
        // 看见未结 intent 并失败关闭，要么等待本事务提交后再读取一致状态。
        transactionTemplate.executeWithoutResult(status -> {
            mapper.update(null, new LambdaUpdateWrapper<InventoryReleaseIntent>()
                    .eq(InventoryReleaseIntent::getId, intent.getId())
                    .eq(InventoryReleaseIntent::getStatus, InventoryReleaseIntent.STATUS_PENDING)
                    .set(InventoryReleaseIntent::getStatus, InventoryReleaseIntent.STATUS_SUCCESS)
                    .set(InventoryReleaseIntent::getErrorMessage, null));
            ledger.markReleased(intent.getReservationId());
        });
        metrics.recordRelease(result == ReleaseResult.RELEASED
                ? TicketRushMetrics.ReleaseOutcome.RELEASED
                : TicketRushMetrics.ReleaseOutcome.ALREADY_RELEASED);
    }

    private String truncate(String value) {
        return value == null || value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private boolean hasIntent(String reservationId) {
        return mapper.selectCount(new LambdaQueryWrapper<InventoryReleaseIntent>()
                .eq(InventoryReleaseIntent::getReservationId, reservationId)) > 0;
    }
}
