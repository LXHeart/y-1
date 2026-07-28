package com.grassland.trust;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.event.OutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({AdjudicationProperties.class, OutboxProperties.class})
@SpringBootApplication
public class TrustServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrustServiceApplication.class, args);
    }
}
