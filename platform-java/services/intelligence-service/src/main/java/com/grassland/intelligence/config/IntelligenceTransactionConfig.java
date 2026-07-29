package com.grassland.intelligence.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/** intelligence 领域多写事务地基（media ticket 配额预留 + 行创建原子性）。 */
@Configuration
public class IntelligenceTransactionConfig {

    @Bean
    ReactiveTransactionManager intelligenceReactiveTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    TransactionalOperator intelligenceTransactionalOperator(
            ReactiveTransactionManager intelligenceReactiveTransactionManager) {
        return TransactionalOperator.create(intelligenceReactiveTransactionManager);
    }
}
