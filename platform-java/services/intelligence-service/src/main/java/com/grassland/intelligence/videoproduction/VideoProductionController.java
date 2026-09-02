package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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
	private final StoryboardService storyboards;
	private final ShotAnchorImageService anchorImages;
	private final VideoStoryboardRepository storyboardRows;
	private final VideoShotRepository shotRows;
	private final VideoShotTakeRepository takeRows;
	private final ObjectMapper mapper = new ObjectMapper();

	public VideoProductionController(IntelligenceCallerResolver callers, VideoGenerationService video,
			VideoGenerationProviderResolver videoProviders, FrozenTextExecutionService frozenText,
			VideoTaskCreationContext creationContexts, VideoGenerationJobRepository jobs,
			MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider,
			@Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds,
			com.grassland.intelligence.contentsafety.ContentSafetyService safety,
			StoryboardService storyboards, ShotAnchorImageService anchorImages,
			VideoStoryboardRepository storyboardRows, VideoShotRepository shotRows,
			VideoShotTakeRepository takeRows) {
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
		this.storyboards = storyboards;
		this.anchorImages = anchorImages;
		this.storyboardRows = storyboardRows;
		this.shotRows = shotRows;
		this.takeRows = takeRows;
	}

	/**
	 * 分镜只读详情（任务书 #66 C2/C3，画布专业模式数据源）：shots + 候选（含质检分与
	 * presign 播放址）+ grouping。属主闸（非属主 404 同详情口径）。
	 */
	@GetMapping("/api/video-production/storyboards/{id}")
	public Mono<Map<String, Object>> storyboardDetail(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> storyboardRows.findById(id, caller.accountId())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
						.flatMap(storyboard -> shotRows.findByStoryboard(id).collectList()
								.flatMap(shots -> takeRows.findByStoryboard(id).collectList()
										.flatMap(takes -> takeMediaReferences(takes)
												.map(refs -> storyboardBody(storyboard, shots, takes, refs))))))
				.map(data -> Map.of("success", true, "data", data));
	}

	/**
	 * 分组与版本分支（任务书 #66 C3，§3 契约）：PATCH /storyboards/{id}/grouping。
	 * 登录；仅分镜编辑期（draft，建任务即 committed）；分支 ≥1、镜头归属校验。
	 */
	@PatchMapping("/api/video-production/storyboards/{id}/grouping")
	public Mono<Map<String, Object>> patchGrouping(@PathVariable UUID id,
			@RequestBody GroupingPatchRequest body, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> storyboardRows.findById(id, caller.accountId())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
						.flatMap(storyboard -> {
							if (storyboard.isCommitted()) {
								return Mono.error(new IntelligenceException(409, "分镜已提交成片，不能再改分组"));
							}
							return shotRows.findByStoryboard(id).collectList().flatMap(shots -> {
								String normalized = normalizeGrouping(body, shots);
								return storyboardRows.updateGrouping(id, caller.accountId(), normalized)
										.flatMap(updated -> updated
												? Mono.just(Map.of("success", true,
														"data", Map.of("grouping", readJson(normalized))))
												: Mono.error(new IntelligenceException(409,
														"分镜已提交成片，不能再改分组")));
							});
						}));
	}

	/**
	 * 镜头内容编辑（任务书 #66 C3，同快速模式字段）：PUT /shots/{shotId}/content。
	 * 仅分镜编辑期；plannedSeconds 钳 4-6；prompt 不动（沿用行上原值）。
	 */
	@PutMapping("/api/video-production/shots/{shotId}/content")
	public Mono<Map<String, Object>> updateShotContent(@PathVariable UUID shotId,
			@RequestBody ShotContentRequest body, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> shotRows.findByIdForAccount(shotId, caller.accountId())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "镜头不存在")))
						.flatMap(shot -> storyboardRows.findById(shot.storyboardId())
								.flatMap(storyboard -> {
									if (storyboard.isCommitted()) {
										return Mono.error(new IntelligenceException(409,
												"分镜已提交成片，不能再编辑镜头"));
									}
									String visual = body.visual() == null || body.visual().isBlank()
											? shot.visual() : body.visual().trim();
									String narration = body.narration() == null
											? shot.narration() : body.narration().trim();
									int plannedSeconds = body.plannedSeconds() == null
											? shot.plannedSeconds()
											: Math.min(6, Math.max(4, body.plannedSeconds()));
									String cameraMove = body.cameraMove() == null || body.cameraMove().isBlank()
											? shot.cameraMove() : body.cameraMove().trim();
									int anchorImageIndex = body.anchorImageIndex() == null
											? shot.anchorImageIndex() : body.anchorImageIndex();
									return shotRows.updateContent(shotId, visual, narration, plannedSeconds,
											cameraMove, anchorImageIndex, shot.prompt())
											.flatMap(updated -> updated
													? Mono.just(Map.of("success", true, "data", Map.of(
															"shotId", shotId.toString(),
															"plannedSeconds", plannedSeconds)))
													: Mono.error(new IntelligenceException(404, "镜头不存在")));
								})));
	}

	public record GroupingPatchRequest(List<GroupingShotPatch> shots, List<GroupingBranchPatch> branches) {}

	public record GroupingShotPatch(UUID id, String groupId) {}

	public record GroupingBranchPatch(String id, String name, List<UUID> shotIds) {}

	public record ShotContentRequest(String visual, String narration, Integer plannedSeconds,
			String cameraMove, Integer anchorImageIndex) {}

	/** 画布数据装配：分镜元信息 + 镜头（含候选与质检分）+ grouping 解析。 */
	private Map<String, Object> storyboardBody(VideoStoryboard storyboard, List<VideoShot> shots,
			List<VideoShotTake> takes, Map<UUID, MediaReference> refs) {
		Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("id", storyboard.id().toString());
		data.put("targetDurationSeconds", storyboard.targetDurationSeconds());
		data.put("resolution", storyboard.resolutionOrDefault());
		data.put("status", storyboard.status());
		data.put("grouping", storyboard.grouping() == null ? null : readJson(storyboard.grouping()));
		Map<String, List<VideoShotTake>> takesByShot = new java.util.LinkedHashMap<>();
		for (VideoShotTake take : takes) {
			takesByShot.computeIfAbsent(take.shotId().toString(), key -> new java.util.ArrayList<>())
					.add(take);
		}
		List<Map<String, Object>> shotPayloads = new java.util.ArrayList<>();
		for (VideoShot shot : shots) {
			Map<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("id", shot.id().toString());
			payload.put("seq", shot.seq());
			payload.put("visual", shot.visual());
			payload.put("narration", shot.narration());
			payload.put("plannedSeconds", shot.plannedSeconds());
			payload.put("cameraMove", shot.cameraMove());
			payload.put("anchorImageIndex", shot.anchorImageIndex());
			payload.put("status", shot.status());
			List<Map<String, Object>> takePayloads = new java.util.ArrayList<>();
			for (VideoShotTake take : takesByShot.getOrDefault(shot.id().toString(), List.of())) {
				Map<String, Object> takePayload = new java.util.LinkedHashMap<>();
				takePayload.put("id", take.id().toString());
				takePayload.put("takeNo", take.takeNo());
				takePayload.put("status", take.status());
				takePayload.put("selectable", take.isSelectable());
				takePayload.put("score", take.score());
				takePayload.put("scoreLabels", parseLabels(take.scoreLabels()));
				takePayload.put("url", take.isSelectable() ? presignTakeUrl(take.mediaId(), refs) : null);
				takePayloads.add(takePayload);
			}
			payload.put("takes", takePayloads);
			shotPayloads.add(payload);
		}
		data.put("shots", shotPayloads);
		return data;
	}

	private Mono<Map<UUID, MediaReference>> takeMediaReferences(List<VideoShotTake> takes) {
		List<UUID> mediaIds = takes.stream().map(VideoShotTake::mediaId).filter(java.util.Objects::nonNull)
				.distinct().toList();
		if (mediaIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		return reactor.core.publisher.Flux.fromIterable(mediaIds)
				.flatMap(mediaRefs::findById)
				.collectMap(MediaReference::id);
	}

	private String presignTakeUrl(UUID mediaId, Map<UUID, MediaReference> refs) {
		if (mediaId == null) {
			return null;
		}
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		MediaReference reference = refs.get(mediaId);
		if (storage == null || reference == null) {
			return null;
		}
		try {
			return storage.presignDownload(reference.objectKey(), downloadUrlTtlSeconds).toString();
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** grouping 校验与归一（§3）：shots 归属 + branches ≥1 + shotIds ⊆ 分镜镜头。 */
	private String normalizeGrouping(GroupingPatchRequest body, List<VideoShot> shots) {
		if (body == null) {
			throw new IntelligenceException(400, "分组载荷不能为空");
		}
		java.util.Set<UUID> shotIds = shots.stream().map(VideoShot::id).collect(java.util.stream.Collectors.toSet());
		Map<String, Object> normalized = new java.util.LinkedHashMap<>();
		List<Map<String, Object>> shotPatches = new java.util.ArrayList<>();
		if (body.shots() != null) {
			for (GroupingShotPatch patch : body.shots()) {
				if (patch == null || patch.id() == null || !shotIds.contains(patch.id())) {
					throw new IntelligenceException(400, "分组包含未知镜头");
				}
				Map<String, Object> entry = new java.util.LinkedHashMap<>();
				entry.put("id", patch.id().toString());
				if (patch.groupId() != null && !patch.groupId().isBlank()) {
					entry.put("groupId", patch.groupId());
				}
				shotPatches.add(entry);
			}
		}
		normalized.put("shots", shotPatches);
		if (body.branches() == null || body.branches().isEmpty()) {
			throw new IntelligenceException(400, "至少保留一个版本分支");
		}
		List<Map<String, Object>> branches = new java.util.ArrayList<>();
		for (GroupingBranchPatch branch : body.branches()) {
			if (branch == null || branch.name() == null || branch.name().isBlank()) {
				throw new IntelligenceException(400, "分支名称不能为空");
			}
			List<String> branchShotIds = new java.util.ArrayList<>();
			if (branch.shotIds() != null) {
				for (UUID shotId : branch.shotIds()) {
					if (shotId == null || !shotIds.contains(shotId)) {
						throw new IntelligenceException(400, "分支包含未知镜头");
					}
					branchShotIds.add(shotId.toString());
				}
			}
			Map<String, Object> entry = new java.util.LinkedHashMap<>();
			entry.put("id", branch.id() == null || branch.id().isBlank() ? UUID.randomUUID().toString() : branch.id().trim());
			entry.put("name", branch.name().trim());
			entry.put("shotIds", branchShotIds);
			branches.add(entry);
		}
		normalized.put("branches", branches);
		try {
			return mapper.writeValueAsString(normalized);
		} catch (Exception error) {
			throw new IntelligenceException(500, "分组序列化失败");
		}
	}

	/** Jackson 3 的 JsonNode 直接入响应会在部分解码端保留节点形态——一律转普通 Map/List。 */
	private Object readJson(String raw) {
		try {
			return mapper.convertValue(mapper.readTree(raw),
					new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
		} catch (Exception error) {
			return null;
		}
	}

	private static List<String> parseLabels(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		try {
			return new ObjectMapper().readValue(raw,
					new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
		} catch (Exception error) {
			return List.of();
		}
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

	/**
	 * 结构化分镜生成（任务书 #64 卡3，§4.1 契约）：SSE 帧序列 meta → shot* → safety → [DONE]。
	 * 先执行后发帧（与 generate-script 同款收敛——执行环无流式结算包装，聚合后逐镜转发；
	 * 402/400/502 在 SSE 开始前以 JSON 返回）。分镜计费不变（video_production_script）。
	 */
	@PostMapping("/api/video-production/storyboard")
	public Mono<ResponseEntity<Flux<DataBuffer>>> storyboard(@RequestBody StoryboardRequest body,
			ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> storyboards.generate(exchange, caller.accountId(), caller.organizationId(),
						body))
				.map(outcome -> sseEntity(outcome.frames(), exchange))
				.onErrorMap(error -> error instanceof IntelligenceException
						|| error instanceof IllegalArgumentException
								? error
								: new IntelligenceException(502, "分镜生成失败"));
	}

	/**
	 * AI 补图首帧（任务书 #65 卡2，§3 契约）：平台资助执行环生成锚定图，落 shot 行并替换旧图。
	 * 409 = 分镜已提交（不在编辑期）/ 镜头仍绑定用户锚定图；503 = image_generation 未配置。
	 */
	@PostMapping("/api/video-production/shots/{shotId}/anchor:generate")
	public Mono<ResponseEntity<Map<String, Object>>> generateAnchor(@PathVariable UUID shotId,
			ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> anchorImages.generate(shotId, caller.accountId()))
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
						"mediaId", result.mediaId().toString(),
						"shot", anchorShotView(result.shot(), result.media())))));
	}

	/** 锚定响应的 ShotView（契约：anchorSource / anchorMediaId + 预览 presign）。 */
	private Map<String, Object> anchorShotView(VideoShot shot, MediaReference media) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", shot.id().toString());
		body.put("seq", shot.seq());
		body.put("visual", shot.visual());
		body.put("narration", shot.narration());
		body.put("plannedSeconds", shot.plannedSeconds());
		body.put("cameraMove", shot.cameraMove());
		body.put("anchorImageIndex", shot.anchorImageIndex());
		body.put("prompt", shot.prompt());
		body.put("status", shot.status());
		body.put("anchorSource", shot.anchorSource());
		body.put("anchorMediaId", shot.anchorMediaId() == null ? null : shot.anchorMediaId().toString());
		ObjectStorageAdapter storage = storageProvider.getIfAvailable();
		if (storage != null && media != null) {
			try {
				body.put("anchorUrl", storage.presignDownload(media.objectKey(), downloadUrlTtlSeconds).toString());
			} catch (RuntimeException ignored) {
				// presign 失败仅缺预览 URL，锚定本身已生效
			}
		}
		return body;
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

	/**
	 * 分镜请求（任务书 #64 卡3）：沿用 ScriptRequest 全部字段与校验（经 canonical 实例复用，
	 * 含 trim），新增 targetDurationSeconds 与 resolution。
	 * #65 卡1：时长放宽 15-180（步进 5 不变）；resolution 可选白名单两档，
	 * 缺省按平台映射（bilibili→1920x1080 横版，其余→1080x1920 竖版）。
	 */
	public record StoryboardRequest(List<String> images, String shopName, String industryType,
			String shopAddress, String shopDescription, String videoStyle, String customPrompt,
			String targetPlatform, Boolean taskMode, UUID contextSnapshotId, Integer targetDurationSeconds,
			String resolution, ReferenceShotStructure referenceShotStructure) {

		public StoryboardRequest {
			ScriptRequest canonical = new ScriptRequest(images, shopName, industryType, shopAddress,
					shopDescription, videoStyle, customPrompt, targetPlatform, taskMode, contextSnapshotId);
			images = canonical.images();
			shopName = canonical.shopName();
			industryType = canonical.industryType();
			shopAddress = canonical.shopAddress();
			shopDescription = canonical.shopDescription();
			videoStyle = canonical.videoStyle();
			customPrompt = canonical.customPrompt();
			targetPlatform = canonical.targetPlatform();
			taskMode = canonical.taskMode();
			contextSnapshotId = canonical.contextSnapshotId();
			if (targetDurationSeconds == null || targetDurationSeconds < 15 || targetDurationSeconds > 180
					|| targetDurationSeconds % 5 != 0) {
				throw new IllegalArgumentException("成片时长须为 15-180 秒且按 5 秒步进");
			}
			if (resolution != null && !resolution.isBlank() && !VideoResolution.allowed(resolution.trim())) {
				throw new IllegalArgumentException("分辨率仅支持 1080x1920 或 1920x1080");
			}
		}

		/** 请求显式指定优先；否则按平台缺省（bilibili 横版）。 */
		String resolvedResolution() {
			return resolution != null && !resolution.isBlank()
					? resolution.trim()
					: VideoResolution.defaultFor(targetPlatform());
		}

		boolean isTaskMode() {
			return Boolean.TRUE.equals(taskMode);
		}

		/**
		 * 参考分析结构化引用（任务书 #66 E1，§3 契约）：带参考视频分析时前端随请求透传，
		 * 分镜生成 user 消息按 §3 文案注入（仅参考节奏与结构，不复刻内容）。数值一律包装类型
		 * （Jackson 3 record 缺失 primitive 直接 400 的坑）。
		 */
		public record ReferenceShotStructure(List<ReferenceShot> shotStructure, Double hookAtSeconds) {

			public List<ReferenceShot> safeShots() {
				return shotStructure == null ? List.of() : shotStructure;
			}
		}

		public record ReferenceShot(Double durationSeconds, String purpose) {
		}
	}
}
