package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.creationcanvas.CreationCanvasRepository;
import com.grassland.intelligence.creationcanvas.VideoCanvasWorkspaceRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 独立方案派生（任务书 #100 C100-14 / API-11/12 / §7.2）。
 *
 * <p>一致快照 + ID 重映射 + 原子派生：新 draft/storyboard/shot ID 全独立；复制表单、Brief、
 * 分组与来源（mediaId 共享引用），不复制运行/选择/成片/任务。画布 refs 按 shotIdMap 重写，
 * 旧 take/delivery 等悬空节点不带入。操作键幂等重放（同键同参返回原结果、异参 409）；
 * 每根至多 20 个派生（根 storyboard 行锁下计数）；中途失败事务全回滚（§7.2）。
 */
@Service
public class VideoStoryboardVariantService {

    private static final Logger log = LoggerFactory.getLogger(VideoStoryboardVariantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** §6.3 API-12：每根最多 20 个派生。 */
    static final int MAX_DERIVATIONS_PER_ROOT = 20;

    private final VideoStoryboardRepository storyboards;
    private final VideoShotRepository shots;
    private final VideoShotSourceRepository sources;
    private final VideoStoryboardVariantRepository variants;
    private final VideoCanvasWorkspaceRepository bindings;
    private final CreationCanvasRepository canvas;
    private final org.springframework.r2dbc.core.DatabaseClient db;
    private final TransactionalOperator transactions;

    public VideoStoryboardVariantService(VideoStoryboardRepository storyboards, VideoShotRepository shots,
            VideoShotSourceRepository sources, VideoStoryboardVariantRepository variants,
            VideoCanvasWorkspaceRepository bindings, CreationCanvasRepository canvas,
            org.springframework.r2dbc.core.DatabaseClient db, TransactionalOperator transactions) {
        this.storyboards = storyboards;
        this.shots = shots;
        this.sources = sources;
        this.variants = variants;
        this.bindings = bindings;
        this.canvas = canvas;
        this.db = db;
        this.transactions = transactions;
    }

    public record CreateVariantRequest(UUID operationId, Long expectedEditVersion, Long expectedDraftVersion,
            String title, List<UUID> shotIds) {
    }

    public record CreateVariantResult(Map<String, Object> variant, Map<String, Object> project,
            Map<String, String> shotIdMap) {
    }

    public Mono<CreateVariantResult> derive(String accountId, UUID sourceStoryboardId,
            CreateVariantRequest request) {
        if (request == null || request.operationId() == null || request.title() == null
                || request.title().isBlank() || request.shotIds() == null || request.shotIds().isEmpty()) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                    "operationId/title/shotIds 必填"));
        }
        if (request.title().codePoints().count() > 240) {
            return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "标题超过 240 字符"));
        }
        if (request.shotIds().size() > 30
                || request.shotIds().stream().distinct().count() != request.shotIds().size()) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                    "shotIds 须 1～30 个且不重复"));
        }
        String requestHash = hashOf(sourceStoryboardId, request);
        return storyboards.findById(sourceStoryboardId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "分镜不存在")))
                .flatMap(source -> variants.findByAccountAndOperation(accountId, request.operationId())
                        .flatMap(existing -> replayExisting(existing, sourceStoryboardId, requestHash))
                        .switchIfEmpty(Mono.defer(() -> deriveFresh(accountId, source, request, requestHash))));
    }

    /** 谱系根：方案自身无谱系行 → 根即自身；否则沿用其 root。 */
    public Mono<UUID> rootOf(UUID storyboardId) {
        return variants.findByStoryboard(storyboardId)
                .map(VideoStoryboardVariantRepository.Variant::rootStoryboardId)
                .defaultIfEmpty(storyboardId);
    }

    /** 方案列表（API-12）：根 + 全部派生（createdAt/id 升序）。 */
    public Mono<List<Map<String, Object>>> list(String accountId, UUID storyboardId) {
        return storyboards.findById(storyboardId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "分镜不存在")))
                .flatMap(source -> rootOf(storyboardId).flatMap(rootId ->
                        variants.findByRoot(rootId).collectList().flatMap(rows -> {
                            // 根行 + 谱系行（含请求自身的行——保留真实父系/来源版本）。
                            // 不能再补「requested 摘要」：请求自身是派生时会与谱系行同 id
                            // 重复（前端 v-for key 冲突 → DOM 属性错位，C100-19 e2e 实测）。
                            List<Map<String, Object>> items = new ArrayList<>();
                            items.add(summary(rootId, null, rootId, null, null));
                            for (VideoStoryboardVariantRepository.Variant row : rows) {
                                if (row.storyboardId().equals(rootId)) {
                                    continue;
                                }
                                items.add(summary(row.storyboardId(), row.parentStoryboardId(), rootId,
                                        row.sourceEditVersion(), row.title()));
                            }
                            return Mono.just(items);
                        })));
    }

    /** 同键重放：同源同参返回既有结果；异源/异参 409 OPERATION_CONFLICT。 */
    private Mono<CreateVariantResult> replayExisting(VideoStoryboardVariantRepository.Variant existing,
            UUID sourceStoryboardId, String requestHash) {
        if (!existing.parentStoryboardId().equals(sourceStoryboardId)
                || !existing.requestHash().equals(requestHash)) {
            return Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT",
                    "操作键已用于其他派生请求"));
        }
        return assembleResult(existing);
    }

    private Mono<CreateVariantResult> deriveFresh(String accountId, VideoStoryboard source,
            CreateVariantRequest request, String requestHash) {
        if (request.expectedEditVersion() == null || request.expectedEditVersion() != source.editVersion()) {
            return Mono.error(new IntelligenceException(409, "CANVAS_VERSION_CONFLICT",
                    "分镜版本已变化，请刷新后重试"));
        }
        return bindings.findByStoryboard(source.id())
                .switchIfEmpty(Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT",
                        "方案派生需要源分镜已绑定画布草稿")))
                .flatMap(binding -> db.sql("SELECT version FROM creation_draft WHERE id=CAST(:id AS uuid)")
                        .bind("id", binding.draftId().toString())
                        .map(row -> row.get(0, Integer.class)).one()
                        .flatMap(sourceDraftVersion -> {
                            if (request.expectedDraftVersion() == null
                                    || request.expectedDraftVersion().intValue() != sourceDraftVersion) {
                                return Mono.error(new IntelligenceException(409, "CANVAS_VERSION_CONFLICT",
                                        "草稿版本已变化，请刷新后重试"));
                            }
                            return rootOf(source.id()).flatMap(rootId ->
                                    deriveInLock(accountId, source, binding.draftId(), sourceDraftVersion,
                                            rootId, request, requestHash));
                        }));
    }

    private Mono<CreateVariantResult> deriveInLock(String accountId, VideoStoryboard source, UUID sourceDraftId,
            int sourceDraftVersion, UUID rootId, CreateVariantRequest request, String requestHash) {
        Mono<CreateVariantResult> work = storyboards.lockById(rootId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "根方案不存在")))
                .flatMap(lockedRoot -> variants.countByRoot(rootId).flatMap(count -> {
                    if (count >= MAX_DERIVATIONS_PER_ROOT) {
                        return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                                "每个方案至多 " + MAX_DERIVATIONS_PER_ROOT + " 个派生"));
                    }
                    return buildVariant(accountId, source, sourceDraftId, sourceDraftVersion, rootId, request,
                            requestHash);
                }));
        return transactions.transactional(work);
    }

    /** 原子派生主体（§7.2 事务全回滚）。 */
    private Mono<CreateVariantResult> buildVariant(String accountId, VideoStoryboard source, UUID sourceDraftId,
            int sourceDraftVersion, UUID rootId, CreateVariantRequest request, String requestHash) {
        return shots.findByStoryboard(source.id()).collectList()
                .flatMap(sourceShots -> {
                    Map<UUID, VideoShot> byId = new LinkedHashMap<>();
                    for (VideoShot shot : sourceShots) {
                        byId.put(shot.id(), shot);
                    }
                    List<VideoShot> selected = new ArrayList<>();
                    for (UUID shotId : request.shotIds()) {
                        VideoShot shot = byId.get(shotId);
                        if (shot == null) {
                            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                    "shotIds 含不属于该分镜的镜头"));
                        }
                        selected.add(shot);
                    }
                    Map<String, String> shotIdMap = new LinkedHashMap<>();
                    List<UUID> newShotIds = new ArrayList<>();
                    for (int index = 0; index < selected.size(); index++) {
                        UUID newId = UUID.randomUUID();
                        newShotIds.add(newId);
                        shotIdMap.put(selected.get(index).id().toString(), newId.toString());
                    }
                    String shotIdMapJson = toJson(Map.of(), shotIdMap);
                    UUID storyboardId = UUID.randomUUID();
                    UUID draftId = UUID.randomUUID();
                    int targetDuration = alignedTargetDuration(selected);

                    // 1) 同源派生草稿：复制表单/Brief，重映射 storyboard 引用（§6.3：不从浏览器构造身份）
                    Mono<Long> draftInsert = db.sql("SELECT workspace_json::text AS w FROM creation_draft "
                                    + "WHERE id=CAST(:source AS uuid)")
                            .bind("source", sourceDraftId.toString())
                            .map(row -> row.get("w", String.class)).one()
                            .flatMap(sourceWorkspace -> db.sql(
                                            "INSERT INTO creation_draft(id, owner_account_id, title, "
                                                    + "source_type, status, version, organization_id, platform, "
                                                    + "content_form, workspace_json) SELECT CAST(:id AS uuid), "
                                                    + ":account, :title, 'independent', 'draft', 1, "
                                                    + "organization_id, platform, content_form, "
                                                    + "CAST(:workspace AS jsonb) FROM creation_draft "
                                                    + "WHERE id=CAST(:source AS uuid)")
                                    .bind("id", draftId.toString())
                                    .bind("account", accountId)
                                    .bind("title", request.title())
                                    .bind("source", sourceDraftId.toString())
                                    .bind("workspace", variantWorkspaceJson(sourceWorkspace, storyboardId))
                                    .fetch().rowsUpdated());
                    // 2) 新分镜行（draft 态、edit_version 1、grouping 重映射）。可空 jsonb
                    //    必须 bindNull（R2DBC 拒绝 null bind；源分镜无 grouping 是常态）。
                    String remappedGrouping = remappedGrouping(source.grouping(), shotIdMap);
                    var storyboardSpec = db.sql("INSERT INTO video_storyboard(id, account_id, "
                                    + "organization_id, context_snapshot_id, target_duration_seconds, "
                                    + "resolution, request_payload, grouping) SELECT CAST(:id AS uuid), :account, "
                                    + "organization_id, context_snapshot_id, :duration, resolution, "
                                    + "request_payload, CAST(:grouping AS jsonb) FROM video_storyboard "
                                    + "WHERE id=CAST(:source AS uuid)")
                            .bind("id", storyboardId.toString())
                            .bind("account", accountId)
                            .bind("duration", targetDuration)
                            .bind("source", source.id().toString());
                    storyboardSpec = remappedGrouping == null
                            ? storyboardSpec.bindNull("grouping", String.class)
                            : storyboardSpec.bind("grouping", remappedGrouping);
                    Mono<Long> storyboardInsert = storyboardSpec.fetch().rowsUpdated();
                    // 3) 新镜头（新 ID、seq 重排 1..n、内容字段复制、状态 draft）
                    Flux<Long> shotInserts = Flux.range(0, selected.size())
                            .concatMap(index -> db.sql("INSERT INTO video_shot(id, storyboard_id, seq, visual, "
                                            + "narration, planned_seconds, camera_move, anchor_image_index, "
                                            + "prompt, status) SELECT CAST(:id AS uuid), CAST(:sb AS uuid), :seq, "
                                            + "visual, narration, planned_seconds, camera_move, "
                                            + "anchor_image_index, prompt, 'draft' FROM video_shot "
                                            + "WHERE id=CAST(:source AS uuid)")
                                    .bind("id", newShotIds.get(index).toString())
                                    .bind("sb", storyboardId.toString())
                                    .bind("seq", index + 1)
                                    .bind("source", selected.get(index).id().toString())
                                    .fetch().rowsUpdated());
                    // 4) 来源复制（mediaId 共享引用、shotId 重映射）
                    Mono<Long> sourceCopies = Flux.range(0, selected.size())
                            .concatMap(index -> db.sql("INSERT INTO video_shot_media_source(shot_id, "
                                            + "storyboard_id, source_kind, media_id, trim_start_ms, "
                                            + "trim_end_ms, audio_mode) SELECT CAST(:newShot AS uuid), "
                                            + "CAST(:sb AS uuid), source_kind, media_id, trim_start_ms, "
                                            + "trim_end_ms, audio_mode FROM video_shot_media_source "
                                            + "WHERE shot_id=CAST(:oldShot AS uuid)")
                                    .bind("newShot", newShotIds.get(index).toString())
                                    .bind("sb", storyboardId.toString())
                                    .bind("oldShot", selected.get(index).id().toString())
                                    .fetch().rowsUpdated())
                            .collectList().map(list -> (long) list.size());
                    // 5) 谱系行（含 source 版本与 shotIdMap）
                    Mono<Long> variantInsert = variants.insert(
                            new VideoStoryboardVariantRepository.Variant(storyboardId, source.id(), rootId,
                                    accountId, request.operationId(), requestHash, source.editVersion(),
                                    sourceDraftVersion, request.title(), shotIdMapJson, null));
                    // 6) 新方案绑定（draft 唯一关联 + 操作键幂等）
                    Mono<Long> bindingInsert = bindings.insert(
                            new VideoCanvasWorkspaceRepository.WorkspaceBinding(storyboardId, draftId, accountId,
                                    request.operationId(), requestHash, null));
                    // 7) 画布文档复制重映射（无则跳过；brief/shot 重写、旧 take/delivery 节点剔除）。
                    //    重映射失败 = 中途失败，随事务整体回滚（§7.2 不留半份方案）
                    Mono<Void> canvasCopy = canvas.findByDraftId(sourceDraftId)
                            .flatMap(sourceDoc -> canvas.insert(draftId, accountId,
                                    remappedCanvasDocument(sourceDoc.documentJson(), draftId, shotIdMap)))
                            .then();

                    return draftInsert.then(storyboardInsert)
                            .then(shotInserts.then())
                            .then(sourceCopies)
                            .then(variantInsert)
                            .then(bindingInsert)
                            .then(canvasCopy)
                            .then(Mono.defer(() -> assembleResultOf(storyboardId, draftId, shotIdMap)));
                });
    }

    // ---- 组装与工具 ----

    private Mono<CreateVariantResult> assembleResultOf(UUID storyboardId, UUID draftId,
            Map<String, String> shotIdMap) {
        return variants.findByStoryboard(storyboardId)
                .flatMap(variant -> assembleResult(variant));
    }

    private Mono<CreateVariantResult> assembleResult(VideoStoryboardVariantRepository.Variant variant) {
        Map<String, String> shotIdMap = readShotIdMap(variant.shotIdMapJson());
        return db.sql("SELECT id::text, title, workspace_json->>'capability' AS capability, status, version "
                        + "FROM creation_draft WHERE id=(SELECT draft_id FROM video_storyboard_workspace "
                        + "WHERE storyboard_id=CAST(:sb AS uuid))")
                .bind("sb", variant.storyboardId().toString())
                .map(row -> {
                    Map<String, Object> project = new LinkedHashMap<>();
                    project.put("id", row.get("id", String.class));
                    project.put("title", row.get("title", String.class));
                    project.put("capability", row.get("capability", String.class));
                    project.put("status", row.get("status", String.class));
                    project.put("version", row.get("version", Integer.class));
                    return project;
                }).one()
                .map(project -> new CreateVariantResult(
                        summary(variant.storyboardId(), variant.parentStoryboardId(),
                                variant.rootStoryboardId(), variant.sourceEditVersion(), variant.title()),
                        project, shotIdMap));
    }

    static Map<String, Object> summary(UUID storyboardId, UUID parentId, UUID rootId, Long sourceEditVersion,
            String title) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("storyboardId", storyboardId.toString());
        summary.put("parentStoryboardId", parentId == null ? null : parentId.toString());
        summary.put("rootStoryboardId", rootId.toString());
        summary.put("sourceEditVersion", sourceEditVersion);
        summary.put("title", title);
        return summary;
    }

    private static Map<String, String> readShotIdMap(String json) {
        try {
            return MAPPER.readValue(json == null ? "{}" : json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
        } catch (Exception error) {
            return Map.of();
        }
    }

    /** 新分镜目标时长：所选镜头时长和，钳制到 [15,180] 且 5 的倍数（表 CHECK 口径）。 */
    private static int alignedTargetDuration(List<VideoShot> selected) {
        int total = selected.stream().mapToInt(VideoShot::plannedSeconds).sum();
        int aligned = Math.max(15, Math.min(180, (int) (Math.round(total / 5.0) * 5)));
        return aligned;
    }

    private static void requireWellFormedNodes(String documentJson) {
        try {
            Map<String, Object> document = MAPPER.readValue(documentJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (document.containsKey("nodes") && !(document.get("nodes") instanceof List)) {
                throw new IllegalStateException("画布文档 nodes 结构损坏");
            }
        } catch (IllegalStateException structural) {
            throw structural;
        } catch (Exception parseFailed) {
            throw new IllegalStateException("画布文档解析失败", parseFailed);
        }
    }

    /** 派生草稿 workspace：重映射 inputs.video.storyboardId，剔除运行/交付/成片引用（不共享可变内容）。 */
    static String variantWorkspaceJson(String sourceWorkspaceJson, UUID newStoryboardId) {
        try {
            Map<String, Object> workspace = MAPPER.readValue(sourceWorkspaceJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (workspace.get("inputs") instanceof Map<?, ?> inputs
                    && inputs.get("video") instanceof Map<?, ?> video) {
                Map<String, Object> nextVideo = new LinkedHashMap<>();
                video.forEach((key, value) -> nextVideo.put(String.valueOf(key), value));
                nextVideo.put("storyboardId", newStoryboardId.toString());
                nextVideo.remove("productionTaskId");
                Map<String, Object> nextInputs = new LinkedHashMap<>();
                inputs.forEach((key, value) -> nextInputs.put(String.valueOf(key), value));
                nextInputs.put("video", nextVideo);
                workspace.put("inputs", nextInputs);
            }
            workspace.remove("delivery");
            return MAPPER.writeValueAsString(workspace);
        } catch (Exception error) {
            return sourceWorkspaceJson;
        }
    }

    static String hashOf(UUID sourceStoryboardId, CreateVariantRequest request) {
        String canonical = String.join("|", sourceStoryboardId.toString(), request.title(),
                String.valueOf(request.expectedEditVersion()), String.valueOf(request.expectedDraftVersion()),
                String.join(",", request.shotIds().stream().map(UUID::toString).toList()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String toJson(Map<String, Object> ignored, Map<String, String> shotIdMap) {
        try {
            return MAPPER.writeValueAsString(shotIdMap);
        } catch (Exception error) {
            throw new IllegalStateException("shotIdMap 序列化失败", error);
        }
    }

    private static String remappedGrouping(String groupingJson, Map<String, String> shotIdMap) {
        if (groupingJson == null || groupingJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> grouping = MAPPER.readValue(groupingJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object shots = grouping.get("shots");
            if (shots instanceof List<?> list) {
                List<Map<String, Object>> kept = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> shotGroup) {
                        Object id = shotGroup.get("id");
                        String mapped = id == null ? null : shotIdMap.get(id.toString());
                        if (mapped != null) {
                            Map<String, Object> next = new LinkedHashMap<>();
                            next.put("id", mapped);
                            Object groupId = shotGroup.get("groupId");
                            if (groupId != null) {
                                next.put("groupId", groupId);
                            }
                            kept.add(next);
                        }
                    }
                }
                grouping.put("shots", kept);
            }
            Object branches = grouping.get("branches");
            if (branches instanceof List<?> list) {
                List<Map<String, Object>> kept = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> branch) {
                        Object shotIds = branch.get("shotIds");
                        List<String> mappedIds = new ArrayList<>();
                        if (shotIds instanceof List<?> ids) {
                            for (Object id : ids) {
                                String mapped = id == null ? null : shotIdMap.get(id.toString());
                                if (mapped != null) {
                                    mappedIds.add(mapped);
                                }
                            }
                        }
                        if (!mappedIds.isEmpty()) {
                            Map<String, Object> next = new LinkedHashMap<>();
                            next.put("id", branch.get("id"));
                            next.put("name", branch.get("name"));
                            next.put("shotIds", mappedIds);
                            kept.add(next);
                        }
                    }
                }
                grouping.put("branches", kept);
            }
            return MAPPER.writeValueAsString(grouping);
        } catch (Exception error) {
            return null;
        }
    }

    /** 画布文档重映射：brief 指向新草稿、shot 节点换新 ID；take/delivery 等悬空节点与相关边剔除。 */
    private static String remappedCanvasDocument(String documentJson, UUID newDraftId,
            Map<String, String> shotIdMap) {
        // 结构损坏是中途失败：抛出随事务回滚（不能吞错留半份方案）
        requireWellFormedNodes(documentJson);
        try {
            Map<String, Object> document = MAPPER.readValue(documentJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            document.put("storyboardId", document.get("storyboardId"));
            List<Map<String, Object>> nodes = new ArrayList<>();
            java.util.Set<String> keptIds = new java.util.HashSet<>();
            if (document.get("nodes") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> node)) {
                        continue;
                    }
                    String id = String.valueOf(node.get("id"));
                    String kind = String.valueOf(node.get("kind"));
                    Map<String, Object> next = new LinkedHashMap<>();
                    node.forEach((key, value) -> next.put(String.valueOf(key), value));
                    if ("brief".equals(kind) || "delivery".equals(kind)) {
                        if ("delivery".equals(kind)) {
                            continue; // 悬空交付节点不带入
                        }
                        next.put("id", "brief:" + newDraftId);
                        next.put("refId", newDraftId.toString());
                    } else if ("shot".equals(kind)) {
                        String mapped = shotIdMap.get(String.valueOf(node.get("refId")));
                        if (mapped == null) {
                            continue; // 未选镜头不带入
                        }
                        next.put("id", "shot:" + mapped);
                        next.put("refId", mapped);
                    } else if ("take".equals(kind)) {
                        continue; // 旧候选节点不带入
                    }
                    keptIds.add(String.valueOf(next.get("id")));
                    nodes.add(next);
                }
            }
            document.put("nodes", nodes);
            if (document.get("edges") instanceof List<?> edges) {
                List<Map<String, Object>> keptEdges = new ArrayList<>();
                for (Object item : edges) {
                    if (item instanceof Map<?, ?> edge
                            && keptIds.contains(String.valueOf(edge.get("fromNodeId")))
                            && keptIds.contains(String.valueOf(edge.get("toNodeId")))) {
                        Map<String, Object> next = new LinkedHashMap<>();
                        edge.forEach((key, value) -> next.put(String.valueOf(key), value));
                        keptEdges.add(next);
                    }
                }
                document.put("edges", keptEdges);
            }
            return MAPPER.writeValueAsString(document);
        } catch (Exception error) {
            return documentJson;
        }
    }
}
