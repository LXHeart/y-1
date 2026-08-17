package com.grassland.trust;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.dispute.EvidenceProperties;
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
@EnableConfigurationProperties({AdjudicationProperties.class, OutboxProperties.class, EvidenceProperties.class})
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

    /**
     * 可覆写的 ObjectMapper bean：{@code MarketplaceReputationEventConsumer} 构造注入需要，
     * 而 trust 此前各处持服务本地实例、无全局 bean（01d5756 引入消费者后 Spring 上下文在无该 bean 时
     * 启动失败）。默认 Jackson 自动配置同款；需要定制时以同类型 bean 覆盖（@ConditionalOnMissingBean）。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.fasterxml.jackson.databind.ObjectMapper.class)
    com.fasterxml.jackson.databind.ObjectMapper trustObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
