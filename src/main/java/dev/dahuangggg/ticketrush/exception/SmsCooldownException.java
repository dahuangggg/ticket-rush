package dev.dahuangggg.ticketrush.exception;

public class SmsCooldownException extends RuntimeException {

    public SmsCooldownException() {
        super("操作过于频繁，请稍后再试");
    }
}
