package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.security.LoginUser;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderQueryToolsTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void invalidStatusReturnsExplicitErrorInsteadOfAllOrders() {
        OrderService orders = mock(OrderService.class);
        when(orders.listByUser(7L)).thenReturn(List.of(order(1L, 0), order(2L, 1)));
        UserContext.set(new LoginUser(7L, "13800138000", "user"));

        var result = new OrderQueryTools(orders).getMyOrders("UNKNOWN");

        assertThat(result.ok()).isFalse();
        assertThat(result.orders()).isEmpty();
        assertThat(result.error()).contains("PENDING_PAY");
        verifyNoInteractions(orders);
    }

    @Test
    void filtersUsingNamedDomainStatus() {
        OrderService orders = mock(OrderService.class);
        when(orders.listByUser(7L)).thenReturn(List.of(order(1L, 0), order(2L, 1)));
        UserContext.set(new LoginUser(7L, "13800138000", "user"));

        var result = new OrderQueryTools(orders).getMyOrders("PAID");

        assertThat(result.ok()).isTrue();
        assertThat(result.orders()).extracting(OrderDTO::id).containsExactly(2L);
    }

    private static OrderDTO order(Long id, int status) {
        return new OrderDTO(id, "order-" + id, 10L, 20L, 1, 58_000L,
                status, null, null, null);
    }
}
