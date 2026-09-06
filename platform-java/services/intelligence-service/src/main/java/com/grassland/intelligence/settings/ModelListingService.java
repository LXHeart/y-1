package com.grassland.intelligence.settings;

import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.PinnedByokClients;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 模型列表服务（任务书 #88 收窄）：现仅服务治理台平台凭据实时列模型 （{@link #listModelsAt}，经
 * {@code PlatformProviderCredentialController} 的 {@code GET
 * /api/admin/ai/credentials/{id}/models}）。
 *
 * <p>
 * 旧用户侧链路（从用户 analysis settings 读 baseUrl/apiKey 的 listModels/verifyModel）已随任务书
 * #88 退役删除——模型解析收敛到 AI 控制面（BYOK + 平台凭据/平台模型配置）。
 *
 * <p>
 * 出站连接与 BYOK 同口径：HTTPS + 全部 DNS 解析结果为公网 + 固定地址连接（关闭 DNS rebinding TOCTOU
 * 窗口）。出站口径与 {@code PlatformProviderCredentialController} javadoc 表述一致——SSRF/DNS
 * 钉扎 防护**刻意不分叉**（本类留在 settings 包属历史位置，不迁移以缩小改动面）。
 */
@Component
public class ModelListingService {

	private final DnsPinningResolver dnsPinning;
	private final ObjectMapperHolder json = new ObjectMapperHolder();

	public ModelListingService(DnsPinningResolver dnsPinning) {
		this.dnsPinning = dnsPinning;
	}

	/**
	 * 按显式 baseUrl + apiKey 列模型，不经用户 analysis settings。
	 *
	 * <p>
	 * 供平台凭据侧复用（治理台「平台模型」表单的模型名下拉）：出站口径与用户 BYOK 完全一致—— 同一个 {@link #pinnedClient}
	 * 固定地址连接、同一个 {@link #parseModels}。**刻意不复制这段逻辑**， 因为 SSRF/DNS-rebinding
	 * 防护一旦分叉就会漏掉一侧。
	 *
	 * <p>
	 * {@code apiKey} 必须是已解密明文，只作 Authorization 头用，不落日志、不入响应。
	 */
	public Mono<List<Map<String, Object>>> listModelsAt(String baseUrl, String apiKey) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return Mono.error(new IntelligenceException(400, "该凭据没有可用的 baseUrl"));
		}
		if (apiKey == null || apiKey.isBlank()) {
			return Mono.error(new IntelligenceException(400, "该凭据未配置密钥，无法列出模型"));
		}
		return pinnedClient(new ProviderConfig(baseUrl, apiKey)).flatMap(client -> client.get().uri("/models")
				.header("Authorization", "Bearer " + apiKey).accept(MediaType.APPLICATION_JSON).retrieve()
				.bodyToMono(String.class).timeout(Duration.ofMillis(15000)).map(this::parseModels)
				.onErrorMap(e -> !(e instanceof IntelligenceException),
						e -> new IntelligenceException(502, "模型列表获取失败：" + e.getMessage())));
	}

	/**
	 * 构建固定连接客户端。校验（DNS 解析）是阻塞操作，放到 boundedElastic； 校验失败映射 400（用户配置问题，非上游故障）——错误在
	 * flatMap 源上， 必须在这里转换，mapper 内层的 502 兜底接不到它。
	 */
	private Mono<WebClient> pinnedClient(ProviderConfig config) {
		return Mono.fromCallable(() -> PinnedByokClients.forBaseUrl(config.baseUrl(), dnsPinning))
				.subscribeOn(Schedulers.boundedElastic()).onErrorMap(e -> e instanceof IllegalArgumentException,
						e -> new IntelligenceException(400, e.getMessage()));
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> parseModels(String body) {
		try {
			Map<String, Object> resp = json.parse(body);
			Object data = resp.get("data");
			if (!(data instanceof List<?> list))
				return List.of();
			List<Map<String, Object>> models = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof Map<?, ?> m) {
					Map<String, Object> model = new LinkedHashMap<>();
					model.put("id", m.get("id"));
					if (m.get("owned_by") != null)
						model.put("ownedBy", m.get("owned_by"));
					models.add(model);
				}
			}
			models.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
			return models;
		} catch (Exception e) {
			return List.of();
		}
	}

	private record ProviderConfig(String baseUrl, String apiKey) {
	}

	/** 轻量 JSON helper（避免注入 ObjectMapper bean 的循环依赖）。 */
	static class ObjectMapperHolder {
		private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

		@SuppressWarnings("unchecked")
		Map<String, Object> parse(String json) {
			try {
				return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
				});
			} catch (Exception e) {
				return Map.of();
			}
		}
	}
}
