package com.grassland.intelligence.creationassistant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 草稿写编排（任务书 #100 C100-04）：从 {@link CreationDraftController} 原样抽出的
 * 创建/保存/归档/删除与 owner 装载逻辑——行为零变化，供既有控制器与画布工作区绑定
 * （creationcanvas / API-07）复用；wire 层（解析/响应包装）留在控制器。
 */
@Service
public class CreationDraftService {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private static final int MAX_TITLE_LENGTH = 120;
	/** 目标问题原文上限（知乎问题标题远短于此，留足补充空间）。 */
	private static final int MAX_QUESTION_LENGTH = 500;
	/** questionId 引用上限（纯数字 id，宽松上限即可）。 */
	private static final int MAX_QUESTION_REF_LENGTH = 64;
	/**
	 * platform / content_form 在 V19 是 varchar(32)；不在此拦就会漏成 Postgres 22001 → 500。
	 */
	private static final int MAX_ENUM_LENGTH = 32;

	private final CreationDraftRepository drafts;
	private final TransactionalOperator transactions;
	private final CreationResultReferences resultReferences;

	public CreationDraftService(CreationDraftRepository drafts, TransactionalOperator transactions,
			CreationResultReferences resultReferences) {
		this.drafts = drafts;
		this.transactions = transactions;
		this.resultReferences = resultReferences;
	}

	public Mono<CreationDraftView> create(Caller caller, CreationDraftController.CreateDraftRequest body) {
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
		CreationWorkspace workspace = CreationWorkspace.parse(body.workspace(), body.capability());
		List<String> resultAssetIds = CreationWorkspace.normalizeIdList(body.resultAssetIds(), "resultAssetIds");
		List<String> runIds = CreationWorkspace.normalizeIdList(body.runIds(), "runIds");
		UUID id = body.requestId() == null
				? UUID.randomUUID()
				: UUID.nameUUIDFromBytes(
						(caller.accountId() + ":creation-draft:" + parseUuid(body.requestId(), "requestId"))
								.getBytes(StandardCharsets.UTF_8));
		CreationDraft draft = new CreationDraft(id, caller.accountId(), null, title, sourceType, body.taskId(),
				body.taskVersion(), body.storeId(), body.platform(), body.contentForm(), body.topic(),
				body.articleTitle(), body.outline(), body.content(), contentMode, body.questionText(),
				body.questionRef(), DraftStatus.DRAFT, 1, null, null, null, workspace.value(), resultAssetIds, runIds);
		return resultReferences.validateNew(workspace.value(), Map.of(), caller).then(drafts.create(draft))
				.filter(saved -> saved.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "创建请求对应的草稿已删除")))
				.map(CreationDraftView::of);
	}

	public Mono<CreationDraftView> save(String id, Caller caller, Map<String, Object> raw) {
		if (raw == null || raw.get("expectedVersion") == null) {
			return Mono.error(new IntelligenceException(400, "expectedVersion 不能为空"));
		}
		return loadOwned(id, caller.accountId()).flatMap(current -> {
			CreationWorkspace.requireWritable(current.workspace());
			Map<String, Object> merged = new LinkedHashMap<>(CreationDraftView.of(current).toMap());
			merged.keySet().retainAll(java.util.Arrays.stream(CreationDraftController.SaveDraftRequest.class
					.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
			merged.putAll(raw);
			CreationDraftController.SaveDraftRequest body;
			try {
				body = MAPPER.convertValue(merged, CreationDraftController.SaveDraftRequest.class);
			} catch (IllegalArgumentException error) {
				return Mono.error(new IntelligenceException(400, "草稿字段类型无效"));
			}
			if (body.expectedVersion() != current.version()) {
				return Mono.error(new IntelligenceException(409, "DRAFT_VERSION_CONFLICT", "草稿已被其他设备修改，请刷新后合并"));
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
			// 任务书 #92 C-02 兼容：旧客户端 PUT 不带工作区三字段 → 字段级 coalesce 保留当前值不覆写。
			CreationWorkspace workspace = CreationWorkspace
					.parse(body.workspace() != null ? body.workspace() : current.workspace(), body.capability());
			List<String> resultAssetIds = body.resultAssetIds() != null
					? CreationWorkspace.normalizeIdList(body.resultAssetIds(), "resultAssetIds")
					: current.resultAssetIds();
			List<String> runIds = body.runIds() != null
					? CreationWorkspace.normalizeIdList(body.runIds(), "runIds")
					: current.runIds();
			if (Objects.equals(title, current.title()) && Objects.equals(blankToNull(body.topic()), current.topic())
					&& Objects.equals(blankToNull(body.articleTitle()), current.articleTitle())
					&& Objects.equals(blankToNull(body.outline()), current.outline())
					&& Objects.equals(blankToNull(body.content()), current.content())
					&& Objects.equals(blankToNull(body.platform()), current.platform())
					&& Objects.equals(blankToNull(body.contentForm()), current.contentForm())
					&& contentMode == current.contentMode() && status == current.status()
					&& Objects.equals(blankToNull(body.questionText()), current.questionText())
					&& Objects.equals(blankToNull(body.questionRef()), current.questionRef())
					&& workspace.value().equals(current.workspace()) && resultAssetIds.equals(current.resultAssetIds())
					&& runIds.equals(current.runIds()))
				return Mono.just(current);
			return resultReferences.validateNew(workspace.value(), current.workspace(), caller)
					.then(drafts.appendVersion(current, caller.accountId()))
					.then(drafts.save(current.id(), body.expectedVersion(), title, body.topic(), body.articleTitle(),
							body.outline(), body.content(), body.platform(), body.contentForm(), contentMode,
							body.questionText(), body.questionRef(), status, workspace.toJson(), resultAssetIds,
							runIds))
					.switchIfEmpty(
							Mono.error(new IntelligenceException(409, "DRAFT_VERSION_CONFLICT", "草稿已被其他设备修改，请刷新后合并")));
		}).as(transactions::transactional).map(CreationDraftView::of);
	}

	/** 归档（幂等）：置 archived + version+1；不删素材/运行/任务数据。 */
	public Mono<CreationDraftView> archive(String id, Caller caller) {
		return loadOwned(id, caller.accountId()).flatMap(draft -> draft.status() == DraftStatus.ARCHIVED
				? Mono.just(draft)
				: drafts.appendVersion(draft, caller.accountId()).then(drafts.archive(draft.id())))
				.as(transactions::transactional).map(CreationDraftView::of);
	}

	public Mono<Map<String, Object>> delete(String id, Caller caller) {
		return loadOwned(id, caller.accountId()).flatMap(draft -> drafts.softDelete(draft.id())
				.filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new IntelligenceException(404, "草稿不存在")))
				.thenReturn(Map.<String, Object>of("deleted", true))).as(transactions::transactional);
	}

	/**
	 * 供画布绑定（API-07）复用的最小草稿创建：从可信分镜行推导（账号/组织/快照引用），不从浏览器
	 * 补身份，不把 request_payload 里的 base64 图复制进 workspace——inputs 只留 storyboard 引用，
	 * 后续布局由客户端经 inputs.videoCanvas 写入。
	 */
	public Mono<CreationDraftView> createMinimalVideoDraft(Caller caller, UUID storyboardId, String organizationId,
			UUID contextSnapshotId, String platform, UUID deterministicFromOperation) {
		String title = "视频画布 " + storyboardId.toString().substring(0, 8);
		Map<String, Object> inputs = new LinkedHashMap<>();
		inputs.put("video", Map.of("storyboardId", storyboardId.toString()));
		Map<String, Object> workspace = new LinkedHashMap<>();
		workspace.put("schemaVersion", 1);
		workspace.put("capability", "video");
		workspace.put("currentStep", "storyboard");
		workspace.put("inputs", inputs);
		UUID id = UUID.nameUUIDFromBytes((caller.accountId() + ":canvas-workspace:" + deterministicFromOperation)
				.getBytes(StandardCharsets.UTF_8));
		DraftSourceType sourceType = contextSnapshotId != null ? DraftSourceType.TASK : DraftSourceType.INDEPENDENT;
		CreationDraft draft = new CreationDraft(id, caller.accountId(), organizationId, title, sourceType, null, null,
				null, platform, null, null, null, null, null, null, null, null, DraftStatus.DRAFT, 1, null, null,
				null, workspace, List.of(), List.of());
		return drafts.create(draft).filter(saved -> saved.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "画布草稿创建冲突，请重试")))
				.map(CreationDraftView::of);
	}

	/** 加载草稿并校验 owner（跨账号/不存在统一 404，防存在性探测）。 */
	public Mono<CreationDraft> loadOwned(String id, String accountId) {
		UUID draftId = parseUuid(id, "id");
		return drafts.findById(draftId).filter(draft -> accountId.equals(draft.ownerAccountId()))
				.filter(draft -> draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "DRAFT_NOT_FOUND", "草稿不存在")));
	}

	/** 返回第一个超 varchar(32) 的字段名，全合规返回 null。 */
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

	static UUID parseUuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (Exception e) {
			throw new IntelligenceException(400, field + " 格式无效");
		}
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
