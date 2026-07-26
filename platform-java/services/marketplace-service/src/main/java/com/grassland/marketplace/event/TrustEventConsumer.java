package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * trust 事件消费方（草场 Epic 6 Slice 6C Phase E / HLD 10.5）——平台首个 Kafka 消费方。
 *
 * <p>消费 {@code grassland.trust.events} 的 {@code DisputeFinalized}（争议终局、钱侧已由 trust Phase D 分派）→
 * 为该 engagement（{@code payload.engagementRef}=marketplace applicationId）补一条 marketplace outbox
 * {@code EngagementSettled}，使 {@link OutboxRepository#latestSettlementStatus} 从 held 翻 settled（结算状态同步）。
 * <b>不动钱</b>——资金 release/capture/reverse 已由 trust {@code releaseHoldAndApplyDecision} 完成。
 *
 * <p><b>幂等</b>：marketplace outbox event_id = type-3 UUID("SettlementResolved:"+trustEventId)，append 走
 * {@code ON CONFLICT (event_id) DO NOTHING}，Kafka 至少一次重投不会重复落地。opt-in（{@code marketplace.trust-consumer.enabled}），
 * 测试不启用（handler 直接单测）。
 */
@Component
@ConditionalOnProperty(name = "marketplace.trust-consumer.enabled", havingValue = "true")
public class TrustEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrustEventConsumer.class);

    private final OutboxRepository outbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrustEventConsumer(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @KafkaListener(
            topics = "${marketplace.trust-consumer.topic:grassland.trust.events}",
            groupId = "${marketplace.trust-consumer.group-id:marketplace-trust-consumer}",
            autoStartup = "${marketplace.trust-consumer.auto-startup:true}")
    public void onEvent(String envelopeJson) {
        handle(envelopeJson);
    }

    void handle(String envelopeJson) {
        JsonNode root;
        try {
            root = mapper.readTree(envelopeJson);
        } catch (Exception e) {
            log.warn("trust event parse failed, ignoring: {}", e.getMessage());
            return;
        }
        String eventType = root.path("eventType").asText();
        if (!"DisputeFinalized".equals(eventType)) {
            return;  // 仅终局事件（钱侧已分派）；DisputeDecided/Opened/Assigned 等不影响结算态
        }
        String trustEventId = root.path("eventId").asText();
        String appId = root.path("payload").path("engagementRef").asText();
        String decision = root.path("payload").path("finalDecision").asText();
        if (appId.isBlank()) {
            log.warn("DisputeFinalized 无 engagementRef，忽略: {}", root.path("aggregateId").asText());
            return;
        }
        String derivedId = UUID.nameUUIDFromBytes(
                ("SettlementResolved:" + trustEventId).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", appId);
        payload.put("reason", decision.isBlank() ? "adjudication" : "adjudication:" + decision);
        EventEnvelope resolved = new EventEnvelope(derivedId, "EngagementSettled", "TaskApplication",
                appId, 1, Instant.now(), null, payload);
        outbox.append(resolved)
                .onErrorResume(e -> {
                    log.error("append SettlementResolved failed for app {}", appId, e);
                    return Mono.empty();
                })
                .block();  // 幂等：重复 trust eventId → 同 derivedId → ON CONFLICT no-op
        log.info("DisputeFinalized consumed: app={} decision={} → settled", appId, decision);
    }
}
