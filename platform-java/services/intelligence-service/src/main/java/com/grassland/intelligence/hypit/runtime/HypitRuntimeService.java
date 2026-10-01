package com.grassland.intelligence.hypit.runtime;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 运行时面服务（任务书 #107-1 C107-07 / §6.2 runtime 组）。
 *
 * <p>
 * 凭据与 auth-flow 委托 sidecar 的 providers/credentials/authflow 命令域（commandAsync，
 * 绝不在事件循环上 block）；Profile 校验按 07.2 的 schema 就地执行，未持久化（C23 部署书统一 落地），响应如实带
 * {@code persisted=false}。命令域失败如实抛 502，不吞错伪造成功。
 *
 * <p>
 * C107F2-09（§6.8）：新增 broker {@code GET /internal/v1/readiness} 探测（2 秒超时）——
 * liveness（healthz）与 feature readiness 分离；版本号只来自发行版 manifest，本服务不硬编码。
 */
@Service
public class HypitRuntimeService {

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final WebClient readinessClient;

	public HypitRuntimeService(HypitProperties properties, HypitSidecarClient sidecar) {
		this.properties = properties;
		this.sidecar = sidecar;
		// readiness 是 broker 的 GET 端点而非命令域；与 HypitSidecarClient 同源的
		// 自建 WebClient（本服务无 WebClient.Builder bean，全仓惯例各客户端自建）。
		this.readinessClient = WebClient.builder().baseUrl(properties.sidecarBaseUrl()).build();
	}

	/**
	 * C09（TC-F2-09-02）：broker feature readiness。disabled/token 未配置/2 秒超时/不可达 一律
	 * empty——调用方以 healthz+enabled 判 liveness，以本探测判各依赖 ready 与版本。
	 */
	public Mono<Optional<Map<String, Object>>> readiness() {
		if (!properties.enabled() || properties.internalToken().length() < 32) {
			return Mono.just(Optional.empty());
		}
		return readinessClient.get().uri("/internal/v1/readiness")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.internalToken())
				.accept(MediaType.APPLICATION_JSON).retrieve()
				.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
				}).map(Optional::of).timeout(Duration.ofSeconds(2)).onErrorResume(error -> Mono.just(Optional.empty()));
	}

	/** 运行时事实：enabled/sidecar 健康（不含 secret）。 */
	public Mono<Map<String, Object>> status() {
		if (!properties.enabled()) {
			return Mono.just(Map.<String, Object>of("enabled", false, "sidecarUp", false));
		}
		boolean up = sidecar.health();
		return Mono.just(Map.<String, Object>of("enabled", true, "sidecarUp", up));
	}

	/** GET 凭据状态：只有 configured/type/expiry，绝无 secret。 */
	public Mono<Map<String, Object>> credentialStatus(String endpoint, String slot) {
		return command("java-credentials-status-" + UUID.randomUUID(), "credentials.status",
				Map.of("store", "file", "key", endpoint + "." + slot));
	}

	/** PUT 凭据：写 sidecar 私有 store（requestId 幂等由命令域保证）。 */
	public Mono<Map<String, Object>> credentialPut(String requestId, String endpoint, String slot, String secret) {
		if (secret == null || secret.isBlank()) {
			return Mono.error(
					new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input", "secret 必填"));
		}
		return command("java-credentials-put-" + requestId, "credentials.put",
				Map.of("store", "file", "key", endpoint + "." + slot, "secret", secret));
	}

	/** DELETE 凭据。 */
	public Mono<Map<String, Object>> credentialDelete(String requestId, String endpoint, String slot) {
		return command("java-credentials-delete-" + requestId, "credentials.delete",
				Map.of("store", "file", "key", endpoint + "." + slot));
	}

	/** 发起 OAuth flow：acquisition 来自 sidecar catalog 的真实包声明，不由 Java 硬编码。 */
	public Mono<Map<String, Object>> authFlowStart(String owner, String requestId, String endpoint, String slot) {
		return catalogAcquisition(endpoint, slot).flatMap(acquisition -> command("java-authflow-start-" + requestId,
				"authflow.start",
				merge(Map.of("owner", owner, "endpoint", endpoint, "slot", slot, "targetStore", "file", "targetKey",
						endpoint + "." + slot, "acquisitionKind", acquisition.get("kind"), "authorizationEndpoint",
						acquisition.get("authorizationEndpoint"), "redirectUri", acquisition.get("redirectUri"),
						"tokenEndpoint", acquisition.get("tokenEndpoint"), "clientId", acquisition.get("clientId")),
						Map.of("scopes", acquisition.get("scopes")))));
	}

	public Mono<Map<String, Object>> authFlowProgress(String owner, String flowId) {
		return command("java-authflow-progress-" + UUID.randomUUID(), "authflow.progress",
				Map.of("owner", owner, "flowId", flowId));
	}

	public Mono<Map<String, Object>> authFlowComplete(String owner, String requestId, String flowId,
			String codeAndState) {
		return command("java-authflow-complete-" + requestId, "authflow.complete",
				Map.of("owner", owner, "flowId", flowId, "codeAndState", codeAndState));
	}

	public Mono<Map<String, Object>> authFlowCancel(String owner, String requestId, String flowId) {
		return command("java-authflow-cancel-" + requestId, "authflow.cancel",
				Map.of("owner", owner, "flowId", flowId));
	}

	/**
	 * Profile 校验（07.2）：format/endpoints/credentials 结构与必填项；凭据只允许 {store,key}
	 * 引用形态（拒绝内联字符串 secret）。校验通过返回报告（persisted=false）。
	 */
	public Mono<Map<String, Object>> validateProfile(Map<String, Object> profile) {
		return Mono.fromSupplier(() -> {
			List<String> problems = new ArrayList<>();
			if (!"hypit.runtime-local@1".equals(profile.get("format"))) {
				problems.add("format 必须是 hypit.runtime-local@1");
			}
			Object endpoints = profile.get("endpoints");
			if (!(endpoints instanceof List<?> endpointList) || endpointList.isEmpty()) {
				problems.add("endpoints 必须是非空数组");
			} else {
				int index = 0;
				for (Object item : endpointList) {
					if (item instanceof Map<?, ?> endpoint) {
						requireText(endpoint.get("use"), "use", index, problems);
						requireText(endpoint.get("instance"), "instance", index, problems);
						Object config = endpoint.get("config");
						if (config != null && !(config instanceof Map)) {
							problems.add("endpoints[" + index + "].config 必须是对象");
						}
					} else {
						problems.add("endpoints[" + index + "] 必须是对象");
					}
					index++;
				}
			}
			Object credentials = profile.get("credentials");
			if (credentials instanceof List<?> credentialList) {
				int index = 0;
				for (Object item : credentialList) {
					if (item instanceof Map<?, ?> credential
							&& credential.get("config") instanceof Map<?, ?> configMap) {
						for (Map.Entry<?, ?> entry : configMap.entrySet()) {
							if (entry.getValue() instanceof String) {
								problems.add("credentials[" + index + "].config." + entry.getKey()
										+ " 不允许内联字符串 secret；只接受 {store,key} 引用");
							}
						}
					}
					index++;
				}
			}
			if (!problems.isEmpty()) {
				throw new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
						"Profile 校验失败：" + String.join("; ", problems));
			}
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("valid", true);
			report.put("persisted", false);
			report.put("note", "Profile 校验通过；持久化与部署绑定在 C23 部署书落地");
			report.put("endpoints", profile.get("endpoints"));
			return report;
		});
	}

	/** 命令域统一入口：disabled 503 / 未配置 503 / failed 状态如实抛错不伪造。 */
	private Mono<Map<String, Object>> command(String commandId, String kind, Map<String, Object> payload) {
		if (!properties.enabled()) {
			return Mono.error(HypitAccessService.disabled());
		}
		if (!sidecar.configured()) {
			return Mono.error(HypitAccessService.unavailable(kind + " 命令"));
		}
		return sidecar.commandAsync(commandId, kind, payload)
				.flatMap(command -> command.state().equals("succeeded") || command.result() != null
						? Mono.just(HypitJson.mapValue(command.result()))
						: Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
								"命令 " + kind + " 失败：" + describe(command.error()))));
	}

	private static String describe(Map<String, Object> error) {
		if (error == null) {
			return "state=" + "failed";
		}
		Object message = error.get("message");
		Object code = error.get("code");
		return (code == null ? "" : code + ": ") + (message == null ? "(无错误信息)" : message);
	}

	private Mono<Map<String, Object>> catalogAcquisition(String endpoint, String slot) {
		return command("java-catalog-" + UUID.randomUUID(), "providers.catalog", Map.of()).flatMap(result -> {
			Object providers = result.get("providers");
			if (!(providers instanceof List<?> list)) {
				return Mono.error(new IntelligenceException(HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
						"catalog 返回无效"));
			}
			for (Object item : list) {
				if (!(item instanceof Map<?, ?> provider)) {
					continue;
				}
				if (!endpoint.equals(provider.get("defaultEndpointId"))) {
					continue;
				}
				Object slots = provider.get("credentials");
				if (slots instanceof List<?> slotList) {
					for (Object slotItem : slotList) {
						if (slotItem instanceof Map<?, ?> slotMap && slot.equals(slotMap.get("slot"))
								&& slotMap.get("acquisition") instanceof Map<?, ?> acquisition) {
							Map<String, Object> out = new LinkedHashMap<>();
							out.put("kind", string(acquisition.get("kind")));
							out.put("authorizationEndpoint", string(acquisition.get("authorizationEndpoint")));
							out.put("redirectUri", string(acquisition.get("redirectUri")));
							out.put("tokenEndpoint", string(acquisition.get("tokenEndpoint")));
							out.put("clientId", string(acquisition.get("clientId")));
							out.put("scopes", acquisition.get("scopes"));
							return Mono.just(out);
						}
					}
				}
				return Mono.error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
						"端点 " + endpoint + " 的凭据槽 " + slot + " 无 OAuth acquisition"));
			}
			return Mono.error(
					new IntelligenceException(HttpStatus.NOT_FOUND.value(), "hypit_not_found", "未知端点 " + endpoint));
		});
	}

	/** catalog 描述符字段可能是 null（本地包无 acquisition），Map.of 不接受 null 值。 */
	private static String string(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
		Map<String, Object> out = new LinkedHashMap<>(base);
		out.putAll(extra);
		return out;
	}

	private static void requireText(Object value, String field, int index, List<String> problems) {
		if (!(value instanceof String text) || text.isBlank()) {
			problems.add("endpoints[" + index + "]." + field + " 必填");
		}
	}
}
