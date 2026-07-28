package com.grassland.marketplace;

import com.grassland.marketplace.event.MarketplaceOutboxProperties;
import com.grassland.marketplace.settlement.SettlementReconciliationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({MarketplaceOutboxProperties.class, SettlementReconciliationProperties.class})
@SpringBootApplication
public class MarketplaceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketplaceServiceApplication.class, args);
    }
}
