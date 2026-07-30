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
    private final VideoRecreationAdaptationParser adaptationParser;
    private final VideoRecreationAdaptationService adaptation;

    public VideoRecreationController(IntelligenceCallerResolver callers, VideoRecreationImageService images,
            VideoRecreationAdaptationParser adaptationParser, VideoRecreationAdaptationService adaptation) {
        this.callers = callers;
        this.images = images;
        this.adaptationParser = adaptationParser;
        this.adaptation = adaptation;
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
            @RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    SceneRequest req = parseSingleScene(body);
                    return images.generateScene(req.scene(), req.overallStyle(), req.size(), owner(caller));
                })
                .map(VideoRecreationController::success);
    }

    @PostMapping(value = "/generate-all-scene-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateAllSceneImages(
            @RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    SceneBatchRequest req = parseBatchScenes(body);
                    return images.generateAllScenes(req.scenes(), req.overallStyle(), req.size(), owner(caller));
                })
                .map(list -> success(Map.of("images", list)));
    }

    @PostMapping(value = "/adapt-content", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> adaptContentJson(
            @RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> adaptation.adapt(adaptationParser.parseJson(body)))
                .map(VideoRecreationController::success);
    }

    @PostMapping(value = "/adapt-content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> adaptContentMultipart(ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> exchange.getMultipartData()
                        .flatMap(adaptationParser::parseMultipart)
                        .flatMap(adaptation::adapt))
                .map(VideoRecreationController::success);
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
        Asset asset = Asset.parse(requiredString(body, "assetType"), assetMap);
        return new AssetRequest(asset, optionalString(body, "visualStyle", "风格描述过长"), validateSize(body));
    }

    private static AssetBatchRequest parseBatchAssets(Map<String, Object> body) {
        Object arrObj = body == null ? null : body.get("assets");
        if (!(arrObj instanceof List<?> arr)) {
            throw new IllegalArgumentException("资源列表无效");
        }
        String assetType = requiredString(body, "assetType");
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
        return new AssetBatchRequest(
                assets, optionalString(body, "visualStyle", "风格描述过长"), validateSize(body));
    }

    private static SceneRequest parseSingleScene(Map<String, Object> body) {
        Object sceneObj = body == null ? null : body.get("scene");
        if (!(sceneObj instanceof Map<?, ?> sceneMap)) {
            throw new IllegalArgumentException("场景信息无效");
        }
        return new SceneRequest(
                VideoScene.parse(sceneMap), optionalString(body, "overallStyle", "风格描述过长"), validateSize(body));
    }

    private static SceneBatchRequest parseBatchScenes(Map<String, Object> body) {
        Object scenesObj = body == null ? null : body.get("scenes");
        if (!(scenesObj instanceof List<?> sceneItems) || sceneItems.isEmpty() || sceneItems.size() > MAX_BATCH) {
            throw new IllegalArgumentException("场景数量需为 1-20");
        }
        List<VideoScene> scenes = new ArrayList<>();
        for (Object item : sceneItems) {
            if (!(item instanceof Map<?, ?> sceneMap)) {
                throw new IllegalArgumentException("场景信息无效");
            }
            scenes.add(VideoScene.parse(sceneMap));
        }
        return new SceneBatchRequest(
                List.copyOf(scenes), optionalString(body, "overallStyle", "风格描述过长"), validateSize(body));
    }

    private static String validateSize(Map<String, Object> body) {
        if (!body.containsKey("size")) {
            return "1024x1792";
        }
        String value = requiredString(body, "size");
        value = LegacyStringValidation.trim(value);
        if (!SIZES.contains(value)) {
            throw new IllegalArgumentException("图片尺寸无效");
        }
        return value;
    }

    private static String requiredString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("请求参数无效");
        }
        return string;
    }

    private static String optionalString(Map<String, Object> body, String field, String message) {
        if (!body.containsKey(field)) {
            return null;
        }
        Object value = body.get(field);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(message);
        }
        return optional(string, 500);
    }

    private static String optional(String value, int max) {
        String trimmed = value == null ? null : LegacyStringValidation.trim(value);
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > max) {
            throw new IllegalArgumentException("风格描述过长");
        }
        return trimmed;
    }

    private record AssetRequest(Asset asset, String visualStyle, String size) {}

    private record AssetBatchRequest(List<Asset> assets, String visualStyle, String size) {}

    private record SceneRequest(VideoScene scene, String overallStyle, String size) {}

    private record SceneBatchRequest(List<VideoScene> scenes, String overallStyle, String size) {}
}
