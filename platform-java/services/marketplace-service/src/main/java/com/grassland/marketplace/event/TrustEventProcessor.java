package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
public class TrustEventProcessor {

    private static final String DISPUTE_FINALIZED = "DisputeFinalized";
    private static final String DISPUTE_OPENED = "DisputeOpened";

    private final InboxRepository inbox;
    private final SettlementReconciliationRepository reconciliations;
    private final TaskApplicationRepository applications;
    private final TaskRepository tasks;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;
    private final String consumerName;

    public TrustEventProcessor(
            InboxRepository inbox,
            SettlementReconciliationRepository reconciliations,
            TransactionalOperator transactions,
            ObjectMapper mapper,
            @Value("${marketplace.trust-consumer.group-id:marketplace-trust-consumer}") String consumerName,
            TaskApplicationRepository applications,
            TaskRepository tasks,
            OutboxRepository outbox) {
        this.inbox = inbox;
        this.reconciliations = reconciliations;
        this.transactions = transactions;
        this.mapper = mapper;
        this.consumerName = consumerName;
        this.applications = applications;
        this.tasks = tasks;
        this.outbox = outbox;
    }

    public Mono<TrustEventProcessingResult> process(ConsumerRecord<String, String> record) {
        return Mono.defer(() -> {
            TrustEventEnvelope envelope = parseEnvelope(record);
            String eventType = envelope.eventType();
            // 载荷在 inbox 前解析——契约错误（缺字段）立即抛，进 DLT 不重投。
            if (DISPUTE_FINALIZED.equals(eventType)) {
                DisputeFinalizedPayload payload = parseDisputeFinalized(envelope);
                return dispatchNew(record, envelope, () -> enqueueReconciliation(envelope, payload));
            }
            if (DISPUTE_OPENED.equals(eventType)) {
                DisputeOpenedPayload payload = parseDisputeOpened(envelope);
                return dispatchNew(record, envelope, () -> emitEngagementDisputed(envelope, payload));
            }
            return Mono.just(TrustEventProcessingResult.IGNORED);
        });
    }

    /**
     * inbox 去重 + 副作用，包在同一 R2DBC 事务（7A 约束）。副作用通过 {@link Supplier} 惰性求值——
     * 仅在 inbox 命中新记录（inserted=true）时才组装（从而才触发仓储调用），重复投递不查库、不发事件。
     */
    private Mono<TrustEventProcessingResult> dispatchNew(
            ConsumerRecord<String, String> record,
            TrustEventEnvelope envelope,
            Supplier<Mono<TrustEventProcessingResult>> sideEffect) {
        String payloadSha256 = payloadSha256(envelope.payload());
        Mono<TrustEventProcessingResult> work = inbox
                .recordIfAbsent(consumerName, record, envelope, payloadSha256)
                .flatMap(inserted -> inserted
                        ? sideEffect.get()
                        : Mono.just(TrustEventProcessingResult.DUPLICATE));
        return transactions.transactional(work);
    }

    /**
     * 落一行对账请求（pending），**不**在此写 EngagementSettled——「争议终局」≠「钱已到位」。
     * 由 {@code SettlementReconciliationDispatcher} 确定性地启动对账 workflow，核对 trust/finance 权威状态、
     * 幂等补执行钱动作、确认后才写 EngagementSettled。workflow id 派生自 disputeId（一争议一对账）。
     */
    private Mono<TrustEventProcessingResult> enqueueReconciliation(
            TrustEventEnvelope source, DisputeFinalizedPayload payload) {
        // D-03 F5：旧 merchant_rejection 已在 trust 同一事务内接续 standard successor。
        // inbox 仍须记账去重，但旧案不能越过 successor 直接驱动资金；successor 终局事件再走正常对账。
        if (payload.settlementDeferred()) {
            return Mono.just(TrustEventProcessingResult.SUPPRESSED);
        }
        String workflowId = "settlement-reconcile-" + payload.disputeId();
        return reconciliations
                .enqueue(source.eventId(), payload.disputeId(), payload.engagementRef(),
                        payload.organizationId(), payload.finalDecision(), workflowId)
                .thenReturn(TrustEventProcessingResult.PROCESSED);
    }

    /**
     * 争议对方通知缺口（草场 Slice 12 遗留）：trust 的 {@code DisputeOpened} 只携带开争议者，
     * 对方账号仅经 {@code engagementRef}（= applicationId）间接引用、不在 trust 表内。marketplace 自有
     * task+application 两表，在本服务内解析对方账号，派生一条 {@code EngagementDisputed} 事件（确定性 eventId）
     * 写本服务 outbox → 由 identity 通知中心消费，通知对方。不动 trust、不需 trust 反查 marketplace。
     *
     * <p>对方判定按 {@code openedByRole}：merchant 开 → 对方=推荐官；recommender 开 → 对方=任务归属商家。
     * application/task 解析不到（engagementRef 过期/任务已删）→ {@link TrustEventProcessingResult#NO_RECIPIENT}，
     * inbox 仍记录、不阻塞分区（镜像 identity「邀请邮箱未注册→静默跳过」语义）。
     *
     * <p><b>D-03 例外（F7）</b>：{@code kind=merchant_rejection} 的争议是本服务 contest 主动经 trust 开的，
     * 且在同一事务已发过 {@code MerchantContested}（收件人=商家+推荐官，文案「履约异议已转客服裁定」）。
     * 该争议经 Kafka 回环到本消费者时若照旧派生 {@code EngagementDisputed}，推荐官会**再收一条**语义更弱的
     * 通用争议通知。故在此按 kind 抑制。
     *
     * <p>抑制点刻意放在 marketplace 而非 identity 侧过滤：① 不写无用 outbox 行、不发无用 Kafka 消息；
     * ② 「这条通知已由谁发过」是 marketplace 自己的知识，不必让 identity 理解 marketplace 内部流程；
     * ③ trust 的 {@code DisputeOpened} 载荷与 identity 的收件人解析都无需改动。
     */
    private Mono<TrustEventProcessingResult> emitEngagementDisputed(
            TrustEventEnvelope source, DisputeOpenedPayload payload) {
        if (payload.isMerchantRejection()) {
            return Mono.just(TrustEventProcessingResult.SUPPRESSED);
        }
        return applications.findById(payload.engagementRef())
                .flatMap((TaskApplication app) -> tasks.findById(app.taskId())
                        .flatMap((Task task) -> {
                            String counterparty = "recommender".equals(payload.openedByRole())
                                    ? task.ownerAccountId()
                                    : app.recommenderAccountId();
                            if (counterparty == null || counterparty.isBlank()) {
                                return Mono.just(TrustEventProcessingResult.NO_RECIPIENT);
                            }
                            return outbox.append(engagementDisputedEnvelope(source.eventId(), payload, counterparty))
                                    .thenReturn(TrustEventProcessingResult.PROCESSED);
                        })
                        .switchIfEmpty(Mono.just(TrustEventProcessingResult.NO_RECIPIENT)))
                .switchIfEmpty(Mono.just(TrustEventProcessingResult.NO_RECIPIENT));
    }

    /** 派生事件：确定性 eventId（type-3 UUID，命名空间「EngagementDisputed:」+ 信源 eventId），重投/重放幂等。 */
    private EventEnvelope engagementDisputedEnvelope(
            String sourceEventId, DisputeOpenedPayload payload, String counterpartyAccountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disputeId", payload.disputeId());
        body.put("engagementRef", payload.engagementRef());
        if (payload.organizationId() != null) {
            body.put("organizationId", payload.organizationId());
        }
        if (payload.openedByAccountId() != null) {
            body.put("openedByAccountId", payload.openedByAccountId());
        }
        body.put("openedByRole", payload.openedByRole());
        body.put("counterpartyAccountId", counterpartyAccountId);
        body.put("status", "open");
        String eventId = UUID.nameUUIDFromBytes(
                ("EngagementDisputed:" + sourceEventId).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "EngagementDisputed", "DisputeCase",
                payload.disputeId(), 1, Instant.now(), null, body);
    }

    private TrustEventEnvelope parseEnvelope(ConsumerRecord<String, String> record) {
        if (record == null || record.value() == null || record.value().isBlank()) {
            throw new EventContractException("trust event value must contain valid JSON");
        }
        JsonNode root;
        try {
            root = mapper.readTree(record.value());
        } catch (Exception error) {
            throw new EventContractException("trust event value must contain valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new EventContractException("trust event envelope must be a JSON object");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new EventContractException("trust event payload must be a JSON object");
        }
        return new TrustEventEnvelope(
                requiredText(root, "eventId"),
                requiredText(root, "eventType"),
                requiredText(root, "aggregateType"),
                requiredText(root, "aggregateId"),
                payload);
    }

    private DisputeFinalizedPayload parseDisputeFinalized(TrustEventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        boolean settlementDeferred = optionalBoolean(payload, "settlementDeferred", false);
        String successorDisputeId = optionalText(payload, "successorDisputeId");
        if (settlementDeferred && (successorDisputeId == null || successorDisputeId.isBlank())) {
            throw new EventContractException(
                    "trust event field successorDisputeId must be a non-blank string when settlementDeferred is true");
        }
        return new DisputeFinalizedPayload(
                requiredText(payload, "disputeId"),
                requiredText(payload, "engagementRef"),
                optionalText(payload, "organizationId"),
                requiredText(payload, "finalDecision"),
                settlementDeferred,
                successorDisputeId);
    }

    private DisputeOpenedPayload parseDisputeOpened(TrustEventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        return new DisputeOpenedPayload(
                requiredText(payload, "disputeId"),
                requiredText(payload, "engagementRef"),
                optionalText(payload, "organizationId"),
                requiredText(payload, "openedByAccountId"),
                requiredDisputeOpenerRole(payload),
                disputeKind(payload));
    }

    /**
     * kind 按**可选**解析：trust V5 才加该列，V5 之前在途/重放的旧事件无此字段，按必填会全部判契约错误进 DLT
     * 且不重投。缺失 → {@code standard}，保留 Slice 12 的对方通知语义（旧事件都是普通争议）。
     */
    private static String disputeKind(JsonNode payload) {
        String kind = optionalText(payload, "kind");
        return kind == null || kind.isBlank() ? DisputeOpenedPayload.KIND_STANDARD : kind;
    }

    /** 对方收件人完全由角色决定，不能把缺失/未知值静默降级成某一方，避免错误通知。 */
    private static String requiredDisputeOpenerRole(JsonNode payload) {
        String role = requiredText(payload, "openedByRole");
        if (!"merchant".equals(role) && !"recommender".equals(role)) {
            throw new EventContractException("trust event field openedByRole must be merchant or recommender");
        }
        return role;
    }

    private String payloadSha256(JsonNode payload) {
        try {
            byte[] canonicalPayload = mapper.writeValueAsBytes(canonicalize(payload));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalPayload);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new EventContractException("trust event payload cannot be canonicalized", error);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode sorted = mapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode array = mapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new EventContractException("trust event field " + field + " must be a non-blank string");
        }
        return value;
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new EventContractException("trust event field " + field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.textValue();
    }
}
