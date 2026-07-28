package com.grassland.trust;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.event.OutboxProperties;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@EnableScheduling
@EnableConfigurationProperties({AdjudicationProperties.class, OutboxProperties.class})
@SpringBootApplication
public class TrustServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrustServiceApplication.class, args);
    }

    @Bean
    ReactiveTransactionManager trustReactiveTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    TransactionalOperator trustTransactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}
