package com.grassland.marketplace.workflow;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * intelligence 履约 AI 视觉核验出站 HTTP 客户端（草场 Slice 11 Verification Stage 4）。
 *
 * <p>商家触发履约核验时，marketplace 作为履约权威，以服务断言（principal=marketplace）跨服务提交待核验附件
 * media id 列表 + 任务上下文，intelligence 内部自读附件字节做 Qwen 视觉判断后返回聚合结果。镜像
 * {@link IntelligenceMediaClient}：WebClient + 每请求现签服务断言（{@link ServiceAssertionIssuer}）。
 *
 * <p>状态映射：200→解析 {@code {success,data}} 信封的 {@code data}（{@link VerificationAnalysis}）；
 * 其余（含 intelligence 不可用、4xx、5xx）→{@link IntelligenceVerificationException}，由核验编排降级为
 * {@code ai_visual} check 的 {@code inconclusive}，不 fail 整次履约核验。
 *
 * <p>{@code mediaIds} 已在 marketplace 提交时经 {@code validateAttachments} 证挂接且 owner==提交人（IDOR 守卫），
 * 故 AI check 不涉用户 URL——intelligence 自读自己的媒体，无 SSRF 面（区别于踩用户输入的 link checker）。
 */
@Component
public class IntelligenceVerificationClient {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceVerificationClient.class);

    private static final ParameterizedTypeReference<Envelope<VerificationAnalysis>> ANALYSIS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public IntelligenceVerificationClient(ServiceAssertionIssuer issuer,
                                          @Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
                                          @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 提交附件 media id 列表 + 任务上下文做 AI 视觉核验。
     *
     * @param orgId           现签服务断言的 org 上下文（任务所属 org）
     * @param mediaIds        待核验附件 media_reference id（已证挂接到该 submission）
     * @param taskTitle       任务标题（核验相关性基准，必填）
     * @param taskDescription 任务要求，可空（空则不下发，intelligence 视为缺省）
     * @param platform        发布平台，可空
     * @return 200→聚合 {@link VerificationAnalysis}；其余→{@link IntelligenceVerificationException}
     */
    public Mono<VerificationAnalysis> analyze(String orgId, List<UUID> mediaIds,
                                              String taskTitle, String taskDescription, String platform) {
        return webClient.post()
                .uri("/api/verification/analyze")
                .header(headerName, issuer.issueForOrg(orgId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(mediaIds, taskTitle, taskDescription, platform))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("verification analyze HTTP {} org={} mediaCount={}", code, orgId, mediaIds.size());
                    if (code == 200) {
                        return resp.bodyToMono(ANALYSIS_TYPE).map(Envelope::data);
                    }
                    return bodyError(resp, code, "verification analyze");
                });
    }

    /** 构造 analyze 请求体：仅下发非空的可选字段（intelligence optionalString 对显式 null 报 400）。 */
    private static Map<String, Object> analyzeBody(List<UUID> mediaIds, String taskTitle,
                                                   String taskDescription, String platform) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mediaIds", mediaIds);
        body.put("taskTitle", taskTitle);
        if (taskDescription != null && !taskDescription.isBlank()) {
            body.put("taskDescription", taskDescription);
        }
        if (platform != null && !platform.isBlank()) {
            body.put("platform", platform);
        }
        return body;
    }

    private <T> Mono<T> bodyError(ClientResponse resp, int code, String op) {
        return resp.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(b -> Mono.<T>error(new IntelligenceVerificationException(
                        op + " failed: HTTP " + code + ": " + b)));
    }

    /** AI 视觉核验聚合结果（与 intelligence {@code VerificationAnalysis} 字段对齐）。 */
    public record VerificationAnalysis(String status, List<MediaResult> results) {
        public VerificationAnalysis {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    /** 单张附件 AI 视觉核验结果（与 intelligence {@code MediaVerificationResult} 字段对齐）。 */
    public record MediaResult(UUID mediaId, String status, String detail) {}

    private record Envelope<T>(boolean success, T data) {}
}
