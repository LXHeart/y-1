package com.grassland.intelligence.videorecreation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 视频改编出图提示词构建（草场 intelligence Slice 9）。忠实移植 legacy
 * {@code server/src/services/video-recreation-image.service.ts} 的 {@code buildSceneImagePrompt/buildAssetImagePrompt}。
 *
 * <p>连接符与 legacy 一致为 {@code ". "}（句点+空格），空段过滤后再拼接；场景 prompt 中
 * {@code sceneEnvironment} 排在 {@code Action}/{@code Style} 限定词之前，且 {@code dialogueVoiceover}
 * <b>不</b>进入画面 prompt（旁白与画面生成无关）。
 */
final class VideoRecreationPrompts {

    private VideoRecreationPrompts() {}

    static String buildSceneImagePrompt(VideoScene scene, String overallStyle) {
        List<String> parts = new ArrayList<>();
        parts.add(scene.shotDescription());
        parts.add(scene.characterDescription());
        parts.add(scene.sceneEnvironment());
        if (scene.actionMovement() != null && !scene.actionMovement().isBlank()) {
            parts.add("Action: " + scene.actionMovement());
        }
        if (overallStyle != null) {
            parts.add("Style: " + overallStyle);
        }
        return joinPromptParts(parts);
    }

    static String buildAssetImagePrompt(Asset asset, String visualStyle) {
        List<String> parts = new ArrayList<>();
        switch (asset) {
            case Asset.CharacterAsset c -> {
                parts.add(c.name());
                parts.add(c.description());
                parts.add(c.threeViewPrompt());
            }
            case Asset.SceneAsset s -> {
                if (s.title() != null) {
                    parts.add(s.title());
                }
                parts.add(s.description());
                parts.add(s.imagePrompt());
            }
            case Asset.PropAsset p -> {
                parts.add(p.name());
                parts.add(p.description());
                parts.add(p.imagePrompt());
            }
        }
        if (visualStyle != null) {
            parts.add("Style: " + visualStyle);
        }
        return joinPromptParts(parts);
    }

    private static String joinPromptParts(List<String> parts) {
        return parts.stream()
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(". "));
    }
}
