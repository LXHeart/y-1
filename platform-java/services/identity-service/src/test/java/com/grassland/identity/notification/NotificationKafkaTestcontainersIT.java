package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.IdentityItSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实 Kafka broker 端到端门禁（Slice 12 Stage 2）。镜像 marketplace {@code MarketplaceKafkaTestcontainersIT}
 * 的<b>子集</b>：发一条 {@code OrgSubAccountCreated} → 通知落库；发毒药消息（坏 JSON）→ 进 DLT 且后续正常消息继续被消费。
 *
 * <p>处理器路由 / 事务原子性 / 幂等 / 收件人解析已由 {@link NotificationEventProcessorTest} +
 * {@link NotificationInboxIT} 覆盖；本测试只验「@KafkaListener 接线 + ack 模式 + DLT 路由」这条集成链
 * （单测覆盖不到的部分，遵循本项目「真 broker/浏览器实测抓集成缺陷」的惯例）。
 */
class NotificationKafkaTestcontainersIT extends IdentityItSupport {

    private static final Duration AWAIT = Duration.ofSeconds(25);
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TOPIC = "identity-notif-it-" + RUN_ID;
    private static final String DLT = TOPIC + ".DLT";
    /** Stage 3：外部 topic（marketplace）接线门禁；trust/finance 走同一 listener 工厂与处理器。 */
    private static final String MARKETPLACE_TOPIC = "identity-notif-mkt-it-" + RUN_ID;
    private static final String MARKETPLACE_DLT = MARKETPLACE_TOPIC + ".DLT";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    static {
        KAFKA.start();
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private NotificationRepository notifications;

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry r) {
        r.add("identity.notification-consumer.enabled", () -> "true");
        r.add("identity.notification-consumer.topic", () -> TOPIC);
        r.add("identity.notification-consumer.group-id", () -> "identity-notif-it-" + RUN_ID);
        // Stage 3：只把 marketplace topic 指向 IT topic；trust/finance 保持默认 topic 名，
        // 本 broker 上无人生产 → 空转不影响断言。
        r.add("identity.notification-consumer.external.enabled", () -> "true");
        r.add("identity.notification-consumer.external.marketplace-topic", () -> MARKETPLACE_TOPIC);
        r.add("identity.notification-consumer.external.marketplace-group-id", () -> "identity-notif-mkt-it-" + RUN_ID);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void topics() throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(p)) {
            admin.createTopics(List.of(
                            new NewTopic(TOPIC, 1, (short) 1),
                            new NewTopic(DLT, 1, (short) 1),
                            new NewTopic(MARKETPLACE_TOPIC, 1, (short) 1),
                            new NewTopic(MARKETPLACE_DLT, 1, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void subAccountCreatedEventProducesNotification() throws Exception {
        var account = seedAccount("kafka-subacct@example.com");
        String envelope = mapper.writeValueAsString(Map.of(
                "eventId", "k-evt-" + RUN_ID,
                "eventType", "OrgSubAccountCreated",
                "aggregateType", "Organization",
                "aggregateId", "inv-" + RUN_ID,
                "payload", Map.of("accountId", account.accountId(), "organizationId", "org-kafka")));

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, "inv-" + RUN_ID, envelope)).get(10, TimeUnit.SECONDS);
        }

        await().atMost(AWAIT).untilAsserted(() ->
                assertThat(notifications.findByAccount(account.accountId(), false, 10, null, null)
                                .collectList().block())
                        .as("子账号事件经真 broker 消费后落通知")
                        .hasSize(1));
    }

    @Test
    void poisonMessageGoesToDltAndSubsequentValidIsStillConsumed() throws Exception {
        var account = seedAccount("kafka-poison-valid@example.com");
        String valid = mapper.writeValueAsString(Map.of(
                "eventId", "k-valid-" + RUN_ID,
                "eventType", "OrgSubAccountCreated",
                "aggregateType", "Organization",
                "aggregateId", "inv-valid-" + RUN_ID,
                "payload", Map.of("accountId", account.accountId(), "organizationId", "org-kafka")));

        try (var producer = producer()) {
            // 毒药：坏 JSON → 不可重试 → 直接 DLT，不阻塞分区
            producer.send(new ProducerRecord<>(TOPIC, "bad", "{not-json")).get(10, TimeUnit.SECONDS);
            // 后续正常消息仍能被消费（证分区未卡死）
            producer.send(new ProducerRecord<>(TOPIC, "inv-valid-" + RUN_ID, valid)).get(10, TimeUnit.SECONDS);
        }

        // 毒药进 DLT
        await().atMost(AWAIT).untilAsserted(() -> assertThat(dltRecordCount()).isGreaterThan(0));
        // 后续正常消息照常落通知
        await().atMost(AWAIT).untilAsserted(() ->
                assertThat(notifications.findByAccount(account.accountId(), false, 10, null, null)
                                .collectList().block())
                        .as("毒药进 DLT 后，分区仍继续消费正常消息")
                        .hasSize(1));
    }

    @Test
    void marketplaceTopicEventProducesEngagementNotification() throws Exception {
        // Stage 3：外部 topic 接线门禁。收件人只来自 payload 里发射端补的 taskOwnerId。
        var owner = seedAccount("kafka-task-owner@example.com");
        String envelope = mapper.writeValueAsString(Map.of(
                "eventId", "k-mkt-" + RUN_ID,
                "eventType", "ApplicationSubmitted",
                "aggregateType", "TaskApplication",
                "aggregateId", "app-" + RUN_ID,
                "payload", Map.of(
                        "taskId", "task-" + RUN_ID,
                        "applicationId", "app-" + RUN_ID,
                        "recommenderAccountId", UUID.randomUUID().toString(),
                        "status", "pending",
                        "taskOwnerId", owner.accountId())));

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(MARKETPLACE_TOPIC, "app-" + RUN_ID, envelope))
                    .get(10, TimeUnit.SECONDS);
        }

        await().atMost(AWAIT).untilAsserted(() -> {
            var list = notifications.findByAccount(owner.accountId(), false, 10, null, null)
                    .collectList().block();
            assertThat(list).as("marketplace 事件经真 broker 消费后落履约通知").hasSize(1);
            assertThat(list.getFirst().category()).isEqualTo(NotificationCategory.ENGAGEMENT);
        });
    }

    private long dltRecordCount() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + RUN_ID);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            org.apache.kafka.common.TopicPartition tp =
                    new org.apache.kafka.common.TopicPartition(DLT, 0);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            return c.poll(Duration.ofSeconds(3)).count();
        }
    }

    private KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(p);
    }
}
