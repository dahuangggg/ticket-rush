package dev.dahuangggg.ticketrush.exception;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(String reservationId) {
        super("抢票 Reservation 不存在: " + reservationId);
    }
}
