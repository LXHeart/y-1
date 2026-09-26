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
 * 三条入库路径：multipart 上传（流式固化→sidecar probe→ready；成功前不显示可用）、 mediaId 导入（校验
 * media_reference 归属/active——owner 不符 404、非 active 拒绝；持久化 media 引用而非临时地址）、URL
 * 抓取（sidecar pinned yt-dlp + URL 策略；失败保留来源站 具体原因）。删除查引用：有效 revision 引用存在 → 409
 * hypit_reference_in_use；否则软删。 工具端点只放行本卡 media.* 白名单（K08：schema 未登记的 action
 * 400）。
 */
@Service
public class HypitAssetService {

	/** C107-05 工具白名单（§6.4 media 面；后续卡增量放开）。 */
	static final Set<String> MEDIA_TOOLS = Set.of("media.probe", "media.cut", "media.frames", "media.tile",
			"media.tiles", "media.boundaries", "media.fetch", "media.prepare-fetch");

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

	public HypitAssetService(HypitAssetRepository assets, HypitProjectRepository projects,
			HypitCommandRepository commands, HypitJobRepository jobs, HypitJobEventRepository events,
			HypitResourceService resources, HypitSidecarClient sidecar, HypitProperties properties,
			TransactionalOperator transactions, org.springframework.r2dbc.core.DatabaseClient db) {
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
		return requireReadyOwner(accountId, projectId).then(resources.ingest(file.content(), fileName, contentType, -1))
				.flatMap(receipt -> runAssetJob(accountId, projectId, requestId, "asset.upload",
						canonicalOf(projectId, role, fileName),
						Map.of("projectId", projectId.toString(), "role", role, "fileName", fileName, "handle",
								receipt.handle(), "sha256", receipt.sha256(), "sizeBytes", receipt.sizeBytes(),
								"mimeType", contentType, "originKind", "upload"),
						false));
	}

	// ------------------------------------------------------------------
	// mediaId 导入：校验 media_reference 归属/active 后持久化引用
	// ------------------------------------------------------------------

	public Mono<Map<String, Object>> importMedia(String accountId, UUID projectId, UUID requestId, UUID mediaId,
			String role) {
		if (requestId == null || mediaId == null) {
			return Mono.error(invalid("requestId 与 mediaId 必填。"));
		}
		if (role == null || !ROLES.contains(role)) {
			return Mono.error(invalid("role 必须是 " + ROLES + " 之一。"));
		}
		record MediaFact(String owner, String status, String mime, Long size, String checksum) {
		}
		return requireReadyOwner(accountId, projectId)
				.then(db.sql("SELECT owner_account_id, status, mime_type, size_bytes, checksum"
						+ " FROM media_reference WHERE id = CAST(:id AS uuid)").bind("id", mediaId.toString())
						.map((row, meta) -> new MediaFact(row.get("owner_account_id", String.class),
								row.get("status", String.class), row.get("mime_type", String.class),
								row.get("size_bytes", Long.class), row.get("checksum", String.class)))
						.one())
				.switchIfEmpty(Mono.error(invalid("mediaId 不存在。")))
				.flatMap(fact -> !accountId.equals(fact.owner())
						? Mono.<MediaFact>error(invalid("mediaId 不属于当前账号。"))
						: !"active".equals(fact.status())
								? Mono.<MediaFact>error(invalid("媒体尚未确认（status=" + fact.status() + "）。"))
								: Mono.just(fact))
				.flatMap(fact -> runAssetJob(accountId, projectId, requestId, "asset.import",
						canonicalOf(projectId, role, "media:" + mediaId),
						Map.of("projectId", projectId.toString(), "role", role, "handle", "media:" + mediaId, "sha256",
								fact.checksum() == null ? "" : fact.checksum(), "sizeBytes",
								fact.size() == null ? 0L : fact.size(), "mimeType",
								fact.mime() == null ? "application/octet-stream" : fact.mime(), "originKind", "import",
								"mediaId", mediaId.toString()),
						true));
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
								.flatMap(receipt -> convergeTool(seed, job, receipt))
								.onErrorResume(error -> convergeFailed(seed, job, error));
					}
					if (directMedia) {
						return convergeAsset(seed, job, new HashMap<>(executePayload));
					}
					return dispatchSidecar(seed, executePayload)
							.flatMap(receipt -> convergeAsset(seed, job, mergedFacts(executePayload, receipt)))
							.onErrorResume(error -> convergeFailed(seed, job, error));
				});
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
		AssetRow row = new AssetRow(assetId, job.projectId(), mediaId,
				HypitJson.stringValue(facts.get("handle"), "unassigned"), role, originKind, originUrl,
				HypitJson.stringValue(facts.get("sha256"), ""),
				HypitJson.stringValue(facts.get("mimeType"), "application/octet-stream"),
				HypitJson.longValue(facts.get("sizeBytes"), 0), null, null,
				HypitJson.stringValue(facts.get("probeJson"), null), "ready", 1, null, null);
		return Mono
				.defer(() -> assets.insert(row).then(jobs.updateState(job.id(), "succeeded", null, null))
						.then(events.append(job.id(), "terminal", HypitJson.write(Map.of("state", "succeeded"))))
						.then(commands.saveResult(seed.command().id(), "succeeded",
								HypitJson.write(Map.of("assetId", assetId.toString(), "jobId", job.id().toString()))))
						.then(assets.findById(job.projectId(), assetId)).map(HypitAssetService::toDto))
				.switchIfEmpty(Mono.error(new IllegalStateException("asset vanished after converge")))
				.as(transactions::transactional);
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
		return Mono.defer(() -> jobs
				.updateState(job.id(), "failed", code,
						error.getMessage() == null ? "asset job failed" : error.getMessage())
				.then(events.append(job.id(), "terminal", HypitJson.write(Map.of("state", "failed", "reason", code))))
				.then(Mono.<Map<String, Object>>error(new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(),
						"hypit_backend_unavailable", "素材任务失败（" + code + "），输入已保留，可按同 requestId 重试。"))))
				.as(transactions::transactional);
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
