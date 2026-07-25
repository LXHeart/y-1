package com.grassland.trust;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TrustServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrustServiceApplication.class, args);
    }
}
