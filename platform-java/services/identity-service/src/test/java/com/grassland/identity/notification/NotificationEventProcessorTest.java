package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.identity.event.EventContractException;
import com.grassland.identity.event.IdentityEventEnvelope;
import com.grassland.identity.event.InboxRepository;
import com.grassland.identity.notify.mail.MailOutboxEnqueuer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link NotificationEventProcessor} 单测（mock 依赖）。草场 Slice 12 Stage 2。
 *
 * <p>事务用 pass-through mock（{@code transactional} 直接返回原 Mono），隔离事务语义——
 * 真实事务回滚由 {@code NotificationInboxIT} 验证。覆盖类型路由、IGNORED 不写 inbox、坏 envelope、
 * 收件人解析失败不报错、多收件人扇出、排除操作者本人。
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventProcessorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private InboxRepository inbox;
    @Mock private NotificationRecipientResolver resolver;
    @Mock private NotificationRepository notifications;
    @Mock private MailOutboxEnqueuer mailOutbox;
    @Mock private TransactionalOperator transactions;

    private NotificationEventProcessor processor;

    @BeforeEach
    void setUp() {
        // pass-through 且 lenient：IGNORED/坏 envelope 等用例不经事务路径，strict stub 会误报。
        org.mockito.Mockito.lenient().when(transactions.transactional(any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // GL-P1-NOTIFY-001：emit 末尾入队邮件（mock 空 Mono；真实入队/原子性由 NotificationInboxIT 验证）。
        org.mockito.Mockito.lenient().when(mailOutbox.enqueue(any(), any())).thenReturn(Mono.empty());
        processor = new NotificationEventProcessor(inbox, resolver, notifications, mailOutbox, transactions,
                "identity-notification-consumer");
    }

    @Test
    void ignoredEventDoesNotTouchInbox() {
        ConsumerRecord<String, String> record = record("OtherEvent", "agg-1", Map.of());

        StepVerifier.create(processor.process(record))
                .expectNext(NotificationProcessingResult.IGNORED)
                .verifyComplete();
        verify(inbox, never()).recordIfAbsent(anyString(), any(), any(), anyString());
    }

    @Test
    void badJsonThrowsContractException() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "not-json");

        assertThatThrownBy(() -> processor.process(record).block())
                .isInstanceOf(EventContractException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    void missingRequiredFieldThrowsContractException() {
        // envelope 缺 eventId
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("t", 0, 0L, "k",
                        "{\"eventType\":\"MembershipInvited\",\"aggregateType\":\"X\",\"aggregateId\":\"a\",\"payload\":{}}");

        assertThatThrownBy(() -> processor.process(record).block())
                .isInstanceOf(EventContractException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void membershipInvitedFansOutToOneRecipient() {
        ConsumerRecord<String, String> record = record("MembershipInvited", "inv-1",
                Map.of("email", "someone@example.com", "organizationId", "org-1"));
        when(inbox.recordIfAbsent(anyString(), any(), any(), anyString())).thenReturn(Mono.just(true));
        when(resolver.resolve(any(IdentityEventEnvelope.class))).thenReturn(Mono.just(List.of("acct-1")));
        when(notifications.insertIfAbsent(eq("acct-1"), any(), anyString(), anyString(), any(), any(),
                eq("evt-1"), any())).thenReturn(Mono.just(notification()));

        StepVerifier.create(processor.process(record))
                .expectNext(NotificationProcessingResult.PROCESSED)
                .verifyComplete();
        verify(notifications, times(1)).insertIfAbsent(eq("acct-1"), any(), anyString(), anyString(),
                any(), any(), eq("evt-1"), any());
    }

    @Test
    void noRecipientStillProcessedAndInboxRecorded() {
        // 邀请邮箱未注册 → resolver 返回空列表 → 仍 PROCESSED（inbox 已记录），不插通知
        ConsumerRecord<String, String> record = record("MembershipInvited", "inv-2",
                Map.of("email", "nobody@example.com"));
        when(inbox.recordIfAbsent(anyString(), any(), any(), anyString())).thenReturn(Mono.just(true));
        when(resolver.resolve(any(IdentityEventEnvelope.class))).thenReturn(Mono.just(List.of()));

        StepVerifier.create(processor.process(record))
                .expectNext(NotificationProcessingResult.PROCESSED)
                .verifyComplete();
        verify(notifications, never()).insertIfAbsent(anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), any());
    }

    @Test
    void duplicateEventSkipsEmission() {
        ConsumerRecord<String, String> record = record("MembershipGranted", "m-1",
                Map.of("accountId", "acct-1", "organizationId", "org-1", "role", "member"));
        when(inbox.recordIfAbsent(anyString(), any(), any(), anyString())).thenReturn(Mono.just(false));

        StepVerifier.create(processor.process(record))
                .expectNext(NotificationProcessingResult.DUPLICATE)
                .verifyComplete();
        verify(resolver, never()).resolve(any());
        verify(notifications, never()).insertIfAbsent(anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), any());
    }

    @Test
    void resolverExcludesActorFromManagers() {
        // org 有 owner=admin1, admin=admin2；操作者 acct=admin1 → 只通知 admin2
        ConsumerRecord<String, String> record = record("MembershipInvitationAccepted", "inv-3",
                Map.of("organizationId", "org-1", "accountId", "admin1", "role", "member"));
        when(inbox.recordIfAbsent(anyString(), any(), any(), anyString())).thenReturn(Mono.just(true));
        when(resolver.resolve(any(IdentityEventEnvelope.class))).thenReturn(Mono.just(List.of("admin2")));
        when(notifications.insertIfAbsent(eq("admin2"), any(), anyString(), anyString(), any(), any(),
                eq("evt-1"), any())).thenReturn(Mono.just(notification()));

        StepVerifier.create(processor.process(record))
                .expectNext(NotificationProcessingResult.PROCESSED)
                .verifyComplete();
        verify(notifications).insertIfAbsent(eq("admin2"), any(), anyString(), anyString(),
                any(), any(), eq("evt-1"), any());
    }

    @Test
    void externalConsumerNameIsUsedAsInboxKey() {
        // Slice 12 Stage 3：外部 topic 传入自己的 consumerName，inbox 幂等键按消费者隔离。
        ConsumerRecord<String, String> record = record("ApplicationSubmitted", "app-1",
                Map.of("taskId", "task-1", "taskOwnerId", "owner-1"));
        when(inbox.recordIfAbsent(eq("identity-notification-marketplace-consumer"), any(), any(), anyString()))
                .thenReturn(Mono.just(true));
        when(resolver.resolve(any(IdentityEventEnvelope.class))).thenReturn(Mono.just(List.of("owner-1")));
        when(notifications.insertIfAbsent(eq("owner-1"), any(), anyString(), anyString(), any(), any(),
                eq("evt-1"), any())).thenReturn(Mono.just(notification()));

        StepVerifier.create(processor.process(record, "identity-notification-marketplace-consumer"))
                .expectNext(NotificationProcessingResult.PROCESSED)
                .verifyComplete();
        verify(inbox).recordIfAbsent(eq("identity-notification-marketplace-consumer"), any(), any(), anyString());
    }

    // ---- helpers ----

    private ConsumerRecord<String, String> record(String eventType, String aggregateId, Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(Map.of(
                    "eventId", "evt-1",
                    "eventType", eventType,
                    "aggregateType", "Aggregate",
                    "aggregateId", aggregateId,
                    "payload", payload));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new ConsumerRecord<>("grassland.identity.events", 0, 0L, aggregateId, json);
    }

    private static Notification notification() {
        return new Notification("n-1", "acct-1", NotificationCategory.INVITATION, "MembershipInvited",
                "t", null, null, "evt-1", Map.of(), null, java.time.Instant.now());
    }
}
