package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.entity.TicketOrderMsg;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMsgMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息追踪记录服务，使用独立事务保证幂等标记不被外层事务回滚。
 */
@Service
public class TicketOrderMsgService {

    private final TicketOrderMsgMapper ticketOrderMsgMapper;

    public TicketOrderMsgService(TicketOrderMsgMapper ticketOrderMsgMapper) {
        this.ticketOrderMsgMapper = ticketOrderMsgMapper;
    }

    /**
     * 在独立事务中插入消息追踪记录。
     * REQUIRES_NEW 确保即使调用方事务回滚，此记录仍然持久化，
     * 防止 Kafka 重试时因幂等标记丢失而导致无限重试循环。
     *
     * @return 插入成功的记录（含自增 ID），如果 messageId 已存在则返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TicketOrderMsg insertIfAbsent(TicketOrderMsg orderMsg) {
        try {
            ticketOrderMsgMapper.insert(orderMsg);
            return orderMsg;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long id) {
        ticketOrderMsgMapper.updateById(
                TicketOrderMsg.builder().id(id).status(TicketOrderMsg.STATUS_SUCCESS).build()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id, String errorMessage) {
        ticketOrderMsgMapper.updateById(
                TicketOrderMsg.builder().id(id).status(TicketOrderMsg.STATUS_FAILED)
                        .errorMessage(errorMessage).build()
        );
    }
}
