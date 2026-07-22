package dev.dahuangggg.ticketrush.infrastructure.mq;

import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.ReservationOutbox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationOutboxDiscoveryRepairTest {

    @Test
    void rebuildsMissingRegistryFromDurableOpenedSkusAndIsolatesOneFailure() {
        TicketSkuMapper mapper = mock(TicketSkuMapper.class);
        ReservationOutbox outbox = mock(ReservationOutbox.class);
        when(mapper.selectOpenedSkuIdsForOutboxDiscovery())
                .thenReturn(List.of(3001L, 3002L, 3003L));
        doThrow(new IllegalStateException("one corrupt registry write"))
                .when(outbox).registerSku(3002L);

        new ReservationOutboxDiscoveryRepair(mapper, outbox).repair();

        verify(outbox).registerSku(3001L);
        verify(outbox).registerSku(3002L);
        verify(outbox).registerSku(3003L);
    }
}
