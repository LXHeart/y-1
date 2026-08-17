package com.grassland.finance.judgereward;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance 首个 Kafka 业务消费者（任务书 #31 / ADR-D15 D4）：消费 trust outbox topic 的
 * {@code JudgeVoteRewarded}，入账 AI 积分。镜像 identity {@code ExternalNotificationEventConsumer} 形态：
 * 手动 ack（事务提交后才前移 offset）、DLT/重试由 Kafka 消费者可靠性配置族承担、
 * 处理进度暴露 processed/duplicate/ignored/failed 计数（对齐五个 outbox owner 的观测约定）。
 *
 * <p>默认不启用（本地/测试无 Kafka 时跳过）；compose 置
 * {@code FINANCE_JUDGE_REWARD_CONSUMER_ENABLED=true}。
 */
@Component
@ConditionalOnProperty(name = "finance.judge-reward-consumer.enabled", havingValue = "true")
public class JudgeRewardEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeRewardEventConsumer.class);
    private static final String METRIC_PREFIX = "finance.judge.reward.";

    private final JudgeRewardEventProcessor processor;
    private final Counter processed;
    private final Counter duplicate;
    private final Counter ignored;
    private final Counter failed;

    public JudgeRewardEventConsumer(
            JudgeRewardEventProcessor processor,
            @Autowired(required = false) MeterRegistry registry) {
        this.processor = processor;
        this.processed = counter(registry, "processed");
        this.duplicate = counter(registry, "duplicate");
        this.ignored = counter(registry, "ignored");
        this.failed = counter(registry, "failed");
    }



    @KafkaListener(
            topics = "${finance.judge-reward-consumer.topic:grassland.trust.events}",
            groupId = "${finance.judge-reward-consumer.group-id:finance-judge-reward-consumer}",
            autoStartup = "${finance.judge-reward-consumer.auto-startup:true}",
            containerFactory = "financeJudgeRewardKafkaListenerContainerFactory")
    public Mono<Void> onTrustEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        return processor.process(record)
                .doOnNext(this::recordOutcome)
                .then(Mono.<Void>fromRunnable(acknowledgment::acknowledge))
                .doOnError(error -> {
                    log.error("judge reward consume failed offset={} partition={}",
                            record.offset(), record.partition(), error);
                    failed.increment();
                });
    }

    private void recordOutcome(JudgeRewardEventProcessor.Outcome outcome) {
        switch (outcome) {
            case PROCESSED -> processed.increment();
            case DUPLICATE -> duplicate.increment();
            case IGNORED -> ignored.increment();
        }
    }

    /** 无 MeterRegistry（测试上下文）时用无副作用哑计数器。 */
    private static Counter counter(MeterRegistry registry, String name) {
        return registry == null
                ? Counter.builder(name).register(io.micrometer.core.instrument.Metrics.globalRegistry)
                : Counter.builder(METRIC_PREFIX + name)
                        .description("finance judge reward consumer outcome: " + name)
                        .register(registry);
    }
}
