package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
public class TrustEventProcessor {

    private static final String DISPUTE_FINALIZED = "DisputeFinalized";

    private final InboxRepository inbox;
    private final SettlementReconciliationRepository reconciliations;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;
    private final String consumerName;

    public TrustEventProcessor(
            InboxRepository inbox,
            SettlementReconciliationRepository reconciliations,
            TransactionalOperator transactions,
            ObjectMapper mapper,
            @Value("${marketplace.trust-consumer.group-id:marketplace-trust-consumer}") String consumerName) {
        this.inbox = inbox;
        this.reconciliations = reconciliations;
        this.transactions = transactions;
        this.mapper = mapper;
        this.consumerName = consumerName;
    }

    public Mono<TrustEventProcessingResult> process(ConsumerRecord<String, String> record) {
        return Mono.defer(() -> {
            TrustEventEnvelope envelope = parseEnvelope(record);
            if (!DISPUTE_FINALIZED.equals(envelope.eventType())) {
                return Mono.just(TrustEventProcessingResult.IGNORED);
            }
            DisputeFinalizedPayload payload = parseDisputeFinalized(envelope);
            String payloadSha256 = payloadSha256(envelope.payload());
            Mono<TrustEventProcessingResult> work = inbox
                    .recordIfAbsent(consumerName, record, envelope, payloadSha256)
                    .flatMap(inserted -> inserted
                            ? enqueueReconciliation(envelope, payload)
                            : Mono.just(TrustEventProcessingResult.DUPLICATE));
            return transactions.transactional(work);
        });
    }

    /**
     * 落一行对账请求（pending），**不**在此写 EngagementSettled——「争议终局」≠「钱已到位」。
     * 由 {@code SettlementReconciliationDispatcher} 确定性地启动对账 workflow，核对 trust/finance 权威状态、
     * 幂等补执行钱动作、确认后才写 EngagementSettled。workflow id 派生自 disputeId（一争议一对账）。
     */
    private Mono<TrustEventProcessingResult> enqueueReconciliation(
            TrustEventEnvelope source, DisputeFinalizedPayload payload) {
        String workflowId = "settlement-reconcile-" + payload.disputeId();
        return reconciliations
                .enqueue(source.eventId(), payload.disputeId(), payload.engagementRef(),
                        payload.organizationId(), payload.finalDecision(), workflowId)
                .thenReturn(TrustEventProcessingResult.PROCESSED);
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
        return new DisputeFinalizedPayload(
                requiredText(payload, "disputeId"),
                requiredText(payload, "engagementRef"),
                optionalText(payload, "organizationId"),
                requiredText(payload, "finalDecision"));
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
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
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

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.textValue();
    }
}
