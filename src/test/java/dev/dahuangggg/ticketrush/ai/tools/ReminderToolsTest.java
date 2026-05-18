package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.reminder.ReminderToolResult;
import dev.dahuangggg.ticketrush.security.LoginUser;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.RushReminderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReminderToolsTest {

    @AfterEach
    void clearCtx() { UserContext.clear(); }

    @Test
    void delegatesWithUserIdFromContext() {
        RushReminderService svc = mock(RushReminderService.class);
        when(svc.setReminder(7L, 42L, 5))
                .thenReturn(ReminderToolResult.builder().ok(true).reminderId(1L).message("done").build());
        UserContext.set(new LoginUser(7L, "13800000000", "user"));

        ReminderToolResult r = new ReminderTools(svc).setRushReminder(42L, 5);

        assertThat(r.isOk()).isTrue();
        verify(svc).setReminder(7L, 42L, 5);
    }

    @Test
    void throwsWhenNoUserInContext() {
        RushReminderService svc = mock(RushReminderService.class);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new ReminderTools(svc).setRushReminder(42L, 5));
        verifyNoInteractions(svc);
    }
}
