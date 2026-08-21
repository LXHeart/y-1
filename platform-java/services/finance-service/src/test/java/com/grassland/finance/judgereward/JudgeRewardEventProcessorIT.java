package com.grassland.finance.judgereward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.finance.FinanceItSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 审判官激励消费者真 broker 端到端门禁（任务书 #31 / ADR-D15 B2）。镜像 identity
 * {@code NotificationKafkaTestcontainersIT}：发一条 {@code JudgeVoteRewarded} → credits 入账
 * （余额 + 流水 type=judge_reward + operation_id）；同 eventId 重投 → inbox 幂等不重复入账；
 * 坏消息（缺字段）→ 契约错误进 DLT，后续正常消息继续被消费。
 */
class JudgeRewardEventProcessorIT extends FinanceItSupport {

    private static final Duration AWAIT = Duration.ofSeconds(25);
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TOPIC = "finance-judge-it-" + RUN_ID;
    private static final String DLT = TOPIC + ".DLT";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    static {
        KAFKA.start();
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry r) {
        r.add("finance.judge-reward-consumer.enabled", () -> "true");
        r.add("finance.judge-reward-consumer.topic", () -> TOPIC);
        r.add("finance.judge-reward-consumer.group-id", () -> "finance-judge-it-" + RUN_ID);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // The random topic is created before the listener joins. earliest prevents the first
        // test record from being skipped when the consumer assignment races the producer.
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @BeforeAll
    static void topics() throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(p)) {
            admin.createTopics(List.of(
                            new NewTopic(TOPIC, 1, (short) 1),
                            new NewTopic(DLT, 1, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void rewardEventCreditsJudgeAndReplayIsIdempotent() throws Exception {
        String judge = UUID.randomUUID().toString();
        String dispute = UUID.randomUUID().toString();
        String eventId = "jr-evt-" + RUN_ID;
        String envelope = mapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "JudgeVoteRewarded",
                "aggregateType", "DisputeCase",
                "aggregateId", dispute,
                "payload", Map.of(
                        "disputeId", dispute,
                        "round", 2,
                        "judgeAccountId", judge,
                        "credits", 20)));

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, dispute, envelope)).get(10, TimeUnit.SECONDS);
        }

        await().atMost(AWAIT).untilAsserted(() -> {
            Integer balance = db.sql("SELECT balance FROM credits_account WHERE account_id = CAST(:a AS uuid)")
                    .bind("a", judge).map(r -> r.get("balance", Integer.class)).one().block();
            assertThat(balance).as("20 积分到账").isEqualTo(20);
        });
        String operationId = "judge-reward:" + dispute + ":2:" + judge;
        String txn = db.sql("""
                        SELECT type FROM credits_transaction WHERE operation_id = :op
                        """)
                .bind("op", operationId)
                .map(r -> r.get("type", String.class)).one().block();
        assertThat(txn).as("流水 type=judge_reward，operation_id 可对账").isEqualTo("judge_reward");

        // 同 eventId 重投（at-least-once）→ inbox 吸收，不重复入账
        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, dispute, envelope)).get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(2_000);
        Integer balanceAfterReplay = db.sql(
                        "SELECT balance FROM credits_account WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judge).map(r -> r.get("balance", Integer.class)).one().block();
        assertThat(balanceAfterReplay).as("重放不重复入账").isEqualTo(20);
        Long inboxRows = db.sql("SELECT COUNT(*)::int AS c FROM finance_inbox WHERE event_id = :e")
                .bind("e", eventId).map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(inboxRows).isEqualTo(1);
    }

    @Test
    void commissionEventCreditsWalletPostsJournalAndReplayIsIdempotent() throws Exception {
        // ADR-D18：JudgeVoteCommissionRewarded → 钱包余额/流水 + journal 双录（Dr 费用 / Cr WALLET）；
        // 同 eventId 重投 inbox 吸收不重复入账。
        String judge = UUID.randomUUID().toString();
        String dispute = UUID.randomUUID().toString();
        String eventId = "jc-evt-" + RUN_ID;
        String envelope = mapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "JudgeVoteCommissionRewarded",
                "aggregateType", "DisputeCase",
                "aggregateId", dispute,
                "payload", Map.of(
                        "disputeId", dispute,
                        "round", 1,
                        "judgeAccountId", judge,
                        "amountCents", 15)));

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, dispute, envelope)).get(10, TimeUnit.SECONDS);
        }

        await().atMost(AWAIT).untilAsserted(() -> {
            Long balance = db.sql("SELECT balance_cents FROM recommender_wallet"
                            + " WHERE account_id = CAST(:a AS uuid)")
                    .bind("a", judge).map(r -> r.get("balance_cents", Long.class)).one().block();
            assertThat(balance).as("现金佣金 15 分入钱包").isEqualTo(15L);
        });
        String entryType = db.sql("SELECT entry_type FROM wallet_ledger"
                        + " WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judge).map(r -> r.get("entry_type", String.class)).one().block();
        assertThat(entryType).isEqualTo("judge_commission");

        String operationId = "judge-commission:" + dispute + ":1:" + judge;
        String journalType = db.sql("SELECT journal_type FROM journal WHERE operation_id = :op")
                .bind("op", operationId)
                .map(r -> r.get("journal_type", String.class)).one().block();
        assertThat(journalType).as("journal 类型=JUDGE_COMMISSION，operationId 可对账")
                .isEqualTo("JUDGE_COMMISSION");
        // 双录借贷平衡：Dr JUDGE_COMMISSION_EXPENSE / Cr WALLET 各 15
        Long debits = db.sql("SELECT COALESCE(SUM(p.amount_cents), 0)::bigint AS d FROM posting p"
                        + " JOIN journal j ON j.id = p.journal_id"
                        + " WHERE j.operation_id = :op AND p.direction = 'DEBIT'")
                .bind("op", operationId).map(r -> r.get("d", Long.class)).one().block();
        Long creditsPosted = db.sql("SELECT COALESCE(SUM(p.amount_cents), 0)::bigint AS c FROM posting p"
                        + " JOIN journal j ON j.id = p.journal_id"
                        + " WHERE j.operation_id = :op AND p.direction = 'CREDIT'")
                .bind("op", operationId).map(r -> r.get("c", Long.class)).one().block();
        assertThat(debits).isEqualTo(15L);
        assertThat(creditsPosted).isEqualTo(15L);

        // 同 eventId 重投 → 不重复入账
        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, dispute, envelope)).get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(2_000);
        Long balanceAfterReplay = db.sql("SELECT balance_cents FROM recommender_wallet"
                        + " WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judge).map(r -> r.get("balance_cents", Long.class)).one().block();
        assertThat(balanceAfterReplay).as("重放不重复入账").isEqualTo(15L);
    }

    @Test
    void commissionPoisonMessageGoesToDltWithoutAnyAccounting() throws Exception {
        // 坏佣金消息：amountCents 缺失 → 契约错误 → DLT，不开钱包不记账。
        String judge = UUID.randomUUID().toString();
        String dispute = UUID.randomUUID().toString();
        String poison = mapper.writeValueAsString(Map.of(
                "eventId", "jc-poison-" + RUN_ID,
                "eventType", "JudgeVoteCommissionRewarded",
                "aggregateType", "DisputeCase",
                "aggregateId", dispute,
                "payload", Map.of("disputeId", dispute, "round", 1, "judgeAccountId", judge)));

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, dispute, poison)).get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(2_500);
        Long walletRows = db.sql("SELECT COUNT(*)::int AS c FROM recommender_wallet"
                        + " WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judge).map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(walletRows).as("坏佣金消息不开钱包").isZero();
        Long journalRows = db.sql("SELECT COUNT(*)::int AS c FROM journal WHERE operation_id = :op")
                .bind("op", "judge-commission:" + dispute + ":1:" + judge)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(journalRows).as("坏佣金消息不记账").isZero();
    }

    @Test
    void poisonMessageGoesToDltAndSubsequentValidIsStillConsumed() throws Exception {
        String judge = UUID.randomUUID().toString();
        String dispute = UUID.randomUUID().toString();
        // 坏消息：credits 缺失 → 契约错误（不重试）→ DLT
        String poison = mapper.writeValueAsString(Map.of(
                "eventId", "jr-poison-" + RUN_ID,
                "eventType", "JudgeVoteRewarded",
                "aggregateType", "DisputeCase",
                "aggregateId", dispute,
                "payload", Map.of("disputeId", dispute, "round", 1, "judgeAccountId", judge)));
        String valid = mapper.writeValueAsString(Map.of(
                "eventId", "jr-valid-" + RUN_ID,
                "eventType", "JudgeVoteRewarded",
                "aggregateType", "DisputeCase",
                "aggregateId", dispute,
                "payload", Map.of(
                        "disputeId", dispute, "round", 1,
                        "judgeAccountId", judge, "credits", 20)));

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, dispute, poison)).get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(TOPIC, dispute, valid)).get(10, TimeUnit.SECONDS);
        }

        // 坏消息进 DLT（Kafka 侧）→ 用行为断言：后续 valid 消息被消费（分区未阻塞）
        await().atMost(AWAIT).untilAsserted(() -> {
            Integer balance = db.sql("SELECT balance FROM credits_account WHERE account_id = CAST(:a AS uuid)")
                    .bind("a", judge).map(r -> r.get("balance", Integer.class)).one().block();
            assertThat(balance).as("毒药进 DLT 后分区继续消费").isEqualTo(20);
        });
        // 毒药事件未入账（inbox 只记了 valid 的事件？契约错误在 inbox 前 → 不写 inbox 不入账）
        Long poisoned = db.sql("SELECT COUNT(*)::int AS c FROM credits_transaction"
                        + " WHERE operation_id = :op")
                .bind("op", "judge-reward:" + dispute + ":1:" + judge)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(poisoned).as("坏消息无入账（同一 judge+round+dispute 只有 valid 一次）").isEqualTo(1);
    }

    private KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(p);
    }
}
