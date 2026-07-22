package dev.dahuangggg.ticketrush.infrastructure.mq;

import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.ReservationOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis SKU registry 只是可重建的发现索引，不能成为 accepted Journal 的单点。
 */
@Component
public class ReservationOutboxDiscoveryRepair {

    private static final Logger log = LoggerFactory.getLogger(ReservationOutboxDiscoveryRepair.class);

    private final TicketSkuMapper ticketSkuMapper;
    private final ReservationOutbox outbox;

    public ReservationOutboxDiscoveryRepair(TicketSkuMapper ticketSkuMapper,
                                             ReservationOutbox outbox) {
        this.ticketSkuMapper = ticketSkuMapper;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString =
            "${ticket-rush.reservation-relay.discovery-repair-delay-ms:30000}")
    public void repair() {
        try {
            for (Long skuId : ticketSkuMapper.selectOpenedSkuIdsForOutboxDiscovery()) {
                try {
                    outbox.registerSku(skuId);
                } catch (RuntimeException e) {
                    log.warn("Failed to repair reservation outbox discovery for SKU; later SKUs continue: skuId={}",
                            skuId, e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to load durable SKU catalog for reservation outbox discovery repair", e);
        }
    }
}
