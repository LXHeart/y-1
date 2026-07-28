package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
public class TrustEventProcessor {

    private static final String DISPUTE_FINALIZED = "DisputeFinalized";

    private final InboxRepository inbox;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;
    private final String consumerName;

    public TrustEventProcessor(
            InboxRepository inbox,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            ObjectMapper mapper,
            @Value("${marketplace.trust-consumer.group-id:marketplace-trust-consumer}") String consumerName) {
        this.inbox = inbox;
        this.outbox = outbox;
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
                            ? appendDerivedEvent(envelope, payload)
                            : Mono.just(TrustEventProcessingResult.DUPLICATE));
            return transactions.transactional(work);
        });
    }

    private Mono<TrustEventProcessingResult> appendDerivedEvent(
            TrustEventEnvelope source, DisputeFinalizedPayload payload) {
        String derivedId = UUID.nameUUIDFromBytes(
                        ("SettlementResolved:" + source.eventId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        String decision = payload.finalDecision();
        String reason = decision == null || decision.isBlank()
                ? "adjudication"
                : "adjudication:" + decision;
        EventEnvelope derived = new EventEnvelope(
                derivedId,
                "EngagementSettled",
                "TaskApplication",
                payload.engagementRef(),
                1,
                Instant.now(),
                source.eventId(),
                Map.of("applicationId", payload.engagementRef(), "reason", reason));
        return outbox.append(derived).thenReturn(TrustEventProcessingResult.PROCESSED);
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
                optionalText(payload, "disputeId"),
                requiredText(payload, "engagementRef"),
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
