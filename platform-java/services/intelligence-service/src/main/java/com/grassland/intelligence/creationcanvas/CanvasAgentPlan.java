package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 画布 AI 计划领域（任务书 #100 C100-16 / §6.6）。
 *
 * <p>严格动作解析：单一顶层 action；edit 允许 1～12 个 update-shot/append-shot，更新只能
 * 命中明确选中的镜头（{@link #validateAgainstSelection} 在 apply 侧复核），append 受 30 镜
 * 上限；variant/prepare-generation 形态固定。未知工具/字段/ID/错误类型直接失败——
 * 不「尽量执行」合法前半批（TC-036）。
 */
public final class CanvasAgentPlan {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int MAX_EDIT_ACTIONS = 12;
    public static final int MAX_SHOTS_PER_STORYBOARD = 30;

    private CanvasAgentPlan() {
    }

    /**
     * 解析模型输出为合法动作 json（原样存库）。非法抛 IllegalArgumentException——
     * 调用方转 502 CANVAS_AGENT_INVALID_PLAN，业务零写入。
     */
    public static String parseAction(String modelOutput) {
        JsonNode root;
        try {
            root = MAPPER.readTree(modelOutput);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型输出不是合法 JSON");
        }
        if (!root.isObject() || root.size() != 1) {
            throw new IllegalArgumentException("必须是单一顶层 action 对象");
        }
        String kind = root.fieldNames().next();
        JsonNode action = root.get(kind);
        if (!action.isObject()) {
            throw new IllegalArgumentException("action 必须是对象");
        }
        switch (kind) {
            case "edit" -> requireEdit(action);
            case "variant" -> requireVariant(action);
            case "prepare-generation" -> requirePrepareGeneration(action);
            default -> throw new IllegalArgumentException("未知动作 kind: " + kind);
        }
        return modelOutput;
    }

    private static void requireEdit(JsonNode edit) {
        requireFields(edit, java.util.Set.of("actions"), java.util.Set.of());
        if (!edit.path("actions").isArray() || edit.path("actions").isEmpty()) {
            throw new IllegalArgumentException("edit.actions 必须是非空数组");
        }
        if (edit.path("actions").size() > MAX_EDIT_ACTIONS) {
            throw new IllegalArgumentException("edit.actions 超过上限 " + MAX_EDIT_ACTIONS);
        }
        for (JsonNode item : edit.path("actions")) {
            if (!item.isObject() || item.size() != 2 || !item.has("kind")) {
                throw new IllegalArgumentException("动作项必须是 {kind, ...} 恰两字段对象");
            }
            String itemKind = item.path("kind").asText();
            if ("update-shot".equals(itemKind)) {
                JsonNode patch = item.path("patch");
                if (!patch.isObject()) {
                    throw new IllegalArgumentException("update-shot 需要 patch 对象");
                }
                requireShotId(patch);
                requireContentFields(patch);
            } else if ("append-shot".equals(itemKind)) {
                JsonNode shot = item.path("shot");
                if (!shot.isObject()) {
                    throw new IllegalArgumentException("append-shot 需要 shot 对象");
                }
                requireFields(shot, java.util.Set.of("visual", "narration", "plannedSeconds",
                        "cameraMove", "anchorImageIndex"), java.util.Set.of());
                requireContentFields(shot);
                if (!shot.path("plannedSeconds").isInt()
                        || shot.path("plannedSeconds").asInt() < 4
                        || shot.path("plannedSeconds").asInt() > 6) {
                    throw new IllegalArgumentException("plannedSeconds 必须是 4～6 整数");
                }
                if (!shot.path("cameraMove").isTextual()) {
                    throw new IllegalArgumentException("cameraMove 必须是字符串");
                }
                if (!shot.path("anchorImageIndex").isInt() || shot.path("anchorImageIndex").asInt() < 0) {
                    throw new IllegalArgumentException("anchorImageIndex 必须是非负整数");
                }
            } else {
                throw new IllegalArgumentException("未知动作项 kind: " + itemKind);
            }
        }
    }

    private static void requireVariant(JsonNode variant) {
        requireFields(variant, java.util.Set.of("title", "shotIds"), java.util.Set.of());
        if (!variant.path("title").isTextual()
                || variant.path("title").asText().codePoints().count() > 240) {
            throw new IllegalArgumentException("variant.title 必须是 ≤240 字符");
        }
        if (!variant.path("shotIds").isArray() || variant.path("shotIds").isEmpty()
                || variant.path("shotIds").size() > 30) {
            throw new IllegalArgumentException("variant.shotIds 必须是 1～30 项");
        }
        for (JsonNode shotId : variant.path("shotIds")) {
            if (!shotId.isTextual()) {
                throw new IllegalArgumentException("shotIds 项必须是字符串");
            }
        }
    }

    private static void requirePrepareGeneration(JsonNode prepare) {
        requireFields(prepare, java.util.Set.of("mode", "shotId"), java.util.Set.of());
        String mode = prepare.path("mode").asText(null);
        if (!"initial".equals(mode) && !"regenerate".equals(mode) && !"reroll".equals(mode)) {
            throw new IllegalArgumentException("prepare-generation.mode 非法");
        }
        JsonNode shotId = prepare.path("shotId");
        if ("initial".equals(mode)) {
            if (!shotId.isNull()) {
                throw new IllegalArgumentException("initial 模式 shotId 必须为 null");
            }
        } else if (!shotId.isTextual()) {
            throw new IllegalArgumentException("regenerate/reroll 必须指向具体镜头");
        }
    }

    /** update-shot 的 patch：shotId + 至少一个内容字段；字段集与 §6.1 ShotContentPatch 一致。 */
    private static void requireContentFields(JsonNode patch) {
        boolean any = false;
        for (String field : new String[] { "visual", "narration", "plannedSeconds", "cameraMove",
                "anchorImageIndex" }) {
            if (patch.has(field)) {
                any = true;
                if ("plannedSeconds".equals(field) && (!patch.path(field).isInt()
                        || patch.path(field).asInt() < 4 || patch.path(field).asInt() > 6)) {
                    throw new IllegalArgumentException("plannedSeconds 必须是 4～6 整数");
                }
                if ("anchorImageIndex".equals(field) && (!patch.path(field).isInt()
                        || patch.path(field).asInt() < 0)) {
                    throw new IllegalArgumentException("anchorImageIndex 必须是非负整数");
                }
                if (("visual".equals(field) || "narration".equals(field) || "cameraMove".equals(field))
                        && !patch.path(field).isTextual()) {
                    throw new IllegalArgumentException(field + " 必须是字符串");
                }
            }
        }
        if (!any) {
            throw new IllegalArgumentException("patch 至少含一个内容字段");
        }
    }

    private static void requireShotId(JsonNode patch) {
        if (!patch.path("shotId").isTextual() || patch.path("shotId").asText().isBlank()) {
            throw new IllegalArgumentException("patch.shotId 必填");
        }
    }

    private static void requireFields(JsonNode node, java.util.Set<String> required,
            java.util.Set<String> optional) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(seen::add);
        for (String field : seen) {
            if (!required.contains(field) && !optional.contains(field)) {
                throw new IllegalArgumentException("未知字段: " + field);
            }
        }
        for (String field : required) {
            if (!seen.contains(field)) {
                throw new IllegalArgumentException("缺字段: " + field);
            }
        }
    }

    /** apply 侧复核：edit 的 update-shot 只允许命中选中镜头（TC-040 越界拒绝）。 */
    public static void validateUpdateScope(String actionJson, java.util.Set<String> selectedShotIds) {
        try {
            JsonNode root = MAPPER.readTree(actionJson);
            JsonNode edit = root.path("edit");
            if (!edit.isObject()) {
                return;
            }
            for (JsonNode item : edit.path("actions")) {
                if ("update-shot".equals(item.path("kind").asText())) {
                    String shotId = item.path("patch").path("shotId").asText();
                    if (!selectedShotIds.contains(shotId)) {
                        throw new IllegalArgumentException("update-shot 越界：未选中镜头 " + shotId);
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("动作解析失败", e);
        }
    }
}
