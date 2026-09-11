package com.grassland.intelligence.creationassistant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 创作草稿 API（草场 PRD §4.9.7 / Slice 15 Stage 1）。
 *
 * <p>
 * 承载推荐官/用户在 AI 创作中心的生产内容，支持自动保存（乐观锁 PUT，前端 debounce 触发）+
 * 跨设备继续（后端存储）。任意登录用户管理自己的草稿，owner 级 IDOR 守卫（跨账号 404）。
 *
 * <p>
 * source 关联复用前端 {@code CreationSource} 联合类型；task 源带 taskVersion 引用，是 §4.12
 * 不可变创作上下文快照的衔接入口（完整快照另立 Slice）。
 *
 * <p>
 * 任务书 #100 C100-04：创建/保存/归档/删除与 owner 装载委托 {@link CreationDraftService}
 * （画布工作区绑定复用同一写路径）；本层只保留 wire——请求解析、列表/版本读侧与响应包装。
 */
@RestController
@RequestMapping("/api/creation-drafts")
public class CreationDraftController {
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private static final int DEFAULT_VERSION_LIMIT = 20;
	private static final int MAX_VERSION_LIMIT = 100;
	/** 任务书 #92 C-02：最近项目列表分页上限（默认 20，最大 50）。 */
	private static final int DEFAULT_LIST_LIMIT = 20;
	private static final int MAX_LIST_LIMIT = 50;

	private final IntelligenceCallerResolver callers;
	private final CreationDraftRepository drafts;
	private final CreationDraftService service;
	private final CreationDraftExportService exports;

	public CreationDraftController(IntelligenceCallerResolver callers, CreationDraftRepository drafts,
			CreationDraftService service, CreationDraftExportService exports) {
		this.callers = callers;
		this.drafts = drafts;
		this.service = service;
		this.exports = exports;
	}

	/** 创建草稿。 */
	@PostMapping
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateDraftRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.create(caller, body))
				.map(view -> success(view.toMap()));
	}

	/**
	 * 列出自己的草稿（updatedAt DESC, id DESC 兜底）。任务书 #92 C-02：{@code limit}（默认 20，最大 50）+
	 * {@code status=active} 过滤（排除已归档——最近项目列表语义）；不带 status 时保持旧全量口径。
	 */
	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(
			@RequestParam(defaultValue = "" + DEFAULT_LIST_LIMIT) int limit,
			@RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
			ServerWebExchange exchange) {
		if (limit < 1 || limit > MAX_LIST_LIMIT) {
			return Mono.error(new IntelligenceException(400, "limit 必须在 1 到 50 之间"));
		}
		String filter = status == null || status.isBlank() ? "all" : status;
		if (!List.of("all", "active", "archived").contains(filter)) {
			return Mono.error(new IntelligenceException(400, "status 过滤仅支持 active/archived/all"));
		}
		DraftCursor before = decodeCursor(cursor);
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> drafts.listByAccount(caller.accountId(), limit + 1, filter,
						before == null ? null : Instant.parse(before.updatedAt()),
						before == null ? null : UUID.fromString(before.id())).collectList())
				.map(items -> {
					List<CreationDraft> page = items.subList(0, Math.min(limit, items.size()));
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("items", page.stream().map(draft -> CreationDraftView.of(draft).toMap()).toList());
					data.put("nextCursor", items.size() > limit ? encodeCursor(page.getLast()) : null);
					return success(data);
				});
	}

	/**
	 * 归档草稿（任务书 #92 C-02：删除最近项目索引）。不接受 body；置 archived + version+1；重复归档幂等返回当前行。
	 * 不删除素材、运行记录或任务数据（§5.3）。
	 */
	@PostMapping("/{id}/archive")
	public Mono<ResponseEntity<Map<String, Object>>> archive(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.archive(id, caller))
				.map(view -> success(view.toMap()));
	}

	/** 草稿详情（owner 校验，跨账号 404）。 */
	@GetMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.loadOwned(id, caller.accountId()))
				.map(draft -> success(CreationDraftView.of(draft).toMap()));
	}

	/** 版本历史（含当前版本），按 version 倒序做 keyset 分页。 */
	@GetMapping("/{id}/versions")
	public Mono<ResponseEntity<Map<String, Object>>> versions(@PathVariable String id,
			@RequestParam(defaultValue = "" + DEFAULT_VERSION_LIMIT) int limit,
			@RequestParam(required = false) Integer cursor, ServerWebExchange exchange) {
		if (limit < 1 || limit > MAX_VERSION_LIMIT) {
			return Mono.error(new IntelligenceException(400, "limit 必须在 1 到 100 之间"));
		}
		if (cursor != null && cursor < 1) {
			return Mono.error(new IntelligenceException(400, "cursor 必须是正版本号"));
		}
		return callers.resolve(exchange.getRequest()).flatMap(caller -> service.loadOwned(id, caller.accountId()))
				.flatMap(draft -> drafts.listVersions(draft.id(), cursor, limit + 1).collectList())
				.map(items -> versionPage(items, limit)).map(CreationDraftController::success);
	}

	/** 指定版本的完整只读快照（owner 校验，跨账号 404）。 */
	@GetMapping("/{id}/versions/{version}")
	public Mono<ResponseEntity<Map<String, Object>>> version(@PathVariable String id, @PathVariable int version,
			ServerWebExchange exchange) {
		if (version < 1) {
			return Mono.error(new IntelligenceException(404, "草稿版本不存在"));
		}
		return callers.resolve(exchange.getRequest()).flatMap(caller -> service.loadOwned(id, caller.accountId()))
				.flatMap(draft -> drafts.findVersion(draft.id(), version))
				.switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(404, "草稿版本不存在"))))
				.map(CreationDraftController::toVersionResponse).map(CreationDraftController::success);
	}

	/**
	 * 自动保存（乐观锁）。前端 debounce 触发；先落旧版快照（appendVersion）再 save（version+1），同事务。 版本冲突 →
	 * 409（前端 reload 后合并）。
	 */
	@PutMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> save(@PathVariable String id,
			@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.save(id, caller, body))
				.map(view -> success(view.toMap()));
	}

	/** 软删草稿（owner 校验）。 */
	@DeleteMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.delete(id, caller))
				.map(CreationDraftController::success);
	}

	/**
	 * 图文导出（AI内容中心改造-02 / T15、T31、T32）：按指定版本（缺省=当前版本）组装交付 manifest， 媒体引用返回 presigned
	 * 短期下载链接。不写 workspace、不触发生成；过期重新请求即可。
	 */
	@PostMapping("/{id}/exports")
	public Mono<ResponseEntity<Map<String, Object>>> export(@PathVariable String id, @RequestBody ExportRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.loadOwned(id, caller.accountId()).flatMap(draft -> exports.export(draft,
						body == null ? null : body.version(), body == null ? null : body.format(), caller)))
				.map(CreationDraftController::success);
	}

	// ---- 响应序列化 ----

	private static Map<String, Object> versionPage(java.util.List<CreationDraftVersion> fetched, int limit) {
		boolean hasMore = fetched.size() > limit;
		java.util.List<CreationDraftVersion> page = hasMore ? fetched.subList(0, limit) : fetched;
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("items", page.stream().map(version -> Map.<String, Object>of("version", version.version(), "createdAt",
				version.createdAt(), "title", version.title())).toList());
		data.put("nextCursor",
				hasMore && !page.isEmpty() ? Integer.toString(page.get(page.size() - 1).version()) : null);
		return data;
	}

	private static Map<String, Object> toVersionResponse(CreationDraftVersion version) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("version", version.version());
		map.put("createdAt", version.createdAt());
		map.put("title", version.title());
		map.put("sourceType", version.sourceType().db());
		map.put("status", version.status().db());
		if (version.taskId() != null)
			map.put("taskId", version.taskId());
		if (version.taskVersion() != null)
			map.put("taskVersion", version.taskVersion());
		if (version.storeId() != null)
			map.put("storeId", version.storeId());
		map.put("contentMode", (version.contentMode() == null ? DraftContentMode.ARTICLE : version.contentMode()).db());
		if (version.questionText() != null)
			map.put("questionText", version.questionText());
		if (version.questionRef() != null)
			map.put("questionRef", version.questionRef());
		if (version.platform() != null)
			map.put("platform", version.platform());
		if (version.contentForm() != null)
			map.put("contentForm", version.contentForm());
		if (version.topic() != null)
			map.put("topic", version.topic());
		if (version.articleTitle() != null)
			map.put("articleTitle", version.articleTitle());
		if (version.outline() != null)
			map.put("outline", version.outline());
		if (version.content() != null)
			map.put("content", version.content());
		map.put("workspace", CreationWorkspace.sanitizeForRead(version.workspace()));
		map.put("resultAssetIds", version.resultAssetIds());
		map.put("runIds", version.runIds());
		map.put("capability", version.workspace().getOrDefault("capability", "article"));
		return map;
	}

	private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
		return ResponseEntity.ok(Map.of("success", true, "data", data));
	}

	private record DraftCursor(String updatedAt, String id) {
	}

	private static DraftCursor decodeCursor(String cursor) {
		if (cursor == null)
			return null;
		try {
			if (cursor.length() > 512)
				throw new IllegalArgumentException();
			DraftCursor decoded = MAPPER.readValue(Base64.getUrlDecoder().decode(cursor), DraftCursor.class);
			Instant.parse(decoded.updatedAt());
			UUID.fromString(decoded.id());
			return decoded;
		} catch (Exception error) {
			throw new IntelligenceException(400, "cursor 无效");
		}
	}

	private static String encodeCursor(CreationDraft draft) {
		try {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
					MAPPER.writeValueAsBytes(new DraftCursor(draft.updatedAt().toString(), draft.id().toString())));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	// ---- 请求 DTO（业务校验在 CreationDraftService） ----

	public record CreateDraftRequest(String title, String sourceType, String taskId, Integer taskVersion,
			String storeId, String platform, String contentForm, String topic, String contentMode, String questionText,
			String questionRef, String capability, Map<String, Object> workspace, List<String> resultAssetIds,
			List<String> runIds, String articleTitle, String outline, String content, String requestId) {

		public CreateDraftRequest(String title, String sourceType, String taskId, Integer taskVersion, String storeId,
				String platform, String contentForm, String topic, String contentMode, String questionText,
				String questionRef, String capability, Map<String, Object> workspace, List<String> resultAssetIds,
				List<String> runIds) {
			this(title, sourceType, taskId, taskVersion, storeId, platform, contentForm, topic, contentMode,
					questionText, questionRef, capability, workspace, resultAssetIds, runIds, null, null, null, null);
		}

		/** 旧客户端载荷（省略新字段）仍可反序列化。 */
		public CreateDraftRequest(String title, String sourceType, String taskId, Integer taskVersion, String storeId,
				String platform, String contentForm, String topic, String contentMode, String questionText,
				String questionRef) {
			this(title, sourceType, taskId, taskVersion, storeId, platform, contentForm, topic, contentMode,
					questionText, questionRef, null, null, null, null);
		}
	}

	public record SaveDraftRequest(Integer expectedVersion, String title, String topic, String articleTitle,
			String outline, String content, String platform, String contentForm, String contentMode,
			String questionText, String questionRef, String status, String capability, Map<String, Object> workspace,
			List<String> resultAssetIds, List<String> runIds) {

		/** 旧客户端载荷（省略新字段）仍可反序列化。 */
		public SaveDraftRequest(Integer expectedVersion, String title, String topic, String articleTitle,
				String outline, String content, String platform, String contentForm, String contentMode,
				String questionText, String questionRef, String status) {
			this(expectedVersion, title, topic, articleTitle, outline, content, platform, contentForm, contentMode,
					questionText, questionRef, status, null, null, null, null);
		}
	}

	/** 导出请求：version 缺省导出当前版本（T32：指定版本不混用）。 */
	public record ExportRequest(Integer version, String format) {
	}
}
