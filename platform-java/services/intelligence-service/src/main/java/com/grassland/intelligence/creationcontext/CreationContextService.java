package com.grassland.intelligence.creationcontext;

import com.grassland.intelligence.ai.byok.AiProviderKeyRepository;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import com.grassland.intelligence.contentlibrary.ContentAsset;
import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videoproduction.FrozenVideoGenerationConfigResolver;
import com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Builds a complete, server-authoritative and immutable creation context. */
@Service
public class CreationContextService {
    private static final Map<String, List<String>> FORMS = Map.of(
            "xiaohongshu", List.of("graphic", "video"), "douyin", List.of("graphic", "video"),
            "dianping", List.of("graphic", "video"), "kuaishou", List.of("video"),
            "wechat-channels", List.of("video"), "bilibili", List.of("video"),
            "wechat-official", List.of("graphic"), "zhihu", List.of("graphic"),
            "moments", List.of("image-text", "video-text"));

    private final MarketplaceCreationContextClient marketplace;
    private final ContentAssetRepository assets;
    private final CreationContextSnapshotRepository snapshots;
    private final AiProviderKeyRepository keys;
    private final PlatformModelControlPlaneService models;
    private final FrozenVideoGenerationConfigResolver videoGenerationConfig;
    private final FrozenImageGenerationConfigResolver imageGenerationConfig;

    public CreationContextService(MarketplaceCreationContextClient marketplace,
                                  ContentAssetRepository assets,
                                  CreationContextSnapshotRepository snapshots,
                                  AiProviderKeyRepository keys,
                                  PlatformModelControlPlaneService models,
                                  FrozenVideoGenerationConfigResolver videoGenerationConfig,
                                  FrozenImageGenerationConfigResolver imageGenerationConfig) {
        this.marketplace = marketplace;
        this.assets = assets;
        this.snapshots = snapshots;
        this.keys = keys;
        this.models = models;
        this.videoGenerationConfig = videoGenerationConfig;
        this.imageGenerationConfig = imageGenerationConfig;
    }

    public Mono<CreationContextSnapshot> create(String accountId, CreateCreationContextRequest request) {
        if (request == null || blank(request.taskId()) || blank(request.applicationId())
                || blank(request.platformId()) || blank(request.contentFormId())) {
            return Mono.error(new IntelligenceException(400, "任务创作上下文参数不完整"));
        }
        List<UUID> materialIds;
        try {
            materialIds = (request.materialIds() == null ? List.<String>of() : request.materialIds()).stream()
                    .map(UUID::fromString).toList();
        } catch (IllegalArgumentException error) {
            return Mono.error(new IntelligenceException(400, "素材引用不合法"));
        }
        if (materialIds.size() > 50) {
            return Mono.error(new IntelligenceException(400, "一次最多冻结 50 个素材"));
        }
        if (new LinkedHashSet<>(materialIds).size() != materialIds.size()) {
            return Mono.error(new IntelligenceException(400, "素材引用不能重复"));
        }
        String platform = canonicalPlatform(request.platformId());
        String form = canonicalForm(request.contentFormId());
        if (!FORMS.containsKey(platform) || !FORMS.get(platform).contains(form)) {
            return Mono.error(new IntelligenceException(400, "平台与内容形式不匹配"));
        }
        Mono<CreationContextSnapshot> existing = request.taskVersion() == null
                ? Mono.empty()
                : snapshots.findByKey(accountId, request.applicationId(), request.taskVersion(), platform, form);
        return existing.switchIfEmpty(Mono.defer(() -> marketplace
                .fetch(request.applicationId(), request.taskId(), accountId)
                .flatMap(authoritative -> validateTask(authoritative, request, platform, form))
                .flatMap(authoritative -> assets.findForCreation(materialIds, accountId, authoritative.organizationId())
                        .collectList()
                        .flatMap(found -> found.size() == materialIds.size()
                                ? freeze(accountId, authoritative, platform, form,
                                        orderMaterials(materialIds, found))
                                : Mono.error(new IntelligenceException(403, "存在无权使用、过期或已失效的素材"))))));
    }

    private Mono<MarketplaceCreationContextClient.AuthoritativeContext> validateTask(
            MarketplaceCreationContextClient.AuthoritativeContext authoritative,
            CreateCreationContextRequest request, String platform, String form) {
        Map<String, Object> task = authoritative.taskContext();
        int version = taskVersion(task);
        if (!request.taskId().equals(String.valueOf(task.get("taskId")))
                || !request.applicationId().equals(String.valueOf(task.get("applicationId")))) {
            return Mono.error(new IntelligenceException(409, "任务上下文引用不一致"));
        }
        if (request.taskVersion() != null && request.taskVersion() != version) {
            return Mono.error(new IntelligenceException(409, "任务版本已变化，请重新进入创作"));
        }
        Object taskPlatform = task.get("platform");
        Object taskForm = task.get("contentForm");
        if (taskPlatform != null && !platform.equals(canonicalPlatform(String.valueOf(taskPlatform)))) {
            return Mono.error(new IntelligenceException(409, "目标平台与已接受任务不一致"));
        }
        if (taskForm != null && !form.equals(canonicalForm(String.valueOf(taskForm)))) {
            return Mono.error(new IntelligenceException(409, "内容形式与已接受任务不一致"));
        }
        return Mono.just(authoritative);
    }

    private Mono<CreationContextSnapshot> freeze(String accountId,
                                                   MarketplaceCreationContextClient.AuthoritativeContext authoritative,
                                                   String platform, String form, List<ContentAsset> found) {
        Map<String, Object> task = new LinkedHashMap<>(authoritative.taskContext());
        task.putIfAbsent("organizationId", authoritative.organizationId());
        int taskVersion = taskVersion(task);
        Map<String, Object> rules = PlatformCreationRuleCatalog.snapshot(platform, form);
        Map<String, Object> materialSnapshot = new LinkedHashMap<>();
        materialSnapshot.put("items", found.stream().map(CreationContextService::assetSnapshot).toList());
        return aiConfig(accountId)
                .map(config -> {
                    Map<String, Object> complete = new LinkedHashMap<>(config);
                    if ("video".equals(form)) {
                        complete.put("videoGeneration", videoGenerationConfig.snapshot());
                    }
                    if ("graphic".equals(form) || "video".equals(form)) {
                        complete.put("imageGeneration", imageGenerationConfig.snapshot());
                    }
                    return complete;
                })
                .map(config -> new CreationContextSnapshot(
                        null, accountId, authoritative.organizationId(),
                        String.valueOf(task.get("taskId")), String.valueOf(task.get("applicationId")),
                        taskVersion, platform, form, task, rules, materialSnapshot, config, null))
                .flatMap(snapshots::create);
    }

    private Mono<Map<String, Object>> aiConfig(String accountId) {
        return keys.findByPersonalAndCapability(accountId, "text")
                .map(key -> {
                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("resolutionType", "BYOK");
                    config.put("configId", key.id());
                    config.put("provider", key.provider());
                    config.put("model", key.model());
                    config.put("keyVersion", key.keyVersion());
                    config.put("maskedHint", key.maskedHint());
                    config.put("configUpdatedAt", key.updatedAt() == null ? null : key.updatedAt().toString());
                    return config;
                })
                .switchIfEmpty(Mono.defer(() -> models.resolve("text").map(optional -> optional
                        .map(CreationContextService::platformConfig)
                        .orElseGet(() -> Map.of("resolutionType", "PLATFORM", "status", "unavailable")))));
    }

    private static Map<String, Object> platformConfig(ResolvedPlatformModel model) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("resolutionType", "PLATFORM");
        config.put("provider", model.provider());
        config.put("model", model.model());
        config.put("platformModelVersion", model.version());
        config.put("modelRole", model.modelRole());
        config.put("configId", model.configId());
        return config;
    }

    private static Map<String, Object> assetSnapshot(ContentAsset asset) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetId", asset.id().toString());
        result.put("mediaReferenceId", asset.mediaReferenceId().toString());
        result.put("version", asset.version());
        result.put("category", asset.category() == null ? null : asset.category().db());
        result.put("title", asset.title());
        result.put("tags", asset.tags());
        result.put("mimeType", asset.mimeType());
        result.put("sizeBytes", asset.sizeBytes());
        result.put("validUntil", asset.validUntil());
        result.put("storeId", asset.storeId());
        return result;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static List<ContentAsset> orderMaterials(List<UUID> ids, List<ContentAsset> found) {
        Map<UUID, ContentAsset> byId = found.stream()
                .collect(Collectors.toMap(ContentAsset::id, asset -> asset));
        return ids.stream().map(byId::get).toList();
    }

    private static int taskVersion(Map<String, Object> task) {
        try {
            return Integer.parseInt(String.valueOf(task.get("taskVersion")));
        } catch (RuntimeException error) {
            throw new IntelligenceException(502, "任务上下文版本不合法");
        }
    }

    private static String canonicalPlatform(String value) {
        if (value == null) return null;
        String key = value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_+\\-/]+", "");
        return switch (key) {
            case "xiaohongshu", "xhs", "小红书" -> "xiaohongshu";
            case "douyin", "抖音" -> "douyin";
            case "dianping", "大众点评", "点评" -> "dianping";
            case "kuaishou", "快手" -> "kuaishou";
            case "wechatchannels", "视频号" -> "wechat-channels";
            case "bilibili", "b站" -> "bilibili";
            case "wechatofficial", "微信公众号", "公众号" -> "wechat-official";
            case "zhihu", "知乎" -> "zhihu";
            case "moments", "微信朋友圈", "朋友圈" -> "moments";
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String canonicalForm(String value) {
        if (value == null) return null;
        String key = value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_+\\-/]+", "");
        return switch (key) {
            case "graphic", "article", "image", "图文", "文章" -> "graphic";
            case "video", "视频", "短视频" -> "video";
            case "imagetext", "图片文字" -> "image-text";
            case "videotext", "视频文字" -> "video-text";
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }

    public record CreateCreationContextRequest(
            String taskId, String applicationId, Integer taskVersion,
            String platformId, String contentFormId, List<String> materialIds) {}
}
