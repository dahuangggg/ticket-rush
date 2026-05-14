package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketRollbackTask;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketRollbackTaskMapper;
import dev.dahuangggg.ticketrush.service.PaymentService;
import dev.dahuangggg.ticketrush.service.RedisRollbackService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 支付服务实现：处理支付、取消和超时扫描，均使用 CAS（WHERE status=0）防并发。
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    // 待支付超时阈值（分钟）
    private static final int TIMEOUT_MINUTES = 15;
    private static final int MAX_ROLLBACK_RETRY_COUNT = 10;
    private static final String ROLLBACK_RETRY_LOCK_KEY = "lock:ticket:rollback:retry";

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketRollbackTaskMapper ticketRollbackTaskMapper;
    private final RedisRollbackService redisRollbackService;
    private final RedissonClient redissonClient;

    public PaymentServiceImpl(TicketOrderMapper ticketOrderMapper,
                              TicketRollbackTaskMapper ticketRollbackTaskMapper,
                              RedisRollbackService redisRollbackService,
                              Optional<RedissonClient> redissonClient) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.ticketRollbackTaskMapper = ticketRollbackTaskMapper;
        this.redisRollbackService = redisRollbackService;
        this.redissonClient = redissonClient.orElse(null);
    }

    @Override
    @Transactional
    public void pay(Long orderId, Long userId) {
        // 归属校验：订单不存在或不属于当前用户，统一返回 404
        TicketOrder order = ticketOrderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderId);
        }

        // CAS 更新：只有 status=0 时才能支付，避免并发重复支付
        int updated = ticketOrderMapper.update(
                null,
                new LambdaUpdateWrapper<TicketOrder>()
                        .eq(TicketOrder::getId, orderId)
                        .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                        .set(TicketOrder::getStatus, TicketOrder.STATUS_PAID)
                        .set(TicketOrder::getPayTime, LocalDateTime.now())
        );

        // updated=0 说明状态已不是待支付（被并发请求或超时任务抢先处理）
        if (updated == 0) {
            throw new OrderNotPendingException(orderId);
        }
        log.info("订单支付成功: orderId={} userId={}", orderId, userId);
    }

    @Override
    @Transactional
    public void cancel(Long orderId, Long userId) {
        // 归属校验：订单不存在或不属于当前用户，统一返回 404
        TicketOrder order = ticketOrderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderId);
        }

        // CAS 更新：只有 status=0 时才能取消
        int updated = ticketOrderMapper.update(
                null,
                new LambdaUpdateWrapper<TicketOrder>()
                        .eq(TicketOrder::getId, orderId)
                        .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                        .set(TicketOrder::getStatus, TicketOrder.STATUS_CANCELED)
                        .set(TicketOrder::getCancelTime, LocalDateTime.now())
        );

        if (updated == 0) {
            throw new OrderNotPendingException(orderId);
        }

        // 回滚 Redis：归还库存 + 移除用户抢购记录，使该用户可以再次抢票
        tryRollbackOrRecord(order.getId(), order.getSkuId(), userId);
        log.info("订单取消成功: orderId={} userId={}", orderId, userId);
    }

    @Override
    @Scheduled(fixedDelay = 60_000)
    public void cancelTimeoutOrders() {
        // 查询所有超时未支付的订单（create_time < 15 分钟前且 status=0）
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<TicketOrder> timeoutOrders = ticketOrderMapper.selectList(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                        .lt(TicketOrder::getCreateTime, timeout)
        );

        for (TicketOrder order : timeoutOrders) {
            // CAS 更新：若并发取消/支付已处理，updated=0 则跳过
            int updated = ticketOrderMapper.update(
                    null,
                    new LambdaUpdateWrapper<TicketOrder>()
                            .eq(TicketOrder::getId, order.getId())
                            .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                            .set(TicketOrder::getStatus, TicketOrder.STATUS_TIMEOUT)
                            .set(TicketOrder::getCancelTime, LocalDateTime.now())
            );
            if (updated > 0) {
                tryRollbackOrRecord(order.getId(), order.getSkuId(), order.getUserId());
                log.info("订单超时取消: orderId={} userId={}", order.getId(), order.getUserId());
            }
        }
    }

    @Override
    @Scheduled(fixedDelay = 30_000)
    public void retryRollbackTasks() {
        if (redissonClient != null) {
            RLock lock = redissonClient.getLock(ROLLBACK_RETRY_LOCK_KEY);
            if (!lock.tryLock()) {
                return;
            }
            try {
                doRetryRollbackTasks();
            } finally {
                lock.unlock();
            }
        } else {
            doRetryRollbackTasks();
        }
    }

    private void doRetryRollbackTasks() {
        List<TicketRollbackTask> tasks = ticketRollbackTaskMapper.selectList(
                new LambdaQueryWrapper<TicketRollbackTask>()
                        .eq(TicketRollbackTask::getStatus, TicketRollbackTask.STATUS_PENDING)
                        .lt(TicketRollbackTask::getRetryCount, MAX_ROLLBACK_RETRY_COUNT)
                        .last("LIMIT 100")
        );

        for (TicketRollbackTask task : tasks) {
            try {
                redisRollbackService.rollback(task.getSkuId(), task.getUserId());
                ticketRollbackTaskMapper.update(
                        null,
                        new LambdaUpdateWrapper<TicketRollbackTask>()
                                .eq(TicketRollbackTask::getId, task.getId())
                                .eq(TicketRollbackTask::getStatus, TicketRollbackTask.STATUS_PENDING)
                                .set(TicketRollbackTask::getStatus, TicketRollbackTask.STATUS_SUCCESS)
                                .set(TicketRollbackTask::getErrorMessage, null)
                );
            } catch (Exception e) {
                int nextRetryCount = task.getRetryCount() + 1;
                LambdaUpdateWrapper<TicketRollbackTask> wrapper = new LambdaUpdateWrapper<TicketRollbackTask>()
                        .eq(TicketRollbackTask::getId, task.getId())
                        .eq(TicketRollbackTask::getStatus, TicketRollbackTask.STATUS_PENDING)
                        .setSql("retry_count = retry_count + 1")
                        .set(TicketRollbackTask::getErrorMessage, truncate(e.getMessage()));
                if (nextRetryCount >= MAX_ROLLBACK_RETRY_COUNT) {
                    wrapper.set(TicketRollbackTask::getStatus, TicketRollbackTask.STATUS_FAILED);
                }
                ticketRollbackTaskMapper.update(null, wrapper);
            }
        }
    }

    private void tryRollbackOrRecord(Long orderId, Long skuId, Long userId) {
        try {
            redisRollbackService.rollback(skuId, userId);
        } catch (Exception e) {
            ticketRollbackTaskMapper.insert(TicketRollbackTask.builder()
                    .orderId(orderId)
                    .userId(userId)
                    .skuId(skuId)
                    .status(TicketRollbackTask.STATUS_PENDING)
                    .retryCount(0)
                    .errorMessage(truncate(e.getMessage()))
                    .build());
            log.error("Redis 回滚失败，已写入补偿任务: orderId={} userId={} skuId={}", orderId, userId, skuId, e);
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }
}
