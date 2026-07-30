package com.grassland.intelligence.videorecreation;

/**
 * 视频改编「场景」出图请求中的单个场景（草场 intelligence Slice 9）。镜像 legacy
 * {@code server/src/schemas/video-recreation.ts} 的 {@code videoSceneSchema}。
 *
 * <p>上限逐字对齐 legacy Zod：shotDescription/characterDescription/sceneEnvironment 1..2000；
 * actionMovement/dialogueVoiceover ≤1000（可空）。
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
        actionMovement = optional(actionMovement, 1000);
        dialogueVoiceover = optional(dialogueVoiceover, 1000);
        sceneEnvironment = require(sceneEnvironment, 1, 2000);
    }

    private static String require(String value, int min, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return trimmed;
    }

    private static String optional(String value, int max) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > max) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return trimmed;
    }
}
