package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.domain.rush.ReleaseResult;

/** Inventory Release Module 的 Redis seam。 */
public interface ReservationReleaseStore {

    ReleaseResult release(String reservationId, Long skuId, Long userId);
}
