package com.grassland.intelligence.creationcontext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.core.publisher.Mono;

/** Fetches authoritative accepted-task facts from marketplace; intelligence never reads its database. */
@Component
public class MarketplaceCreationContextClient {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final WebClient client;
    private final IntelligenceServiceAssertionIssuer issuer;
    private final String headerName;
    private final ObjectMapper mapper = new ObjectMapper();

    public MarketplaceCreationContextClient(
            IntelligenceServiceAssertionIssuer issuer,
            @Value("${marketplace.service.base-url:http://marketplace-service:8083}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.client = WebClient.builder().baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(3)))).build();
    }

    public Mono<AuthoritativeContext> fetch(String applicationId, String taskId, String accountId) {
        return client.post()
                .uri("/internal/marketplace/engagements/{id}/creation-context", applicationId)
                .header(headerName, issuer.issueService("grassland-marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("taskId", taskId, "recommenderAccountId", accountId))
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(String.class)
                                .switchIfEmpty(Mono.error(new IllegalStateException("任务上下文为空")))
                                .map(this::parse)
                        : response.releaseBody().then(Mono.error(new IntelligenceException(
                                response.statusCode().value(), "无法读取已接受任务上下文"))));
    }

    private AuthoritativeContext parse(String body) {
        try {
            Map<String, Object> envelope = mapper.readValue(body, MAP);
            Object data = envelope.get("data");
            if (!(data instanceof Map<?, ?> map) || !(map.get("taskContext") instanceof Map<?, ?>)) {
                throw new IllegalStateException("任务上下文响应不合法");
            }
            @SuppressWarnings("unchecked") Map<String, Object> context = (Map<String, Object>) map.get("taskContext");
            // 任务书 #24：门店品牌块可选（组织级任务/门店无资料时缺省）。
            Map<String, Object> storeBranding = map.get("storeBranding") instanceof Map<?, ?> branding
                    ? cast(branding)
                    : Map.of();
            return new AuthoritativeContext(context,
                    map.get("organizationId") == null ? null : String.valueOf(map.get("organizationId")),
                    storeBranding);
        } catch (Exception error) {
            throw new IllegalStateException("任务上下文响应不合法", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    public record AuthoritativeContext(
            Map<String, Object> taskContext, String organizationId,
            Map<String, Object> storeBranding) {}
}
