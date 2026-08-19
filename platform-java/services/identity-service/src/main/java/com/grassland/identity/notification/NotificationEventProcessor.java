package com.grassland.identity.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.messaging.EventContractException;
import com.grassland.identity.event.IdentityEventEnvelope;
import com.grassland.identity.event.InboxRepository;
import com.grassland.identity.notify.mail.MailOutboxEnqueuer;
import com.grassland.identity.notify.external.ExternalDeliveryEnqueuer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 把 identity outbox 事件转成站内通知。草场 Slice 12 Stage 2。
 *
 * <p>流程（镜像 marketplace {@code TrustEventProcessor}，但「副作用 = 插通知」而非「启对账 workflow」）：
 * <ol>
 *   <li>解 envelope——形状/必填字段不符抛 {@link EventContractException}（不可重试 → DLT）。</li>
 *   <li>非关注类型 → {@link NotificationProcessingResult#IGNORED}，<b>不写 inbox</b>（与 marketplace 一致）。</li>
 *   <li>关注类型：{@code inbox.recordIfAbsent} + {@code resolver.resolve} + 逐收件人插通知，
 *       全部在<b>同一 R2DBC 事务</b>（7A 约束）——inbox 与通知要么都提交、要么都回滚。</li>
 * </ol>
 *
 * <p><b>两道幂等</b>：① {@code (consumer_name, event_id)} inbox 吸收 at-least-once 重投；
 * ② notification 的 {@code UNIQUE(source_event_id, account_id)} 兜换名/重放。
 */
@Component
public class NotificationEventProcessor {

    private final InboxRepository inbox;
    private final NotificationRecipientResolver resolver;
    private final NotificationRepository notifications;
    private final MailOutboxEnqueuer mailOutbox;
    private final ExternalDeliveryEnqueuer externalDelivery;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String consumerName;

    public NotificationEventProcessor(
            InboxRepository inbox,
            NotificationRecipientResolver resolver,
            NotificationRepository notifications,
            MailOutboxEnqueuer mailOutbox,
            ExternalDeliveryEnqueuer externalDelivery,
            TransactionalOperator transactions,
            @Value("${identity.notification-consumer.group-id:identity-notification-consumer}") String consumerName) {
        this.inbox = inbox;
        this.resolver = resolver;
        this.notifications = notifications;
        this.mailOutbox = mailOutbox;
        this.externalDelivery = externalDelivery;
        this.transactions = transactions;
        this.consumerName = consumerName;
    }

    /** identity 自有 topic：用注入的默认 consumerName（Stage 2 行为不变）。 */
    public Mono<NotificationProcessingResult> process(ConsumerRecord<String, String> record) {
        return process(record, consumerName);
    }

    /**
     * 处理一条事件。Stage 3 起同一处理器服务四个 topic（identity / marketplace / trust / finance），
     * 每个消费者传入<b>自己的</b> {@code consumerName}——inbox 幂等键是
     * {@code (consumer_name, event_id)}，各 topic 互不干扰（不同服务的 eventId 也可能撞号）。
     */
    public Mono<NotificationProcessingResult> process(ConsumerRecord<String, String> record, String consumerName) {
        return Mono.defer(() -> {
            IdentityEventEnvelope envelope = parseEnvelope(record);
            NotificationTemplates.Template template =
                    NotificationTemplates.template(envelope.eventType(), envelope.payload());
            if (template == null) {
                return Mono.just(NotificationProcessingResult.IGNORED);
            }
            String payloadSha256 = payloadSha256(envelope.payload());
            Mono<NotificationProcessingResult> work = inbox
                    .recordIfAbsent(consumerName, record, envelope, payloadSha256)
                    .flatMap(inserted -> inserted
                            ? emit(envelope, template).thenReturn(NotificationProcessingResult.PROCESSED)
                            : Mono.just(NotificationProcessingResult.DUPLICATE));
            return transactions.transactional(work);
        });
    }

    /**
     * 解析收件人并逐条插通知（同事务），随后入队事务邮件（同事务）。无收件人（如邀请邮箱未注册）时仍视为
     * PROCESSED——inbox 已记录；邮件 enqueuer 对邀请事件会直接用 payload.email 入队（未注册邮箱也能收到）。
     *
     * <p>「站内通知插入」与「邮件入队」在同一事务：任一失败则整体回滚，保证不漂移（GL-P1-NOTIFY-001）。
     */
    private Mono<Long> emit(IdentityEventEnvelope envelope, NotificationTemplates.Template template) {
        return resolver.resolve(envelope).flatMap(recipients -> {
            Mono<Long> chain = Mono.just(0L);
            for (String accountId : recipients) {
                chain = chain.flatMap(ignored -> notifications.insertIfAbsent(
                                accountId, template.category(), envelope.eventType(),
                                template.title(), template.body(), template.linkPath(),
                                envelope.eventId(), template.payload())
                        .thenReturn(1L));
            }
            return chain.then(mailOutbox.enqueue(envelope, recipients))
                    .then(externalDelivery.enqueue(envelope, template, recipients))
                    .thenReturn((long) recipients.size());
        });
    }

    private IdentityEventEnvelope parseEnvelope(ConsumerRecord<String, String> record) {
        if (record == null || record.value() == null || record.value().isBlank()) {
            throw new EventContractException("identity event value must contain valid JSON");
        }
        JsonNode root;
        try {
            root = mapper.readTree(record.value());
        } catch (Exception error) {
            throw new EventContractException("identity event value must contain valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new EventContractException("identity event envelope must be a JSON object");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new EventContractException("identity event payload must be a JSON object");
        }
        return new IdentityEventEnvelope(
                requiredText(root, "eventId"),
                requiredText(root, "eventType"),
                requiredText(root, "aggregateType"),
                requiredText(root, "aggregateId"),
                payload);
    }

    private String payloadSha256(JsonNode payload) {
        try {
            byte[] canonicalPayload = mapper.writeValueAsBytes(canonicalize(payload));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalPayload);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new EventContractException("identity event payload cannot be canonicalized", error);
        }
    }

    /** 规范化：对象键按字典序排（TreeMap），使相同语义的 payload 产出相同 SHA-256，与字段顺序无关。 */
    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var sorted = mapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new EventContractException("identity event field " + field + " must be a non-blank string");
        }
        return value.asText();
    }
}
