package com.grassland.marketplace.workflow;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * intelligence media 中转读出站 HTTP 客户端（草场 Slice 11 Stage 2）。
 *
 * <p>商家查看/下载推荐官上传的履约附件时，附件的 media_reference 属于 intelligence 库且 owner 是推荐官
 * （organization_id=NULL），intelligence 的 owner-only 读端点无法对商家放行。故 marketplace 作为履约权威，
 * 以服务断言（principal=marketplace）经本客户端中转调 intelligence 的两个 service-only 端点取 metadata / 下载 URL。
 * 镜像 {@link TrustDisputeClient}：WebClient + 每请求现签服务断言（{@link ServiceAssertionIssuer}）。
 *
 * <p>状态映射：200→解析 {@code {success,data}} 信封的 data；404（media 不存在/非 engagement_attachment/非活跃/过期）→
 * {@code Mono.empty()}（调用方据此返回 404「附件不可用」）；其余→{@link IntelligenceMediaException}（→ 5xx）。
 *
 * <p>{@code metadata} 返回的 {@code ownerAccountId} 由 marketplace 在提交时做 IDOR 守卫（owner==提交人），
 * 防止推荐官挂接他人附件致商家越权下载。
 */
@Component
public class IntelligenceMediaClient {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceMediaClient.class);

    private static final ParameterizedTypeReference<Envelope<MediaMetadata>> METADATA_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<MediaDownload>> DOWNLOAD_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public IntelligenceMediaClient(ServiceAssertionIssuer issuer,
                                   @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
                                   @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** 取附件元数据：200→元数据，404（不可用）→empty，其余→异常。orgId 用于现签服务断言。 */
    public Mono<MediaMetadata> metadata(
            String orgId, UUID mediaId, String domainType, String domainId) {
        return webClient.get()
                .uri(builder -> builder.path("/api/media/{id}/metadata")
                        .queryParam("domainType", domainType).queryParam("domainId", domainId)
                        .build(mediaId))
                .header(headerName, issuer.issueForOrg(orgId, "grassland-intelligence"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("media metadata HTTP {} org={} mediaId={}", code, orgId, mediaId);
                    if (code == 200) {
                        return resp.bodyToMono(METADATA_TYPE).map(Envelope::data);
                    }
                    if (code == 404) {
                        return Mono.empty();
                    }
                    return bodyError(resp, code, "media metadata");
                });
    }

    /** 取附件短时下载 URL：200→URL，404（不可用）→empty，其余→异常。orgId 用于现签服务断言。 */
    public Mono<MediaDownload> downloadUrl(
            String orgId, UUID mediaId, String domainType, String domainId) {
        return webClient.get()
                .uri(builder -> builder.path("/api/media/{id}/download-url")
                        .queryParam("domainType", domainType).queryParam("domainId", domainId)
                        .build(mediaId))
                .header(headerName, issuer.issueForOrg(orgId, "grassland-intelligence"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("media download-url HTTP {} org={} mediaId={}", code, orgId, mediaId);
                    if (code == 200) {
                        return resp.bodyToMono(DOWNLOAD_TYPE).map(Envelope::data);
                    }
                    if (code == 404) {
                        return Mono.empty();
                    }
                    return bodyError(resp, code, "media download-url");
                });
    }

    private <T> Mono<T> bodyError(ClientResponse resp, int code, String op) {
        return resp.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(b -> Mono.<T>error(new IntelligenceMediaException(op + " failed: HTTP " + code + ": " + b)));
    }

    /** intelligence media service-only metadata 视图（与 intelligence MediaServiceMetadataResponse 字段对齐）。 */
    public record MediaMetadata(
            UUID id, String ownerAccountId, String purpose, String domainType, String domainId,
            String status, String checksum, String mimeType, long sizeBytes, Instant expiresAt) {
        public MediaMetadata(UUID id, String ownerAccountId, String purpose, String status,
                             String mimeType, long sizeBytes, Instant expiresAt) {
            this(id, ownerAccountId, purpose, null, null, status, null, mimeType, sizeBytes, expiresAt);
        }
    }

    /** intelligence media service-only download-url 视图；expiresAt 为媒体资产 TTL（非 URL 过期）。 */
    public record MediaDownload(URI downloadUrl, Instant expiresAt) {}

    private record Envelope<T>(boolean success, T data) {}
}
