package dev.dahuangggg.ticketrush.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ReminderNotFoundException extends RuntimeException {
    public ReminderNotFoundException(Long id) {
        super("reminder not found: " + id);
    }
}
