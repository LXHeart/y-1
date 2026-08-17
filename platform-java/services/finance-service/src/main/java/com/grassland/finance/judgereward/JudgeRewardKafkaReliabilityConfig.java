package com.grassland.finance.judgereward;

import com.grassland.finance.judgereward.FinanceInboxRepository.FinanceInboxContractException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * finance 审判奖励消费者的 Kafka 可靠性配置（任务书 #31 / ADR-D15 D4/D6）。镜像 marketplace
 * {@code KafkaConsumerReliabilityConfig}：坏消息（契约错误）不重投直接进 DLT；瞬态错误重试 2 次
 * （500ms 间隔）后进 DLT——DLT 不阻塞分区。手动 ack、异步 ack、投递头与观测全开。
 */
@Configuration
public class JudgeRewardKafkaReliabilityConfig {

    // TransactionalOperator / ReactiveTransactionManager 复用 finance 既有 bean（EscrowController 族），
    // 不在此重复声明（曾因双 bean 导致 AccountController 注入歧义）。

    /** finance 无全局 ObjectMapper bean（各处服务本地持有）——消费者域提供一个可覆写的默认 bean。 */
    @Bean
    @ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
    com.fasterxml.jackson.databind.ObjectMapper financeJudgeRewardObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Bean
    FixedBackOff financeJudgeRewardRetryBackOff() {
        return new FixedBackOff(500L, 2L);
    }

    @Bean
    DeadLetterPublishingRecoverer financeJudgeRewardDeadLetterRecoverer(
            KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setAppendOriginalHeaders(true);
        recoverer.setStripPreviousExceptionHeaders(false);
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        return recoverer;
    }

    @Bean
    DefaultErrorHandler financeJudgeRewardErrorHandler(
            DeadLetterPublishingRecoverer recoverer,
            FixedBackOff backOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(FinanceInboxContractException.class);
        return errorHandler;
    }

    @Bean(name = "financeJudgeRewardKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object>
            financeJudgeRewardKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    DefaultErrorHandler financeJudgeRewardErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(financeJudgeRewardErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
