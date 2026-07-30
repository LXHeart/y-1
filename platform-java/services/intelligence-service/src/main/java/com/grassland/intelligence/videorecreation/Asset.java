package com.grassland.intelligence.videorecreation;

import java.util.Map;

/**
 * 视频改编「资产」出图请求中的资源卡（草场 intelligence Slice 9）。镜像 legacy
 * {@code server/src/schemas/video-recreation.ts} 的 {@code adaptedCharacterSheet/sceneCard/propCard}。
 *
 * <p>判别字段 {@code assetType} 位于父请求体（非 asset 内），故由 {@link VideoRecreationController}
 * 读出 assetType 后经 {@link #parse(String, JsonNode)} 分发到具体变体——Jackson 多态注解无法按兄弟字段分发，
 * 这里手动解析并交由各 record 的紧凑构造器逐字段校验上限。
 *
 * <p>上限逐字对齐 legacy Zod：id 1..100；name 1..200；title ≤200（可空）；description/threeViewPrompt/imagePrompt 1..2000。
 */
public sealed interface Asset permits Asset.CharacterAsset, Asset.SceneAsset, Asset.PropAsset {

    /** 角色三视图卡：name / description / threeViewPrompt。 */
    record CharacterAsset(String id, String name, String description, String threeViewPrompt) implements Asset {
        public CharacterAsset {
            id = require(id, 1, 100);
            name = require(name, 1, 200);
            description = require(description, 1, 2000);
            threeViewPrompt = require(threeViewPrompt, 1, 2000);
        }
    }

    /** 场景卡：title（可空）/ description / imagePrompt。 */
    record SceneAsset(String id, String title, String description, String imagePrompt) implements Asset {
        public SceneAsset {
            id = require(id, 1, 100);
            title = optional(title, 200);
            description = require(description, 1, 2000);
            imagePrompt = require(imagePrompt, 1, 2000);
        }
    }

    /** 道具卡：name / description / imagePrompt。 */
    record PropAsset(String id, String name, String description, String imagePrompt) implements Asset {
        public PropAsset {
            id = require(id, 1, 100);
            name = require(name, 1, 200);
            description = require(description, 1, 2000);
            imagePrompt = require(imagePrompt, 1, 2000);
        }
    }

    /**
     * 按 assetType 分发解析单个 asset 节点；未知类型或缺字段 → IllegalArgumentException（→ 400）。
     * 取 {@link Map} 而非 {@code JsonNode}：Spring Boot 4 用 Jackson 3（{@code tools.jackson}），
     * 其 WebFlux 解码器无法把请求体反序列化为抽象的 {@code JsonNode}。
     */
    static Asset parse(String assetType, Map<?, ?> node) {
        if (node == null) {
            throw new IllegalArgumentException("资源信息无效");
        }
        return switch (assetType == null ? "" : assetType) {
            case "character-three-view" -> new CharacterAsset(
                    str(node, "id"), str(node, "name"),
                    str(node, "description"), str(node, "threeViewPrompt"));
            case "scene" -> new SceneAsset(
                    str(node, "id"), str(node, "title"),
                    str(node, "description"), str(node, "imagePrompt"));
            case "prop" -> new PropAsset(
                    str(node, "id"), str(node, "name"),
                    str(node, "description"), str(node, "imagePrompt"));
            default -> throw new IllegalArgumentException("资源类型无效");
        };
    }

    private static String str(Map<?, ?> node, String name) {
        Object value = node.get(name);
        return value == null ? null : value.toString();
    }

    private static String require(String value, int min, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw new IllegalArgumentException("资源信息无效");
        }
        return trimmed;
    }

    private static String optional(String value, int max) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > max) {
            throw new IllegalArgumentException("资源信息无效");
        }
        return trimmed;
    }
}
