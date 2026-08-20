package com.grassland.intelligence.verification.official;

import com.grassland.http.ManagedWebClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 官方数据源防腐层网关客户端（P1 骨架，ADR-D04）：平台官方 API（抖音/B站开放平台等）的 逐平台可用性与合法性尚待确认（HLD D-04
 * TBD），本适配器不直接对接任何平台——它调用
 * **我们自定义契约**的认证网关（{@code verification.official.gateway.*}），由部署侧把网关 实现为官方 API
 * 的代理（或持牌数据供应商）。契约由本仓库定义（ADR-D04 附录），换数据源 不改代码——这正是 HLD §12.2
 * {@code VerificationDataAdapter} 防腐层的意义。
 *
 * <p>
 * fail-closed：{@code verification.official.gateway.enabled=false}（默认）时 bean
 * 不装配， 内部端点回 {@code configured:false}，marketplace 侧直接省略 official_data 检查项
 * （官方数据是附加证据源，未配置 ≠ 存疑，不进人工队列）。启用但网关故障 → {@code unavailable}， marketplace 记
 * inconclusive（不确定即人工）。
 */
@Component
@ConditionalOnProperty(name = "verification.official.gateway.enabled", havingValue = "true")
public class OfficialVerificationGateway {

	private final WebClient webClient;
	private final String token;
	private final Duration timeout;
	private final ObjectMapper mapper = new ObjectMapper();

	public OfficialVerificationGateway(@Value("${verification.official.gateway.base-url:}") String baseUrl,
			@Value("${verification.official.gateway.token:}") String token,
			@Value("${verification.official.gateway.timeout-ms:8000}") long timeoutMs) {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalStateException("verification.official.gateway.enabled=true 需要 base-url（认证网关地址）");
		}
		if (token == null || token.isBlank()) {
			throw new IllegalStateException("verification.official.gateway.enabled=true 需要 token（网关凭据，走 env/SOPS 不入库）");
		}
		this.webClient = ManagedWebClientFactory.create(OfficialVerificationGateway.class, baseUrl);
		this.token = token;
		this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
	}

	/**
	 * 官方数据查询结果：accountMatch/published/commentFound 为三态（null=网关无法判定）； metrics
	 * 为平台互动指标名值对（likes/comments 等，键由网关归一）。
	 */
	public record OfficialData(Boolean accountMatch, Boolean published, Boolean commentFound,
			Map<String, Long> metrics) {
	}

	/** 网关故障/响应不合法 → empty（调用方转 unavailable → inconclusive，不伪装成结论）。 */
	public Mono<OfficialData> fetch(String platform, String contentUrl, String platformHandle, String commentText) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("platform", platform);
		body.put("contentUrl", contentUrl);
		if (platformHandle != null && !platformHandle.isBlank()) {
			body.put("platformHandle", platformHandle);
		}
		if (commentText != null && !commentText.isBlank()) {
			body.put("commentText", commentText.trim());
		}
		return webClient.post().uri("/v1/official-verification").headers(headers -> headers.setBearerAuth(token))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(String.class)
				.timeout(timeout).map(this::parse).onErrorResume(error -> Mono.empty());
	}

	private OfficialData parse(String raw) {
		try {
			Map<String, Object> root = mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
			});
			Map<String, Long> metrics = new java.util.LinkedHashMap<>();
			Object metricsNode = root.get("metrics");
			if (metricsNode instanceof Map<?, ?> map) {
				for (var entry : map.entrySet()) {
					if (entry.getValue() instanceof Number number) {
						metrics.put(String.valueOf(entry.getKey()), number.longValue());
					}
				}
			}
			return new OfficialData(boolOrNull(root.get("accountMatch")), boolOrNull(root.get("published")),
					boolOrNull(root.get("commentFound")), Map.copyOf(metrics));
		} catch (Exception error) {
			return null;
		}
	}

	private static Boolean boolOrNull(Object value) {
		return value instanceof Boolean bool ? bool : null;
	}

	/** 供契约测试断言请求体形态。 */
	List<String> requestContractFields() {
		return List.of("platform", "contentUrl", "platformHandle", "commentText");
	}
}
