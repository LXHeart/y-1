package com.grassland.intelligence;

import com.grassland.intelligence.event.OutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@SpringBootApplication
public class IntelligenceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntelligenceServiceApplication.class, args);
    }
}
