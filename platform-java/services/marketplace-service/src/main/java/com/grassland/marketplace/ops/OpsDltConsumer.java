package com.grassland.marketplace.ops;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 死信登记消费者（GL-P1-OPS-001 Stage 2）。
 *
 * <p>此前 DLT 只有投递没有落地：消息躺在 {@code *.DLT} topic 里，运营既不知道有多少条、也没法重投。
 * 本消费者把死信落到 {@code ops_dlt_message} 并为每条开一张 case，让它进同一个处置队列。
 *
 * <p><b>刻意用默认 container factory，不用 marketplaceTrustKafkaListenerContainerFactory</b>：
 * 那个 factory 带 DLT recoverer，一旦本消费者自己失败就会把消息投到 {@code *.DLT.DLT}，
 * 无限套娃。本消费者失败就不 ack，靠重投重试 —— 登记是幂等的（位点唯一键），重复消费无副作用。
 */
@Component
@ConditionalOnProperty(name = "marketplace.dlt-consumer.enabled", havingValue = "true")
public class OpsDltConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpsDltConsumer.class);

    /** Spring Kafka DeadLetterPublishingRecoverer 写入的原始 topic 头。 */
    private static final String ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";
    private static final String EXCEPTION_MESSAGE_HEADER = "kafka_dlt-exception-message";

    /** 摘要截断长度：错误消息可能带整个 stack trace，队列列表里没必要也不该展开。 */
    private static final int MAX_ERROR_SUMMARY = 1000;

    private final OpsDltRegistrar registrar;

    public OpsDltConsumer(OpsDltRegistrar registrar) {
        this.registrar = registrar;
    }

    @KafkaListener(
            topics = "#{'${marketplace.dlt-consumer.topics:grassland.trust.events.DLT}'.split(',')}",
            groupId = "${marketplace.dlt-consumer.group-id:marketplace-dlt-registrar}",
            autoStartup = "${marketplace.dlt-consumer.auto-startup:true}")
    public Mono<Void> onDeadLetter(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        return registrar.register(
                        record.topic(), record.partition(), record.offset(),
                        header(record, ORIGINAL_TOPIC_HEADER, stripSuffix(record.topic())),
                        record.key(),
                        record.value() == null ? "" : record.value(),
                        truncate(header(record, EXCEPTION_MESSAGE_HEADER, null)))
                .then(Mono.<Void>fromRunnable(acknowledgment::acknowledge))
                .doOnError(error -> log.error("dlt register failed topic={} partition={} offset={}",
                        record.topic(), record.partition(), record.offset(), error));
    }

    private static String header(ConsumerRecord<String, String> record, String name, String fallback) {
        Header found = record.headers().lastHeader(name);
        if (found == null || found.value() == null) {
            return fallback;
        }
        return new String(found.value(), StandardCharsets.UTF_8);
    }

    /** 头缺失时的兜底：{@code x.DLT} → {@code x}。 */
    private static String stripSuffix(String topic) {
        return topic.endsWith(".DLT") ? topic.substring(0, topic.length() - 4) : topic;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_SUMMARY ? value : value.substring(0, MAX_ERROR_SUMMARY);
    }
}
