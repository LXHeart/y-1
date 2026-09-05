package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 个人 BYOK 偏好 API（任务书 #47 S5；任务书 #78 卡 B 起收敛为「模型来源」总开关契约）。
 *
 * <p>
 * 端点（self-scoped，只操作调用者自己的偏好）：
 * <ul>
 * <li>GET /api/ai/preferences — 总开关（{@code modelSource: platform|own} +
 * {@code masterVersion}） + 旧 per-capability items（只读兼容展示，路由不再消费）</li>
 * <li>PUT /api/ai/preferences/model-source — 设总开关，{@code expectedVersion}
 * 乐观锁，冲突 409</li>
 * </ul>
 * per-capability {@code PUT /{capability}} 已下线（404）——D3 定死一个总开关取代碎片开关。
 *
 * <p>
 * 不做 admin 面：这是用户自己的偏好，没有「代客修改」的场景。无 KEK 门控——本端点不碰密钥， 只记「用谁的模型」，故 KEK
 * 未配时也应能正常切换（否则用户在加密基建故障时连退路都没有）。
 */
@RestController
@RequestMapping("/api/ai/preferences")
public class AiProviderPreferenceController {

	/** 响应顺序固定，便于前端稳定渲染。 */
	private static final List<String> ORDERED = List.of("text", "image", "image_generation", "video_generation");

	private final IntelligenceCallerResolver callers;
	private final AiProviderPreferenceRepository repository;

	public AiProviderPreferenceController(IntelligenceCallerResolver callers,
			AiProviderPreferenceRepository repository) {
		this.callers = callers;
		this.repository = repository;
	}

	/** 总开关 + 四个能力的旧开关全集（未显式配置的补默认展示，路由不读）。 */
	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> repository
				.find(caller.accountId(), AiProviderPreferenceRepository.MASTER_CAPABILITY).map(Optional::of)
				.defaultIfEmpty(Optional.empty())
				.flatMap(master -> repository.findByAccount(caller.accountId()).filter(
						preference -> !AiProviderPreferenceRepository.MASTER_CAPABILITY.equals(preference.capability()))
						.collectMap(AiProviderPreference::capability, preference -> preference).map(configured -> {
							List<Map<String, Object>> items = ORDERED.stream()
									.map(capability -> toItem(configured.containsKey(capability)
											? configured.get(capability)
											: AiProviderPreference.defaultFor(caller.accountId(), capability)))
									.toList();
							Map<String, Object> data = new LinkedHashMap<>();
							data.put("items", items);
							data.put("modelSource",
									master.map(m -> m.useOwnKey() ? "own" : "platform").orElse("platform"));
							data.put("masterVersion", master.map(AiProviderPreference::version).orElse(0L));
							return success(data);
						})));
	}

	/** 模型来源总开关（任务书 #78 卡 B）：platform（默认）/ own，乐观锁 409。 */
	@PutMapping("/model-source")
	public Mono<ResponseEntity<Map<String, Object>>> updateModelSource(@RequestBody UpdateModelSourceRequest body,
			ServerWebExchange exchange) {
		if (!"own".equals(body.modelSource()) && !"platform".equals(body.modelSource())) {
			return Mono.error(new IntelligenceException(400, "modelSource 只支持 platform 或 own"));
		}
		if (body.expectedVersion() == null || body.expectedVersion() < 0) {
			return Mono.error(new IntelligenceException(400, "expectedVersion 必填且不能为负"));
		}
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> repository
						.upsert(caller.accountId(), AiProviderPreferenceRepository.MASTER_CAPABILITY,
								"own".equals(body.modelSource()), body.expectedVersion())
						.map(saved -> success(toModelSourceItem(saved)))
						.switchIfEmpty(Mono.error(new IntelligenceException(409, "模型来源开关已被其他会话修改，请重新加载后再试"))));
	}

	/** 任务书 #78 卡 B：per-capability 开关端点下线——模型来源由 PUT /model-source 统一管理。 */
	@PutMapping("/{capability}")
	public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String capability,
			@RequestBody UpdatePreferenceRequest body, ServerWebExchange exchange) {
		return Mono.error(new IntelligenceException(404, "按能力开关已下线：模型来源由 PUT /api/ai/preferences/model-source 统一管理"));
	}

	private static Map<String, Object> toItem(AiProviderPreference preference) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("capability", preference.capability());
		item.put("useOwnKey", preference.useOwnKey());
		// configured=false 表示走 D14 默认，前端据此区分「未配置」与「显式设为 true」
		item.put("configured", preference.version() > 0);
		item.put("version", preference.version());
		item.put("updatedAt", preference.updatedAt());
		return item;
	}

	private static Map<String, Object> toModelSourceItem(AiProviderPreference master) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("modelSource", master.useOwnKey() ? "own" : "platform");
		item.put("masterVersion", master.version());
		item.put("updatedAt", master.updatedAt());
		return item;
	}

	private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("data", data);
		return ResponseEntity.ok(body);
	}

	/** {@code expectedVersion=0} 表示预期无行（首次设置）。旧 per-capability 端点的请求体（端点已 404）。 */
	public record UpdatePreferenceRequest(Boolean useOwnKey, Long expectedVersion) {
	}

	/** 模型来源总开关请求（任务书 #78 卡 B）：{@code expectedVersion=0} 表示预期无主行（首次设置）。 */
	public record UpdateModelSourceRequest(String modelSource, Long expectedVersion) {
	}
}
