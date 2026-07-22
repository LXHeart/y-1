package com.grassland.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 回归守卫：确认 Spring Boot KafkaAutoConfiguration 激活并产出 {@link KafkaTemplate}。
 *
 * <p>草场技术债清理的根因验证——Boot 4 把 Kafka auto-config 拆进独立模块 {@code spring-boot-kafka}，
 * 原先裸引 {@code spring-kafka} 拿不到 auto-config（无 KafkaTemplate bean），故历史用手动 {@code @Bean} 兜底。
 * 改用 {@code spring-boot-starter-kafka} 后 auto-config 激活；本测试断言 bean 存在且 {@code spring.kafka.*} 正确流入。
 *
 * <p>{@code OutboxPublisher} 注入 {@code KafkaTemplate<String,String>}（required=false）——若 auto-config 不产出则静默 null、
 * outbox 停发 kafka，故需此显式守卫。继承 {@link IdentityItSupport} 复用完整上下文。
 */
class KafkaAutoConfigurationIT extends IdentityItSupport {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void kafkaTemplateAutoConfiguredFromProperties() {
        assertThat(kafkaTemplate)
                .as("auto-config 必须产出 KafkaTemplate（换 spring-boot-starter-kafka 后）")
                .isNotNull();

        // 直接读 ProducerFactory 配置 map，证明 spring.kafka.* 经 KafkaProperties → ProducerFactory 正确流入
        Map<String, Object> cfg = kafkaTemplate.getProducerFactory().getConfigurationProperties();
        assertThat(String.valueOf(cfg.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))).contains("kafka:9092");
        assertThat(cfg.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(cfg.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        // retries 经 spring.kafka.producer.properties.retries 流入（原生 producer 属性存为 String，Kafka 内部强转）
        assertThat(cfg.get(ProducerConfig.RETRIES_CONFIG)).isEqualTo("3");
    }
}
