package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videoproduction.VideoStoryboardEditService;
import com.grassland.intelligence.videoproduction.VideoStoryboardVariantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 计划原子应用（任务书 #100 C100-17 / API-15 / §6.6）。
 *
 * <p>重放优先（已应用返回既有 apply_result）；再校验归属/未过期/ready/draft/edit/canvas 三
 * 版本与选择范围（update 只命中选中镜头）。edit：先全部校验（含 append 的 30 镜上限）再
 * 同编辑闸事务应用——任一非法整批不写，只升一次 editVersion；variant 委托既有派生服务；
 * prepare-generation 只返回准备参数（生成调用为零——媒体请求由用户按钮发起）。apply_result
 * 与业务更新同事务（最后一步失败全回滚）。
 */
@Service
public class CanvasAgentActionService {

    private static final Logger log = LoggerFactory.getLogger(CanvasAgentActionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CanvasAgentPlanRepository plans;
    private final org.springframework.r2dbc.core.DatabaseClient db;
    private final VideoStoryboardEditService editGate;
    private final VideoStoryboardVariantService variants;
    private final TransactionalOperator transactions;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CanvasAgentActionService(CanvasAgentPlanRepository plans,
            org.springframework.r2dbc.core.DatabaseClient db, VideoStoryboardEditService editGate,
            VideoStoryboardVariantService variants, TransactionalOperator transactions) {
        this(plans, db, editGate, variants, transactions, Clock.systemUTC());
    }

    CanvasAgentActionService(CanvasAgentPlanRepository plans,
            org.springframework.r2dbc.core.DatabaseClient db, VideoStoryboardEditService editGate,
            VideoStoryboardVariantService variants, TransactionalOperator transactions, Clock clock) {
        this.plans = plans;
        this.db = db;
        this.editGate = editGate;
        this.variants = variants;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Mono<Map<String, Object>> apply(String accountId, UUID planId) {
        return plans.findById(planId)
                .filter(row -> row.accountId().equals(accountId))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "计划不存在")))
                .flatMap(plan -> {
                    // 重放优先：已应用返回持久化的唯一结果（不再写任何业务）
                    if ("applied".equals(plan.status())) {
                        return Mono.just(readApplyResult(plan.applyResultJson()));
                    }
                    if (!"ready".equals(plan.status())) {
                        return Mono.error(new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED",
                                "计划当前状态不可应用（" + plan.status() + "）"));
                    }
                    if (plan.expiresAt().toInstant().isBefore(clock.instant())) {
                        return Mono.error(new IntelligenceException(409, "CANVAS_PLAN_EXPIRED",
                                "计划已过期，请重新提出修改"));
                    }
                    return validateVersions(plan).then(applyWithinTransaction(plan));
                });
    }

    /** 三版本闸：draft/edit/canvas 与计划基线一致（计划过期外的第二道防漂移闸）。 */
    private Mono<Void> validateVersions(CanvasAgentPlanRepository.AgentPlanRow plan) {
        return db.sql("SELECT d.version AS draftVersion, s.edit_version AS editVersion FROM creation_draft d "
                        + "JOIN video_storyboard s ON s.id=CAST(:sb AS uuid) WHERE d.id=CAST(:draft AS uuid) "
                        + "AND d.owner_account_id=:account AND d.deleted_at IS NULL")
                .bind("sb", plan.storyboardId().toString())
                .bind("draft", plan.draftId().toString())
                .bind("account", plan.accountId())
                .map(row -> Map.of("draftVersion", row.get("draftVersion", Integer.class),
                        "editVersion", row.get("editVersion", Long.class)))
                .one()
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "草稿或分镜不存在")))
                .flatMap(versions -> db.sql("SELECT revision FROM creation_canvas_document "
                                + "WHERE draft_id=CAST(:draft AS uuid)")
                        .bind("draft", plan.draftId().toString())
                        .map(row -> row.get(0, Long.class)).one()
                        .switchIfEmpty(Mono.just(-1L))
                        .flatMap(canvasRevision -> {
                            if (((Number) versions.get("draftVersion")).intValue() != plan.baseDraftVersion()
                                    || ((Number) versions.get("editVersion")).longValue() != plan.baseEditVersion()
                                    || canvasRevision != plan.baseCanvasRevision()) {
                                return Mono.error(new IntelligenceException(409, "CANVAS_VERSION_CONFLICT",
                                        "画布已变化，请重新提出修改"));
                            }
                            return Mono.empty();
                        }));
    }

    private Mono<Map<String, Object>> applyWithinTransaction(CanvasAgentPlanRepository.AgentPlanRow plan) {
        return Mono.<Map<String, Object>>defer(() -> {
            JsonNode action;
            try {
                action = MAPPER.readTree(plan.actionJson());
            } catch (Exception e) {
                return Mono.error(new IntelligenceException(502, "CANVAS_AGENT_INVALID_PLAN",
                        "计划动作不可读"));
            }
            String kind = action.fieldNames().next();
            Set<String> selectedShotIds = selectedShotIds(plan);
            return switch (kind) {
                case "edit" -> applyEdit(plan, action.path("edit"), selectedShotIds);
                case "variant" -> applyVariant(plan, action.path("variant"));
                case "prepare-generation" -> applyPrepare(plan, action.path("prepare-generation"),
                        selectedShotIds);
                default -> Mono.error(new IntelligenceException(502, "CANVAS_AGENT_INVALID_PLAN",
                        "未知动作 kind: " + kind));
            };
        });
    }

    /** edit：先全部校验（update 范围/append 30 镜）再同编辑闸事务整批应用，只升一次 editVersion。 */
    private Mono<Map<String, Object>> applyEdit(CanvasAgentPlanRepository.AgentPlanRow plan, JsonNode edit,
            Set<String> selectedShotIds) {
        List<JsonNode> updates = new ArrayList<>();
        List<JsonNode> appends = new ArrayList<>();
        for (JsonNode item : edit.path("actions")) {
            if ("update-shot".equals(item.path("kind").asText())) {
                updates.add(item.path("patch"));
            } else {
                appends.add(item.path("shot"));
            }
        }
        // 预校验（IO）：update 范围 + 30 镜上限——任一失败整批不写
        Mono<Void> precheck = Flux.fromIterable(updates)
                .flatMap(patch -> {
                    String shotId = patch.path("shotId").asText();
                    if (!selectedShotIds.contains(shotId)) {
                        return Mono.<Void>error(new IntelligenceException(502, "CANVAS_AGENT_INVALID_PLAN",
                                "update-shot 越界：镜头未选中 " + shotId));
                    }
                    return db.sql("SELECT COUNT(*) FROM video_shot WHERE id=CAST(:id AS uuid) "
                                    + "AND storyboard_id=CAST(:sb AS uuid)")
                            .bind("id", shotId).bind("sb", plan.storyboardId().toString())
                            .map(row -> row.get(0, Long.class)).one()
                            .flatMap(count -> count == 0
                                    ? Mono.<Void>error(new IntelligenceException(502,
                                            "CANVAS_AGENT_INVALID_PLAN", "镜头不存在: " + shotId))
                                    : Mono.empty());
                }).then()
                .then(db.sql("SELECT COUNT(*) FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid)")
                        .bind("sb", plan.storyboardId().toString())
                        .map(row -> row.get(0, Long.class)).one()
                        .flatMap(count -> count + appends.size() > 30
                                ? Mono.<Void>error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                                        "追加后超过 30 镜上限"))
                                : Mono.empty()));
        return precheck.then(Mono.defer(() -> {
            Mono<Map<String, Object>> work = editGate.inEditLockWithValue(plan.accountId(),
                    plan.storyboardId(), plan.baseEditVersion(), "已制作内容通过独立方案修改",
                    locked -> {
                        List<String> affected = new ArrayList<>();
                        Mono<Void> writes = Flux.fromIterable(updates)
                                .concatMap(patch -> applyUpdatePatch(patch).doOnSuccess(ignored ->
                                        affected.add(patch.path("shotId").asText())))
                                .then();
                        Mono<Void> appendsWork = Flux.fromIterable(appends)
                                .concatMap(shot -> appendShotRow(plan.storyboardId(), shot))
                                .then();
                        return writes.then(appendsWork).thenReturn(
                                new VideoStoryboardEditService.EditWritePayload<>(affected,
                                        new VideoStoryboardEditService.EditWrite(true,
                                                List.copyOf(affected))));
                    })
                    .flatMap(outcome -> {
                        List<String> affected = outcome.value();
                        Map<String, Object> result = applyResultView(plan, outcome.outcome().editVersion(),
                                affected, null, null);
                        // apply_result 与业务更新同事务：最后一步失败全回滚
                        return transactions.transactional(persistApplyResult(plan, result)
                                .thenReturn(result));
                    });
            return transactions.transactional(work);
        }));
    }

    /** variant：委托既有派生服务（同域语义：新草稿/分镜/镜头，版本闸由其自查）。 */
    private Mono<Map<String, Object>> applyVariant(CanvasAgentPlanRepository.AgentPlanRow plan,
            JsonNode variant) {
        List<UUID> shotIds = new ArrayList<>();
        for (JsonNode shotId : variant.path("shotIds")) {
            shotIds.add(UUID.fromString(shotId.asText()));
        }
        return variants.derive(plan.accountId(), plan.storyboardId(),
                        new VideoStoryboardVariantService.CreateVariantRequest(plan.operationId(), null, null,
                                variant.path("title").asText(), shotIds))
                .flatMap(result -> {
                    Map<String, Object> view = applyResultView(plan, plan.baseEditVersion(), List.of(),
                            result.variant(), null);
                    return transactions.transactional(persistApplyResult(plan, view).thenReturn(view));
                });
    }

    /** prepare-generation：只返回准备参数（生成调用为零——用户按钮发起）。 */
    private Mono<Map<String, Object>> applyPrepare(CanvasAgentPlanRepository.AgentPlanRow plan,
            JsonNode prepare, Set<String> selectedShotIds) {
        String mode = prepare.path("mode").asText();
        String shotId = prepare.path("shotId").isTextual() ? prepare.path("shotId").asText() : null;
        if (shotId != null && !selectedShotIds.contains(shotId)) {
            return Mono.error(new IntelligenceException(502, "CANVAS_AGENT_INVALID_PLAN",
                    "prepare-generation 指向未选中镜头"));
        }
        Map<String, Object> view = applyResultView(plan, plan.baseEditVersion(), List.of(), null,
                Map.of("mode", mode, "shotId", shotId));
        return transactions.transactional(persistApplyResult(plan, view).thenReturn(view));
    }

    private Mono<Void> applyUpdatePatch(JsonNode patch) {
        StringBuilder sql = new StringBuilder("UPDATE video_shot SET ");
        List<String> sets = new ArrayList<>();
        Map<String, Object> binds = new LinkedHashMap<>();
        if (patch.has("visual")) {
            sets.add("visual=:visual");
            binds.put("visual", patch.path("visual").asText());
        }
        if (patch.has("narration")) {
            sets.add("narration=:narration");
            binds.put("narration", patch.path("narration").asText());
        }
        if (patch.has("plannedSeconds")) {
            sets.add("planned_seconds=:plannedSeconds");
            binds.put("plannedSeconds", patch.path("plannedSeconds").asInt());
        }
        if (patch.has("cameraMove")) {
            sets.add("camera_move=:cameraMove");
            binds.put("cameraMove", patch.path("cameraMove").asText());
        }
        if (patch.has("anchorImageIndex")) {
            sets.add("anchor_image_index=:anchorImageIndex");
            binds.put("anchorImageIndex", patch.path("anchorImageIndex").asInt());
        }
        sql.append(String.join(", ", sets)).append(" WHERE id=CAST(:shotId AS uuid)");
        var spec = db.sql(sql.toString()).bind("shotId", patch.path("shotId").asText());
        for (Map.Entry<String, Object> bind : binds.entrySet()) {
            spec = spec.bind(bind.getKey(), bind.getValue());
        }
        return spec.then();
    }

    private Mono<Void> appendShotRow(UUID storyboardId, JsonNode shot) {
        return db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds, "
                        + "camera_move, anchor_image_index, prompt) SELECT CAST(:sb AS uuid), "
                        + "COALESCE(MAX(seq), 0) + 1, :visual, :narration, :plannedSeconds, :cameraMove, "
                        + ":anchorImageIndex, '' FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid)")
                .bind("sb", storyboardId.toString())
                .bind("visual", shot.path("visual").asText())
                .bind("narration", shot.path("narration").asText())
                .bind("plannedSeconds", shot.path("plannedSeconds").asInt())
                .bind("cameraMove", shot.path("cameraMove").asText())
                .bind("anchorImageIndex", shot.path("anchorImageIndex").asInt())
                .then();
    }

    private Mono<Void> persistApplyResult(CanvasAgentPlanRepository.AgentPlanRow plan,
            Map<String, Object> result) {
        String json;
        try {
            json = MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("apply_result 序列化失败", e);
        }
        return plans.apply(plan.id(), json).flatMap(updated -> updated == 0
                ? Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "计划状态已变化"))
                : Mono.empty());
    }

    private static Map<String, Object> applyResultView(CanvasAgentPlanRepository.AgentPlanRow plan,
            long editVersion, List<String> affectedShotIds, Map<String, Object> variant,
            Map<String, Object> preparedGeneration) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", plan.id().toString());
        view.put("storyboardId", plan.storyboardId().toString());
        view.put("draftId", plan.draftId().toString());
        view.put("editVersion", editVersion);
        view.put("affectedShotIds", affectedShotIds);
        view.put("variant", variant);
        view.put("preparedGeneration", preparedGeneration);
        return view;
    }

    private static Set<String> selectedShotIds(CanvasAgentPlanRepository.AgentPlanRow plan) {
        Set<String> ids = new HashSet<>();
        try {
            JsonNode array = MAPPER.readTree(plan.selectedNodeIdsJson());
            for (JsonNode node : array) {
                if (node.asText().startsWith("shot:")) {
                    ids.add(node.asText().substring(5));
                }
            }
        } catch (Exception ignored) {
            // 解析失败 = 无选中（update 范围闸会拒绝全部 update）
        }
        return ids;
    }

    private static Map<String, Object> readApplyResult(String json) {
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<
                    Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
