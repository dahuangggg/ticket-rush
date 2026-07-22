package dev.dahuangggg.ticketrush;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
@Tag("integration")
class TicketRushApplicationTests {

    @Autowired
    private Flyway flyway;

    @Test
    void contextLoadsWithLatestFlywayMigration() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
    }

}
