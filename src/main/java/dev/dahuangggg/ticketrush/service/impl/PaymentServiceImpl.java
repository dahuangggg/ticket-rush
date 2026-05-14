package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付服务实现：处理支付、取消和超时扫描，均使用 CAS（WHERE status=0）防并发。
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    // Redis key 前缀，与 TicketRushServiceImpl 保持一致
    private static final String STOCK_KEY_PREFIX      = "ticket:stock:";
    private static final String ORDER_USER_KEY_PREFIX = "ticket:order:user:";

    // 待支付超时阈值（分钟）
    private static final int TIMEOUT_MINUTES = 15;

    private final TicketOrderMapper ticketOrderMapper;
    private final StringRedisTemplate redisTemplate;

    public PaymentServiceImpl(TicketOrderMapper ticketOrderMapper,
                              StringRedisTemplate redisTemplate) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.redisTemplate = redisTemplate;
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
                        .eq(TicketOrder::getStatus, 0)
                        .set(TicketOrder::getStatus, 1)
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
                        .eq(TicketOrder::getStatus, 0)
                        .set(TicketOrder::getStatus, 2)
                        .set(TicketOrder::getCancelTime, LocalDateTime.now())
        );

        if (updated == 0) {
            throw new OrderNotPendingException(orderId);
        }

        // 回滚 Redis：归还库存 + 移除用户抢购记录，使该用户可以再次抢票
        rollbackRedis(order.getSkuId(), userId);
        log.info("订单取消成功: orderId={} userId={}", orderId, userId);
    }

    @Override
    @Scheduled(fixedDelay = 60_000)
    public void cancelTimeoutOrders() {
        // 查询所有超时未支付的订单（create_time < 15 分钟前且 status=0）
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<TicketOrder> timeoutOrders = ticketOrderMapper.selectList(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getStatus, 0)
                        .lt(TicketOrder::getCreateTime, timeout)
        );

        for (TicketOrder order : timeoutOrders) {
            // CAS 更新：若并发取消/支付已处理，updated=0 则跳过
            int updated = ticketOrderMapper.update(
                    null,
                    new LambdaUpdateWrapper<TicketOrder>()
                            .eq(TicketOrder::getId, order.getId())
                            .eq(TicketOrder::getStatus, 0)
                            .set(TicketOrder::getStatus, 3)
                            .set(TicketOrder::getCancelTime, LocalDateTime.now())
            );
            if (updated > 0) {
                rollbackRedis(order.getSkuId(), order.getUserId());
                log.info("订单超时取消: orderId={} userId={}", order.getId(), order.getUserId());
            }
        }
    }

    /**
     * 回滚 Redis：归还库存计数 + 从用户抢购 Set 中移除，使该用户可以重新抢票。
     *
     * 与 TicketRushServiceImpl 中 Lua 脚本操作的 key 对应：
     *   INCR  ticket:stock:{skuId}
     *   SREM  ticket:order:user:{skuId}  {userId}
     */
    private void rollbackRedis(Long skuId, Long userId) {
        redisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + skuId);
        redisTemplate.opsForSet().remove(ORDER_USER_KEY_PREFIX + skuId, String.valueOf(userId));
    }
}
