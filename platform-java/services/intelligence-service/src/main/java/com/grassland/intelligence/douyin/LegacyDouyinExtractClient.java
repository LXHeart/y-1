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
 * Douyin extract-video 回落 legacy（草场 GL-P3-MEDIA-001）。intelligence 的 HTTP 解析阶段拿不到
 * 可播放地址（挑战页/缺播放源）时，把整个请求反向代理回 legacy Express——legacy 有 Playwright 浏览器
 * 抓取与登录态（storage state）增强阶段，这些 Node worker 能力首期不迁（worker 边界）。
 *
 * <p>公开端点无身份，直转原请求体即可（不经 edge-bff，直连 legacy 容器，与 {@code LegacyBilibiliAnalyzeClient}
 * 同模式）。响应透传 legacy 的 {@code {success,data}} 信封；非 2xx 取 {@code error} 字段抛
 * {@link IntelligenceException}（经全局 handler 还原同状态码 + 同信封）。
 */
@Component
public class LegacyDouyinExtractClient {

    private static final String EXTRACT_PATH = "/api/douyin/extract-video";

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacyDouyinExtractClient(LegacyMediaProxyProperties properties) {
        this.client = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    /**
     * @param body 原请求体 {@code {input}}。
     * @return legacy 的 {@code {success,data}} 信封（2xx）；非 2xx → {@link IntelligenceException}（带 legacy 状态码）。
     */
    public Mono<Map<String, Object>> delegate(Map<String, Object> body) {
        return client.post().uri(EXTRACT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
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
            throw new IntelligenceException(502, "视频提取服务返回了无效响应");
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
        return "视频提取服务暂不可用";
    }
}
