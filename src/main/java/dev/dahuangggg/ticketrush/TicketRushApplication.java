package dev.dahuangggg.ticketrush;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@MapperScan("dev.dahuangggg.ticketrush.mapper")
@SpringBootApplication
public class TicketRushApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketRushApplication.class, args);
    }

}
