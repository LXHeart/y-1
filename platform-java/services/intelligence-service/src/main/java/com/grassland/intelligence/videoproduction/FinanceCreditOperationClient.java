package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Read-only Finance authority lookup used only by the admin reconciliation report. */
@Component
public class FinanceCreditOperationClient {
    private static final String FINANCE_AUDIENCE = "grassland-finance";
    private final WebClient client;
    private final IntelligenceServiceAssertionIssuer assertions;
    private final String path;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public FinanceCreditOperationClient(
            @Value("${credits.finance.base-url:http://finance-service:8084}") String baseUrl,
            @Value("${credits.finance.operation-query-path:/internal/credits/consume-operations/query}")
            String path,
            @Value("${credits.finance.response-timeout-ms:5000}") long timeoutMs,
            IntelligenceServiceAssertionIssuer assertions) {
        this.client = WebClient.builder().baseUrl(baseUrl).build();
        this.assertions = assertions;
        this.path = path;
        this.timeout = Duration.ofMillis(Math.max(1, timeoutMs));
    }

    public Mono<Map<UUID, Operation>> query(List<UUID> operationIds) {
        if (operationIds.isEmpty()) return Mono.just(Map.of());
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", assertions.issueService(FINANCE_AUDIENCE))
                .bodyValue(Map.of("operationIds", operationIds.stream().map(UUID::toString).toList()))
                .retrieve().bodyToMono(String.class).timeout(timeout)
                .map(this::parse);
    }

    private Map<UUID, Operation> parse(String raw) {
        try {
            JsonNode operations = mapper.readTree(raw).at("/data/operations");
            if (!operations.isArray()) throw new IllegalStateException("Finance 对账响应缺少 operations");
            Map<UUID, Operation> result = new LinkedHashMap<>();
            for (JsonNode item : operations) {
                UUID id = UUID.fromString(required(item, "operationId"));
                result.put(id, new Operation(
                        id, required(item, "accountId"), required(item, "feature"),
                        required(item, "state"), optional(item, "source"),
                        item.path("policyVersion").isIntegralNumber()
                                ? item.path("policyVersion").asLong() : null,
                        optional(item, "consumeTransactionId"),
                        optional(item, "refundTransactionId")));
            }
            return Map.copyOf(result);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Finance 对账响应 JSON 无效", error);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) throw new IllegalStateException("Finance 对账响应缺少 " + field);
        return value;
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    public record Operation(
            UUID operationId, String accountId, String feature, String state,
            String source, Long policyVersion,
            String consumeTransactionId, String refundTransactionId) {}
}
