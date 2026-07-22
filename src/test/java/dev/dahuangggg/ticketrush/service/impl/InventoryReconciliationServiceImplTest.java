package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.mapper.RushReservationMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReconciliationServiceImplTest {

    @Test
    void missingStockRebuildIncludesReservationThatHasNotReachedMysqlYet() {
        TicketSku sku = TicketSku.builder().id(3001L).stock(1).build();
        TicketSkuMapper skuMapper = proxy(TicketSkuMapper.class, (method, args) ->
                method.getName().equals("selectById") ? sku : defaultValue(method.getReturnType()));
        RushReservationMapper reservationMapper = proxy(RushReservationMapper.class, (method, args) ->
                method.getName().equals("sumHeldQuantity") ? 0L : defaultValue(method.getReturnType()));
        TicketOrderMapper orderMapper = proxy(TicketOrderMapper.class, (method, args) ->
                method.getName().equals("sumActiveQuantity") ? 0L : defaultValue(method.getReturnType()));
        RushReservationStore store = new CountingReservationStore(1L, null);

        var result = new InventoryReconciliationServiceImpl(
                skuMapper, orderMapper, reservationMapper, store).inspect(3001L);

        assertThat(result.redisConsumedReservations()).isOne();
        assertThat(result.expectedAvailableStock()).isZero();
        assertThat(result.inventoryConserved()).isFalse();
        assertThat(result.ledgerCaughtUp()).isFalse();
        assertThat(result.balanced()).isFalse();
    }

    @Test
    void conservedRedisDoesNotHideLedgerLag() {
        TicketSku sku = TicketSku.builder().id(3001L).stock(100).build();
        TicketSkuMapper skuMapper = proxy(TicketSkuMapper.class, (method, args) ->
                method.getName().equals("selectById") ? sku : defaultValue(method.getReturnType()));
        RushReservationMapper reservationMapper = proxy(RushReservationMapper.class, (method, args) ->
                method.getName().equals("sumHeldQuantity") ? 0L : defaultValue(method.getReturnType()));
        TicketOrderMapper orderMapper = proxy(TicketOrderMapper.class, (method, args) ->
                method.getName().equals("sumActiveQuantity") ? 0L : defaultValue(method.getReturnType()));
        RushReservationStore store = new CountingReservationStore(10L, 90);

        var result = new InventoryReconciliationServiceImpl(
                skuMapper, orderMapper, reservationMapper, store).inspect(3001L);

        assertThat(result.inventoryConserved()).isTrue();
        assertThat(result.ledgerCaughtUp()).isFalse();
        assertThat(result.balanced()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method, args));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return 0;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }

    private record CountingReservationStore(long consumed, Integer available)
            implements RushReservationStore {
        @Override public boolean initializeSku(TicketSku sku, int availableStock) { return false; }
        @Override public Integer getAvailableStock(Long skuId) { return available; }
        @Override public long getConsumedReservationCount(Long skuId) { return consumed; }
        @Override public boolean hasBuyerState(Long skuId) { return consumed > 0; }
        @Override public ReservationDecision reserve(Long userId, Long eventId, Long skuId, Integer quantity,
                                                     String reservationId, String idempotencyHash) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ReservationSnapshot> find(String reservationId) { return Optional.empty(); }
        @Override public void markQueued(String reservationId) {}
        @Override public void markOrderCreated(String reservationId, Long orderId) {}
        @Override public void markPaid(String reservationId) {}
        @Override public void markRejected(String reservationId, String reason) {}
    }
}
