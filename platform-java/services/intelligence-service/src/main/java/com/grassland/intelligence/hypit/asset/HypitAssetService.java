package com.grassland.intelligence.hypit.asset;

import com.grassland.intelligence.hypit.asset.HypitAssetRepository.AssetRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.Accepted;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.CommandRow;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 素材域（任务书 #107-1 C107-05 / K05/K08/K09）。
 *
 * <p>
 * 三条入库路径（C107F2-31 起全部真实字节）：multipart 上传（256MiB 上限 →流式固化→sidecar probe 核验
 * →ready；成功前不显示可用）、 mediaId 导入/交接（归属/固化核验——owner 不符与不存在同答 404、非 active
 * 409；从对象存储复制真实字节为 res- 句柄，不再落 media: JSON 引用）、URL 抓取（sidecar pinned yt-dlp +
 * URL 策略；失败保留来源站 具体原因）。probe 回执 failed 不伪装成功（422 invalid_content/透传 B 端码）； 相同
 * project+sha256 复用既有 ready 资源。 删除查引用：有效 revision 引用存在 → 409
 * hypit_reference_in_use；否则软删。 工具端点只放行白名单（K08：schema 未登记的 action 400）。
 */
@Service
public class HypitAssetService {

	/**
	 * 工具白名单（§6.4 终态 19 项：media 8 + speech 3 + image 2 + snapshot 1 + capture 3 +
	 * packages.build/pack 2； C107-05 起 media，C107F-02 speech/image，C107F-03
	 * snapshot/capture/packages）。白名单集 == B dispatcher 已路由的 owner 级
	 * kind（tests/deployment/hypit-tools.contract.test.ts 三向锁定： 契约 ↔ B kind ↔
	 * 本集；packages.install/status 为 operator 专用预注记排除）。
	 */
	static final Set<String> MEDIA_TOOLS = Set.of("media.probe", "media.cut", "media.frames", "media.tile",
			"media.tiles", "media.boundaries", "media.fetch", "media.prepare-fetch", "speech.transcribe",
			"speech.measure", "speech.align", "image.transform", "image.compose", "snapshot", "capture.screenshot",
			"capture.run", "capture.install-browser", "packages.build", "packages.pack");

	/** role 枚举（契约 §6.2 P/assets）。 */
	static final Set<String> ROLES = Set.of("reference", "portrait", "product", "voice", "music", "footage", "font",
			"other");

	private static final Set<String> ACCEPTED_UPLOAD_PREFIXES = Set.of("video/", "audio/", "image/");

	private final HypitAssetRepository assets;
	private final HypitProjectRepository projects;
	private final HypitCommandRepository commands;
	private final HypitJobRepository jobs;
	private final HypitJobEventRepository events;
	private final HypitResourceService resources;
	private final HypitSidecarClient sidecar;
	private final HypitProperties properties;
	private final TransactionalOperator transactions;
	private final org.springframework.r2dbc.core.DatabaseClient db;
	private final com.grassland.intelligence.ai.run.FrozenTextExecutionService frozen;
	private final com.grassland.intelligence.hypit.agent.HypitReferenceAnalysisService analyses;
	private final HypitAssetUploadService uploads;

	public HypitAssetService(HypitAssetRepository assets, HypitProjectRepository projects,
			HypitCommandRepository commands, HypitJobRepository jobs, HypitJobEventRepository events,
			HypitResourceService resources, HypitSidecarClient sidecar, HypitProperties properties,
			TransactionalOperator transactions, org.springframework.r2dbc.core.DatabaseClient db,
			com.grassland.intelligence.ai.run.FrozenTextExecutionService frozen,
			com.grassland.intelligence.hypit.agent.HypitReferenceAnalysisService analyses,
			HypitAssetUploadService uploads) {
		this.assets = assets;
		this.projects = projects;
		this.commands = commands;
		this.jobs = jobs;
		this.events = events;
		this.resources = resources;
		this.sidecar = sidecar;
		this.properties = properties;
		this.transactions = transactions;
		this.db = db;
		this.frozen = frozen;
		this.analyses = analyses;
		this.uploads = uploads;
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "hypit_invalid_input", message);
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "hypit_not_found", "资源不存在。");
	}

	private Mono<Void> requireReadyOwner(String accountId, UUID projectId) {
		// ready 时保持值流动到 switchIfEmpty 之后（empty 会被误判为不存在——同
		// HypitAccessService.requireProjectOwner 的教训）。
		return projects.findOwnerStatus(accountId, projectId)
				.flatMap(status -> "ready".equals(status)
						? Mono.just(status)
						: Mono.<String>error(
								new IntelligenceException(409, "hypit_state_conflict", "工程当前状态不可导入素材：" + status)))
				.switchIfEmpty(Mono.error(notFound())).then();
	}

	// ------------------------------------------------------------------
	// 列表 / 详情 / 内容（Range 代理）
	// ------------------------------------------------------------------

	public Mono<List<Map<String, Object>>> list(String accountId, UUID projectId) {
		return requireReadyOwner(accountId, projectId)
				.then(assets.listByProject(projectId).map(HypitAssetService::toDto).collectList());
	}

	public Mono<Map<String, Object>> get(String accountId, UUID projectId, UUID assetId) {
		return requireReadyOwner(accountId, projectId).then(assets.findById(projectId, assetId))
				.switchIfEmpty(Mono.error(notFound())).map(HypitAssetService::toDto);
	}

	public Mono<HypitResourceService.ResourceResponse> content(String accountId, UUID projectId, UUID assetId,
			String rangeHeader) {
		return requireReadyOwner(accountId, projectId).then(assets.findById(projectId, assetId))
				.switchIfEmpty(Mono.error(notFound()))
				.flatMap(row -> "ready".equals(row.status())
						? resources.open(row.resourceHandle(), rangeHeader)
						: Mono.error(notFound()));
	}

	// ------------------------------------------------------------------
	// multipart 上传：流式固化 → probe → ready
	// ------------------------------------------------------------------

	public Mono<Map<String, Object>> upload(String accountId, UUID projectId, UUID requestId, FilePart file,
			String role) {
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		if (role == null || !ROLES.contains(role)) {
			return Mono.error(invalid("role 必须是 " + ROLES + " 之一。"));
		}
		String contentType = file.headers().getContentType() == null
				? "application/octet-stream"
				: file.headers().getContentType().toString();
		if (ACCEPTED_UPLOAD_PREFIXES.stream().noneMatch(contentType::startsWith)) {
			return Mono.error(invalid("不接受的素材类型：" + contentType));
		}
		String fileName = sanitizeFileName(file.filename());
		return requireReadyOwner(accountId, projectId).then(uploads.ingestUpload(file, fileName, contentType))
				.flatMap(receipt -> runAssetJob(accountId, projectId, requestId, "asset.upload",
						canonicalOf(projectId, role, fileName),
						Map.of("projectId", projectId.toString(), "role", role, "fileName", fileName, "handle",
								receipt.handle(), "sha256", receipt.sha256(), "sizeBytes", receipt.sizeBytes(),
								"mimeType", contentType, "originKind", "upload"),
						false));
	}

	// ------------------------------------------------------------------
	// mediaId 导入（C107F2-31：真实字节复制，不再落 media: JSON 引用）
	// ------------------------------------------------------------------

	/** 素材库媒体事实（归属/固化核验后的可复制源）。 */
	record MediaFact(String owner, String status, String mime, Long size, String checksum, String objectKey) {
	}

	/**
	 * 归属/固化核验：本人 + active 才可交接。他人与不存在同答 404（不泄漏存在性）；
	 * 非激活（pending/finalizing/deleting）→ 409 hypit_source_not_permanent（先固化再交接）。
	 */
	private Mono<MediaFact> mediaFact(String accountId, UUID mediaId) {
		return db
				.sql("SELECT owner_account_id, status, mime_type, size_bytes, checksum, object_key"
						+ " FROM media_reference WHERE id = CAST(:id AS uuid)")
				.bind("id", mediaId.toString())
				.map((row, meta) -> new MediaFact(row.get("owner_account_id", String.class),
						row.get("status", String.class), row.get("mime_type", String.class),
						row.get("size_bytes", Long.class), row.get("checksum", String.class),
						row.get("object_key", String.class)))
				.one().switchIfEmpty(Mono.error(notFound()))
				.flatMap(fact -> !accountId.equals(fact.owner())
						? Mono.<MediaFact>error(notFound())
						: "active".equals(fact.status())
								? Mono.just(fact)
								: Mono.<MediaFact>error(new IntelligenceException(409, "hypit_source_not_permanent",
										"参考媒体尚未固化（status=" + fact.status() + "），请先存入素材库后再交接。")));
	}

	public Mono<Map<String, Object>> importMedia(String accountId, UUID projectId, UUID requestId, UUID mediaId,
			String role) {
		if (requestId == null || mediaId == null) {
			return Mono.error(invalid("requestId 与 mediaId 必填。"));
		}
		if (role == null || !ROLES.contains(role)) {
			return Mono.error(invalid("role 必须是 " + ROLES + " 之一。"));
		}
		return requireReadyOwner(accountId, projectId).then(mediaFact(accountId, mediaId)).flatMap(fact -> uploads
				.copyMediaObject(fact.objectKey(), fact.mime() == null ? "application/octet-stream" : fact.mime(),
						fact.size() == null ? 0L : fact.size())
				.flatMap(receipt -> runAssetJob(accountId, projectId, requestId, "asset.import",
						canonicalOf(projectId, role, "media:" + mediaId),
						Map.of("projectId", projectId.toString(), "role", role, "handle", receipt.handle(), "sha256",
								receipt.sha256(), "sizeBytes", receipt.sizeBytes(), "mimeType",
								fact.mime() == null ? "application/octet-stream" : fact.mime(), "originKind", "import",
								"mediaId", mediaId.toString()),
						false)));
	}

	// ------------------------------------------------------------------
	// sourceContext 交接物化（C107F2-31：创建后进入参考素材即可见）
	// ------------------------------------------------------------------

	/** 交接物化结果：asset=null 表示无需物化（无 sourceContext / brief / analysis 锚定）。 */
	public record SourceOutcome(String sourceKind, Map<String, Object> asset, String note) {
	}

	/**
	 * 物化工程 sourceContext：kind=media 复制真实字节为 {@code res-} 素材（幂等——同工程同 mediaId
	 * 已物化则直接返回；崩溃重放由稳定 requestId 命中命令幂等）；kind=analysis 仅锚定 run id（B 站/抖音
	 * 解析产物是临时代理媒体，无可复制本地字节——参考媒体须先固化素材库，见 useCloneReferenceTransfer 契约），
	 * 返回可行动说明；kind=brief 无引用。
	 */
	public Mono<SourceOutcome> importSource(String accountId, UUID projectId) {
		record SourceRef(String kind, String id) {
		}
		return requireReadyOwner(accountId, projectId).then(db
				.sql("SELECT COALESCE(source_context::text, '') AS ctx FROM hypit_project"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("ctx", String.class)).one())
				.flatMap(raw -> {
					if (raw == null || raw.isBlank()) {
						return Mono.just(new SourceOutcome(null, null, null));
					}
					Map<String, Object> parsed = HypitJson.read(raw);
					String kind = HypitJson.stringValue(parsed.get("kind"), "");
					String id = HypitJson.stringValue(parsed.get("id"), "");
					if ("media".equals(kind)) {
						UUID mediaId;
						try {
							mediaId = UUID.fromString(id);
						} catch (IllegalArgumentException error) {
							return Mono.just(new SourceOutcome("media", null, "sourceContext.id 不是合法 uuid。"));
						}
						return materializeMediaSource(accountId, projectId, mediaId);
					}
					if ("analysis".equals(kind)) {
						return Mono
								.just(new SourceOutcome("analysis", null, "分析交接仅锚定运行记录；参考视频请直接上传，或先固化到素材库后用媒体入口交接。"));
					}
					return Mono.just(new SourceOutcome(kind.isBlank() ? null : kind, null, null));
				});
	}

	private Mono<SourceOutcome> materializeMediaSource(String accountId, UUID projectId, UUID mediaId) {
		UUID stableRequestId = UUID.nameUUIDFromBytes(
				("hypit-import-source:" + projectId + ":" + mediaId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return assets.findActiveByProjectAndMediaId(projectId, mediaId).map(HypitAssetService::toDto).map(dto -> {
			dto.put("reused", true);
			return new SourceOutcome("media", dto, null);
		}).switchIfEmpty(Mono.defer(() -> importMedia(accountId, projectId, stableRequestId, mediaId, "reference")
				.map(asset -> new SourceOutcome("media", asset, null))));
	}

	// ------------------------------------------------------------------
	// URL 抓取（sidecar pinned yt-dlp + URL 策略；失败保留具体原因）
	// ------------------------------------------------------------------

	public Mono<Map<String, Object>> importUrl(String accountId, UUID projectId, UUID requestId, String url,
			String role) {
		if (requestId == null || url == null || url.isBlank()) {
			return Mono.error(invalid("requestId 与 url 必填。"));
		}
		if (role == null || !ROLES.contains(role)) {
			return Mono.error(invalid("role 必须是 " + ROLES + " 之一。"));
		}
		String fileName = "import-" + UUID.randomUUID() + ".mp4";
		return requireReadyOwner(accountId, projectId).then(runAssetJob(
				accountId, projectId, requestId, "asset.url", canonicalOf(projectId, role, url), Map.of("projectId",
						projectId.toString(), "role", role, "fileName", fileName, "url", url, "originKind", "url"),
				false));
	}

	// ------------------------------------------------------------------
	// 全片参考分析（C107F2-16 / RULE-10 / W110）
	// ------------------------------------------------------------------

	/** 分析产物：analysis 持久后可刷新重读；未就绪时 analysis=null 且 reason 可行动。 */
	public record ReferenceAnalysisOutcome(com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis analysis,
			String notReadyReason) {

		public boolean ready() {
			return analysis != null;
		}
	}

	/**
	 * intent=analyze 的确定性分析步骤（RULE-10 步骤 1/2）：真实 probe（duration/音轨）→ 真实抽帧 →
	 * 有音轨才真实转写（无声以 ABSENT 证据落档，不伪造台词）→ 平台执行入口综合分段 →
	 * {@link HypitReferenceAnalysisService#analyze} 合并 coverage/锚点并幂等持久。
	 * 视觉/转写综合的平台模型未配置时返回未就绪（不冒充全片完成）；sidecar 各步失败如实上抛。
	 */
	public Mono<ReferenceAnalysisOutcome> analyzeReference(String accountId, UUID projectId, UUID assetId,
			UUID operationId) {
		return requireReadyOwner(accountId, projectId)
				.then(assets.findById(projectId, assetId).switchIfEmpty(Mono.error(notFound())))
				.flatMap(asset -> sidecar
						.commandAsync("java-ref-probe-" + operationId, "media.probe",
								Map.of("handle", asset.resourceHandle()))
						.map(HypitAssetService::commandResult).flatMap(probe -> {
							double duration = HypitJson.doubleValue(probe.get("durationSeconds"), 0.0);
							boolean hasAudio = Boolean.TRUE.equals(probe.get("hasAudio"));
							Mono<Map<String, Object>> transcription = hasAudio
									? sidecar
											.commandAsync("java-ref-transcribe-" + operationId, "speech.transcribe",
													Map.of("handle", asset.resourceHandle()))
											.map(HypitAssetService::commandResult)
									: Mono.just(Map.of("audioTrack", "ABSENT"));
							// C107F2-37：media.frames 契约只收显式 times[]（间隔采样须走
							// media.tiles）；取 6 个均匀中点帧，零/负时长退化为单帧 0s。
							List<Double> frameTimes = new ArrayList<>();
							if (duration > 0) {
								for (int index = 0; index < 6; index++) {
									frameTimes.add(duration * (2 * index + 1) / 12.0);
								}
							} else {
								frameTimes.add(0.0);
							}
							Mono<Map<String, Object>> frames = sidecar
									.commandAsync("java-ref-frames-" + operationId, "media.frames",
											Map.of("handle", asset.resourceHandle(), "times", frameTimes))
									.map(HypitAssetService::commandResult);
							return Mono.zip(transcription, frames).flatMap(tuple -> synthesizeSegments(accountId, asset,
									duration, hasAudio, tuple.getT1(), tuple.getT2(), operationId));
						}));
	}

	/** 平台执行入口综合分段（W119 prompt）；未配置/失败返回未就绪。 */
	private Mono<ReferenceAnalysisOutcome> synthesizeSegments(String accountId, AssetRow asset, double duration,
			boolean hasAudio, Map<String, Object> transcription, Map<String, Object> frames, UUID operationId) {
		Map<String, Object> userFacts = new HashMap<>();
		userFacts.put("durationSeconds", duration);
		userFacts.put("audioTrack", hasAudio ? "PRESENT" : "ABSENT");
		userFacts.put("transcript", transcription.get("text") == null ? "" : transcription.get("text"));
		userFacts.put("frames", frames.get("frames") == null ? List.of() : frames.get("frames"));
		userFacts.put("assetId", asset.id().toString());
		com.grassland.intelligence.security.IntelligenceCallerResolver.Caller caller = new com.grassland.intelligence.security.IntelligenceCallerResolver.Caller(
				accountId, null, null, null, null, null, null, null);
		return frozen
				.executeIndependent(null, caller,
						List.of(com.grassland.intelligence.ai.ChatMessage.system(REFERENCE_ANALYSIS_PROMPT),
								com.grassland.intelligence.ai.ChatMessage.user(HypitJson.write(userFacts))),
						4000, com.grassland.intelligence.credits.CreditFeature.AI_RUN_TEXT,
						java.time.Duration.ofSeconds(90), completion -> completion.content())
				.map(traced -> traced.value()).flatMap(raw -> parseSegments(raw, asset))
				.flatMap(observed -> analyses
						.analyze(new com.grassland.intelligence.hypit.agent.HypitReferenceAnalysisService.AnalysisInput(
								"ref-" + operationId, asset.sha256(), duration,
								String.valueOf(transcription.getOrDefault("language", "unknown")),
								String.valueOf(frames.getOrDefault("aspectRatio", "unknown")),
								hasAudio
										? com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.AudioTrack.PRESENT
										: com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.AudioTrack.ABSENT,
								transcription.get("text") != null || !hasAudio, observed.segments(), observed.systems(),
								observed.events(), observed.openQuestions()))
						.map(analysis -> new ReferenceAnalysisOutcome(analysis, null)))
				.onErrorResume(error -> Mono.just(new ReferenceAnalysisOutcome(null, "视觉/转写综合能力未就绪："
						+ (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()))));
	}

	/** 综合模型输出的分段解析（宽松容错；无有效段落返回未就绪）。 */
	private Mono<ObservedSegments> parseSegments(String raw, AssetRow asset) {
		try {
			String text = raw == null ? "" : raw.trim();
			if (text.startsWith("```")) {
				int first = text.indexOf('\n');
				int last = text.lastIndexOf("```");
				if (first > 0 && last > first) {
					text = text.substring(first + 1, last);
				}
			}
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(text);
			List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment> segments = new java.util.ArrayList<>();
			List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.System> systems = new java.util.ArrayList<>();
			List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Event> events = new java.util.ArrayList<>();
			List<String> openQuestions = new java.util.ArrayList<>();
			for (com.fasterxml.jackson.databind.JsonNode node : root.path("segments")) {
				List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence> evidence = new java.util.ArrayList<>();
				for (com.fasterxml.jackson.databind.JsonNode ev : node.path("evidence")) {
					evidence.add(new com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence(
							ev.path("assetId").asText(asset.id().toString()), ev.path("sourceTimeSeconds").asDouble(0),
							ev.path("note").asText("")));
				}
				if (evidence.isEmpty()) {
					evidence.add(new com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence(
							asset.id().toString(), node.path("startSeconds").asDouble(0), "综合模型分段"));
				}
				segments.add(new com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment(
						node.path("index").asInt(segments.size()), node.path("startSeconds").asDouble(0),
						node.path("endSeconds").asDouble(0), node.path("summary").asText(""), evidence));
			}
			for (com.fasterxml.jackson.databind.JsonNode node : root.path("systems")) {
				List<String> indexes = new java.util.ArrayList<>();
				node.path("segmentIndexes").forEach(index -> indexes.add(index.asText()));
				systems.add(new com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.System(
						node.path("systemId").asText("sys-" + systems.size()), node.path("kind").asText("other"),
						node.path("name").asText(""), node.path("firstSeenSeconds").asDouble(0),
						node.path("lastSeenSeconds").asDouble(0), indexes));
			}
			for (com.fasterxml.jackson.databind.JsonNode node : root.path("events")) {
				events.add(new com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Event(
						node.path("kind").asText("cut"), node.path("atSeconds").asDouble(0),
						node.path("trigger").isTextual() ? node.path("trigger").asText() : null,
						node.path("evidenceAsset").asText(asset.id().toString()),
						node.path("inferred").asBoolean(false)));
			}
			root.path("openQuestions").forEach(question -> openQuestions.add(question.asText()));
			if (segments.isEmpty()) {
				return Mono.error(new IllegalStateException("综合输出无有效分段"));
			}
			return Mono.just(new ObservedSegments(segments, systems, events, openQuestions));
		} catch (Exception invalid) {
			return Mono.error(new IllegalStateException("综合输出无法解析为分段结构"));
		}
	}

	private record ObservedSegments(
			List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment> segments,
			List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.System> systems,
			List<com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Event> events,
			List<String> openQuestions) {
	}

	private static Map<String, Object> commandResult(SidecarCommand command) {
		if ("failed".equals(command.state())) {
			throw new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_backend_unavailable",
					"参考分析工具失败：" + command.kind());
		}
		return HypitJson.mapValue(command.result());
	}

	/** W119：分段综合 prompt（resources/hypit/prompts/reference-analysis.md）。 */
	private static final String REFERENCE_ANALYSIS_PROMPT = loadReferenceAnalysisPrompt();

	private static String loadReferenceAnalysisPrompt() {
		try (var input = HypitAssetService.class.getResourceAsStream("/hypit/prompts/reference-analysis.md")) {
			return input == null
					? "把帧与转写证据合并为覆盖全片的分段 JSON。"
					: new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception error) {
			return "把帧与转写证据合并为覆盖全片的分段 JSON。";
		}
	}

	// ------------------------------------------------------------------
	// 工具端点（C05 白名单 = media.*）
	// ------------------------------------------------------------------

	public Mono<Map<String, Object>> runTool(String accountId, UUID projectId, String tool, UUID requestId,
			Map<String, Object> input) {
		if (!MEDIA_TOOLS.contains(tool)) {
			return Mono.error(new IntelligenceException(400, "hypit_unsupported_action", "工具未登记或未开放：" + tool));
		}
		if (requestId == null) {
			return Mono.error(invalid("requestId 必填。"));
		}
		return requireReadyOwner(accountId, projectId)
				.then(runAssetJob(
						accountId, projectId, requestId, "tool.run", HypitJson.write(Map.of("projectId",
								projectId.toString(), "tool", tool, "input", input == null ? Map.of() : input)),
						null, false));
	}

	// ------------------------------------------------------------------
	// 删除：引用守卫 + 软删
	// ------------------------------------------------------------------

	public Mono<Void> delete(String accountId, UUID projectId, UUID assetId, String idempotencyKey) {
		return requireReadyOwner(accountId, projectId).then(assets.findById(projectId, assetId))
				.switchIfEmpty(Mono.error(notFound()))
				.flatMap(row -> assets.countReferencing(assetId)
						.flatMap(referencing -> referencing > 0
								? Mono.<Void>error(new IntelligenceException(409, "hypit_reference_in_use",
										"素材已被 " + referencing + " 处修订引用，不能删除。"))
								: assets.markStatus(assetId, "deleted").then()));
	}

	// ------------------------------------------------------------------
	// command/job 收敛（复用 C04 幂等命令骨架）
	// ------------------------------------------------------------------

	private static String canonicalOf(UUID projectId, String role, String identity) {
		return "{\"projectId\":\"" + projectId + "\",\"role\":\"" + role + "\",\"id\":" + HypitJson.write(identity)
				+ "}";
	}

	/**
	 * 单事务记账（command+job）→ sidecar 执行 → 单事务收敛。{@code directMedia=true} 表示 无 sidecar
	 * 步骤（mediaId 导入直接落 ready）。重放命中已存在 command 时读结果返回。
	 */
	/** command+job 同事务记账的产物。 */
	private record Seed(CommandRow command, UUID jobId, boolean replay) {
	}

	/**
	 * 单事务记账（command+job）→ sidecar 执行 → 单事务收敛。{@code directMedia=true} 表示 无 sidecar
	 * 步骤（mediaId 导入直接落 ready）。重放命中已存在 command 时读结果返回。
	 */
	private Mono<Map<String, Object>> runAssetJob(String accountId, UUID projectId, UUID requestId, String action,
			String canonical, Map<String, Object> executePayload, boolean directMedia) {
		String payloadHash = sha256Hex(canonical);
		Mono<Seed> acceptedTx = commands
				.insert(accountId, action, requestId, "asset:" + projectId, payloadHash,
						"{\"canonical\":" + HypitJson.write(canonical) + "}", projectId)
				.flatMap(accepted -> accepted.existing() && !accepted.row().payloadHash().equals(payloadHash)
						? Mono.<Accepted>error(HypitCommandRepository.conflict(accepted.row()))
						: Mono.just(accepted))
				.flatMap(accepted -> accepted.existing()
						? Mono.just(new Seed(accepted.row(), null, true))
						: jobs.insert(new JobRow(UUID.randomUUID(), accepted.row().id(), projectId, accountId, action,
								"queued", "pending", null, null, null, null, 0, 1, null, null, 1, null, null, null,
								null, null, null)).map(job -> new Seed(accepted.row(), job.id(), false)))
				.as(transactions::transactional);
		return acceptedTx.flatMap(
				seed -> seed.replay() ? replayAsset(seed.command()) : executeAsset(seed, executePayload, directMedia));
	}

	private Mono<Map<String, Object>> executeAsset(Seed seed, Map<String, Object> executePayload, boolean directMedia) {
		return jobs.findById(seed.jobId()).switchIfEmpty(Mono.error(new IllegalStateException("asset job missing")))
				.flatMap(job -> {
					if ("tool.run".equals(seed.command().action())) {
						return dispatchSidecar(seed, executePayload)
								.flatMap(receipt -> "failed".equals(receipt.state())
										? convergeFailed(seed, job, receiptFailure(seed, receipt))
										: convergeTool(seed, job, receipt))
								.onErrorResume(error -> convergeFailed(seed, job, error));
					}
					if (directMedia) {
						return convergeAsset(seed, job, new HashMap<>(executePayload));
					}
					return dispatchSidecar(seed, executePayload)
							.flatMap(receipt -> "failed".equals(receipt.state())
									? convergeFailed(seed, job, receiptFailure(seed, receipt))
									: convergeAsset(seed, job, mergedFacts(executePayload, receipt)))
							.onErrorResume(error -> convergeFailed(seed, job, error));
				});
	}

	/**
	 * C107F2-31：sidecar 回执 state=failed 不再伪装成功收敛。probe 拒绝（伪装扩展名/坏字节）→ 422
	 * hypit_invalid_content；fetch/工具失败按 B 端码原样透传（保留来源站具体原因），状态取 422。
	 */
	private static IntelligenceException receiptFailure(Seed seed, SidecarCommand receipt) {
		String action = seed.command().action();
		String code = receipt.error() == null ? null : HypitJson.stringValue(receipt.error().get("code"), null);
		String message = receipt.error() == null ? null : HypitJson.stringValue(receipt.error().get("message"), null);
		if ("asset.upload".equals(action) || "asset.import".equals(action) || "tool.run".equals(action)) {
			return new IntelligenceException(422, code == null ? "hypit_invalid_content" : code,
					message == null ? "服务端探测未通过，素材内容与声明不符。" : message);
		}
		// asset.url：抓取失败是输入/来源问题，透传 B 端码与具体原因。
		return new IntelligenceException(422, code == null ? "hypit_fetch_failed" : code,
				message == null ? "来源抓取失败，链接与平台支持范围请核对后重试。" : message);
	}

	private Mono<SidecarCommand> dispatchSidecar(Seed seed, Map<String, Object> executePayload) {
		if (!properties.enabled()) {
			return Mono.error(new IllegalStateException("hypit engine disabled"));
		}
		String action = seed.command().action();
		if ("tool.run".equals(action)) {
			Map<String, Object> toolPayload = new HashMap<>(toolInputOf(seed.command()));
			toolPayload.put("projectId",
					seed.command().projectId() == null ? "" : seed.command().projectId().toString());
			return sidecar.commandAsync(seed.command().id().toString(), toolKindOf(seed.command()), toolPayload);
		}
		if ("asset.url".equals(action)) {
			Map<String, Object> payload = new HashMap<>(Map.of("projectId", seed.command().projectId().toString(),
					"fileName", HypitJson.stringValue(executePayload.get("fileName"), "import.mp4"), "url",
					HypitJson.stringValue(executePayload.get("url"), "")));
			return sidecar.commandAsync("java-fetch-" + seed.command().projectId(), "media.fetch", payload);
		}
		// asset.upload：ingest 已完成，probe 校验结构后落 ready
		return sidecar.commandAsync("java-asset-probe-" + seed.command().id(), "media.probe",
				Map.of("handle", HypitJson.stringValue(executePayload.get("handle"), "")));
	}

	/** canonical {"projectId","tool","input"} 中的 input 即工具载荷。 */
	private Map<String, Object> toolInputOf(CommandRow command) {
		Map<String, Object> payload = HypitJson.read(command.payloadJson());
		Map<String, Object> canonical = HypitJson.read(HypitJson.stringValue(payload.get("canonical"), "{}"));
		return HypitJson.mapValue(canonical.get("input"));
	}

	private String toolKindOf(CommandRow command) {
		Map<String, Object> payload = HypitJson.read(command.payloadJson());
		Map<String, Object> canonical = HypitJson.read(HypitJson.stringValue(payload.get("canonical"), "{}"));
		return HypitJson.stringValue(canonical.get("tool"), "");
	}

	/** 落库事实：执行载荷为底，回执覆盖（fetch 带回新句柄；probe 补结构 JSON）。 */
	private static Map<String, Object> mergedFacts(Map<String, Object> executePayload, SidecarCommand receipt) {
		Map<String, Object> merged = new HashMap<>(executePayload == null ? Map.of() : executePayload);
		Map<String, Object> result = HypitJson.mapValue(receipt.result());
		Object handle = result.get("handle");
		if (handle != null) {
			merged.put("handle", HypitJson.stringValue(handle, ""));
			merged.put("sha256",
					HypitJson.stringValue(result.get("sha256"), HypitJson.stringValue(merged.get("sha256"), "")));
			merged.put("sizeBytes",
					HypitJson.longValue(result.get("sizeBytes"), HypitJson.longValue(merged.get("sizeBytes"), 0)));
		}
		Object probe = result.get("probe");
		if (probe == null) {
			probe = result.isEmpty() ? null : result;
		}
		if (probe != null) {
			merged.put("probeJson", HypitJson.write(probe));
		}
		return merged;
	}

	private Mono<Map<String, Object>> convergeAsset(Seed seed, JobRow job, Map<String, Object> facts) {
		UUID assetId = UUID.randomUUID();
		String role = roleOf(seed.command());
		String originKind = HypitJson.stringValue(facts.get("originKind"), "upload");
		String mediaIdRaw = HypitJson.stringValue(facts.get("mediaId"), null);
		UUID mediaId = "import".equals(originKind) && mediaIdRaw != null ? UUID.fromString(mediaIdRaw) : null;
		String originUrl = "url".equals(originKind) ? urlOf(seed.command()) : null;
		String sha256 = HypitJson.stringValue(facts.get("sha256"), "");
		// C107F2-31（§6.14）：相同 project/hash 复用既有 ready 资源（来源保留在原行），不建第二行。
		return Mono.defer(() -> assets.findReadyByProjectAndSha(job.projectId(), sha256)
				.flatMap(existing -> jobs.updateState(job.id(), "succeeded", null, null)
						.then(events.append(job.id(), "terminal",
								HypitJson.write(Map.of("state", "succeeded", "reused", true))))
						.then(commands.saveResult(seed.command().id(), "succeeded",
								HypitJson.write(Map.of("assetId", existing.id().toString(), "jobId",
										job.id().toString(), "reused", true))))
						.then(Mono.just(reusedDto(existing))))
				.switchIfEmpty(Mono.defer(() -> {
					AssetRow row = new AssetRow(assetId, job.projectId(), mediaId,
							HypitJson.stringValue(facts.get("handle"), "unassigned"), role, originKind, originUrl,
							sha256, HypitJson.stringValue(facts.get("mimeType"), "application/octet-stream"),
							HypitJson.longValue(facts.get("sizeBytes"), 0), null, null,
							HypitJson.stringValue(facts.get("probeJson"), null), "ready", 1, null, null);
					return assets.insert(row).then(jobs.updateState(job.id(), "succeeded", null, null))
							.then(events.append(job.id(), "terminal", HypitJson.write(Map.of("state", "succeeded"))))
							.then(commands.saveResult(seed.command().id(), "succeeded",
									HypitJson.write(
											Map.of("assetId", assetId.toString(), "jobId", job.id().toString()))))
							.then(assets.findById(job.projectId(), assetId)).map(HypitAssetService::toDto);
				})).switchIfEmpty(Mono.error(new IllegalStateException("asset vanished after converge"))))
				.as(transactions::transactional);
	}

	private static Map<String, Object> reusedDto(AssetRow existing) {
		Map<String, Object> dto = toDto(existing);
		dto.put("reused", true);
		return dto;
	}

	/** 工具任务收敛：不建素材行；回执即结果（句柄化的产物 + 时间 metadata）。 */
	private Mono<Map<String, Object>> convergeTool(Seed seed, JobRow job, SidecarCommand receipt) {
		Map<String, Object> result = HypitJson.mapValue(receipt.result());
		return Mono
				.defer(() -> jobs.updateState(job.id(), "succeeded", null, null)
						.then(events.append(job.id(), "terminal", HypitJson.write(Map.of("state", "succeeded"))))
						.then(commands.saveResult(seed.command().id(), "succeeded",
								HypitJson.write(Map.of("jobId", job.id().toString(), "tool", toolKindOf(seed.command()),
										"result", result))))
						.then(Mono.just(Map.<String, Object>of("jobId", job.id().toString(), "state", "succeeded",
								"tool", toolKindOf(seed.command()), "result", result == null ? Map.of() : result))))
				.as(transactions::transactional);
	}

	private Mono<Map<String, Object>> convergeFailed(Seed seed, JobRow job, Throwable error) {
		String code = error instanceof IntelligenceException exception && exception.code() != null
				? exception.code()
				: "hypit_backend_unavailable";
		// C107F2-31：4xx 输入/内容类错误原样浮出（413 超限、422 探测未过——保留真实语义与
		// B 端码）；基础设施类才收敛为 503 通用可重试错误。
		IntelligenceException surfaced = error instanceof IntelligenceException clientError
				&& clientError.status() < 500 && clientError.code() != null
						? clientError
						: new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_backend_unavailable",
								"素材任务失败（" + code + "），输入已保留，可按同 requestId 重试。");
		// 失败记录先独立事务提交（事务内发错误信号会整体回滚——失败留档就没了），
		// 再在事务外把可行动错误浮给调用方。
		return jobs
				.updateState(job.id(), "failed", code,
						error.getMessage() == null ? "asset job failed" : error.getMessage())
				.then(events.append(job.id(), "terminal", HypitJson.write(Map.of("state", "failed", "reason", code))))
				.as(transactions::transactional).then(Mono.<Map<String, Object>>error(surfaced));
	}

	private Mono<Map<String, Object>> replayAsset(CommandRow existing) {
		if (existing.resultJson() == null) {
			return Mono.error(
					new IntelligenceException(503, "hypit_backend_unavailable", "先前素材任务仍在收敛中，请稍后按同 requestId 重试。"));
		}
		Map<String, Object> result = HypitJson.read(existing.resultJson());
		UUID assetId = UUID.fromString(HypitJson.stringValue(result.get("assetId"), ""));
		return assets.findById(existing.projectId(), assetId).map(HypitAssetService::toDto)
				.switchIfEmpty(Mono.error(notFound()));
	}

	private static String roleOf(CommandRow command) {
		Map<String, Object> payload = HypitJson.read(command.payloadJson());
		Map<String, Object> canonical = HypitJson.read(HypitJson.stringValue(payload.get("canonical"), "{}"));
		return HypitJson.stringValue(canonical.get("role"), "reference");
	}

	private static String urlOf(CommandRow command) {
		Map<String, Object> payload = HypitJson.read(command.payloadJson());
		Map<String, Object> canonical = HypitJson.read(HypitJson.stringValue(payload.get("canonical"), "{}"));
		return HypitJson.stringValue(canonical.get("id"), null);
	}

	static String sanitizeFileName(String raw) {
		String name = raw == null ? "" : raw.replace("\\", "/");
		int slash = name.lastIndexOf('/');
		name = slash >= 0 ? name.substring(slash + 1) : name;
		if (name.isBlank() || name.startsWith(".") || name.contains("..")) {
			throw invalid("文件名非法。");
		}
		return name.length() > 128 ? name.substring(name.length() - 128) : name;
	}

	static String sha256Hex(String canonical) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	public static Map<String, Object> toDto(AssetRow row) {
		Map<String, Object> dto = new HashMap<>();
		dto.put("id", row.id().toString());
		dto.put("projectId", row.projectId().toString());
		dto.put("role", row.role());
		dto.put("originKind", row.originKind());
		dto.put("originUrl", row.originUrl());
		dto.put("mediaId", row.mediaId() == null ? null : row.mediaId().toString());
		dto.put("resourceHandle", row.resourceHandle());
		dto.put("mimeType", row.mimeType());
		dto.put("sizeBytes", row.sizeBytes());
		dto.put("sha256", row.sha256());
		dto.put("width", row.width());
		dto.put("height", row.height());
		dto.put("status", row.status());
		dto.put("createdAt", row.createdAt().toString());
		dto.put("updatedAt", row.updatedAt().toString());
		return dto;
	}

	/** 资源句柄形状（B 侧 res-…）供 controller 校验。 */
	public static boolean looksLikeHandle(String handle) {
		return handle != null && handle.matches("^res-[0-9a-f]{16}-[0-9a-z]+$");
	}
}
