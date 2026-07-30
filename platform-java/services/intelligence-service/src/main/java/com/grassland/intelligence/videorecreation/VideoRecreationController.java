package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 视频改编出图四端点（草场 intelligence Slice 9），镜像 legacy {@code /api/video-recreation/*} 图片端点。
 * 保持 legacy URL、字段名与响应信封；零积分（图片端点免费语义）。
 *
 * <p>资产端点因判别字段 {@code assetType} 在父 body，取 {@code @RequestBody JsonNode} 手动分发到 {@link Asset}；
 * 场景端点固定形状，走 house-style {@code @RequestBody} 记录 + 紧凑构造器校验。鉴权统一经 edge-bff 签发的
 * {@code X-Grassland-Identity}（{@link IntelligenceCallerResolver}，缺/失效 → 401「未登录」）。
 */
@RestController
@RequestMapping("/api/video-recreation")
public class VideoRecreationController {

    private static final Set<String> SIZES = Set.of("1024x1024", "1024x1792", "1792x1024");
    private static final int MAX_BATCH = 20;

    private final IntelligenceCallerResolver callers;
    private final VideoRecreationImageService images;

    public VideoRecreationController(IntelligenceCallerResolver callers, VideoRecreationImageService images) {
        this.callers = callers;
        this.images = images;
    }

    @PostMapping(value = "/generate-asset-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateAssetImage(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    AssetRequest req = parseSingleAsset(body);
                    return images.generateAsset(req.asset(), req.visualStyle(), req.size(), owner(caller));
                })
                .map(VideoRecreationController::success);
    }

    @PostMapping(value = "/generate-all-asset-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateAllAssetImages(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    AssetBatchRequest req = parseBatchAssets(body);
                    return images.generateAllAssets(req.assets(), req.visualStyle(), req.size(), owner(caller));
                })
                .map(list -> success(Map.of("images", list)));
    }

    @PostMapping(value = "/generate-scene-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateSceneImage(
            @RequestBody GenerateSceneImageRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> images.generateScene(
                        body.scene(), body.overallStyle(), body.size(), owner(caller)))
                .map(VideoRecreationController::success);
    }

    @PostMapping(value = "/generate-all-scene-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateAllSceneImages(
            @RequestBody GenerateAllSceneImagesRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> images.generateAllScenes(
                        body.scenes(), body.overallStyle(), body.size(), owner(caller)))
                .map(list -> success(Map.of("images", list)));
    }

    private static MediaOwner owner(IntelligenceCallerResolver.Caller caller) {
        return new MediaOwner(caller.accountId(), caller.organizationId());
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    private static AssetRequest parseSingleAsset(Map<String, Object> body) {
        Object assetObj = body == null ? null : body.get("asset");
        if (!(assetObj instanceof Map<?, ?> assetMap)) {
            throw new IllegalArgumentException("资源信息无效");
        }
        Asset asset = Asset.parse(str(body, "assetType"), assetMap);
        return new AssetRequest(asset, optional(str(body, "visualStyle"), 500), validateSize(str(body, "size")));
    }

    private static AssetBatchRequest parseBatchAssets(Map<String, Object> body) {
        Object arrObj = body == null ? null : body.get("assets");
        if (!(arrObj instanceof List<?> arr)) {
            throw new IllegalArgumentException("资源列表无效");
        }
        String assetType = str(body, "assetType");
        List<Asset> assets = new ArrayList<>();
        for (Object element : arr) {
            if (!(element instanceof Map<?, ?> assetMap)) {
                throw new IllegalArgumentException("资源信息无效");
            }
            assets.add(Asset.parse(assetType, assetMap));
        }
        if (assets.isEmpty() || assets.size() > MAX_BATCH) {
            throw new IllegalArgumentException("资源数量需为 1-20");
        }
        return new AssetBatchRequest(assets, optional(str(body, "visualStyle"), 500), validateSize(str(body, "size")));
    }

    private static String validateSize(String raw) {
        String value = raw == null || raw.isBlank() ? "1024x1792" : raw.trim();
        if (!SIZES.contains(value)) {
            throw new IllegalArgumentException("图片尺寸无效");
        }
        return value;
    }

    private static String optional(String value, int max) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > max) {
            throw new IllegalArgumentException("风格描述过长");
        }
        return trimmed;
    }

    private static String str(Map<String, Object> node, String field) {
        Object value = node.get(field);
        return value == null ? null : value.toString();
    }

    private record AssetRequest(Asset asset, String visualStyle, String size) {}

    private record AssetBatchRequest(List<Asset> assets, String visualStyle, String size) {}

    public record GenerateSceneImageRequest(VideoScene scene, String overallStyle, String size) {
        public GenerateSceneImageRequest {
            if (scene == null) {
                throw new IllegalArgumentException("场景信息无效");
            }
            overallStyle = optional(overallStyle, 500);
            size = validateSize(size);
        }
    }

    public record GenerateAllSceneImagesRequest(List<VideoScene> scenes, String overallStyle, String size) {
        public GenerateAllSceneImagesRequest {
            if (scenes == null || scenes.isEmpty() || scenes.size() > MAX_BATCH) {
                throw new IllegalArgumentException("场景数量需为 1-20");
            }
            scenes = List.copyOf(scenes);
            overallStyle = optional(overallStyle, 500);
            size = validateSize(size);
        }
    }
}
