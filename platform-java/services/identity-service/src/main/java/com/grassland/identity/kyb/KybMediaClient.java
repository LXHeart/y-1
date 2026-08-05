package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** identity 到 intelligence 的 KYB 媒体元数据客户端。任何上游故障都 fail-closed。 */
@Component
public class KybMediaClient {

    private static final String INTELLIGENCE_AUDIENCE = "grassland-intelligence";
    private static final ParameterizedTypeReference<Envelope<KybMediaMetadata>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<KybUploadTicket>> UPLOAD_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<KybMediaDownload>> DOWNLOAD_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<KybMediaRetentionReceipt>> RETENTION_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final IdentityServiceAssertionIssuer issuer;
    private final KybMediaValidator validator;
    private final String headerName;
    private final Duration timeout;

    public KybMediaClient(
            IdentityServiceAssertionIssuer issuer,
            KybMediaValidator validator,
            @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${identity.kyb.media-validation-timeout-ms:3000}") long timeoutMs) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.issuer = issuer;
        this.validator = validator;
        this.headerName = headerName;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 100));
    }

    public Mono<KybMediaMetadata> requireUsable(UUID mediaId, String organizationId, String accountId) {
        return webClient.get()
                .uri("/api/media/{id}/kyb-metadata", mediaId)
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .exchangeToMono(this::readResponse)
                .switchIfEmpty(Mono.error(new IdentityException(400, "附件媒体不存在或不可用")))
                .map(metadata -> validator.requireUsable(metadata, accountId, organizationId))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "媒体校验服务暂不可用"));
    }

    public Mono<KybUploadTicket> createUploadTicket(String organizationId, String accountId,
                                                     String contentType, long sizeBytes) {
        return webClient.post()
                .uri("/api/media/kyb-upload-tickets")
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .bodyValue(new UploadTicketRequest(accountId, contentType, sizeBytes))
                .exchangeToMono(response -> readResponse(response, UPLOAD_RESPONSE_TYPE,
                        "KYB 上传凭据申请失败"))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "媒体校验服务暂不可用"));
    }

    public Mono<KybMediaDownload> issueDownloadUrl(UUID mediaId, String organizationId) {
        return webClient.get()
                .uri("/api/media/{id}/kyb-download-url", mediaId)
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .exchangeToMono(response -> readResponse(response, DOWNLOAD_RESPONSE_TYPE,
                        "审核材料不存在或不可用"))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "媒体校验服务暂不可用"));
    }

    public Mono<Void> retain(UUID mediaId, String organizationId, UUID referenceId) {
        return webClient.post()
                .uri("/api/media/{id}/kyb-retentions", mediaId)
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .bodyValue(Map.of("referenceId", referenceId.toString()))
                .exchangeToMono(response -> retentionResponse(response, "媒体留存失败"))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "媒体留存服务暂不可用"));
    }

    public Mono<KybMediaRetentionReceipt> acquireLease(
            UUID mediaId, String organizationId, UUID referenceId,
            String referenceType, long leaseSeconds) {
        return upsertRetention(mediaId, organizationId, referenceId,
                new UpsertRetentionRequest(referenceType, "lease", leaseSeconds, null));
    }

    public Mono<KybMediaRetentionReceipt> seal(
            UUID mediaId, String organizationId, UUID referenceId,
            String referenceType, Instant retainUntil) {
        return upsertRetention(mediaId, organizationId, referenceId,
                new UpsertRetentionRequest(referenceType, "sealed", null, retainUntil));
    }

    public Mono<Void> release(UUID mediaId, String organizationId, UUID referenceId) {
        return webClient.delete()
                .uri("/api/media/{id}/kyb-retentions/{referenceId}", mediaId, referenceId)
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .exchangeToMono(response -> retentionResponse(response, "媒体留存释放失败"))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "媒体留存服务暂不可用"));
    }

    private Mono<KybMediaRetentionReceipt> upsertRetention(
            UUID mediaId, String organizationId, UUID referenceId, UpsertRetentionRequest body) {
        return webClient.put()
                .uri("/api/media/{id}/kyb-retentions/{referenceId}", mediaId, referenceId)
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .bodyValue(body)
                .exchangeToMono(response -> readResponse(
                        response, RETENTION_RESPONSE_TYPE, "媒体留存失败"))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "媒体留存服务暂不可用"));
    }

    private Mono<KybMediaMetadata> readResponse(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(RESPONSE_TYPE)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                            ? Mono.just(envelope.data())
                            : Mono.error(new IdentityException(503, "媒体校验服务暂不可用")));
        }
        if (status == 404) {
            return response.releaseBody().then(Mono.empty());
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "媒体校验服务暂不可用")));
    }

    private <T> Mono<T> readResponse(ClientResponse response,
                                     ParameterizedTypeReference<Envelope<T>> responseType,
                                     String notFoundMessage) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(responseType)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                            ? Mono.just(envelope.data())
                            : Mono.error(new IdentityException(503, "媒体校验服务暂不可用")));
        }
        if (status == 400) {
            return response.releaseBody().then(Mono.error(new IdentityException(400, notFoundMessage)));
        }
        if (status == 404) {
            return response.releaseBody().then(Mono.error(new IdentityException(409, notFoundMessage)));
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "媒体校验服务暂不可用")));
    }

    private Mono<Void> retentionResponse(ClientResponse response, String notFoundMessage) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.releaseBody();
        }
        if (status == 404) {
            return response.releaseBody().then(Mono.error(new IdentityException(409, notFoundMessage)));
        }
        return response.releaseBody().then(Mono.error(new IdentityException(503, "媒体留存服务暂不可用")));
    }

    private record Envelope<T>(boolean success, T data) {}
    private record UploadTicketRequest(String ownerAccountId, String contentType, long sizeBytes) {}
    private record UpsertRetentionRequest(
            String referenceType, String mode, Long leaseSeconds, Instant retainUntil) {}
}
