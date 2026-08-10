package com.grassland.identity;

import com.grassland.identity.event.OutboxProperties;
import com.grassland.identity.kyb.KybMediaRetentionProperties;
import com.grassland.identity.kyb.KybDocumentAnalysisProperties;
import com.grassland.identity.identityprofile.IdentitySessionPolicyProperties;
import com.grassland.identity.notify.mail.MailOutboxProperties;
import com.grassland.identity.notify.external.ExternalDeliveryProperties;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        OutboxProperties.class,
        MailOutboxProperties.class,
        ExternalDeliveryProperties.class,
        KybMediaRetentionProperties.class,
        KybDocumentAnalysisProperties.class,
        IdentitySessionPolicyProperties.class
})
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }

    @Bean
    ReactiveTransactionManager identityReactiveTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    TransactionalOperator identityTransactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}
