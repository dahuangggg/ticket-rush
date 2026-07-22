package dev.dahuangggg.ticketrush.domain.order;

/** Order Intake Module 的稳定结果。 */
public record OrderCreationResult(Status status, Long orderId, String reason) {

    public enum Status {
        CREATED,
        ALREADY_CREATED,
        REJECTED
    }

    public static OrderCreationResult created(Long orderId) {
        return new OrderCreationResult(Status.CREATED, orderId, null);
    }

    public static OrderCreationResult alreadyCreated(Long orderId) {
        return new OrderCreationResult(Status.ALREADY_CREATED, orderId, null);
    }

    public static OrderCreationResult rejected(String reason) {
        return new OrderCreationResult(Status.REJECTED, null, reason);
    }
}
