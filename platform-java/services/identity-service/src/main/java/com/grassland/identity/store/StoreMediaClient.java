package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * identity 到 intelligence 的门店媒体客户端（#42 D2/D5，镜像 {@code BrandLogoMediaClient}）。
 *
 * <ul>
 *   <li>{@link #createTicket} — MANAGER 授权后代开门店媒体上传票据；4xx 透传同码 + 上游中文错误。</li>
 *   <li>{@link #downloadUrls} — 批量换整店短时 GET URL（四重过滤子集语义）：绑定时调用方
 *       fail-closed（缺席 → 400），展示时单项被滤静默跳过；上游整体故障 → 503。</li>
 * </ul>
 *
 * <p>断言头 {@code issueForOrganization(orgId, "grassland-intelligence")}；组织上下文只取服务断言。
 * 5xx/超时/坏信封一律 fail-closed {@code 503 门店媒体服务暂不可用}。
 */
@Component
public class StoreMediaClient {

    private static final String INTELLIGENCE_AUDIENCE = "grassland-intelligence";
    private static final ParameterizedTypeReference<Envelope<StoreMediaUploadTicket>> TICKET_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<DownloadUrlsData>> DOWNLOAD_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErrorEnvelope> ERROR_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final IdentityServiceAssertionIssuer issuer;
    private final String headerName;
    private final Duration timeout;

    public StoreMediaClient(
            IdentityServiceAssertionIssuer issuer,
            @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${identity.store.media-validation-timeout-ms:3000}") long timeoutMs) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.issuer = issuer;
        this.headerName = headerName;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 100));
    }

    /** 代开门店媒体上传票据；4xx 透传同状态码 + 上游中文错误，其余故障 fail-closed 503。 */
    public Mono<StoreMediaUploadTicket> createTicket(String organizationId, String ownerAccountId,
                                                     String storeId, String contentType, long sizeBytes) {
        return webClient.post()
                .uri("/api/media/store-media-upload-tickets")
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .bodyValue(new TicketRequest(ownerAccountId, storeId, contentType, sizeBytes))
                .exchangeToMono(this::readTicket)
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "门店媒体服务暂不可用"));
    }

    /**
     * 批量换整店下载 URL（#42 D5）：返回通过四重过滤的子集（id → 解析结果）；
     * 过滤失败项缺席，由调用方按 fail-closed/静默跳过语义处理。4xx 透传，其余故障 503。
     */
    public Mono<Map<String, ResolvedMedia>> downloadUrls(String organizationId, String storeId,
                                                         List<String> mediaIds) {
        return webClient.post()
                .uri("/api/media/store-media-download-urls")
                .header(headerName, issuer.issueForOrganization(organizationId, INTELLIGENCE_AUDIENCE))
                .bodyValue(new DownloadRequest(storeId, mediaIds))
                .exchangeToMono(this::readDownloads)
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof IdentityException),
                        error -> new IdentityException(503, "门店媒体服务暂不可用"));
    }

    private Mono<StoreMediaUploadTicket> readTicket(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(TICKET_TYPE)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                            ? Mono.just(envelope.data())
                            : Mono.error(new IdentityException(503, "门店媒体服务暂不可用")));
        }
        if (status >= 400 && status < 500) {
            return upstreamError(response);
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "门店媒体服务暂不可用")));
    }

    private Mono<Map<String, ResolvedMedia>> readDownloads(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 200) {
            return response.bodyToMono(DOWNLOAD_TYPE)
                    .flatMap(envelope -> envelope.success() && envelope.data() != null
                                    && envelope.data().items() != null
                            ? Mono.just(toResolvedMap(envelope.data().items()))
                            : Mono.error(new IdentityException(503, "门店媒体服务暂不可用")));
        }
        if (status >= 400 && status < 500) {
            return upstreamError(response);
        }
        return response.releaseBody()
                .then(Mono.error(new IdentityException(503, "门店媒体服务暂不可用")));
    }

    private static Map<String, ResolvedMedia> toResolvedMap(List<DownloadItem> items) {
        Map<String, ResolvedMedia> resolved = new LinkedHashMap<>();
        for (DownloadItem item : items) {
            if (item == null || item.id() == null || item.downloadUrl() == null) {
                // 字段漂移/坏项 fail-closed：不得把 null URL 当成功，直接视为缺席。
                continue;
            }
            resolved.put(item.id().toString(), new ResolvedMedia(
                    item.mimeType(), item.sizeBytes(), item.downloadUrl().toString(), item.expiresAt()));
        }
        return resolved;
    }

    /**
     * 读取上游错误信封 {@code {success:false,error}} 并透传同码 + 中文错误；
     * 信封缺失回通用文案，信封本身不可读（解码失败）则异常外泄由调用侧 onErrorMap 收敛为 503。
     */
    private <T> Mono<T> upstreamError(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(ERROR_TYPE)
                .map(error -> error.error() == null || error.error().isBlank()
                        ? new IdentityException(status, "门店媒体服务暂不可用")
                        : new IdentityException(status, error.error()))
                .defaultIfEmpty(new IdentityException(status, "门店媒体服务暂不可用"))
                .flatMap(Mono::error);
    }

    private record Envelope<T>(boolean success, T data) {}

    private record ErrorEnvelope(boolean success, String error) {}

    private record TicketRequest(String ownerAccountId, String storeId, String contentType, long sizeBytes) {}

    private record DownloadRequest(String storeId, List<String> mediaIds) {}

    private record DownloadUrlsData(List<DownloadItem> items) {}

    private record DownloadItem(UUID id, String mimeType, Long sizeBytes, URI downloadUrl, Instant expiresAt) {}
}
