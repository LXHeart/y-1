package com.grassland.intelligence.creationvoice;

import com.grassland.intelligence.creationvoice.CreationVoiceTypes.VoiceProfile;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 私有文风档案 HTTP 入口（任务书 #108 C-02 / W09；§6.1～6.3）。
 *
 * <p>
 * 公共前缀 {@code /api/creation-voice}，由 Edge 精确 method/path
 * 模板路由（{@code /{role}}）代理到本服务， 沿用 image-analysis 的认证/CSRF
 * 与启用条件（EDGE_ROUTE_IMAGE_ANALYSIS_STYLE_INTELLIGENCE）。 成功信封
 * {@code {success:true,data:...}}；错误经全局 {@code IntelligenceErrorHandler} 以
 * {@code {success:false,error,code}}
 * 返回（VOICE_INVALID_INPUT/VOICE_REVISION_CONFLICT/VOICE_UNAVAILABLE/
 * account_closure_barrier，§6.4）。
 *
 * <p>
 * 账户只来自 {@link IntelligenceCallerResolver}（RULE-014：body/query 携带
 * accountId/organizationId 一律 400 拒绝，伪造不返回他人数据）。preview 仅返回候选，不写入档案。
 */
@RestController
@RequestMapping("/api/creation-voice")
public class CreationVoiceController {

	private final IntelligenceCallerResolver callers;
	private final CreationVoiceService voices;

	public CreationVoiceController(IntelligenceCallerResolver callers, CreationVoiceService voices) {
		this.callers = callers;
		this.voices = voices;
	}

	/** API-001：GET /{role}——缺行返回 revision=0/enabled=false/空数组/updatedAt=null。 */
	@GetMapping("/{role}")
	public Mono<Map<String, Object>> getProfile(@PathVariable("role") String role, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> {
			rejectQueryParameters(request);
			return voices.get(caller.accountId(), role);
		}).map(profile -> success(profile));
	}

	/** API-002：PUT /{role}——完整档案 CAS 替换（幂等重放返回当前值；旧 revision 409）。 */
	@PutMapping(value = "/{role}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<Map<String, Object>> putProfile(@PathVariable("role") String role,
			@RequestBody(required = true) Map<String, Object> body, ServerHttpRequest request) {
		// §5.2 校验顺序：先解析可信 caller（401/403），再做类型/长度/限额校验，最后落库。
		return callers.requireUser(request).flatMap(caller -> Mono.fromCallable(() -> {
			rejectQueryParameters(request);
			return voices.parsePutBody(body);
		}).flatMap(requestBody -> voices.put(caller.accountId(), role, requestBody)))
				.map(CreationVoiceController::success);
	}

	@PostMapping(value = "/{role}/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<Map<String, Object>> preview(@PathVariable("role") String role, @RequestBody Map<String, Object> body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> {
			rejectQueryParameters(request);
			return voices.preview(caller.accountId(), caller.organizationId(), role, body);
		}).map(candidates -> Map.of("success", true, "data", Map.of("candidates", candidates)));
	}

	private static void rejectQueryParameters(ServerHttpRequest request) {
		if (!request.getQueryParams().isEmpty()) {
			throw CreationVoiceTypes.invalid("文风档案接口不接受查询参数，账户仅来自登录身份");
		}
	}

	private static Map<String, Object> success(VoiceProfile profile) {
		return Map.of("success", true, "data", profile.toMap());
	}
}
