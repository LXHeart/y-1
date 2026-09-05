package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 内容安全修复端点（任务书 #63 卡2）：{@code POST /api/content-safety/fix}（需登录）。
 *
 * <p>
 * 聚合型 SSE（moments/card-series 同款契约）：校验、鉴权与预算拒绝都以非 2xx JSON 先于流； 流内只有
 * progress/result 帧。修复不收积分（P2 拍板，feature=null 平台资助），但 「未配置 content_fix
 * 模型」必须显式报错（503），不静默降级。
 */
@RestController
public class ContentSafetyFixController {

	/** 与 ContentSafetyController 同口径：16k 中文字已覆盖全部生成产物。 */
	private static final int MAX_TEXT_CHARS = 16_000;
	private static final int MAX_FINDINGS = 20;
	/** denied(no_platform_model) 的显式错误（任务书 #63 4.4 定死文案）。 */
	static final String MODEL_NOT_CONFIGURED_MESSAGE = "修复模型未配置,请在治理台为「内容修复」能力配置模型";

	private final IntelligenceCallerResolver callers;
	private final ContentSafetyFixService service;
	private final ContentSafetyBypassPolicy bypassPolicy;

	public ContentSafetyFixController(IntelligenceCallerResolver callers, ContentSafetyFixService service,
			ContentSafetyBypassPolicy bypassPolicy) {
		this.callers = callers;
		this.service = service;
		this.bypassPolicy = bypassPolicy;
	}

	@PostMapping("/api/content-safety/fix")
	public Mono<ResponseEntity<Flux<DataBuffer>>> fix(@RequestBody FixRequest body, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				// 任务书 #78 卡 B（D3）：自有凭据主体不提供平台修复——skipped 帧而非 503
				// （503 只保留「平台模型未配置」语义）。
				.flatMap(caller -> bypassPolicy.isOwnSource(exchange)
						.flatMap(own -> own
								? Mono.just(sseEntity(Flux.just(skippedFrame()), exchange))
								: service.fix(body, exchange).map(frames -> sseEntity(frames, exchange))))
				// 执行环 denied 的通用映射是 403「执行被拒绝：no_platform_model」——修复场景按
				// 任务书 #63 定死契约升级为 503 + 明确配置指引（其余 402 预算类原样透出）。
				.onErrorMap(
						error -> error instanceof IntelligenceException denied && denied.status() == 403
								&& String.valueOf(denied.getMessage()).contains("no_platform_model"),
						error -> new IntelligenceException(503, MODEL_NOT_CONFIGURED_MESSAGE));
	}

	/** BYOK 主体跳过修复的 SSE 帧（任务书 #78 卡 B）：聚合型流内只增新帧型，不动 progress/result。 */
	private static String skippedFrame() {
		return "{\"type\":\"skipped\",\"reason\":\"own_model_source\"," + "\"message\":\"自有模型模式不提供平台内容修复\"}";
	}

	private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
		Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.set("X-Accel-Buffering", "no");
		headers.setCacheControl("no-cache");
		return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
	}

	/**
	 * 修复请求：text 必填 ≤16000 字符；findings 1..20 条（category/match 必填，advice 可空）；
	 * platform ≤32 字符选填；contentForm 仅 answer|article；genre/style 选填（拼文风句）。
	 */
	public record FixRequest(String text, List<FindingInput> findings, String platform, String contentForm,
			String genre, String style) {

		public FixRequest {
			text = text == null ? "" : text.trim();
			if (text.isEmpty()) {
				throw new IllegalArgumentException("待修复正文不能为空");
			}
			if (text.length() > MAX_TEXT_CHARS) {
				throw new IllegalArgumentException("文本超长（上限 " + MAX_TEXT_CHARS + " 字符）");
			}
			if (findings == null || findings.isEmpty()) {
				throw new IllegalArgumentException("问题清单不能为空");
			}
			if (findings.size() > MAX_FINDINGS) {
				throw new IllegalArgumentException("问题清单最多 " + MAX_FINDINGS + " 条");
			}
			List<FindingInput> normalized = new ArrayList<>(findings.size());
			for (FindingInput finding : findings) {
				if (finding == null || finding.category() == null || finding.category().isBlank()
						|| finding.match() == null || finding.match().isBlank()) {
					throw new IllegalArgumentException("问题清单每条需要 category 与 match");
				}
				normalized.add(new FindingInput(finding.category().trim(), finding.match().trim(),
						finding.advice() == null ? "" : finding.advice().trim()));
			}
			findings = List.copyOf(normalized);
			if (platform != null && platform.length() > 32) {
				throw new IllegalArgumentException("platform 超长（上限 32 字符）");
			}
			platform = platform == null || platform.isBlank() ? null : platform.trim();
			if (contentForm != null && !contentForm.isBlank() && !"answer".equals(contentForm)
					&& !"article".equals(contentForm)) {
				throw new IllegalArgumentException("contentForm 只支持 answer 或 article");
			}
			contentForm = contentForm == null || contentForm.isBlank() ? null : contentForm.trim();
			genre = genre == null || genre.isBlank() ? null : genre.trim();
			style = style == null || style.isBlank() ? null : style.trim();
		}

		public record FindingInput(String category, String match, String advice) {
		}
	}
}
