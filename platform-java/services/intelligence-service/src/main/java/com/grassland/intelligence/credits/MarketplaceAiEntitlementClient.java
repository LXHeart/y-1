package com.grassland.intelligence.credits;

import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Fetches the authoritative reputation AI entitlement and fails closed on every invalid response. */
@Component
public final class MarketplaceAiEntitlementClient {

    private final WebClient webClient;
    private final IntelligenceServiceAssertionIssuer issuer;
    private final String headerName;
    private final Duration timeout;

    public MarketplaceAiEntitlementClient(
            IntelligenceServiceAssertionIssuer issuer,
            @Value("${marketplace.service.base-url:http://marketplace-service:8083}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${marketplace.service.ai-entitlement-timeout-seconds:3}") long timeoutSeconds) {
        this.issuer = issuer;
        this.headerName = headerName;
        long boundedSeconds = Math.max(1, Math.min(timeoutSeconds, 30));
        this.timeout = Duration.ofSeconds(boundedSeconds);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(timeout.toMillis()))
                .responseTimeout(timeout);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .build();
    }

    public Mono<AiEntitlement> get(String accountId) {
        if (!isCanonicalUuid(accountId)) {
            return Mono.error(failClosed());
        }
        return Mono.defer(() -> webClient.get()
                        .uri("/internal/marketplace/reputation/{accountId}/ai-entitlement", accountId)
                        .header(headerName, issuer.issueService("grassland-marketplace"))
                        .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                                ? response.bodyToMono(EntitlementEnvelope.class)
                                        .switchIfEmpty(Mono.error(failClosed()))
                                : response.releaseBody().then(Mono.error(failClosed()))))
                .map(body -> validate(accountId, body))
                .timeout(timeout)
                .onErrorMap(error -> error instanceof IntelligenceException ? error : failClosed());
    }

    private static AiEntitlement validate(String requestedAccountId, EntitlementEnvelope envelope) {
        if (envelope == null || !envelope.success() || envelope.data() == null) {
            throw failClosed();
        }
        EntitlementData data = envelope.data();
        if (!requestedAccountId.equals(data.accountId())
                || data.aiQuotaMultiplierBps() == null
                || data.aiQuotaMultiplierBps() < 1_000
                || data.aiQuotaMultiplierBps() > 100_000
                || data.policyVersion() == null
                || data.policyVersion() < 1) {
            throw failClosed();
        }
        return new AiEntitlement(data.accountId(), data.aiQuotaMultiplierBps(), data.policyVersion());
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static IntelligenceException failClosed() {
        return new IntelligenceException(502, "声誉权益服务暂不可用");
    }

    private record EntitlementEnvelope(boolean success, EntitlementData data) {}

    private record EntitlementData(
            String accountId, Integer aiQuotaMultiplierBps, Long policyVersion) {}

    public record AiEntitlement(String accountId, int aiQuotaMultiplierBps, long policyVersion) {}
}
