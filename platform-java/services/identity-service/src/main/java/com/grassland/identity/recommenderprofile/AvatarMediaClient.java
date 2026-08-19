package com.grassland.identity.recommenderprofile;

import com.grassland.http.ManagedWebClientFactory;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * identity 到 intelligence 的头像媒体客户端（任务书 #29+#30 D6）。任何上游故障都 fail-closed。
 *
 * <p>头像是账号级资产（无 org 维度），服务断言的 organizationId 传 null；受信 principal=identity
 * 已足够，归属（owner==account）由 {@link AvatarMediaValidator} 在 identity 侧复验。
 */
@Component
public class AvatarMediaClient {

    private static final String INTELLIGENCE_AUDIENCE = "grassland-intelligence";
    private static final ParameterizedTypeReference<Envelope<AvatarMediaMetadata>> METADATA_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<AvatarMediaDownload>> DOWNLOAD_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final IdentityServiceAssertionIssuer issuer;
    private final AvatarMediaValidator validator;
    private final String headerName;
    private final Duration timeout;

    public AvatarMediaClient(
            IdentityServiceAssertionIssuer issuer,
            AvatarMediaValidator validator,
            @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${identity.avatar.media-validation-timeout-ms:3000}") long timeoutMs) {
        this.webClient = ManagedWebClientFactory.create(AvatarMediaClient.class, baseUrl);
        this.issuer = issuer;
        this.validator = validator;
        this.headerName = headerName;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 100));
    }

    /** 复验头像媒体归属/状态；不符 → 400，上游故障 → 503（fail-closed）。 */
    public Mono<AvatarMediaMetadata> requireUsable(UUID mediaId, String accountId) {
        return webClient.get()
                .uri("/api/media/{id}/avatar-metadata", mediaId)
                .header(headerName, issuer.issueForOrganization(null, INTELLIGENCE_AUDIENCE))
                .exchangeToMono(this::readMetadata)
                .switchIfEmpty(Mono.error(new IdentityException(400, "头像媒体不存在或不可用")))
                .map(metadata -> validator.requireUsable(metadata, accountId))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "头像校验服务暂不可用"));
    }

    /** 换短 TTL presigned GET（公开/自己读头像）；上游故障 fail-closed 为 503。 */
    public Mono<AvatarMediaDownload> issueDownloadUrl(UUID mediaId) {
        return webClient.get()
                .uri("/api/media/{id}/avatar-download-url", mediaId)
                .header(headerName, issuer.issueForOrganization(null, INTELLIGENCE_AUDIENCE))
                .exchangeToMono(response -> readResponse(response, DOWNLOAD_TYPE, "头像不存在或不可用"))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "头像读取服务暂不可用"));
    }

    private Mono<AvatarMediaMetadata> readMetadata(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(METADATA_TYPE)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                            ? Mono.just(envelope.data())
                            : Mono.error(new IdentityException(503, "头像校验服务暂不可用")));
        }
        if (status == 404) {
            return response.releaseBody().then(Mono.empty());
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "头像校验服务暂不可用")));
    }

    private <T> Mono<T> readResponse(ClientResponse response,
                                     ParameterizedTypeReference<Envelope<T>> responseType,
                                     String notFoundMessage) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(responseType)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                            ? Mono.just(envelope.data())
                            : Mono.error(new IdentityException(503, "头像读取服务暂不可用")));
        }
        if (status == 404) {
            return response.releaseBody().then(Mono.error(new IdentityException(404, notFoundMessage)));
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "头像读取服务暂不可用")));
    }

    private record Envelope<T>(boolean success, T data) {}
}
