package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.dto.sku.InventoryReconciliationDTO;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.InventoryRecoveryRequiredException;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.InventoryReconciliationService;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import dev.dahuangggg.ticketrush.service.SkuInventoryMutex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockInitServiceImplTest {

    @Test
    void firstInitializationClaimsDurableMarkerAndUsesConfiguredCapacity() {
        TicketSku sku = sku(0);
        AtomicInteger updates = new AtomicInteger();
        TicketSkuMapper mapper = mapper(sku, updates, 1);
        RecordingStore store = new RecordingStore(null, false, true);

        boolean initialized = service(
                store, mapper, ignoredReconciliation(), false).initStock(sku.getId());

        assertThat(initialized).isTrue();
        assertThat(updates).hasValue(2);
        assertThat(store.initializedStock).isEqualTo(100);
        assertThat(sku.getStockInitialized()).isEqualTo(TicketSku.STOCK_OPENED);
    }

    @Test
    void previouslyOpenedSkuFailsClosedWhenAllRedisEvidenceIsGone() {
        TicketSku sku = sku(1);
        RecordingStore store = new RecordingStore(null, false, true);

        assertThatThrownBy(() -> service(
                store, mapper(sku, new AtomicInteger(), 0), ignoredReconciliation(), false)
                .initStock(sku.getId()))
                .isInstanceOf(InventoryRecoveryRequiredException.class);

        assertThat(store.initializedStock).isNull();
    }

    @Test
    void stockKeyOnlyLossRebuildsFromSurvivingBuyerEvidence() {
        TicketSku sku = sku(1);
        RecordingStore store = new RecordingStore(null, true, true);
        InventoryReconciliationService reconciliation = ignored -> new InventoryReconciliationDTO(
                sku.getId(), 100, null, 2, 1, 3, 97,
                false, true, false);

        boolean initialized = service(
                store, mapper(sku, new AtomicInteger(), 0), reconciliation, false)
                .initStock(sku.getId());

        assertThat(initialized).isTrue();
        assertThat(store.initializedStock).isEqualTo(97);
    }

    @Test
    void openingStateResumesAfterCrashBetweenMysqlClaimAndRedisWrite() {
        TicketSku sku = sku(TicketSku.STOCK_OPENING);
        RecordingStore store = new RecordingStore(null, false, true);

        boolean initialized = service(
                store, mapper(sku, new AtomicInteger(), 1), ignoredReconciliation(), false)
                .initStock(sku.getId());

        assertThat(initialized).isTrue();
        assertThat(store.initializedStock).isEqualTo(100);
        assertThat(sku.getStockInitialized()).isEqualTo(TicketSku.STOCK_OPENED);
    }

    @Test
    void stockRecoveryFailsClosedWhileReleaseIntentIsUnsettled() {
        TicketSku sku = sku(TicketSku.STOCK_OPENED);
        RecordingStore store = new RecordingStore(null, true, true);

        assertThatThrownBy(() -> service(
                store, mapper(sku, new AtomicInteger(), 0), ignoredReconciliation(), true)
                .initStock(sku.getId()))
                .isInstanceOf(InventoryRecoveryRequiredException.class);

        assertThat(store.initializedStock).isNull();
    }

    private static TicketSku sku(int initialized) {
        return TicketSku.builder()
                .id(3001L)
                .stock(100)
                .stockInitialized(initialized)
                .status(TicketSku.STATUS_ON_SALE)
                .build();
    }

    private static InventoryReconciliationService ignoredReconciliation() {
        return ignored -> {
            throw new AssertionError("reconciliation must not be called");
        };
    }

    @SuppressWarnings("unchecked")
    private static TicketSkuMapper mapper(
            TicketSku sku, AtomicInteger updates, int updateResult) {
        return (TicketSkuMapper) Proxy.newProxyInstance(
                TicketSkuMapper.class.getClassLoader(),
                new Class<?>[]{TicketSkuMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> sku;
                    case "claimStockOpeningIfNew" -> {
                        updates.incrementAndGet();
                        if (updateResult == 1) {
                            sku.setStockInitialized(TicketSku.STOCK_OPENING);
                        }
                        yield updateResult;
                    }
                    case "markStockOpenedIfOpening" -> {
                        updates.incrementAndGet();
                        if (updateResult == 1) {
                            sku.setStockInitialized(TicketSku.STOCK_OPENED);
                        }
                        yield updateResult;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return 0;
    }

    private static final class RecordingStore implements RushReservationStore {
        private Integer available;
        private final boolean buyersExist;
        private final boolean initializeResult;
        private Integer initializedStock;

        private RecordingStore(Integer available, boolean buyersExist, boolean initializeResult) {
            this.available = available;
            this.buyersExist = buyersExist;
            this.initializeResult = initializeResult;
        }

        @Override
        public boolean initializeSku(TicketSku sku, int availableStock) {
            initializedStock = availableStock;
            if (available == null) {
                available = availableStock;
            }
            return initializeResult;
        }

        @Override public Integer getAvailableStock(Long skuId) { return available; }
        @Override public long getConsumedReservationCount(Long skuId) { return buyersExist ? 1 : 0; }
        @Override public boolean hasBuyerState(Long skuId) { return buyersExist; }
        @Override public ReservationDecision reserve(Long userId, Long eventId, Long skuId,
                                                     Integer quantity, String reservationId,
                                                     String idempotencyHash) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ReservationSnapshot> find(String reservationId) {
            return Optional.empty();
        }
        @Override public void markQueued(String reservationId) { }
        @Override public void markOrderCreated(String reservationId, Long orderId) { }
        @Override public void markPaid(String reservationId) { }
        @Override public void markRejected(String reservationId, String reason) { }
    }

    private static StockInitServiceImpl service(
            RushReservationStore store,
            TicketSkuMapper mapper,
            InventoryReconciliationService reconciliation,
            boolean unsettledRelease) {
        return new StockInitServiceImpl(
                store,
                mapper,
                reconciliation,
                releaseService(unsettledRelease),
                new DirectSkuInventoryMutex());
    }

    private static InventoryReleaseService releaseService(boolean unsettled) {
        return new InventoryReleaseService() {
            @Override public boolean scheduleBeforeOrder(
                    String reservationId, Long userId, Long skuId, String reason) { return false; }
            @Override public void scheduleAfterOrder(
                    String reservationId, Long orderId, Long userId, Long skuId, String reason) { }
            @Override public void processPending() { }
            @Override public boolean hasUnsettledForSku(Long skuId) { return unsettled; }
            @Override public boolean requeueFailed(String reservationId) { return false; }
        };
    }

    private static final class DirectSkuInventoryMutex implements SkuInventoryMutex {
        @Override
        public <T> T execute(Long skuId, java.util.function.Supplier<T> action) {
            return action.get();
        }
    }
}
