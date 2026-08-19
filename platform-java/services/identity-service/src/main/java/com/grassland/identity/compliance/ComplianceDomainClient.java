package com.grassland.identity.compliance;

import static com.grassland.identity.compliance.ComplianceModels.Blocker;
import static com.grassland.identity.compliance.ComplianceModels.DomainCheck;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ComplianceDomainClient {

    private static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE =
            new ParameterizedTypeReference<>() {};
    private static final int EXPORT_PAGE_SIZE = 500;

    private final WebClient marketplace;
    private final WebClient finance;
    private final WebClient trust;
    private final WebClient intelligence;
    private final IdentityServiceAssertionIssuer assertions;
    private final ComplianceProperties properties;
    private final String headerName;

    public ComplianceDomainClient(
            @Value("${marketplace.service.base-url:http://marketplace-service:8083}") String marketplaceUrl,
            @Value("${finance.service.base-url:http://finance-service:8084}") String financeUrl,
            @Value("${trust.service.base-url:http://trust-service:8085}") String trustUrl,
            @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String intelligenceUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            IdentityServiceAssertionIssuer assertions,
            ComplianceProperties properties) {
        this.marketplace = ManagedWebClientFactory.create(ComplianceDomainClient.class, marketplaceUrl);
        this.finance = ManagedWebClientFactory.create(ComplianceDomainClient.class, financeUrl);
        this.trust = ManagedWebClientFactory.create(ComplianceDomainClient.class, trustUrl);
        this.intelligence = ManagedWebClientFactory.create(ComplianceDomainClient.class, intelligenceUrl);
        this.assertions = assertions;
        this.properties = properties;
        this.headerName = headerName;
    }

    public Mono<DomainCheck> marketplaceCheck(String accountId) {
        return getCheck(marketplace, "marketplace", "grassland-marketplace", accountId);
    }

    public Mono<DomainCheck> financeCheck(String accountId) {
        return getCheck(finance, "finance", "grassland-finance", accountId);
    }

    public Mono<DomainCheck> intelligenceCheck(String accountId) {
        return getCheck(intelligence, "intelligence", "grassland-intelligence", accountId);
    }

    public Mono<DomainCheck> trustCheck(String accountId, Collection<String> engagementRefs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("engagementRefs", engagementRefs == null ? List.of() : engagementRefs);
        return trust.post().uri("/internal/compliance/accounts/{accountId}/closure-check", accountId)
                .header(headerName, token("grassland-trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(ENVELOPE)
                .timeout(properties.upstreamTimeout())
                .map(envelope -> parseCheck("trust", envelope))
                .onErrorReturn(new DomainCheck(List.of(Blocker.unavailable("trust")), List.of()));
    }

    public Mono<List<Map<String, Object>>> financeExport(String accountId) {
        return financeExportPage(accountId, 0, new ArrayList<>());
    }

    public Mono<Void> eraseMarketplace(String accountId) {
        return erase(marketplace, "grassland-marketplace", accountId);
    }

    public Mono<Void> eraseFinance(String accountId) {
        return erase(finance, "grassland-finance", accountId);
    }

    public Mono<Void> eraseTrust(String accountId) {
        return erase(trust, "grassland-trust", accountId);
    }

    public Mono<Void> eraseIntelligence(String accountId) {
        return erase(intelligence, "grassland-intelligence", accountId);
    }

    private Mono<DomainCheck> getCheck(
            WebClient client, String domain, String audience, String accountId) {
        return client.get().uri("/internal/compliance/accounts/{accountId}/closure-check", accountId)
                .header(headerName, token(audience))
                .retrieve().bodyToMono(ENVELOPE)
                .timeout(properties.upstreamTimeout())
                .map(envelope -> parseCheck(domain, envelope))
                .onErrorReturn(new DomainCheck(List.of(Blocker.unavailable(domain)), List.of()));
    }

    private Mono<List<Map<String, Object>>> financeExportPage(
            String accountId, int offset, List<Map<String, Object>> records) {
        return finance.get().uri(builder -> builder
                        .path("/internal/compliance/accounts/{accountId}/financial-records")
                        .queryParam("offset", offset)
                        .queryParam("limit", EXPORT_PAGE_SIZE)
                        .build(accountId))
                .header(headerName, token("grassland-finance"))
                .retrieve().bodyToMono(ENVELOPE)
                .timeout(properties.upstreamTimeout())
                .flatMap(envelope -> {
                    Map<?, ?> data = map(envelope.get("data"));
                    List<Map<String, Object>> page = mapList(data.get("records"));
                    records.addAll(page);
                    boolean hasMore = Boolean.TRUE.equals(data.get("hasMore"));
                    return hasMore
                            ? financeExportPage(accountId, offset + page.size(), records)
                            : Mono.just(List.copyOf(records));
                });
    }

    private Mono<Void> erase(WebClient client, String audience, String accountId) {
        return client.post().uri("/internal/compliance/accounts/{accountId}/erase", accountId)
                .header(headerName, token(audience))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("requester", "identity-retention-worker"))
                .retrieve().bodyToMono(ENVELOPE)
                .timeout(properties.upstreamTimeout())
                .flatMap(envelope -> Boolean.TRUE.equals(envelope.get("success"))
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException("compliance erasure rejected")));
    }

    private String token(String audience) {
        return assertions.issueForOrganization(null, audience);
    }

    private static DomainCheck parseCheck(String domain, Map<String, Object> envelope) {
        if (!Boolean.TRUE.equals(envelope.get("success"))) {
            return new DomainCheck(List.of(Blocker.unavailable(domain)), List.of());
        }
        Map<?, ?> data = map(envelope.get("data"));
        List<Blocker> blockers = new ArrayList<>();
        Object raw = data.get("blockers");
        if (raw instanceof Collection<?> items) {
            for (Object item : items) {
                Map<?, ?> entry = map(item);
                Object domainValue = entry.containsKey("domain") ? entry.get("domain") : domain;
                blockers.add(new Blocker(
                        text(domainValue),
                        text(entry.get("code")),
                        text(entry.get("message")),
                        number(entry.get("count")),
                        nullableNumber(entry.get("amountCents"))));
            }
        }
        List<String> refs = new ArrayList<>();
        Object rawRefs = data.get("engagementRefs");
        if (rawRefs instanceof Collection<?> items) {
            items.stream().map(ComplianceDomainClient::text).filter(value -> !value.isBlank()).forEach(refs::add);
        }
        return new DomainCheck(blockers, refs);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> result ? result : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Collection<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> entry) {
                Map<String, Object> copy = new LinkedHashMap<>();
                entry.forEach((key, field) -> copy.put(String.valueOf(key), field));
                result.add(copy);
            }
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long nullableNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
