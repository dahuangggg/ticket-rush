package dev.dahuangggg.ticketrush.ai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBoundedSessionAndMessage() {
        assertThat(validator.validate(new ChatRequest("session-1", "帮我查询上海演出"))).isEmpty();
    }

    @Test
    void rejectsUnsafeSessionCharactersAndOversizedMessage() {
        String oversized = "问".repeat(2_001);

        assertThat(validator.validate(new ChatRequest("../../other-user", oversized)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("sessionId", "message");
    }
}
