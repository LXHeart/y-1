package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationassistant.CreationDraftView;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videoproduction.VideoStoryboard;
import com.grassland.intelligence.videoproduction.VideoStoryboardRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 画布工作区绑定（任务书 #100 C100-04 / API-07）：旧 storyboard-only 深链按分镜唯一补草稿关联，
 * draft+storyboard 标准入口校验版本与归属。
 *
 * <p>
 * 决策表（§6.3）：
 * <ul>
 * <li>同 (account, operationId) 重放 → 返回既有关联（分镜/草稿与本次请求不符才 409）；
 * <li>分镜已有关联 → 校验请求草稿一致后返回，不迁移关联；
 * <li>带 draftId → 必须带 expectedDraftVersion（CAS），owner 校验 404、能力非 video 400；
 * <li>不带 draftId → 存量引用精确单一匹配则采纳；多个候选 409 要求从最近项目进入；
 * 无匹配则从可信分镜行创建最小视频草稿（不复制 base64、不构造浏览器身份）；
 * <li>关联草稿已删除 → 409（不静默换绑）。
 */
@Service
public class VideoCanvasWorkspaceService {

	/** 服务本地 mapper（模块惯例：不注入容器 ObjectMapper bean）。 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final VideoStoryboardRepository storyboards;
	private final VideoCanvasWorkspaceRepository bindings;
	private final CreationDraftRepository drafts;
	private final CreationDraftService draftService;
	private final TransactionalOperator transactions;

	public VideoCanvasWorkspaceService(VideoStoryboardRepository storyboards,
			VideoCanvasWorkspaceRepository bindings, CreationDraftRepository drafts,
			CreationDraftService draftService, TransactionalOperator transactions) {
		this.storyboards = storyboards;
		this.bindings = bindings;
		this.drafts = drafts;
		this.draftService = draftService;
		this.transactions = transactions;
	}

	public record BindingRequest(String operationId, String draftId, Integer expectedDraftVersion) {
	}

	public Mono<Map<String, Object>> bind(Caller caller, UUID storyboardId, BindingRequest request) {
		if (request == null || request.operationId() == null || request.operationId().isBlank()) {
			return Mono.error(new IntelligenceException(400, "operationId 必填"));
		}
		UUID operationId = parseUuid(request.operationId(), "operationId");
		if (request.draftId() != null && request.draftId().isBlank()) {
			return Mono.error(new IntelligenceException(400, "draftId 不能为空串"));
		}
		UUID requestedDraftId = request.draftId() == null ? null : parseUuid(request.draftId(), "draftId");
		if (requestedDraftId != null && request.expectedDraftVersion() == null) {
			return Mono.error(new IntelligenceException(400, "携带 draftId 时必须同时给 expectedDraftVersion"));
		}
		if (request.expectedDraftVersion() != null && request.expectedDraftVersion() < 1) {
			return Mono.error(new IntelligenceException(400, "expectedDraftVersion 必须是正版本号"));
		}
		String requestHash = digest(caller.accountId() + "|" + storyboardId + "|" + operationId + "|"
				+ (requestedDraftId == null ? "" : requestedDraftId) + "|"
				+ (request.expectedDraftVersion() == null ? "" : request.expectedDraftVersion()));
		return storyboards.findById(storyboardId, caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
				.flatMap(storyboard -> bindings.findByAccountAndOperation(caller.accountId(), operationId)
						.flatMap(existing -> confirmReplay(existing, storyboard, requestedDraftId))
						.switchIfEmpty(Mono.defer(() -> bindFresh(caller, storyboard, operationId, requestedDraftId,
								request.expectedDraftVersion(), requestHash))))
				.flatMap(binding -> buildResult(caller, binding, storyboardId));
	}

	/** 重放与分镜既有关联共用：请求草稿与关联不符 → 409（不迁移关联）。 */
	private Mono<VideoCanvasWorkspaceRepository.WorkspaceBinding> confirmReplay(
			VideoCanvasWorkspaceRepository.WorkspaceBinding existing, VideoStoryboard storyboard,
			UUID requestedDraftId) {
		boolean sameStoryboard = existing.storyboardId().equals(storyboard.id());
		boolean draftMatches = requestedDraftId == null || requestedDraftId.equals(existing.draftId());
		if (!sameStoryboard) {
			return Mono.error(new IntelligenceException(409, "WORKSPACE_OPERATION_REUSED",
					"该 operationId 已绑定到其他分镜，请刷新后重试"));
		}
		if (!draftMatches) {
			return Mono.error(new IntelligenceException(409, "WORKSPACE_BINDING_CONFLICT",
					"该分镜已关联其他草稿，请从最近项目选择草稿进入"));
		}
		return Mono.just(existing);
	}

	private Mono<VideoCanvasWorkspaceRepository.WorkspaceBinding> bindFresh(Caller caller, VideoStoryboard storyboard,
			UUID operationId, UUID requestedDraftId, Integer expectedDraftVersion, String requestHash) {
		return bindings.findByStoryboard(storyboard.id())
				.flatMap(existing -> confirmReplay(existing, storyboard, requestedDraftId))
				.switchIfEmpty(Mono.defer(() -> resolveDraft(caller, storyboard, operationId, requestedDraftId,
						expectedDraftVersion)
								.flatMap(draftId -> insertBinding(storyboard, draftId, caller.accountId(), operationId,
										requestHash))))
				.as(transactions::transactional);
	}

	private Mono<UUID> resolveDraft(Caller caller, VideoStoryboard storyboard, UUID operationId,
			UUID requestedDraftId, Integer expectedDraftVersion) {
		if (requestedDraftId != null) {
			return draftService.loadOwned(requestedDraftId.toString(), caller.accountId())
					.flatMap(draft -> {
						if (!"video".equals(draft.workspace().get("capability"))) {
							return Mono.error(new IntelligenceException(400, "仅视频能力草稿可关联画布"));
						}
						if (draft.version() != expectedDraftVersion) {
							return Mono.error(new IntelligenceException(409, "DRAFT_VERSION_CONFLICT",
									"草稿已被其他设备修改，请刷新后重试"));
						}
						return Mono.just(draft.id());
					});
		}
		return bindings.findCandidateDraftIds(caller.accountId(), storyboard.id()).collectList().flatMap(ids -> {
			if (ids.size() > 1) {
				return Mono.error(new IntelligenceException(409, "WORKSPACE_BINDING_AMBIGUOUS",
						"该分镜匹配到多个草稿，请从最近项目选择草稿进入画布"));
			}
			if (ids.size() == 1) {
				return draftService.loadOwned(ids.getFirst(), caller.accountId()).map(CreationDraft::id)
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "DRAFT_NOT_FOUND", "草稿不存在")));
			}
			return createMinimalDraft(caller, storyboard, operationId);
		});
	}

    /** 平台兜底（仅当分镜行自身带 platform 时透传，可信来源；request_payload 是 JSON 文本）。
     * 无 platform 是合法常态——Optional 包装防 fromSupplier 对 null 收敛成空 Mono
     * （C100-08 e2e 实测：无 platform 的旧深链绑定会静默 200 空体、零写入）。 */
    private Mono<UUID> createMinimalDraft(Caller caller, VideoStoryboard storyboard, UUID operationId) {
        return Mono.fromSupplier(() -> java.util.Optional.ofNullable(parsePlatform(storyboard.requestPayload())))
                .flatMap(platform -> draftService.createMinimalVideoDraft(caller, storyboard.id(),
                        storyboard.organizationId(), storyboard.contextSnapshotId(), platform.orElse(null), operationId))
                .map(view -> view.draft().id());
    }

	private static String parsePlatform(String requestPayloadJson) {
		if (requestPayloadJson == null || requestPayloadJson.isBlank()) {
			return null;
		}
		try {
			com.fasterxml.jackson.databind.JsonNode platform = MAPPER.readTree(requestPayloadJson).path("platform");
			return platform.isTextual() && !platform.asText().isBlank() ? platform.asText() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, Object> result(CreationDraftView view, VideoStoryboard storyboard) {
		String taskRef = null;
		if (view.draft().workspace() instanceof Map<?, ?> workspace
				&& workspace.get("inputs") instanceof Map<?, ?> inputs
				&& inputs.get("video") instanceof Map<?, ?> video && video.get("productionTaskId") instanceof String id
				&& !id.isBlank()) {
			taskRef = id;
		}
		// LinkedHashMap 允许 productionTaskId=null（Map.of 不接受 null 值）
		Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("project", view.toMap());
		data.put("storyboardId", storyboard.id().toString());
		data.put("productionTaskId", taskRef);
		data.put("editVersion", storyboard.editVersion());
		return data;
	}

	private Mono<VideoCanvasWorkspaceRepository.WorkspaceBinding> insertBinding(VideoStoryboard storyboard,
			UUID draftId, String accountId, UUID operationId, String requestHash) {
		VideoCanvasWorkspaceRepository.WorkspaceBinding binding = new VideoCanvasWorkspaceRepository.WorkspaceBinding(
				storyboard.id(), draftId, accountId, operationId, requestHash, null);
		return bindings.insert(binding).flatMap(inserted -> inserted > 0 ? Mono.just(binding)
				: bindings.findByStoryboard(storyboard.id())
						.flatMap(existing -> existing.draftId().equals(draftId) ? Mono.just(existing)
								: Mono.error(new IntelligenceException(409, "WORKSPACE_BINDING_CONFLICT",
										"该分镜已被其他会话关联，请刷新后重试")))
						.switchIfEmpty(Mono.error(new IntelligenceException(409, "画布关联写入失败，请重试"))));
	}

	/** 已有关联先检查资源仍可用（草稿被删/易主 → 409，不静默换绑），再组装响应。 */
	private Mono<Map<String, Object>> buildResult(Caller caller,
			VideoCanvasWorkspaceRepository.WorkspaceBinding binding, UUID storyboardId) {
		return draftService.loadOwned(binding.draftId().toString(), caller.accountId())
				.onErrorResume(com.grassland.intelligence.security.IntelligenceException.class, error ->
						error.status() == 404
								? Mono.error(new IntelligenceException(409, "WORKSPACE_BINDING_DRAFT_GONE",
										"关联草稿已删除，请从最近项目重新进入"))
								: Mono.error(error))
				.flatMap(draft -> storyboards.findById(storyboardId, caller.accountId())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
						.map(storyboard -> result(CreationDraftView.of(draft), storyboard)));
	}

	private static UUID parseUuid(String value, String field) {
		try {
			return UUID.fromString(value.trim());
		} catch (Exception e) {
			throw new IntelligenceException(400, field + " 格式无效");
		}
	}

	private static String digest(String canonical) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
