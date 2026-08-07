package com.grassland.intelligence.contentlibrary;

import com.grassland.intelligence.event.EventEnvelope;
import com.grassland.intelligence.event.OutboxRepository;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 内容素材库 API（草场 PRD §4.8 / Slice 14）。
 *
 * <p>三类素材库（{@link LibraryType}）共用 {@code content_asset} 表；本 Stage 1 仅实现**个人库**（personal）
 * 的完整 CRUD：任意登录用户上传/管理自己的素材，owner 级 IDOR 守卫。商家库（Stage 2，requireMerchant +
 * org 归属 + 授权表）与公共库（Stage 3，requireRole 审核 + 全员只读）在此 controller 上增量扩展。
 *
 * <p>物理资产经 intelligence 既有三步上传（{@code /api/media/upload-tickets}+{@code /confirm}，purpose=
 * {@code content_asset}）落 {@code media_reference}，本端点只做「挂接 + 业务元数据 + 下载中转」——
 * 复用履约附件的 IDOR 守卫范式（挂接时校验 media owner==提交人），不持有 MinIO 凭据写路径。
 *
 * <p>不整体 {@code @Conditional}：CRUD/编辑/历史快照不依赖对象存储，仅下载中转（download-url）需
 * {@link ObjectStorageAdapter} 签 presigned GET。storage 经 {@link ObjectProvider} 可选注入：未启对象存储时
 * 下载端点返回 503，CRUD 照常可用（与 {@code MediaController} 整体条件装配不同——素材业务层不绑死存储后端）。
 */
@RestController
@RequestMapping("/api/content-assets")
public class ContentAssetController {

    /** 个人库下载 URL 有效期（秒），与 media 默认 download-url-ttl 对齐。 */
    private static final long DOWNLOAD_URL_TTL_SECONDS = 300;
    private static final int MAX_TAGS = 20;
    private static final int MAX_TITLE_LENGTH = 120;

    private final IntelligenceCallerResolver callers;
    private final ContentAssetRepository assets;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public ContentAssetController(
            IntelligenceCallerResolver callers,
            ContentAssetRepository assets,
            MediaReferenceRepository mediaRefs,
            ObjectProvider<ObjectStorageAdapter> storageProvider,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.callers = callers;
        this.assets = assets;
        this.mediaRefs = mediaRefs;
        this.storageProvider = storageProvider;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /** 创建个人素材（挂接已 confirm 的 media_reference）。 */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestBody CreateContentAssetRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> createPersonal(caller, body))
                .map(ContentAssetController::success);
    }

    /** 列个人素材（仅自己的 active 素材，按创建倒序）。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(name = "libraryType", required = false) String libraryTypeRaw,
            ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    // Stage 1 只支持个人库列表；Stage 2/3 扩展 merchant/public。
                    LibraryType requested = libraryTypeRaw == null
                            ? LibraryType.PERSONAL : LibraryType.fromRequest(libraryTypeRaw);
                    if (requested != LibraryType.PERSONAL) {
                        return Mono.error(new IntelligenceException(400, "当前仅支持个人素材库"));
                    }
                    return assets.listPersonal(caller.accountId()).collectList();
                })
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toResponse).toList())));
    }

    /** 素材详情（owner 校验，跨账号 404）。 */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadOwned(id, caller.accountId()))
                .map(asset -> success(toResponse(asset)));
    }

    /** 列素材历史快照（owner 校验）。PRD §4.8「更新不覆盖历史快照」。 */
    @GetMapping("/{id}/versions")
    public Mono<ResponseEntity<Map<String, Object>>> versions(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadOwned(id, caller.accountId())
                        .flatMap(asset -> assets.listVersions(asset.id()).collectList()))
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toVersionResponse).toList())));
    }

    /** 编辑素材（落新 version 快照 + 乐观锁）。 */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(
            @PathVariable String id, @RequestBody UpdateContentAssetRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> editPersonal(id, caller, body))
                .map(ContentAssetController::success);
    }

    /** 软删素材（owner 校验）。 */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> deletePersonal(id, caller))
                .map(ContentAssetController::success);
    }

    /** 下载签名 URL（owner 校验 → presigned GET）。URL 短时（默认 5 分钟），不预渲染到 DOM。 */
    @GetMapping("/{id}/download-url")
    public Mono<ResponseEntity<Map<String, Object>>> downloadUrl(
            @PathVariable String id, ServerWebExchange exchange) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.error(new IntelligenceException(503, "对象存储未启用，下载暂不可用"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadOwned(id, caller.accountId())
                        .flatMap(asset -> mediaRefs.findById(asset.mediaReferenceId())
                                .filter(ref -> caller.accountId().equals(ref.ownerAccountId()))
                                .filter(ref -> ref.status() == MediaStatus.ACTIVE)
                                .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                                .map(ref -> Map.<String, Object>of(
                                        "downloadUrl", storage.presignDownload(ref.objectKey(), DOWNLOAD_URL_TTL_SECONDS).toString(),
                                        "expiresIn", DOWNLOAD_URL_TTL_SECONDS))))
                .map(ContentAssetController::success);
    }

    // ---- 个人库业务编排 ----

    private Mono<Map<String, Object>> createPersonal(Caller caller, CreateContentAssetRequest body) {
        if (body == null) {
            return Mono.error(new IntelligenceException(400, "请求体不能为空"));
        }
        AssetCategory category = AssetCategory.fromRequest(body.category());
        if (category == null) {
            return Mono.error(new IntelligenceException(400, "分类无效"));
        }
        String title = requireNonBlank(body.title(), "title");
        if (title.length() > MAX_TITLE_LENGTH) {
            return Mono.error(new IntelligenceException(400, "标题过长"));
        }
        List<String> tags = sanitizeTags(body.tags());
        UUID mediaId = parseUuid(body.mediaId(), "mediaId");
        // IDOR 守卫：取 media 元数据，校验 owner==调用者（镜像履约附件 validateAttachments）。
        return mediaRefs.findById(mediaId)
                .filter(ref -> caller.accountId().equals(ref.ownerAccountId()))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "媒体不存在")))
                .flatMap(ref -> {
                    ContentAsset asset = new ContentAsset(
                            UUID.randomUUID(), mediaId, LibraryType.PERSONAL, category,
                            caller.accountId(), null, title, tags,
                            ref.mimeType(), ref.sizeBytes(), parseInstant(body.validUntil()),
                            AssetStatus.ACTIVE,
                            1, null, null, null, null, null,
                            null, null, null);
                    return assets.create(asset)
                            .flatMap(created -> outbox.append(assetEvent(
                                    "ContentAssetCreated", created, caller.accountId(), null, null))
                                    .thenReturn(created))
                            .as(transactions::transactional);
                })
                .map(ContentAssetController::toResponse);
    }

    private Mono<Map<String, Object>> editPersonal(String id, Caller caller, UpdateContentAssetRequest body) {
        if (body == null || body.expectedVersion() == null) {
            return Mono.error(new IntelligenceException(400, "请求体或 expectedVersion 不能为空"));
        }
        AssetCategory category = AssetCategory.fromRequest(body.category());
        if (category == null) {
            return Mono.error(new IntelligenceException(400, "分类无效"));
        }
        String title = requireNonBlank(body.title(), "title");
        if (title.length() > MAX_TITLE_LENGTH) {
            return Mono.error(new IntelligenceException(400, "标题过长"));
        }
        List<String> tags = sanitizeTags(body.tags());
        UUID assetId = parseUuid(id, "id");
        // 先落旧版快照（appendVersion）再 update（version+1），两步同事务；乐观锁失败 → 409。
        return loadOwned(id, caller.accountId())
                .flatMap(current -> assets.appendVersion(current, caller.accountId())
                        .then(assets.update(assetId, body.expectedVersion(), title, tags,
                                parseInstant(body.validUntil()), category))
                        .switchIfEmpty(Mono.error(new IntelligenceException(409, "素材已被他人修改，请刷新后重试")))
                        .flatMap(updated -> outbox.append(assetEvent(
                                "ContentAssetUpdated", updated, caller.accountId(), null, null))
                                .thenReturn(updated))
                        .as(transactions::transactional))
                .map(ContentAssetController::toResponse);
    }

    private Mono<Map<String, Object>> deletePersonal(String id, Caller caller) {
        return loadOwned(id, caller.accountId())
                .flatMap(asset -> assets.softDelete(asset.id())
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                        .then(outbox.append(assetEvent(
                                "ContentAssetDeleted", asset, caller.accountId(), null, null)))
                        .thenReturn(Map.<String, Object>of("deleted", true)))
                .as(transactions::transactional);
    }

    /** 加载素材并校验 owner（跨账号/不存在统一 404，防存在性探测，同 MediaController.owned 口径）。 */
    private Mono<ContentAsset> loadOwned(String id, String accountId) {
        UUID assetId = parseUuid(id, "id");
        return assets.findById(assetId)
                .filter(asset -> accountId.equals(asset.ownerAccountId()))
                .filter(asset -> asset.deletedAt() == null)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")));
    }

    // ---- outbox 事件 ----

    private static EventEnvelope assetEvent(String eventType, ContentAsset asset, String accountId,
                                            String reviewNote, String reviewer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", asset.id().toString());
        payload.put("libraryType", asset.libraryType().db());
        payload.put("category", asset.category().db());
        payload.put("ownerAccountId", asset.ownerAccountId());
        payload.put("version", asset.version());
        if (asset.organizationId() != null) {
            payload.put("organizationId", asset.organizationId());
        }
        if (reviewNote != null) {
            payload.put("reviewNote", reviewNote);
        }
        if (reviewer != null) {
            payload.put("reviewer", reviewer);
        }
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                "content_asset",
                asset.id().toString(),
                asset.version(),
                Instant.now(),
                accountId,
                payload);
    }

    // ---- 响应序列化 ----

    private static Map<String, Object> toResponse(ContentAsset asset) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", asset.id().toString());
        map.put("mediaId", asset.mediaReferenceId().toString());
        map.put("libraryType", asset.libraryType().db());
        map.put("category", asset.category().db());
        map.put("title", asset.title());
        map.put("tags", asset.tags());
        map.put("status", asset.status().db());
        map.put("version", asset.version());
        map.put("createdAt", asset.createdAt());
        map.put("updatedAt", asset.updatedAt());
        if (asset.mimeType() != null) {
            map.put("mimeType", asset.mimeType());
        }
        if (asset.sizeBytes() != null) {
            map.put("sizeBytes", asset.sizeBytes());
        }
        if (asset.validUntil() != null) {
            map.put("validUntil", asset.validUntil());
        }
        if (asset.organizationId() != null) {
            map.put("organizationId", asset.organizationId());
        }
        if (asset.source() != null) {
            map.put("source", asset.source());
        }
        if (asset.licenseScope() != null) {
            map.put("licenseScope", asset.licenseScope());
        }
        return map;
    }

    private static Map<String, Object> toVersionResponse(ContentAssetVersion v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", v.version());
        map.put("title", v.title());
        map.put("category", v.category().db());
        map.put("tags", v.tags());
        map.put("snapshottedAt", v.snapshottedAt());
        map.put("snapshottedBy", v.snapshottedBy());
        if (v.mimeType() != null) {
            map.put("mimeType", v.mimeType());
        }
        if (v.sizeBytes() != null) {
            map.put("sizeBytes", v.sizeBytes());
        }
        if (v.validUntil() != null) {
            map.put("validUntil", v.validUntil());
        }
        return map;
    }

    private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    // ---- 校验辅助 ----

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IntelligenceException(400, field + " 不能为空");
        }
        return value.trim();
    }

    private static List<String> sanitizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> clean = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .limit(MAX_TAGS)
                .toList();
        return clean;
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            throw new IntelligenceException(400, field + " 格式无效");
        }
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            throw new IntelligenceException(400, "日期格式无效（需 ISO-8601，如 2026-12-31T23:59:59Z）");
        }
    }

    // ---- 请求 DTO ----

    public record CreateContentAssetRequest(
            String mediaId, String category, String title, List<String> tags, String validUntil) {}

    public record UpdateContentAssetRequest(
            Integer expectedVersion, String category, String title, List<String> tags, String validUntil) {}
}
