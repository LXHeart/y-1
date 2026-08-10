package com.grassland.intelligence.contentlibrary;

import com.grassland.intelligence.event.EventEnvelope;
import com.grassland.intelligence.event.OutboxRepository;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.security.IdentityStoreAuthorizationClient;
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
public class ContentAssetController {

    /** 个人库下载 URL 有效期（秒），与 media 默认 download-url-ttl 对齐。 */
    private static final long DOWNLOAD_URL_TTL_SECONDS = 300;
    private static final int MAX_TAGS = 20;
    private static final int MAX_TITLE_LENGTH = 120;

    private final IntelligenceCallerResolver callers;
    private final ContentAssetRepository assets;
    private final ContentAssetGrantRepository grants;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final IdentityStoreAuthorizationClient storeAuthorization;

    public ContentAssetController(
            IntelligenceCallerResolver callers,
            ContentAssetRepository assets,
            ContentAssetGrantRepository grants,
            MediaReferenceRepository mediaRefs,
            ObjectProvider<ObjectStorageAdapter> storageProvider,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            IdentityStoreAuthorizationClient storeAuthorization) {
        this.callers = callers;
        this.assets = assets;
        this.grants = grants;
        this.mediaRefs = mediaRefs;
        this.storageProvider = storageProvider;
        this.outbox = outbox;
        this.transactions = transactions;
        this.storeAuthorization = storeAuthorization;
    }

    /** 创建素材（个人库任意登录用户 / 商家库 requireMerchant+org）。按 body libraryType 分流。 */
    @PostMapping("/api/content-assets")
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestBody CreateContentAssetRequest body, ServerWebExchange exchange) {
        LibraryType type = body != null ? LibraryType.fromRequest(body.libraryType()) : null;
        if (type == null) {
            type = LibraryType.PERSONAL; // 默认个人库（向后兼容 Stage 1 前端不传 libraryType）
        }
        LibraryType finalType = type;
        return switch (finalType) {
            case PERSONAL -> callers.resolve(exchange.getRequest())
                    .flatMap(caller -> createAsset(caller, body, LibraryType.PERSONAL, null, null))
                    .map(ContentAssetController::success);
            case MERCHANT -> callers.requireMerchant(exchange.getRequest())
                    .filter(caller -> caller.organizationId() != null)
                    .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要商家组织身份")))
                    .flatMap(caller -> {
                        String storeId = body == null ? null : blankToNull(body.storeId());
                        Mono<Void> scopeAuthorization = storeId != null
                                ? storeAuthorization.require(caller.accountId(), caller.organizationId(),
                                        storeId, "manager")
                                : Mono.empty();
                        return scopeAuthorization.then(createAsset(caller, body, LibraryType.MERCHANT,
                                caller.organizationId(), storeId));
                    })
                    .map(ContentAssetController::success);
            case PUBLIC -> createPublic(exchange, body);
        };
    }

    /**
     * 列素材。按 {@code libraryType} 分流：
     * <ul>
     *   <li>{@code personal}（默认）— 任意登录用户，仅自己的 active 素材。</li>
     *   <li>{@code merchant} — {@code requireMerchant}，本 org 全部素材（可按 category 筛选）。</li>
     *   <li>{@code merchant} + {@code granted=true} — {@code requireRecommender}，查我被授权的商家素材。</li>
     * </ul>
     */
    @GetMapping("/api/content-assets")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(name = "libraryType", required = false) String libraryTypeRaw,
            @RequestParam(name = "category", required = false) String categoryRaw,
            @RequestParam(name = "granted", required = false) Boolean granted,
            @RequestParam(name = "storeId", required = false) String storeId,
            ServerWebExchange exchange) {
        LibraryType requested = libraryTypeRaw == null ? LibraryType.PERSONAL : LibraryType.fromRequest(libraryTypeRaw);
        if (requested == null) {
            return Mono.error(new IntelligenceException(400, "libraryType 无效"));
        }
        return switch (requested) {
            case PERSONAL -> callers.resolve(exchange.getRequest())
                    .flatMap(caller -> assets.listPersonal(caller.accountId()).collectList())
                    .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toResponse).toList())));
            case MERCHANT -> Boolean.TRUE.equals(granted)
                    ? listGrantedMerchant(exchange)
                    : listOwnMerchant(exchange, categoryRaw, storeId);
            case PUBLIC -> listPublic(categoryRaw);
        };
    }

    private Mono<ResponseEntity<Map<String, Object>>> listOwnMerchant(
            ServerWebExchange exchange, String categoryRaw, String storeId) {
        AssetCategory category = categoryRaw == null ? null : AssetCategory.fromRequest(categoryRaw);
        if (categoryRaw != null && category == null) {
            return Mono.error(new IntelligenceException(400, "category 无效"));
        }
        return callers.requireMerchant(exchange.getRequest())
                .filter(caller -> caller.organizationId() != null)
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要商家组织身份")))
                .flatMap(caller -> storeId == null || storeId.isBlank()
                        ? assets.listMerchantByOrg(caller.organizationId(), category).collectList()
                        : storeAuthorization.require(caller.accountId(), caller.organizationId(), storeId, "staff")
                                .then(assets.listMerchantByStore(
                                        caller.organizationId(), storeId, category).collectList()))
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toResponse).toList())));
    }

    private Mono<ResponseEntity<Map<String, Object>>> listGrantedMerchant(ServerWebExchange exchange) {
        return callers.requireRecommender(exchange.getRequest())
                .flatMap(caller -> grants.listGrantedAssets(caller.accountId()).collectList())
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toResponse).toList())));
    }

    /** 公共库列表（全员只读，不解析身份）。active + 未过期，可按分类筛选。 */
    private Mono<ResponseEntity<Map<String, Object>>> listPublic(String categoryRaw) {
        AssetCategory category = categoryRaw == null ? null : AssetCategory.fromRequest(categoryRaw);
        if (categoryRaw != null && category == null) {
            return Mono.error(new IntelligenceException(400, "category 无效"));
        }
        return assets.listPublic(category).collectList()
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toResponse).toList())));
    }

    /** 素材详情（个人库 owner 校验 / 商家库 org 校验 / 被授权推荐官 grant 校验）。 */
    @GetMapping("/api/content-assets/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadAccessible(id, caller))
                .map(asset -> success(toResponse(asset)));
    }

    /** 列素材历史快照（管理权限校验）。PRD §4.8「更新不覆盖历史快照」。 */
    @GetMapping("/api/content-assets/{id}/versions")
    public Mono<ResponseEntity<Map<String, Object>>> versions(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadManageable(id, caller)
                        .flatMap(asset -> assets.listVersions(asset.id()).collectList()))
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toVersionResponse).toList())));
    }

    /** 编辑素材（落新 version 快照 + 乐观锁）。 */
    @PutMapping("/api/content-assets/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(
            @PathVariable String id, @RequestBody UpdateContentAssetRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> editAsset(id, caller, body))
                .map(ContentAssetController::success);
    }

    /** 软删素材（管理权限校验）。 */
    @DeleteMapping("/api/content-assets/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> deleteAsset(id, caller))
                .map(ContentAssetController::success);
    }

    /** 下载签名 URL（可访问性校验 → presigned GET）。URL 短时（默认 5 分钟），不预渲染到 DOM。 */
    @GetMapping("/api/content-assets/{id}/download-url")
    public Mono<ResponseEntity<Map<String, Object>>> downloadUrl(
            @PathVariable String id, ServerWebExchange exchange) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.error(new IntelligenceException(503, "对象存储未启用，下载暂不可用"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadAccessible(id, caller)
                        .flatMap(asset -> mediaRefs.findById(asset.mediaReferenceId())
                                .filter(ref -> ref.status() == MediaStatus.ACTIVE)
                                .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                                .map(ref -> Map.<String, Object>of(
                                        "downloadUrl", storage.presignDownload(ref.objectKey(), DOWNLOAD_URL_TTL_SECONDS).toString(),
                                        "expiresIn", DOWNLOAD_URL_TTL_SECONDS))))
                .map(ContentAssetController::success);
    }

    /**
     * 商家授权某素材给推荐官使用（PRD §4.8「商家可以指定哪些素材允许推荐官使用」）。
     * 仅商家库素材、且调用者属该 org（loadManageable 校验）。续约幂等（GREATEST 只前进）。
     */
    @PostMapping("/api/content-assets/{id}/grants")
    public Mono<ResponseEntity<Map<String, Object>>> grant(
            @PathVariable String id, @RequestBody GrantRequest body, ServerWebExchange exchange) {
        return callers.requireMerchant(exchange.getRequest())
                .flatMap(caller -> grantAsset(id, caller, body))
                .map(ContentAssetController::success);
    }

    /** 列某素材的全部授权（商家管理用，管理权限校验）。 */
    @GetMapping("/api/content-assets/{id}/grants")
    public Mono<ResponseEntity<Map<String, Object>>> listGrants(
            @PathVariable String id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadManageable(id, caller)
                        .flatMap(asset -> grants.listGrantsForAsset(asset.id()).collectList()))
                .map(list -> success(Map.of("items", list.stream().map(ContentAssetController::toGrantResponse).toList())));
    }

    /** 撤销授权（管理权限校验）。 */
    @DeleteMapping("/api/content-assets/{id}/grants/{granteeAccountId}")
    public Mono<ResponseEntity<Map<String, Object>>> revokeGrant(
            @PathVariable String id, @PathVariable String granteeAccountId, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> loadManageable(id, caller)
                        .flatMap(asset -> grants.release(asset.id(), granteeAccountId)
                                .filter(Boolean::booleanValue)
                                .switchIfEmpty(Mono.error(new IntelligenceException(404, "授权不存在或已撤销")))
                                .thenReturn(Map.<String, Object>of("revoked", true))))
                .map(ContentAssetController::success);
    }

    // ---- 创建（个人/商家库共用编排）----

    private Mono<Map<String, Object>> createAsset(Caller caller, CreateContentAssetRequest body,
                                                   LibraryType libraryType, String organizationId, String storeId) {
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
                            UUID.randomUUID(), mediaId, libraryType, category,
                            caller.accountId(), organizationId, title, tags,
                            ref.mimeType(), ref.sizeBytes(), parseInstant(body.validUntil()),
                            AssetStatus.ACTIVE,
                            1, null, null, null, null, null,
                            null, null, null, storeId);
                    return assets.create(asset)
                            .flatMap(created -> outbox.append(assetEvent(
                                    "ContentAssetCreated", created, caller.accountId(), null, null))
                                    .thenReturn(created))
                            .as(transactions::transactional);
                })
                .map(ContentAssetController::toResponse);
    }

    /**
     * 公共库上传（运营/内容审核员，PRD §4.8「公共素材必须包含来源、授权范围和有效期」）。
     * requireRole(CONTENT_REVIEWER)（PLATFORM_ADMIN 超集）；强制 source/licenseScope/validUntil；
     * 创建即 pending_review（审核状态机，复用任务审核同款范式）。
     */
    private Mono<ResponseEntity<Map<String, Object>>> createPublic(ServerWebExchange exchange,
                                                                   CreateContentAssetRequest body) {
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
        String source = requireNonBlank(body.source(), "source");
        String licenseScope = requireNonBlank(body.licenseScope(), "licenseScope");
        Instant validUntil = parseInstant(body.validUntil());
        if (validUntil == null) {
            return Mono.error(new IntelligenceException(400, "公共素材必须指定有效期（validUntil）"));
        }
        List<String> tags = sanitizeTags(body.tags());
        UUID mediaId = parseUuid(body.mediaId(), "mediaId");
        return callers.requireRole(exchange.getRequest(), com.grassland.identity.assertion.BackendRole.CONTENT_REVIEWER)
                .flatMap(caller -> mediaRefs.findById(mediaId)
                        .filter(ref -> caller.accountId().equals(ref.ownerAccountId()))
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "媒体不存在")))
                        .flatMap(ref -> {
                            ContentAsset asset = new ContentAsset(
                                    UUID.randomUUID(), mediaId, LibraryType.PUBLIC, category,
                                    caller.accountId(), null, title, tags,
                                    ref.mimeType(), ref.sizeBytes(), validUntil,
                                    AssetStatus.PENDING_REVIEW, 1, source, licenseScope,
                                    null, null, null, null, null, null);
                            return assets.create(asset);
                        })
                        .flatMap(created -> outbox.append(assetEvent(
                                "ContentAssetSubmittedForReview", created, caller.accountId(), null, null))
                                .thenReturn(created))
                        .as(transactions::transactional))
                .map(ContentAssetController::toResponse)
                .map(ContentAssetController::success);
    }

    private Mono<Map<String, Object>> editAsset(String id, Caller caller, UpdateContentAssetRequest body) {
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
        return loadManageable(id, caller)
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

    private Mono<Map<String, Object>> deleteAsset(String id, Caller caller) {
        return loadManageable(id, caller)
                .flatMap(asset -> assets.softDelete(asset.id())
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                        .then(outbox.append(assetEvent(
                                "ContentAssetDeleted", asset, caller.accountId(), null, null)))
                        .thenReturn(Map.<String, Object>of("deleted", true)))
                .as(transactions::transactional);
    }

    /**
     * 加载素材并校验管理权限（编辑/删除用）。个人库 owner==accountId；商家库 org 匹配。
     * 跨账号/跨 org/不存在统一 404（防存在性探测，同 MediaController.owned 口径）。
     */
    private Mono<ContentAsset> loadManageable(String id, Caller caller) {
        UUID assetId = parseUuid(id, "id");
        return assets.findById(assetId)
                .filter(asset -> asset.deletedAt() == null)
                .flatMap(asset -> switch (asset.libraryType()) {
                    case PERSONAL -> caller.accountId().equals(asset.ownerAccountId())
                            ? Mono.just(asset)
                            : Mono.empty();
                    case MERCHANT -> manageableMerchantAsset(asset, caller);
                    case PUBLIC -> Mono.empty(); // 公共库管理走审核端点（Stage 3）
                })
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")));
    }

    private Mono<ContentAsset> manageableMerchantAsset(ContentAsset asset, Caller caller) {
        boolean sameOrgMerchant = asset.organizationId() != null
                && asset.organizationId().equals(caller.organizationId())
                && caller.isMerchant();
        if (!sameOrgMerchant) {
            return Mono.empty();
        }
        if (asset.storeId() == null) {
            return Mono.just(asset);
        }
        return storeAuthorization.require(caller.accountId(), asset.organizationId(), asset.storeId(), "manager")
                .thenReturn(asset);
    }

    /**
     * 加载素材并校验读取权限（详情/下载用）。三种放行：个人库 owner、商家库同 org、被授权推荐官。
     * 跨账号/无授权/不存在统一 404。
     */
    private Mono<ContentAsset> loadAccessible(String id, Caller caller) {
        UUID assetId = parseUuid(id, "id");
        return assets.findById(assetId)
                .filter(asset -> asset.deletedAt() == null)
                .filter(asset -> asset.status() == AssetStatus.ACTIVE)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                .flatMap(asset -> switch (asset.libraryType()) {
                    case PERSONAL -> caller.accountId().equals(asset.ownerAccountId())
                            ? Mono.just(asset)
                            : Mono.error(new IntelligenceException(404, "素材不存在"));
                    case MERCHANT -> canReadMerchantAsset(asset, caller);
                    case PUBLIC -> isPublicReadable(asset)
                            ? Mono.just(asset)
                            : Mono.error(new IntelligenceException(404, "素材不存在"));
                });
    }

    /** 公共库可读判定：active（loadAccessible 开头已 filter）+ 未过期。 */
    private static boolean isPublicReadable(ContentAsset asset) {
        return asset.validUntil() == null || asset.validUntil().isAfter(Instant.now());
    }

    /** 商家素材读取：组织级沿用同 org；门店级须 STAFF；否则仅显式 recommender grant 放行。 */
    private Mono<ContentAsset> canReadMerchantAsset(ContentAsset asset, Caller caller) {
        boolean sameOrg = asset.organizationId() != null
                && asset.organizationId().equals(caller.organizationId())
                && caller.isMerchant();
        if (sameOrg) {
            if (asset.storeId() == null) {
                return Mono.just(asset);
            }
            return storeAuthorization.require(caller.accountId(), asset.organizationId(), asset.storeId(), "staff")
                    .thenReturn(asset);
        }
        return grants.isGranted(asset.id(), caller.accountId())
                .flatMap(allowed -> allowed
                        ? Mono.just(asset)
                        : Mono.<ContentAsset>error(new IntelligenceException(404, "素材不存在")));
    }

    /** 商家授权推荐官（商家库素材，管理权限校验 → grantShare）。 */
    private Mono<Map<String, Object>> grantAsset(String id, Caller caller, GrantRequest body) {
        if (body == null || body.granteeAccountId() == null || body.granteeAccountId().isBlank()) {
            return Mono.error(new IntelligenceException(400, "granteeAccountId 不能为空"));
        }
        return loadManageable(id, caller)
                .filter(asset -> asset.libraryType() == LibraryType.MERCHANT)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                .flatMap(asset -> grants.grantShare(asset.id(), body.granteeAccountId().trim(),
                                caller.accountId(), ContentAssetGrantRepository.DEFAULT_LEASE)
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "素材不存在")))
                        .map(ContentAssetController::toGrantResponse));
    }

    // ---- outbox 事件 ----

    static EventEnvelope assetEvent(String eventType, ContentAsset asset, String accountId,
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
        if (asset.storeId() != null) {
            payload.put("storeId", asset.storeId());
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

    static Map<String, Object> toResponse(ContentAsset asset) {
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
        if (asset.storeId() != null) {
            map.put("storeId", asset.storeId());
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
        if (v.storeId() != null) {
            map.put("storeId", v.storeId());
        }
        return map;
    }

    private static Map<String, Object> toGrantResponse(ContentAssetGrant g) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("grantType", g.grantType());
        map.put("granteeAccountId", g.granteeAccountId());
        map.put("grantedBy", g.grantedBy());
        map.put("grantedAt", g.grantedAt());
        if (g.leaseUntil() != null) {
            map.put("leaseUntil", g.leaseUntil());
        }
        if (g.retainedUntil() != null) {
            map.put("retainedUntil", g.retainedUntil());
        }
        if (g.releasedAt() != null) {
            map.put("releasedAt", g.releasedAt());
        }
        return map;
    }

    static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    // ---- 校验辅助 ----

    static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IntelligenceException(400, field + " 不能为空");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    static UUID parseUuid(String value, String field) {
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
            String libraryType, String mediaId, String category, String title,
            List<String> tags, String validUntil, String source, String licenseScope, String storeId) {
        public CreateContentAssetRequest(String libraryType, String mediaId, String category, String title,
                                         List<String> tags, String validUntil, String source,
                                         String licenseScope) {
            this(libraryType, mediaId, category, title, tags, validUntil, source, licenseScope, null);
        }
    }

    public record UpdateContentAssetRequest(
            Integer expectedVersion, String category, String title, List<String> tags, String validUntil) {}

    /** 商家授权推荐官请求。 */
    public record GrantRequest(String granteeAccountId) {}

    /** 公共库审核请求（乐观锁 + 驳回备注）。 */
}
