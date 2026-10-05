package com.grassland.intelligence.moments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 朋友圈内容生成 API（PRD §4.4 朋友圈图片+文字）：{@code POST /api/moments-generation/generate}。
 *
 * <p>
 * SSE 非帧流（镜像 image-analysis）：progress/result/error 判别帧 + [DONE]。校验、鉴权与扣分 都在 SSE
 * headers 之前（400/401/402 JSON）；上游失败在流内先退款再发 error 帧（GL-P0-BILL-002）。 任务模式经
 * {@link MomentsTaskCreationContext} 绑定冻结上下文，积分由冻结执行闭环。
 */
@RestController
@RequestMapping("/api/moments-generation")
public class MomentsGenerationController {

	static final String ERROR_MESSAGE = "朋友圈内容生成失败";

	private final IntelligenceCallerResolver callers;
	private final MomentsGenerationService service;
	private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;
	private final MomentsTaskCreationContext contexts;
	// 任务书 #108 C-03：私有文风档案解析（brief.voice → resolve → 用户消息附录）
	private final com.grassland.intelligence.creationvoice.CreationVoiceService voices;
	private final ObjectMapper mapper = new ObjectMapper();

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MomentsGenerationController.class);

	@org.springframework.beans.factory.annotation.Autowired
	public MomentsGenerationController(IntelligenceCallerResolver callers, MomentsGenerationService service,
			MomentsTaskCreationContext contexts, com.grassland.intelligence.contentsafety.ContentSafetyService safety,
			com.grassland.intelligence.creationvoice.CreationVoiceService voices) {
		this.callers = callers;
		this.service = service;
		this.safety = safety;
		this.contexts = contexts;
		this.voices = voices;
	}

	/**
	 * 兼容构造器（Spring 装配走上面的全参构造）：无档案仓储时仅支持 voice 缺省/none 的旧请求面 ——resolve
	 * 对这两种选择不触仓储、直接返回空附录，行为与 #108 接线前一致。
	 */
	public MomentsGenerationController(IntelligenceCallerResolver callers, MomentsGenerationService service,
			MomentsTaskCreationContext contexts, com.grassland.intelligence.contentsafety.ContentSafetyService safety) {
		this(callers, service, contexts, safety,
				new com.grassland.intelligence.creationvoice.CreationVoiceService(null, null));
	}

	@PostMapping("/generate")
	public Mono<ResponseEntity<Flux<DataBuffer>>> generate(@RequestBody MomentsRequest body,
			ServerWebExchange exchange) {
		// 任务书 #108 C-03（RULE-013/§6.4）：brief 层统一校验（voice 严校验、format 误入拒绝），
		// 方法体内抛 IntelligenceException → 信封化 400 INVALID_CREATION_BRIEF，先于图像校验与执行环。
		// 校验返回的归一化 brief（null→Map.of()，与旧构造器校验语义一致）向下传递。
		Map<String, Object> brief = com.grassland.intelligence.creationcontext.CreationBriefInput
				.validateForGeneration(body.brief());
		MomentsStyle style = MomentsStyle.fromKey(body.style());
		if (body.isTaskMode()) {
			return preflightCaller(exchange)
					.flatMap(caller -> contexts.bind(body.contextSnapshotId(), caller.accountId())
							.flatMap(binding -> resolveVoice("moments/task", caller.accountId(), brief).flatMap(
									voice -> validatedImages(body).map(dataUrls -> sseEntity(withSafety(exchange,
											generateTask(dataUrls, style, body.topic(), body.feelings(), binding,
													exchange, brief, voice.appendix())
													.onErrorResume(e -> Flux.just(errorFrame())),
											binding.snapshot()), exchange)))))
					.onErrorMap(error -> error instanceof IntelligenceException
							? error
							: new IntelligenceException(502, ERROR_MESSAGE));
		}
		// GL-P3-AI-001 尾巴清偿：独立模式经执行环（扣分/失败退款在环内）；执行完成后再发 SSE，
		// 扣费/预算拒绝（402）以 JSON 先于流，与任务模式同契约。
		return validatedImages(body).flatMap(dataUrls -> preflightCaller(exchange)
				.flatMap(caller -> resolveVoice("moments", caller.accountId(), brief)
						.flatMap(voice -> generateStream(dataUrls, style, body.topic(), body.feelings(),
								caller.accountId(), caller.organizationId(), exchange, brief, voice.appendix())
								.map(frames -> sseEntity(withSafety(exchange, frames, null), exchange))))
				.onErrorMap(error -> error instanceof IntelligenceException
						? error
						: new IntelligenceException(502, ERROR_MESSAGE)));
	}

	/**
	 * 执行接缝（任务书 #108 C-03）：文风附录非空走带附录重载；为空（缺省/none）回落旧签名调用面 ——两分支行为等价（旧重载即附录=""
	 * 的委托），同时保持既有单测对旧签名的桩与 verify 兼容。
	 */
	private Flux<String> generateTask(List<String> dataUrls, MomentsStyle style, String topic, String feelings,
			MomentsTaskCreationContext.Binding binding, ServerWebExchange exchange, Map<String, Object> brief,
			String voiceAppendix) {
		return voiceAppendix.isEmpty()
				? service.generateTask(dataUrls, style, topic, feelings, binding, exchange, brief)
				: service.generateTask(dataUrls, style, topic, feelings, binding, exchange, brief, voiceAppendix);
	}

	private Mono<Flux<String>> generateStream(List<String> dataUrls, MomentsStyle style, String topic, String feelings,
			String accountId, String organizationId, ServerWebExchange exchange, Map<String, Object> brief,
			String voiceAppendix) {
		return voiceAppendix.isEmpty()
				? service.generateStream(dataUrls, style, topic, feelings, accountId, organizationId, exchange, brief)
				: service.generateStream(dataUrls, style, topic, feelings, accountId, organizationId, exchange, brief,
						voiceAppendix);
	}

	/**
	 * 文风解析（任务书 #108 C-03 / §5 体裁映射）：朋友圈平台固定 moments、体裁固定 short-post； role/profile
	 * 错误先于执行环与模型调用；解析失败以 JSON 先于 SSE（409/400）。
	 */
	private Mono<com.grassland.intelligence.creationvoice.CreationVoiceTypes.ResolvedVoice> resolveVoice(String chain,
			String accountId, Map<String, Object> brief) {
		return voices.resolve(accountId, "moments", "short-post", brief).doOnNext(voice -> log
				.info("creation voice: chain={} platform=moments genre=short-post {}", chain, voice.logSummary()));
	}

	/** 任务书 #34 D8：朋友圈文案流尾追加安全检查帧（检查文本=result 帧 copy）。 */
	private Flux<String> withSafety(ServerWebExchange exchange, Flux<String> frames,
			com.grassland.intelligence.creationcontext.CreationContextSnapshot snapshot) {
		return safety.appendSafetyFrame(exchange, frames,
				com.grassland.intelligence.contentsafety.ContentSafetyService.momentsCopyExtractor(),
				snapshot == null ? "moments" : snapshot.platformId(),
				com.grassland.intelligence.contentsafety.ContentSafetyService.industryFromSnapshot(snapshot),
				com.grassland.intelligence.contentsafety.ContentSafetyService.generationContext(snapshot));
	}

	/** 素材图 base64 解码与 magic 校验留在 boundedElastic（解码 9×5MB 不占事件循环）。 */
	/** 执行环会消费一次 jti；这里只验签预检，避免同一请求被误判为重放。 */
	private Mono<IntelligenceCallerResolver.Caller> preflightCaller(ServerWebExchange exchange) {
		return callers.resolveForPreflight(exchange.getRequest())
				.filter(caller -> !caller.isService() && caller.accountId() != null)
				.switchIfEmpty(Mono.error(new IntelligenceException(403, "需要用户身份")));
	}

	private Mono<List<String>> validatedImages(MomentsRequest body) {
		return Mono.fromCallable(() -> service.validateAndEncode(body.images()))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
		Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.set("X-Accel-Buffering", "no");
		headers.setCacheControl("no-cache");
		return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
	}

	private String errorFrame() {
		try {
			return mapper.writeValueAsString(Map.of("type", "error", "error", ERROR_MESSAGE));
		} catch (Exception e) {
			return "{\"type\":\"error\",\"error\":\"" + ERROR_MESSAGE + "\"}";
		}
	}

	/**
	 * 请求体：{@code topic} 1-500 字必填；{@code style} 四选一必填；{@code feelings} ≤200 字选填；
	 * {@code images} base64 素材图 0-9 张（data: URI 白名单 MIME 或裸 base64 默认 JPEG）。
	 */
	public record MomentsRequest(String topic, String style, String feelings, List<String> images, Boolean taskMode,
			UUID contextSnapshotId, Map<String, Object> brief) {
		public MomentsRequest(String topic, String style, String feelings, List<String> images, Boolean taskMode,
				UUID contextSnapshotId) {
			this(topic, style, feelings, images, taskMode, contextSnapshotId, null);
		}
		public MomentsRequest {
			// 任务书 #108 C-03（§6.4）：brief 层校验移至控制器方法体首行（构造器抛出会被 Jackson 包成
			// ServerWebInputException 丢失 INVALID_CREATION_BRIEF 信封）；其余字段校验保持原样。
			topic = topic == null ? "" : topic.trim();
			if (topic.isEmpty() || topic.length() > 500) {
				throw new IllegalArgumentException("主题需为 1-500 字");
			}
			MomentsStyle.fromKey(style);
			style = style.trim();
			feelings = feelings == null || feelings.isBlank() ? null : feelings.trim();
			if (feelings != null && feelings.length() > 200) {
				throw new IllegalArgumentException("补充感受不能超过 200 字");
			}
			if (images != null && images.size() > 9) {
				throw new IllegalArgumentException("最多上传 9 张图片");
			}
			boolean task = Boolean.TRUE.equals(taskMode);
			if (task && contextSnapshotId == null) {
				throw new IllegalArgumentException("任务创作必须绑定创作上下文快照");
			}
			if (!task && contextSnapshotId != null) {
				throw new IllegalArgumentException("独立创作不能绑定任务上下文快照");
			}
			taskMode = task;
		}

		boolean isTaskMode() {
			return Boolean.TRUE.equals(taskMode);
		}
	}
}
