package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.util.LinkedHashMap;

/**
 * 视频制作脚本生成（草场 intelligence Slice 4）——首个多模态业务模块。 脚本 SSE 与 provider-neutral
 * 异步视频任务入口。
 */
@RestController
public class VideoProductionController {

	private static final Set<String> INDUSTRY_TYPES = Set.of("餐饮", "零售", "美业", "健身", "教育培训", "其他");
	private static final Set<String> VIDEO_STYLES = Set.of("烟火纪实", "治愈清新", "高级暗调", "数字人口播", "复古胶片");
	private static final String ERROR_MESSAGE = "视频脚本生成失败";

	private final IntelligenceCallerResolver callers;
	private final VideoGenerationService video;
	private final VideoGenerationProviderResolver videoProviders;
	private final FrozenTextExecutionService frozenText;
	private final VideoTaskCreationContext creationContexts;
	private final VideoGenerationJobRepository jobs;
	private final MediaReferenceRepository mediaRefs;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final long downloadUrlTtlSeconds;
	private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;
	private final ObjectMapper mapper = new ObjectMapper();

	public VideoProductionController(IntelligenceCallerResolver callers, VideoGenerationService video,
			VideoGenerationProviderResolver videoProviders, FrozenTextExecutionService frozenText,
			VideoTaskCreationContext creationContexts, VideoGenerationJobRepository jobs,
			MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider,
			@Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds,
			com.grassland.intelligence.contentsafety.ContentSafetyService safety) {
		this.callers = callers;
		this.video = video;
		this.videoProviders = videoProviders;
		this.frozenText = frozenText;
		this.safety = safety;
		this.creationContexts = creationContexts;
		this.jobs = jobs;
		this.mediaRefs = mediaRefs;
		this.storageProvider = storageProvider;
		this.downloadUrlTtlSeconds = Math.max(1L, downloadUrlTtlSeconds);
	}

	@PostMapping("/api/video-production/generate-video")
	public Mono<ResponseEntity<Map<String, Object>>> generateVideo(
			@RequestBody VideoGenerationService.VideoRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(c -> video.create(c.accountId(), c.organizationId(), body))
				.map(job -> ResponseEntity.accepted().body(envelope(job)));
	}

	@GetMapping("/api/video-production/jobs/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> getVideo(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(c -> video.get(id, c.accountId()))
				.map(job -> ResponseEntity.ok(envelope(job))).defaultIfEmpty(ResponseEntity.notFound().build());
	}

	/**
	 * Owner-scoped short-lived access to an archived video. The provider's
	 * temporary URL is deliberately not a fallback: until private archival is
	 * complete this endpoint is 404.
	 */
	@GetMapping("/api/video-production/jobs/{id}/download-url")
	public Mono<Map<String, Object>> downloadVideo(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> jobs.findById(id, caller.accountId()))
				.filter(job -> "succeeded".equals(job.status()) && job.resultUrl() != null)
				.flatMap(job -> archivedReference(job.resultUrl(), job.accountId())).map(ref -> {
					Instant now = Instant.now();
					long remaining = ref.expiresAt() == null
							? downloadUrlTtlSeconds
							: Math.max(1L, Duration.between(now, ref.expiresAt()).toSeconds());
					long ttl = Math.min(downloadUrlTtlSeconds, remaining);
					Instant expiresAt = now.plusSeconds(ttl);
					ObjectStorageAdapter storage = storageProvider.getIfAvailable();
					if (storage == null) {
						throw new IntelligenceException(404, "视频结果尚未归档或不存在");
					}
					return Map.<String, Object>of("downloadUrl",
							storage.presignDownload(ref.objectKey(), ttl).toString(), "expiresAt", expiresAt.toString(),
							"mediaId", ref.id().toString());
				}).switchIfEmpty(Mono.error(new IntelligenceException(404, "视频结果尚未归档或不存在")));
	}

	private Mono<com.grassland.intelligence.media.MediaReference> archivedReference(String resultReference,
			String ownerAccountId) {
		if (!resultReference.startsWith("/api/media/")) {
			return Mono.empty();
		}
		try {
			UUID mediaId = UUID.fromString(resultReference.substring("/api/media/".length()));
			return mediaRefs.findById(mediaId)
					.filter(ref -> ownerAccountId.equals(ref.ownerAccountId())
							&& MediaPurpose.VIDEO_ASSET.db().equals(ref.purpose()) && ref.status() == MediaStatus.ACTIVE
							&& (ref.expiresAt() == null || ref.expiresAt().isAfter(Instant.now())));
		} catch (IllegalArgumentException ignored) {
			return Mono.empty();
		}
	}

	@GetMapping("/api/video-production/jobs")
	public Mono<ResponseEntity<List<Map<String, Object>>>> listVideo(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMapMany(c -> video.list(c.accountId()).map(VideoProductionController::snapshot)).collectList()
				.map(ResponseEntity::ok);
	}

	@PostMapping("/api/video-production/jobs/{id}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancelVideo(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(c -> video.cancel(id, c.accountId()))
				.map(ok -> ok ? ResponseEntity.ok(Map.of("success", true)) : ResponseEntity.notFound().build());
	}

	/**
	 * 能力探测（任务书 #64 卡2 起读控制面）：{@code mode=video|slideshow} 由 video_generation
	 * 是否可解析决定；不可解析 = slideshow（图文成片降级，前端不锁死）。顶层四字段是旧契约
	 * 兼容镜像（卡4 前端改造完成后移除）。
	 */
	@GetMapping("/api/video-production/capabilities")
	public Mono<ResponseEntity<Map<String, Object>>> capabilities() {
		return Mono.zip(videoProviders.resolveVideoGeneration(), videoProviders.resolveTts())
				.map(videoAndTts -> {
					var video = videoAndTts.getT1();
					var tts = videoAndTts.getT2();
					Map<String, Object> videoBlock = new LinkedHashMap<>();
					videoBlock.put("available", video.available());
					videoBlock.put("provider",
							video.available() ? video.plan().resolution().provider() : null);
					videoBlock.put("model", video.available() ? video.plan().resolution().model() : null);
					videoBlock.put("unitPriceCents", video.available() ? video.plan().unitPriceCents() : null);
					videoBlock.put("reason", video.available() ? "" : video.unavailableReason());
					Map<String, Object> ttsBlock = new LinkedHashMap<>();
					ttsBlock.put("available", tts.available());
					ttsBlock.put("model", tts.available() ? tts.model() : null);
					ttsBlock.put("reason", tts.available() ? "" : tts.unavailableReason());
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("mode", video.available() ? "video" : "slideshow");
					body.put("video", videoBlock);
					body.put("tts", ttsBlock);
					body.put("provider", videoBlock.get("provider"));
					body.put("model", videoBlock.get("model"));
					body.put("available", video.available());
					body.put("reason", videoBlock.get("reason"));
					return ResponseEntity.ok(body);
				});
	}
	private static Map<String, Object> envelope(VideoGenerationJob j) {
		return Map.of("success", true, "data", snapshot(j));
	}
	private static Map<String, Object> snapshot(VideoGenerationJob j) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", j.id());
		m.put("status", j.status());
		m.put("progress", j.progress());
		m.put("provider", j.provider());
		m.put("model", j.model());
		m.put("contextSnapshotId", j.contextSnapshotId());
		m.put("resultUrl", j.resultUrl());
		m.put("actualDurationSeconds", j.actualDurationSeconds());
		m.put("actualCostCents", j.actualCostCents());
		m.put("errorMessage", j.errorMessage());
		return m;
	}

	@PostMapping("/api/video-production/generate-script")
	public Mono<ResponseEntity<Flux<DataBuffer>>> generateScript(@RequestBody ScriptRequest body,
			ServerWebExchange exchange) {
		if (body.isTaskMode()) {
			return callers.requireUser(exchange.getRequest())
					.flatMap(caller -> creationContexts.bind(body.contextSnapshotId(), caller.accountId(),
							body.targetPlatform()))
					.flatMap(
							binding -> frozenText
									.execute(exchange, body.contextSnapshotId(),
											List.of(VideoScriptPrompts.system(body.videoStyle(), body.industryType(),
													body.targetPlatform()), binding.promptContext(),
													VideoScriptPrompts.user(body)),
											2048, CreditFeature.VIDEO_PRODUCTION_SCRIPT,
											completion -> completion.content())
									.map(content -> sseEntity(
											withSafety(exchange, Flux.just(frame(Map.of("content", content))),
													binding.snapshot(), body.targetPlatform(), body.industryType()),
											exchange)))
					.onErrorMap(error -> error instanceof com.grassland.intelligence.security.IntelligenceException
							? error
							: new com.grassland.intelligence.security.IntelligenceException(502, ERROR_MESSAGE));
		}
		// GL-P3-AI-001 尾巴清偿：独立模式经执行环单环（预算闸/ai_run/BYOK 路由/积分闭环/失败退款）。
		// 纯流式契约收敛为「先执行后发帧」：完成聚合后一次性发 content 帧（+安全帧），402/502 在
		// SSE 前以 JSON 返回；前端 useVideoProduction 对单帧 content 与非 ok JSON 均兼容。
		return callers
				.resolve(
						exchange.getRequest())
				.flatMap(caller -> frozenText
						.executeIndependent(exchange,
								List.of(VideoScriptPrompts.system(body.videoStyle(), body.industryType(),
										body.targetPlatform()), VideoScriptPrompts.user(body)),
								2048, CreditFeature.VIDEO_PRODUCTION_SCRIPT, completion -> completion.content())
						.map(trace -> sseEntity(withSafety(exchange, Flux.just(frame(Map.of("content", trace.value()))),
								null, body.targetPlatform(), body.industryType()), exchange)));
	}

	/** 任务书 #34 D8：视频脚本流尾追加安全检查帧（脚本=长文本，L2 已配置时深检）。 */
	private Flux<String> withSafety(ServerWebExchange exchange, Flux<String> frames,
			com.grassland.intelligence.creationcontext.CreationContextSnapshot snapshot, String requestedPlatform,
			String requestedIndustry) {
		return safety.appendSafetyFrame(exchange, frames,
				com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor(),
				snapshot == null ? requestedPlatform : snapshot.platformId(),
				snapshot == null
						? requestedIndustry
						: com.grassland.intelligence.contentsafety.ContentSafetyService.industryFromSnapshot(snapshot),
				com.grassland.intelligence.contentsafety.ContentSafetyService.generationContext(snapshot));
	}

	private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
		Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.set("X-Accel-Buffering", "no");
		headers.setCacheControl("no-cache");
		return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
	}

	private String frame(Map<String, String> fields) {
		try {
			return mapper.writeValueAsString(fields);
		} catch (Exception error) {
			return "{\"error\":\"" + ERROR_MESSAGE + "\"}";
		}
	}

	/** 请求校验镜像 legacy zod：images 1-9，店铺/可选字段长度限制，行业/风格白名单。 */
	public record ScriptRequest(List<String> images, String shopName, String industryType, String shopAddress,
			String shopDescription, String videoStyle, String customPrompt, String targetPlatform, Boolean taskMode,
			UUID contextSnapshotId) {

		public ScriptRequest {
			images = images == null ? List.of() : List.copyOf(images);
			shopName = trimmed(shopName);
			industryType = trimmed(industryType);
			shopAddress = optionalTrimmed(shopAddress);
			shopDescription = optionalTrimmed(shopDescription);
			videoStyle = trimmed(videoStyle);
			customPrompt = optionalTrimmed(customPrompt);
			targetPlatform = optionalTrimmed(targetPlatform);

			if (images.isEmpty() || images.size() > 9 || images.stream().anyMatch(String::isBlank)) {
				throw new IllegalArgumentException("请上传 1-9 张有效图片");
			}
			if (shopName.isEmpty() || shopName.length() > 100) {
				throw new IllegalArgumentException("店铺名称需为 1-100 字");
			}
			if (!INDUSTRY_TYPES.contains(industryType)) {
				throw new IllegalArgumentException("请选择行业类型");
			}
			if (shopAddress != null && shopAddress.length() > 200) {
				throw new IllegalArgumentException("店铺地址最多 200 字");
			}
			if (shopDescription != null && shopDescription.length() > 500) {
				throw new IllegalArgumentException("店铺描述最多 500 字");
			}
			if (!VIDEO_STYLES.contains(videoStyle)) {
				throw new IllegalArgumentException("请选择视频风格");
			}
			if (customPrompt != null && customPrompt.length() > 500) {
				throw new IllegalArgumentException("用户要求最多 500 字");
			}
		}

		boolean isTaskMode() {
			return Boolean.TRUE.equals(taskMode);
		}

		private static String trimmed(String value) {
			return value == null ? "" : value.trim();
		}

		private static String optionalTrimmed(String value) {
			if (value == null) {
				return null;
			}
			String result = value.trim();
			return result.isEmpty() ? null : result;
		}
	}
}
