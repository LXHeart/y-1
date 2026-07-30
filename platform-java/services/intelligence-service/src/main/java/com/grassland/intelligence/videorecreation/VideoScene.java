package com.grassland.intelligence.videorecreation;

import java.util.Map;

/**
 * 视频改编「场景」出图请求中的单个场景（草场 intelligence Slice 9）。镜像 legacy
 * {@code server/src/schemas/video-recreation.ts} 的 {@code videoSceneSchema}。
 *
 * <p>上限逐字对齐 legacy Zod：shotDescription/characterDescription/sceneEnvironment 1..2000；
 * actionMovement/dialogueVoiceover 为<b>必给 string</b>（可为空串），长度 ≤1000。
 *
 * <p>{@code dialogueVoiceover} 在 legacy 中被校验与接收，但<b>不进入生图 prompt</b>（旁白与画面无关），
 * 见 {@link VideoRecreationPrompts#buildSceneImagePrompt}。
 */
public record VideoScene(
        String shotDescription,
        String characterDescription,
        String actionMovement,
        String dialogueVoiceover,
        String sceneEnvironment) {

    public VideoScene {
        shotDescription = require(shotDescription, 1, 2000);
        characterDescription = require(characterDescription, 1, 2000);
        actionMovement = requiredOptionalText(actionMovement, 1000);
        dialogueVoiceover = requiredOptionalText(dialogueVoiceover, 1000);
        sceneEnvironment = require(sceneEnvironment, 1, 2000);
    }

    /**
     * 从 controller 解码的 map 严格读取五个 legacy 必填 string 字段；action/dialogue 可为空串但不可省略、null 或非 string。
     */
    static VideoScene parse(Map<?, ?> node) {
        if (node == null) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return new VideoScene(
                str(node, "shotDescription"),
                str(node, "characterDescription"),
                str(node, "actionMovement"),
                str(node, "dialogueVoiceover"),
                str(node, "sceneEnvironment"));
    }

    private static String str(Map<?, ?> node, String name) {
        Object value = node.get(name);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return string;
    }

    private static String require(String value, int min, int max) {
        String trimmed = value == null ? "" : LegacyStringValidation.trim(value);
        if (trimmed.length() < min || trimmed.length() > max) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return trimmed;
    }

    private static String requiredOptionalText(String value, int max) {
        if (value == null) {
            throw new IllegalArgumentException("场景信息无效");
        }
        String trimmed = LegacyStringValidation.trim(value);
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return trimmed;
    }
}
