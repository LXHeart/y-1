package com.grassland.intelligence.douyin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.mediaplatform.LegacyMediaProxyProperties;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Douyin analyze-video 回落 legacy（草场 GL-P3-MEDIA-001）。超 {@code maxSingleSegmentSeconds} 需 FFmpeg
 * 切片 / 非 qwen provider 等不归 Java 处理的用例，把整个请求反向代理回 legacy Express（legacy 用同
 * {@code DOUYIN_PROXY_TOKEN_SECRET} 验 token + FFmpeg 切片 + analysis-media 临时会话 + 自行
 * {@code requireCredit} 扣分）。
 *
 * <p>identity 透传：legacy {@code analyzeDouyinVideoHandler} 靠 session cookie（{@code getSessionUser}）识人，
 * 而 cookie 经 edge-bff→intelligence 端到端透传，故本 client 只需把客户端 {@code Cookie} 头连同 JSON body
 * 一并转发给 {@code legacy.backend.base-url/api/douyin/analyze-video}，直连 legacy 容器（不经 edge-bff，
 * 避免循环，与 {@code LegacyBilibiliAnalyzeClient} 同模式）。
 *
 * <p>响应透传 legacy 的 {@code {success,data}} 信封；legacy 非 2xx 时取 {@code error} 字段抛
 * {@link IntelligenceException}（经全局 handler 还原同状态码 + 同信封），保持对前端透明。
 */
@Component
public class LegacyDouyinAnalyzeClient {

    private static final String ANALYZE_PATH = "/api/douyin/analyze-video";

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacyDouyinAnalyzeClient(LegacyMediaProxyProperties properties) {
        this.client = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    /**
     * @param cookie 客户端 {@code Cookie} 头原值（可空——匿名时 legacy 自行 401）。
     * @param body   原请求体 {@code {proxyVideoUrl}}。
     * @return legacy 的 {@code {success,data}} 信封（2xx）；非 2xx → {@link IntelligenceException}（带 legacy 状态码）。
     */
    public Mono<Map<String, Object>> delegate(String cookie, Map<String, Object> body) {
        return client.post().uri(ANALYZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (cookie != null && !cookie.isBlank()) {
                        headers.set("Cookie", cookie);
                    }
                })
                .bodyValue(body)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(raw -> {
                            int status = response.statusCode().value();
                            if (status >= 200 && status < 300) {
                                return Mono.just(parseEnvelope(raw));
                            }
                            return Mono.error(new IntelligenceException(status, extractError(raw)));
                        }));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEnvelope(String raw) {
        try {
            return mapper.readValue(raw, Map.class);
        } catch (Exception error) {
            throw new IntelligenceException(502, "视频分析服务返回了无效响应");
        }
    }

    private String extractError(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            JsonNode error = node.path("error");
            if (error.isTextual() && !error.asText().isBlank()) {
                return error.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体 → 回退通用文案。
        }
        return "视频分析服务暂不可用";
    }
}
