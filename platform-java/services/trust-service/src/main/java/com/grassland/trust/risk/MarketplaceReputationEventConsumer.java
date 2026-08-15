package com.grassland.trust.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.trust.risk.RiskModels.RegisterSignalRequest;
import com.grassland.trust.security.TrustCallerResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * D-05 merchant-side reputation consumer. Marketplace already emits the immutable cancel fact;
 * trust derives a merchant risk signal without a synchronous cross-service lookup.
 */
@Component
@ConditionalOnProperty(name = "trust.marketplace-consumer.enabled", havingValue = "true")
public class MarketplaceReputationEventConsumer {
    private static final String EVENT_TYPE = "EngagementRefundedOnCancel";
    private static final String RULE = "merchant_cancelled_engagement";

    private final ObjectMapper mapper;
    private final RiskService risk;
    private final RiskRepository repository;
    private final TrustCallerResolver.Caller actor = new TrustCallerResolver.Caller(
            null, null, null, "service", "marketplace", null, "service");

    public MarketplaceReputationEventConsumer(ObjectMapper mapper, RiskService risk, RiskRepository repository) {
        this.mapper = mapper;
        this.risk = risk;
        this.repository = repository;
    }

    @KafkaListener(
            topics = "${trust.marketplace-consumer.topic:grassland.marketplace.events}",
            groupId = "${trust.marketplace-consumer.group-id:trust-marketplace-reputation}",
            autoStartup = "${trust.marketplace-consumer.auto-startup:true}")
    public Mono<Void> onEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        return Mono.defer(() -> parse(record.value()))
                .flatMap(envelope -> {
                    if (!EVENT_TYPE.equals(text(envelope, "eventType"))) return Mono.empty();
                    JsonNode payload = envelope.path("payload");
                    String reason = payload.path("reason").asText("");
                    String taskOwnerId = first(payload, "taskOwnerId", "ownerAccountId");
                    String organizationId = payload.path("organizationId").asText("");
                    String applicationId = payload.path("applicationId").asText("");
                    if (!"merchant_cancel".equals(reason) || blank(taskOwnerId)
                            || blank(organizationId) || blank(applicationId)) {
                        return Mono.error(new IllegalArgumentException("EngagementRefundedOnCancel payload 不完整"));
                    }
                    String eventId = text(envelope, "eventId");
                    if (blank(eventId)) return Mono.error(new IllegalArgumentException("缺少 eventId"));
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("taskId", payload.path("taskId").asText(null));
                    evidence.put("applicationId", applicationId);
                    evidence.put("recommenderAccountId", payload.path("recommenderAccountId").asText(null));
                    return repository.countSignals("account", taskOwnerId, RULE)
                            .flatMap(existing -> risk.register(new RegisterSignalRequest(
                                    "marketplace", eventId, "account", taskOwnerId, organizationId,
                                    RULE, "v1", null, existing + 1, parseInstant(envelope), evidence), actor))
                            .then();
                })
                .then(Mono.fromRunnable(acknowledgment::acknowledge));
    }

    private Mono<JsonNode> parse(String json) {
        try { return Mono.just(mapper.readTree(json)); }
        catch (Exception error) { return Mono.error(new IllegalArgumentException("marketplace 事件 JSON 无效", error)); }
    }

    private static String text(JsonNode node, String field) { return node.path(field).asText(""); }
    private static String first(JsonNode node, String a, String b) {
        String value = node.path(a).asText(""); return blank(value) ? node.path(b).asText("") : value;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static Instant parseInstant(JsonNode envelope) {
        String value = text(envelope, "occurredAt");
        try { return value.isBlank() ? Instant.now() : Instant.parse(value); }
        catch (RuntimeException ignored) { return Instant.now(); }
    }
}
