package com.grassland.intelligence.creationassistant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.videoproduction.VideoStoryboard;
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
 * 草稿写编排（任务书 #100 C100-04）：从 {@link CreationDraftController} 原样抽出的 创建/保存/归档/删除与
 * owner 装载逻辑——行为零变化，供既有控制器与画布工作区绑定 （creationcanvas / API-07）复用；wire
 * 层（解析/响应包装）留在控制器。
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
	private final CreationContextSnapshotRepository snapshots;
	private final com.grassland.intelligence.creationstudio.CreationStudioReferenceValidator studioReferences;

	public CreationDraftService(CreationDraftRepository drafts, TransactionalOperator transactions,
			CreationResultReferences resultReferences, CreationContextSnapshotRepository snapshots,
			com.grassland.intelligence.creationstudio.CreationStudioReferenceValidator studioReferences) {
		this.drafts = drafts;
		this.transactions = transactions;
		this.resultReferences = resultReferences;
		this.snapshots = snapshots;
		this.studioReferences = studioReferences;
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
		return resultReferences.validateNew(workspace.value(), Map.of(), caller)
				.then(studioReferences.validateWorkspace(workspace.value(), caller, id))
				.then(drafts.create(draft))
				.filter(saved -> saved.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "创建请求对应的草稿已删除"))).map(CreationDraftView::of);
	}

	public Mono<CreationDraftView> save(String id, Caller caller, Map<String, Object> raw) {
		if (raw == null || raw.get("expectedVersion") == null) {
			return Mono.error(new IntelligenceException(400, "expectedVersion 不能为空"));
		}
		return lockOwned(id, caller.accountId()).flatMap(current -> {
			if (current.status() == DraftStatus.ARCHIVED) {
				return Mono.error(new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "归档草稿只读"));
			}
			CreationWorkspace.requireWritable(current.workspace());
			Map<String, Object> merged = new LinkedHashMap<>(CreationDraftView.of(current).toMap());
			merged.keySet()
					.retainAll(java.util.Arrays
							.stream(CreationDraftController.SaveDraftRequest.class.getRecordComponents())
							.map(java.lang.reflect.RecordComponent::getName).toList());
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
					.parse(deliveryWorkspace(body.workspace(), current.workspace()), body.capability());
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
			return resultReferences.validateNew(workspace.value(), current.workspace(), caller, current.id())
					.then(studioReferences.validateWorkspace(workspace.value(), caller, current.id()))
					.then(drafts.appendVersion(current, caller.accountId()))
					.then(drafts.save(current.id(), body.expectedVersion(), title, body.topic(), body.articleTitle(),
							body.outline(), body.content(), body.platform(), body.contentForm(), contentMode,
							body.questionText(), body.questionRef(), status, workspace.toJson(), resultAssetIds,
							runIds))
					.switchIfEmpty(
							Mono.error(new IntelligenceException(409, "DRAFT_VERSION_CONFLICT", "草稿已被其他设备修改，请刷新后合并")));
		}).as(transactions::transactional).map(CreationDraftView::of);
	}

	/**
	 * A delivery-only patch preserves all other workflow data; full workspace
	 * writes retain their existing semantics.
	 */
	private static Map<String, Object> deliveryWorkspace(Map<String, Object> incoming, Map<String, Object> current) {
		if (incoming == null)
			return current;
		if (!incoming.containsKey("delivery")
				|| !java.util.Set.of("schemaVersion", "delivery", "resultRefs").containsAll(incoming.keySet()))
			return incoming;
		Map<String, Object> merged = new LinkedHashMap<>(current);
		merged.putAll(incoming);
		if (current.get("delivery") instanceof Map<?, ?> old && incoming.get("delivery") instanceof Map<?, ?> patch) {
			Map<String, Object> delivery = new LinkedHashMap<>();
			old.forEach((key, value) -> delivery.put(key.toString(), value));
			patch.forEach((key, value) -> delivery.put(key.toString(), value));
			merged.put("delivery", delivery);
		}
		return merged;
	}

	/** 归档（幂等）：置 archived + version+1；不删素材/运行/任务数据。 */
	public Mono<CreationDraftView> archive(String id, Caller caller) {
		return lockOwned(id, caller.accountId())
				.flatMap(draft -> draft.status() == DraftStatus.ARCHIVED
						? Mono.just(draft)
						: drafts.appendVersion(draft, caller.accountId()).then(drafts.archive(draft.id())))
				.as(transactions::transactional).map(CreationDraftView::of);
	}

	/**
	 * 任务书 #101 C101-04：studio 服务端应用动作的同事务完整写路径（复用既有 appendVersion + save，
	 * 不新建旁路 save）。锁草稿 → 校验 expectedVersion → 落历史版本 → 按字段写新版本 → 返回视图；
	 * mutator 只允许改指定字段（正文/标题/摘要引用等），其余字段原样保留。
	 */
	public Mono<CreationDraft> applyStudioMutation(String id, String accountId, int expectedVersion,
			java.util.function.UnaryOperator<CreationDraft> mutator) {
		return lockOwned(id, accountId).flatMap(current -> {
			if (current.version() != expectedVersion) {
				return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿已被修改，请刷新后重试"));
			}
			CreationDraft mutated = mutator.apply(current);
			return drafts.appendVersion(current, accountId)
					.then(drafts.save(current.id(), expectedVersion, mutated.title(), mutated.topic(),
							mutated.articleTitle(), mutated.outline(), mutated.content(), mutated.platform(),
							mutated.contentForm(), mutated.contentMode(), mutated.questionText(),
							mutated.questionRef(), mutated.status(), writeWorkspaceJson(mutated.workspace()),
							mutated.resultAssetIds(), mutated.runIds()))
					.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT",
							"草稿已被修改，请刷新后重试")));
		}).as(transactions::transactional);
	}

	public Mono<Map<String, Object>> delete(String id, Caller caller) {
		return lockOwned(id, caller.accountId()).flatMap(draft -> drafts.softDelete(draft.id())
				.filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new IntelligenceException(404, "草稿不存在")))
				.thenReturn(Map.<String, Object>of("deleted", true))).as(transactions::transactional);
	}

	/**
	 * 供画布绑定（API-07）复用的最小草稿创建：从可信分镜行推导（账号/组织/快照引用），不从浏览器 补身份，不把 request_payload 里的
	 * base64 图复制进 workspace——inputs 只留 storyboard 引用， 后续布局由客户端经 inputs.videoCanvas
	 * 写入。
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
		Mono<java.util.Optional<CreationContextSnapshot>> context = contextSnapshotId == null
				? Mono.just(java.util.Optional.empty())
				: ownedSnapshot(contextSnapshotId, caller.accountId()).map(java.util.Optional::of);
		return context.flatMap(value -> {
			CreationContextSnapshot snapshot = value.orElse(null);
			if (snapshot != null && !Objects.equals(organizationId, snapshot.organizationId()))
				return Mono.error(sourceConflict());
			if (snapshot != null)
				inputs.put("video",
						Map.of("storyboardId", storyboardId.toString(), "contextSnapshotId", snapshot.id().toString()));
			String storeId = snapshot != null && snapshot.taskSnapshot().get("storeId") instanceof String store
					? store
					: null;
			CreationDraft draft = new CreationDraft(id, caller.accountId(), organizationId, title,
					snapshot == null ? DraftSourceType.INDEPENDENT : DraftSourceType.TASK,
					snapshot == null ? null : snapshot.taskId(), snapshot == null ? null : snapshot.taskVersion(),
					storeId, snapshot == null ? platform : snapshot.platformId(),
					snapshot == null ? null : snapshot.contentFormId(), null, null, null, null, null, null, null,
					DraftStatus.DRAFT, 1, null, null, null, workspace, List.of(), List.of());
			return drafts.create(draft);
		}).filter(saved -> saved.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "画布草稿创建冲突，请重试"))).map(CreationDraftView::of);
	}

	/**
	 * The persisted storyboard snapshot is the authority; request/workspace labels
	 * never grant task access.
	 */
	public Mono<Void> validateVideoSource(CreationDraft draft, VideoStoryboard storyboard) {
		if (storyboard.contextSnapshotId() == null) {
			return draft.sourceType() == DraftSourceType.TASK ? Mono.error(sourceConflict()) : Mono.empty();
		}
		return ownedSnapshot(storyboard.contextSnapshotId(), draft.ownerAccountId()).flatMap(snapshot -> {
			if (draft.sourceType() != DraftSourceType.TASK || !Objects.equals(draft.taskId(), snapshot.taskId())
					|| !Objects.equals(draft.taskVersion(), snapshot.taskVersion())
					|| !Objects.equals(draft.organizationId(), snapshot.organizationId())
					|| !Objects.equals(storyboard.organizationId(), snapshot.organizationId())
					|| !Objects.equals(draft.platform(), snapshot.platformId())
					|| !Objects.equals(draft.contentForm(), snapshot.contentFormId()))
				return Mono.error(sourceConflict());
			return Mono.empty();
		});
	}

	private Mono<CreationContextSnapshot> ownedSnapshot(UUID id, String accountId) {
		return snapshots.findById(id).filter(snapshot -> accountId.equals(snapshot.accountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND", "创作来源不可用")));
	}

	private static IntelligenceException sourceConflict() {
		return new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "创作来源无法确认，请从原方案重新派生");
	}

	/** 加载草稿并校验 owner（跨账号/不存在统一 404，防存在性探测）。 */
	public Mono<CreationDraft> loadOwned(String id, String accountId) {
		UUID draftId = parseUuid(id, "id");
		return drafts.findById(draftId).filter(draft -> accountId.equals(draft.ownerAccountId()))
				.filter(draft -> draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "DRAFT_NOT_FOUND", "草稿不存在")));
	}

	private Mono<CreationDraft> lockOwned(String id, String accountId) {
		return drafts.lockById(parseUuid(id, "id"))
				.filter(draft -> accountId.equals(draft.ownerAccountId()) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "DRAFT_NOT_FOUND", "草稿不存在")));
	}

	/** 返回第一个超 varchar(32) 的字段名，全合规返回 null。 */
	private static String firstOverlongQuestion(String questionText, String questionRef) {		if (questionText != null && questionText.length() > MAX_QUESTION_LENGTH) {
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

	private static String writeWorkspaceJson(Map<String, Object> workspace) {
		try {
			return MAPPER.writeValueAsString(workspace == null ? Map.of() : workspace);
		} catch (Exception error) {
			throw new IllegalStateException("工作区序列化失败", error);
		}
	}
}
