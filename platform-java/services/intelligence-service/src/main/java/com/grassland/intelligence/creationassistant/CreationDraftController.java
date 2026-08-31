package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.reactive.TransactionalOperator;
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
 */
@RestController
@RequestMapping("/api/creation-drafts")
public class CreationDraftController {

	private static final int MAX_TITLE_LENGTH = 120;
	/** 目标问题原文上限（知乎问题标题远短于此，留足补充空间）。 */
	private static final int MAX_QUESTION_LENGTH = 500;
	/** questionId 引用上限（纯数字 id，宽松上限即可）。 */
	private static final int MAX_QUESTION_REF_LENGTH = 64;
	/**
	 * platform / content_form 在 V19 是 varchar(32)；不在此拦就会漏成 Postgres 22001 → 500。
	 */
	private static final int MAX_ENUM_LENGTH = 32;
	private static final int DEFAULT_VERSION_LIMIT = 20;
	private static final int MAX_VERSION_LIMIT = 100;

	private final IntelligenceCallerResolver callers;
	private final CreationDraftRepository drafts;
	private final TransactionalOperator transactions;

	public CreationDraftController(IntelligenceCallerResolver callers, CreationDraftRepository drafts,
			TransactionalOperator transactions) {
		this.callers = callers;
		this.drafts = drafts;
		this.transactions = transactions;
	}

	/** 创建草稿。 */
	@PostMapping
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateDraftRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> createDraft(caller, body))
				.map(CreationDraftController::success);
	}

	/** 列出自己的草稿（按更新时间倒序）。 */
	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> drafts.listByAccount(caller.accountId()).collectList())
				.map(list -> success(Map.of("items", list.stream().map(CreationDraftController::toResponse).toList())));
	}

	/** 草稿详情（owner 校验，跨账号 404）。 */
	@GetMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> loadOwned(id, caller.accountId()))
				.map(draft -> success(toResponse(draft)));
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
		return callers.resolve(exchange.getRequest()).flatMap(caller -> loadOwned(id, caller.accountId()))
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
		return callers.resolve(exchange.getRequest()).flatMap(caller -> loadOwned(id, caller.accountId()))
				.flatMap(draft -> drafts.findVersion(draft.id(), version))
				.switchIfEmpty(Mono.defer(() -> Mono.error(new IntelligenceException(404, "草稿版本不存在"))))
				.map(CreationDraftController::toVersionResponse).map(CreationDraftController::success);
	}

	/**
	 * 自动保存（乐观锁）。前端 debounce 触发；先落旧版快照（appendVersion）再 save（version+1），同事务。 版本冲突 →
	 * 409（前端 reload 后合并）。
	 */
	@PutMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> save(@PathVariable String id, @RequestBody SaveDraftRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> saveDraft(id, caller, body))
				.map(CreationDraftController::success);
	}

	/** 软删草稿（owner 校验）。 */
	@DeleteMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> deleteDraft(id, caller))
				.map(CreationDraftController::success);
	}

	// ---- 业务编排 ----

	private Mono<Map<String, Object>> createDraft(Caller caller, CreateDraftRequest body) {
		if (body == null) {
			return Mono.error(new IntelligenceException(400, "请求体不能为空"));
		}
		DraftSourceType sourceType = DraftSourceType.fromRequest(body.sourceType());
		if (sourceType == null) {
			return Mono.error(new IntelligenceException(400, "sourceType 无效"));
		}
		String title = body.title() == null || body.title().isBlank() ? "未命名草稿" : body.title().trim();
		if (title.length() > MAX_TITLE_LENGTH) {
			return Mono.error(new IntelligenceException(400, "标题过长"));
		}
		String tooLong = firstOverlong(body.platform(), body.contentForm());
		if (tooLong != null) {
			return Mono.error(new IntelligenceException(400, tooLong + " 过长"));
		}
		DraftContentMode contentMode = DraftContentMode.orDefault(body.contentMode());
		if (contentMode == null) {
			return Mono.error(new IntelligenceException(400, "contentMode 无效"));
		}
		String questionOverlong = firstOverlongQuestion(body.questionText(), body.questionRef());
		if (questionOverlong != null) {
			return Mono.error(new IntelligenceException(400, questionOverlong + " 过长"));
		}
		CreationDraft draft = new CreationDraft(UUID.randomUUID(), caller.accountId(), null, title, sourceType,
				body.taskId(), body.taskVersion(), body.storeId(), body.platform(), body.contentForm(), body.topic(),
				null, null, null, contentMode, body.questionText(), body.questionRef(), DraftStatus.DRAFT, 1, null,
				null, null);
		return drafts.create(draft).map(CreationDraftController::toResponse);
	}

	private Mono<Map<String, Object>> saveDraft(String id, Caller caller, SaveDraftRequest body) {
		if (body == null || body.expectedVersion() == null) {
			return Mono.error(new IntelligenceException(400, "expectedVersion 不能为空"));
		}
		String title = body.title() == null || body.title().isBlank() ? "未命名草稿" : body.title().trim();
		if (title.length() > MAX_TITLE_LENGTH) {
			return Mono.error(new IntelligenceException(400, "标题过长"));
		}
		String tooLong = firstOverlong(body.platform(), body.contentForm());
		if (tooLong != null) {
			return Mono.error(new IntelligenceException(400, tooLong + " 过长"));
		}
		DraftStatus status = body.status() == null ? DraftStatus.DRAFT : DraftStatus.fromDb(body.status());
		if (status == null) {
			return Mono.error(new IntelligenceException(400, "status 无效"));
		}
		DraftContentMode contentMode = DraftContentMode.orDefault(body.contentMode());
		if (contentMode == null) {
			return Mono.error(new IntelligenceException(400, "contentMode 无效"));
		}
		String questionOverlong = firstOverlongQuestion(body.questionText(), body.questionRef());
		if (questionOverlong != null) {
			return Mono.error(new IntelligenceException(400, questionOverlong + " 过长"));
		}
		// 先落旧版快照（appendVersion）再 save（version+1），同事务；乐观锁失败 → 409。
		return loadOwned(id, caller.accountId()).flatMap(current -> drafts.appendVersion(current, caller.accountId())
				.then(drafts.save(current.id(), body.expectedVersion(), title, body.topic(), body.articleTitle(),
						body.outline(), body.content(), body.platform(), body.contentForm(), contentMode,
						body.questionText(), body.questionRef(), status))
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "草稿已被其他设备修改，请刷新后合并")))
				.as(transactions::transactional)).map(CreationDraftController::toResponse);
	}

	private Mono<Map<String, Object>> deleteDraft(String id, Caller caller) {
		return loadOwned(id, caller.accountId()).flatMap(draft -> drafts.softDelete(draft.id())
				.filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new IntelligenceException(404, "草稿不存在")))
				.thenReturn(Map.<String, Object>of("deleted", true))).as(transactions::transactional);
	}

	/** 加载草稿并校验 owner（跨账号/不存在统一 404，防存在性探测）。 */
	private Mono<CreationDraft> loadOwned(String id, String accountId) {
		UUID draftId = parseUuid(id, "id");
		return drafts.findById(draftId).filter(draft -> accountId.equals(draft.ownerAccountId()))
				.filter(draft -> draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "草稿不存在")));
	}

	// ---- 响应序列化 ----

	private static Map<String, Object> toResponse(CreationDraft d) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", d.id().toString());
		map.put("title", d.title());
		map.put("sourceType", d.sourceType().db());
		map.put("status", d.status().db());
		map.put("version", d.version());
		map.put("createdAt", d.createdAt());
		map.put("updatedAt", d.updatedAt());
		if (d.topic() != null)
			map.put("topic", d.topic());
		if (d.articleTitle() != null)
			map.put("articleTitle", d.articleTitle());
		if (d.outline() != null)
			map.put("outline", d.outline());
		if (d.content() != null)
			map.put("content", d.content());
		// 任务书 #62：contentMode 恒下发（前端恢复草稿要据此还原模式）；问题字段仅回答模式有值
		map.put("contentMode", (d.contentMode() == null ? DraftContentMode.ARTICLE : d.contentMode()).db());
		if (d.questionText() != null)
			map.put("questionText", d.questionText());
		if (d.questionRef() != null)
			map.put("questionRef", d.questionRef());
		if (d.platform() != null)
			map.put("platform", d.platform());
		if (d.contentForm() != null)
			map.put("contentForm", d.contentForm());
		if (d.taskId() != null)
			map.put("taskId", d.taskId());
		if (d.taskVersion() != null)
			map.put("taskVersion", d.taskVersion());
		if (d.storeId() != null)
			map.put("storeId", d.storeId());
		return map;
	}

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
		return map;
	}

	private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
		return ResponseEntity.ok(Map.of("success", true, "data", data));
	}

	/** 返回第一个超 varchar(32) 的字段名，全合规返回 null。 */
	/**
	 * 问题字段长度闸（任务书 #62）：question_text/question_ref 是 text 列无 DB 上限， 但前端 textarea
	 * 有边界——超长在此 400，不放进库里当垃圾数据。
	 */
	private static String firstOverlongQuestion(String questionText, String questionRef) {
		if (questionText != null && questionText.length() > MAX_QUESTION_LENGTH) {
			return "questionText";
		}
		if (questionRef != null && questionRef.length() > MAX_QUESTION_REF_LENGTH) {
			return "questionRef";
		}
		return null;
	}

	private static String firstOverlong(String platform, String contentForm) {
		if (platform != null && platform.length() > MAX_ENUM_LENGTH) {
			return "platform";
		}
		if (contentForm != null && contentForm.length() > MAX_ENUM_LENGTH) {
			return "contentForm";
		}
		return null;
	}

	private static UUID parseUuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (Exception e) {
			throw new IntelligenceException(400, field + " 格式无效");
		}
	}

	// ---- 请求 DTO ----

	public record CreateDraftRequest(String title, String sourceType, String taskId, Integer taskVersion,
			String storeId, String platform, String contentForm, String topic, String contentMode, String questionText,
			String questionRef) {
	}

	public record SaveDraftRequest(Integer expectedVersion, String title, String topic, String articleTitle,
			String outline, String content, String platform, String contentForm, String contentMode,
			String questionText, String questionRef, String status) {
	}
}
