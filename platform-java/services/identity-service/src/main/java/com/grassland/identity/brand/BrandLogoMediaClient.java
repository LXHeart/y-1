package com.grassland.identity.brand;

import com.grassland.http.ManagedWebClientFactory;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * identity 到 intelligence 的品牌 Logo 媒体客户端（#32 D7）。单端点双用：
 * {@code GET /api/media/{id}/brand-logo-url} 既是归属/可用性校验信号（HTTP 状态），也是展示 URL 来源
 * （{@code downloadUrl} 字段）。
 *
 * <ul>
 *   <li>{@link #usableLogoUrl} — PUT 保存前 fail-closed 复验：200 → URL；404（不符四重过滤/不存在/
 *       非 active）→ empty；上游故障 → {@code 503 品牌Logo服务暂不可用}。</li>
 *   <li>{@link #createTicket} — ADMIN+ 授权后代开上传票据；4xx 透传同码 + 上游中文错误；5xx → 503。</li>
 *   <li>{@link #logoUrlFailSoft} — GET 资料展示用：任何失败都置空（empty）仅记日志，资料仍可读。</li>
 * </ul>
 */
@Component
public class BrandLogoMediaClient {

    private static final Logger log = LoggerFactory.getLogger(BrandLogoMediaClient.class);

    private static final String INTELLIGENCE_AUDIENCE = "grassland-intelligence";
    private static final ParameterizedTypeReference<Envelope<BrandLogoUploadTicket>> TICKET_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<BrandLogoDownload>> DOWNLOAD_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErrorEnvelope> ERROR_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final IdentityServiceAssertionIssuer issuer;
    private final String headerName;
    private final Duration timeout;

    public BrandLogoMediaClient(
            IdentityServiceAssertionIssuer issuer,
            @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${identity.brand.media-validation-timeout-ms:3000}") long timeoutMs) {
        this.webClient = ManagedWebClientFactory.create(BrandLogoMediaClient.class, baseUrl);
        this.issuer = issuer;
        this.headerName = headerName;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 100));
    }

    /** 校验 + 换取 Logo 展示 URL：404 → empty（调用方转 400/置空）；上游故障 fail-closed 503。 */
    public Mono<String> usableLogoUrl(String mediaId, String organizationId) {
        return webClient.get()
                .uri("/api/media/{id}/brand-logo-url", mediaId)
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .exchangeToMono(this::readLogoUrl)
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "品牌Logo服务暂不可用"));
    }

    /** 代开品牌 Logo 上传票据；4xx 透传同状态码 + 上游中文错误，其余故障 fail-closed 503。 */
    public Mono<BrandLogoUploadTicket> createTicket(String organizationId, String ownerAccountId,
                                                    String contentType, long sizeBytes) {
        return webClient.post()
                .uri("/api/media/brand-logo-upload-tickets")
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .bodyValue(new TicketRequest(ownerAccountId, contentType, sizeBytes))
                .exchangeToMono(this::readTicket)
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "品牌Logo服务暂不可用"));
    }

    /** 展示用 fail-soft 包装（D7）：解析失败置空（empty → 调用方映射 null）仅记日志，资料仍可读。 */
    public Mono<String> logoUrlFailSoft(String mediaId, String organizationId) {
        return usableLogoUrl(mediaId, organizationId)
                .onErrorResume(error -> {
                    log.warn("brand logo url resolution failed: mediaId={}", mediaId, error);
                    return Mono.empty();
                });
    }

    private Mono<String> readLogoUrl(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(DOWNLOAD_TYPE)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                                    && envelope.data().downloadUrl() != null
                            ? Mono.just(envelope.data().downloadUrl().toString())
                            : Mono.error(new IdentityException(503, "品牌Logo服务暂不可用")));
        }
        if (status == 404) {
            return response.releaseBody().then(Mono.empty());
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "品牌Logo服务暂不可用")));
    }

    private Mono<BrandLogoUploadTicket> readTicket(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(TICKET_TYPE)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                            ? Mono.just(envelope.data())
                            : Mono.error(new IdentityException(503, "品牌Logo服务暂不可用")));
        }
        if (status >= 400 && status < 500) {
            return upstreamError(response);
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "品牌Logo服务暂不可用")));
    }

    /**
     * 读取上游错误信封 {@code {success:false,error}} 并透传同码 + 中文错误；
     * 信封缺失回通用文案，信封本身不可读（解码失败）则异常外泄由调用侧 onErrorMap 收敛为 503。
     */
    private <T> Mono<T> upstreamError(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(ERROR_TYPE)
                .map(error -> error.error() == null || error.error().isBlank()
                        ? new IdentityException(status, "品牌Logo服务暂不可用")
                        : new IdentityException(status, error.error()))
                .defaultIfEmpty(new IdentityException(status, "品牌Logo服务暂不可用"))
                .flatMap(Mono::error);
    }

    private record Envelope<T>(boolean success, T data) {}

    private record ErrorEnvelope(boolean success, String error) {}

    private record TicketRequest(String ownerAccountId, String contentType, long sizeBytes) {}

    private record BrandLogoDownload(URI downloadUrl, Instant expiresAt) {}
}
