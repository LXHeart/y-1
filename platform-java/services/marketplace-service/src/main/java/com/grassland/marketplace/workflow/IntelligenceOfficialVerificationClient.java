package com.grassland.marketplace.workflow;

import com.grassland.http.ManagedWebClientFactory;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 官方数据源客户端（P1 骨架，ADR-D04）：intelligence {@code POST /internal/verification/
 * official-data}（服务断言）的 marketplace 侧封装。
 *
 * <p>
 * 三态语义（与 official_data 检查项一一对应）：
 * <ul>
 * <li>{@code configured:false}（网关未启用，默认）→ empty——检查项**省略**：官方数据是附加 证据源，未配置 ≠
 * 存疑，不得把存量核验全刷成 inconclusive 涌入人工队列。</li>
 * <li>{@code unavailable:true}（网关故障）→ {@link OfficialData#UNAVAILABLE}——检查项
 * inconclusive（不确定即人工）。</li>
 * <li>数据体 → {@link OfficialData} 三态字段（null=网关无法判定）。</li>
 * </ul>
 * 调用自身故障（intelligence 不可达）按省略处理（与未配置同形——没有官方证据时以既有检查表为准）。
 */
@Component
public class IntelligenceOfficialVerificationClient {

	private static final Logger log = LoggerFactory.getLogger(IntelligenceOfficialVerificationClient.class);
	private static final ParameterizedTypeReference<Envelope<OfficialData>> TYPE = new ParameterizedTypeReference<>() {
	};

	private final WebClient webClient;
	private final ServiceAssertionIssuer issuer;
	private final String headerName;

	public IntelligenceOfficialVerificationClient(ServiceAssertionIssuer issuer,
			@Value("${intelligence.service.base-url:http://intelligence-service:8086}") String baseUrl,
			@Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
		this.webClient = ManagedWebClientFactory.create(IntelligenceOfficialVerificationClient.class, baseUrl);
		this.issuer = issuer;
		this.headerName = headerName;
	}

	/** 网关故障占位（unavailable → inconclusive）；字段全 null。 */
	public static final OfficialData UNAVAILABLE = new OfficialData(Boolean.TRUE, null, null, null, Map.of());

	/** 平台官方数据（经 ADR-D04 认证网关归一）：三态字段 null=网关无法判定。 */
	public record OfficialData(Boolean unavailable, Boolean accountMatch, Boolean published, Boolean commentFound,
			Map<String, Long> metrics) {
	}

	/** 查询官方数据；未配置/调用失败 → empty（检查项省略），网关故障 → UNAVAILABLE。 */
	public Mono<OfficialData> fetchOfficialData(String organizationId, String platform, String contentUrl,
			String platformHandle, String commentText) {
		return Mono.defer(() -> {
			Map<String, Object> body = new java.util.LinkedHashMap<>();
			body.put("platform", platform == null ? "" : platform);
			body.put("contentUrl", contentUrl == null ? "" : contentUrl);
			if (platformHandle != null && !platformHandle.isBlank()) {
				body.put("platformHandle", platformHandle.trim());
			}
			if (commentText != null && !commentText.isBlank()) {
				body.put("commentText", commentText.trim());
			}
			return webClient.post().uri("/internal/verification/official-data")
					.header(headerName, issuer.issueForOrg(organizationId, "grassland-intelligence"))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchangeToMono(response -> {
						if (!response.statusCode().is2xxSuccessful()) {
							log.warn("official verification data unavailable status={}", response.statusCode().value());
							return response.releaseBody().then(Mono.empty());
						}
						return response.bodyToMono(TYPE).map(Envelope::data);
					})
					.flatMap(data -> Boolean.TRUE.equals(data.unavailable()) ? Mono.just(UNAVAILABLE) : Mono.just(data))
					.onErrorResume(error -> {
						log.warn("official verification data client failed", error);
						return Mono.empty();
					});
		});
	}

	/** intelligence 信封。 */
	private record Envelope<T>(boolean success, T data) {
	}
}
