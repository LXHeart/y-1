package com.grassland.marketplace.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.analytics.AnalyticsModels.RecordEventRequest;
import com.grassland.marketplace.analytics.MarketingAttributionModels.Campaign;
import com.grassland.marketplace.analytics.MarketingAttributionModels.ProviderEvent;
import com.grassland.marketplace.analytics.MarketingAttributionRepository.WebhookClaim;
import com.grassland.marketplace.security.MarketplaceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Public provider ingress. Trust comes from the provider HMAC and a server-side campaign binding. */
@RestController
public class MarketingAttributionWebhookController {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> EVENT_TYPES = Set.of(
            "exposure", "interaction", "conversion", "conversion_refund");
    private final MarketingAttributionWebhookVerifier verifier;
    private final MarketingAttributionRepository attribution;
    private final AnalyticsRepository analytics;
    private final ObjectMapper mapper;

    public MarketingAttributionWebhookController(MarketingAttributionWebhookVerifier verifier,
                                                 MarketingAttributionRepository attribution,
                                                 AnalyticsRepository analytics, ObjectMapper mapper) {
        this.verifier = verifier;
        this.attribution = attribution;
        this.analytics = analytics;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/analytics/webhooks/{provider}", consumes = "application/json")
    public Mono<ResponseEntity<Map<String, Object>>> receive(
            @PathVariable String provider, @RequestBody String rawBody, ServerHttpRequest request) {
        return Mono.defer(() -> {
            String normalizedProvider = normalizeProvider(provider);
            if (rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                throw new MarketplaceException(413, "归因 webhook 请求体超过 1 MB");
            }
            String eventId = request.getHeaders().getFirst("X-Marketing-Event-Id");
            String timestamp = request.getHeaders().getFirst("X-Marketing-Timestamp");
            String signature = request.getHeaders().getFirst("X-Marketing-Signature");
            verifier.verify(normalizedProvider, eventId, timestamp, signature, rawBody, Instant.now());
            ProviderEvent event = parse(eventId, rawBody);
            String hash = sha256(rawBody);
            return attribution.findActive(normalizedProvider, event.externalCampaignId())
                    .switchIfEmpty(Mono.error(new MarketplaceException(409, "Campaign 未绑定或已停用")))
                    .flatMap(campaign -> ingest(normalizedProvider, hash, campaign, event));
        }).onErrorMap(IllegalStateException.class, error -> new MarketplaceException(503, error.getMessage()));
    }

    private Mono<ResponseEntity<Map<String, Object>>> ingest(
            String provider, String payloadHash, Campaign campaign, ProviderEvent event) {
        return attribution.claimWebhook(provider, event.eventId(), payloadHash).flatMap(claim -> {
            if (claim == WebhookClaim.PAYLOAD_CONFLICT) {
                return Mono.error(new MarketplaceException(409, "Webhook eventId 已被不同载荷使用"));
            }
            if (claim == WebhookClaim.DUPLICATE) {
                return Mono.just(ResponseEntity.ok(success(Map.of(
                        "accepted", true, "duplicate", true, "eventId", event.eventId()))));
            }
            RecordEventRequest request = new RecordEventRequest(
                    "webhook:" + provider + ":" + sha256(event.eventId()), event.sourceEventId(), event.eventType(),
                    campaign.organizationId(), campaign.storeId(), campaign.taskId(),
                    campaign.recommenderAccountId(), event.occurredAt(), event.valueCents(),
                    Map.of("provider", provider, "externalCampaignId", event.externalCampaignId(),
                            "webhookEventId", event.eventId()));
            return analytics.record(request, null, provider)
                    .flatMap(result -> attribution.markWebhookProcessed(provider, event.eventId())
                            .thenReturn(ResponseEntity.ok(success(Map.of(
                                    "accepted", true, "duplicate", !result.created(),
                                    "eventId", event.eventId(), "eventType", event.eventType())))));
        });
    }

    private ProviderEvent parse(String eventId, String rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Webhook JSON 必须是对象");
            JsonNode data = root.path("data").isObject() ? root.path("data") : root;
            String type = requiredText(data, root, "eventType", "event_type", "type").toLowerCase(Locale.ROOT);
            type = switch (type) {
                case "impression" -> "exposure";
                case "click", "engagement" -> "interaction";
                case "purchase" -> "conversion";
                case "refund" -> "conversion_refund";
                default -> type;
            };
            if (!EVENT_TYPES.contains(type)) throw new IllegalArgumentException("Webhook eventType 不受支持");
            String campaignId = requiredText(data, root,
                    "externalCampaignId", "external_campaign_id", "campaignId", "campaign_id");
            if (campaignId.length() > 160) throw new IllegalArgumentException("Webhook campaignId 过长");
            Instant occurredAt = instant(value(data, root, "occurredAt", "occurred_at", "timestamp"));
            long valueCents = longValue(value(data, root, "valueCents", "value_cents", "amountCents", "amount_cents"));
            if (!"conversion".equals(type) && !"conversion_refund".equals(type) && valueCents != 0) {
                throw new IllegalArgumentException("曝光/互动事件 valueCents 必须为 0");
            }
            String sourceEventId = optionalText(data, root,
                    "sourceEventId", "source_event_id", "conversionId", "orderId");
            if (sourceEventId != null && sourceEventId.length() > 160) {
                throw new IllegalArgumentException("Webhook sourceEventId 过长");
            }
            return new ProviderEvent(eventId, type, campaignId, occurredAt, valueCents, sourceEventId);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Webhook JSON 无效", error);
        }
    }

    private static JsonNode value(JsonNode preferred, JsonNode fallback, String... names) {
        for (String name : names) if (preferred.hasNonNull(name)) return preferred.get(name);
        for (String name : names) if (fallback.hasNonNull(name)) return fallback.get(name);
        return null;
    }

    private static String requiredText(JsonNode preferred, JsonNode fallback, String... names) {
        String text = optionalText(preferred, fallback, names);
        if (text == null) throw new IllegalArgumentException("Webhook 缺少字段 " + names[0]);
        return text;
    }

    private static String optionalText(JsonNode preferred, JsonNode fallback, String... names) {
        JsonNode node = value(preferred, fallback, names);
        if (node == null || node.asText().isBlank()) return null;
        return node.asText().trim();
    }

    private static Instant instant(JsonNode node) {
        if (node == null) return Instant.now();
        if (node.isNumber()) {
            long value = node.asLong();
            return value > 10_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        try { return Instant.parse(node.asText()); }
        catch (DateTimeParseException error) { throw new IllegalArgumentException("Webhook occurredAt 无效"); }
    }

    private static long longValue(JsonNode node) {
        if (node == null) return 0L;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
            throw new IllegalArgumentException("Webhook valueCents 必须是非负整数");
        }
        return node.asLong();
    }

    private static String normalizeProvider(String provider) {
        String value = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 48 || !value.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("provider 格式错误");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算 Webhook 摘要", error);
        }
    }

    private static Map<String, Object> success(Object data) { return Map.of("success", true, "data", data); }
}
