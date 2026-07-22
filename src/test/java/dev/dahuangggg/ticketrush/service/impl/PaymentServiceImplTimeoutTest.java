package dev.dahuangggg.ticketrush.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import dev.dahuangggg.ticketrush.service.TimeoutOrderProcessor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.stream.LongStream;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceImplTimeoutTest {

    @Test
    @SuppressWarnings("unchecked")
    void fullPoisonPageDoesNotStarveCandidateOnNextPage() {
        TicketOrderMapper mapper = mock(TicketOrderMapper.class);
        TimeoutOrderProcessor processor = mock(TimeoutOrderProcessor.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 21, 0, 0);
        List<TicketOrder> poisonPage = LongStream.rangeClosed(1, 100)
                .mapToObj(id -> candidate(id, createdAt.plusSeconds(id)))
                .toList();
        TicketOrder healthy = candidate(101L, createdAt.plusSeconds(101));
        when(mapper.selectTimeoutCandidates(any(Integer.class), any(), any(), any(), any(Integer.class)))
                .thenReturn(poisonPage, List.of(healthy));
        for (long id = 1; id <= 100; id++) {
            when(processor.timeout(id)).thenThrow(new IllegalStateException("poison row"));
        }
        when(processor.timeout(101L)).thenReturn(true);

        PaymentServiceImpl service = new PaymentServiceImpl(
                mapper,
                mock(InventoryReleaseService.class),
                mock(RushReservationLedger.class),
                mock(RushReservationStore.class),
                processor,
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));

        Logger logger = (Logger) LoggerFactory.getLogger(PaymentServiceImpl.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            service.cancelTimeoutOrders();
        } finally {
            logger.setLevel(previousLevel);
        }

        verify(processor, times(101)).timeout(any(Long.class));
        verify(processor).timeout(101L);
    }

    private static TicketOrder candidate(Long id, LocalDateTime createTime) {
        return TicketOrder.builder().id(id).createTime(createTime).build();
    }
}
