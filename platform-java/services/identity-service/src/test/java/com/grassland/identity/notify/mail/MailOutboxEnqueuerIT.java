package com.grassland.identity.notify.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.notification.NotificationEventProcessor;
import com.grassland.identity.notification.NotificationProcessingResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 事务邮件入队接线 IT（GL-P1-NOTIFY-001）。真实 {@link NotificationEventProcessor} + 单例 Postgres，
 * 验证 emit 同事务内「站内通知 ⇔ 邮件入队」，以及邀请事件对<b>未注册邮箱</b>的特殊路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailOutboxEnqueuerIT extends IdentityItSupport {

    @Autowired private NotificationEventProcessor processor;

    private final ObjectMapper mapper = new ObjectMapper();
    // identity_inbox 唯一键含 (topic, partition, offset)；每条 record 用递增 offset 避开冲突。
    private static final java.util.concurrent.atomic.AtomicLong OFFSET = new java.util.concurrent.atomic.AtomicLong();

    @BeforeEach
    void cleanMailOutbox() {
        db.sql("DELETE FROM mail_outbox").then().block();
    }

    @Test
    void applicationSubmittedPersistsNotificationAndEnqueuesMail() {
        var owner = seedAccount("app-owner@example.com");
        String eventId = UUID.randomUUID().toString();

        var result = processor.process(record(eventId, "ApplicationSubmitted", "app-1",
                Map.of("taskId", "t-1", "taskOwnerId", owner.accountId()))).block();

        assertThat(result).isEqualTo(NotificationProcessingResult.PROCESSED);
        // 站内通知 + 邮件入队 同事务都落库
        assertThat(notificationCount(eventId)).isEqualTo(1);
        assertThat(mailRecipients(eventId)).containsExactly("app-owner@example.com");
    }

    @Test
    void subAccountPlaceholderEmailIsShortCircuitedFromMailQueue() {
        // 任务书 #49 D10：子账号未绑邮箱时 email 列是 @sub.grassland.invalid 占位——
        // 站内通知照常落库，但邮件入队短路（防 .invalid 域退信）
        String eventId = UUID.randomUUID().toString();
        var sub = seedAccount("prefix-zhang@sub.grassland.invalid");

        var result = processor.process(record(eventId, "OrgSubAccountCreated", "sub-1",
                Map.of("accountId", sub.accountId(), "organizationId", "org-1"))).block();

        assertThat(result).isEqualTo(NotificationProcessingResult.PROCESSED);
        assertThat(notificationCount(eventId)).isEqualTo(1);
        assertThat(mailRecipients(eventId)).isEmpty();
    }

    @Test
    void fundsCapturedEnqueuesMailToPayee() {
        var payee = seedAccount("payee@example.com");
        String eventId = UUID.randomUUID().toString();

        processor.process(record(eventId, "FundsCaptured", "res-1",
                Map.of("engagementRef", "e-1", "payeeAccountId", payee.accountId(),
                        "payoutCents", 5000))).block();

        assertThat(mailRecipients(eventId)).containsExactly("payee@example.com");
    }

    // ---- helpers ----

    private ConsumerRecord<String, String> record(String eventId, String eventType,
                                                   String aggregateId, Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "eventId", eventId, "eventType", eventType,
                    "aggregateType", "Aggregate", "aggregateId", aggregateId, "payload", payload));
            // 1_000_000 基数错开 NotificationInboxIT 的小 offset（同 consumer/topic/partition 共享 inbox）
            return new ConsumerRecord<>("grassland.identity.events", 0,
                    1_000_000L + OFFSET.incrementAndGet(), aggregateId, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long notificationCount(String eventId) {
        return db.sql("SELECT COUNT(*)::bigint AS n FROM notification WHERE source_event_id = :evt")
                .bind("evt", eventId)
                .map(row -> row.get("n", Long.class))
                .one().block();
    }

    private List<String> mailRecipients(String eventId) {
        return db.sql("SELECT recipient FROM mail_outbox WHERE source_event_id = :evt ORDER BY recipient")
                .bind("evt", eventId)
                .map(row -> row.get("recipient", String.class))
                .all().collectList().block();
    }
}
