package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 门店媒体库管理端点（任务书 #42 Stage 2，D2/D3/D5/D6/D7/D10）。
 * 挂 {@code /api/organizations/{orgId}/stores/{storeId}/media}。
 *
 * <ul>
 *   <li>POST /media/upload-tickets — MANAGER+ 代开上传票据（kind→MIME/帽前置校验，ownerAccountId=操作者）。</li>
 *   <li>POST /media — MANAGER+ 绑定（每个 mediaId 经 intelligence 批量换 URL fail-closed 校验）。</li>
 *   <li>GET /media — 门店 STAFF+ / org MEMBER 回落（照 StoreController.requireStoreProfileReadable）。</li>
 *   <li>PUT /media/order — MANAGER+ 整类重排（精确集合匹配，否则 409）。</li>
 *   <li>DELETE /media/{mediaId} — MANAGER+ 解绑（只删绑定行，不删对象本体，D6）。</li>
 * </ul>
 *
 * <p>identity 没有全局 SecurityWebFilterChain：每个端点显式鉴权，跨组织由
 * {@link StoreAuthorization#ensureStoreInOrg} 统一 404。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores/{storeId}/media")
public class StoreMediaController {

    /** 单次绑定请求的 mediaId 数量帽（任务书 #42 Stage 2：1..12 个）。 */
    private static final int MAX_BIND_BATCH = 12;

    private final StoreAuthorization storeAuthz;
    private final OrgAuthorization orgAuthz;
    private final StoreMediaRepository storeMedia;
    private final StoreMediaClient mediaClient;
    private final TransactionalOperator transactions;

    public StoreMediaController(StoreAuthorization storeAuthz, OrgAuthorization orgAuthz,
                                StoreMediaRepository storeMedia, StoreMediaClient mediaClient,
                                TransactionalOperator transactions) {
        this.storeAuthz = storeAuthz;
        this.orgAuthz = orgAuthz;
        this.storeMedia = storeMedia;
        this.mediaClient = mediaClient;
        this.transactions = transactions;
    }

    /** MANAGER+ 代开门店媒体上传票据（D2）：kind→MIME/帽前置校验，ownerAccountId=操作者。 */
    @PostMapping(path = "/upload-tickets", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createUploadTicket(@PathVariable String orgId,
                                                                        @PathVariable String storeId,
                                                                        @RequestBody CreateTicketRequest body,
                                                                        ServerHttpRequest request) {
        return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
                .flatMap(account -> Mono.defer(() -> {
                    StoreMediaKind kind = StoreMediaKind.fromRequest(body == null ? null : body.kind());
                    if (body.contentType() == null || body.contentType().isBlank()) {
                        return Mono.error(new IdentityException(400, "文件类型无效"));
                    }
                    if (!kind.mimeTypes().contains(body.contentType().trim().toLowerCase(Locale.ROOT))) {
                        return Mono.error(new IdentityException(400, "该分类不支持此文件类型"));
                    }
                    if (body.sizeBytes() == null || body.sizeBytes() < 1) {
                        return Mono.error(new IdentityException(400, "文件大小无效"));
                    }
                    if (body.sizeBytes() > kind.maxBytes()) {
                        return Mono.error(new IdentityException(400, "文件大小超出限制"));
                    }
                    return mediaClient.createTicket(orgId, account.id(), storeId,
                            body.contentType().trim().toLowerCase(Locale.ROOT), body.sizeBytes());
                }))
                .map(ticket -> ResponseEntity.ok(envelope(toBody(ticket))));
    }

    /**
     * MANAGER+ 绑定媒体（D5 fail-closed）：mediaIds 1..12、UUID、去重；每个 mediaId 经
     * 批量换 URL 校验，不在返回集 → 400「媒体不可用或类型不符」（pending/他人资产/错店资产
     * 都落这）；快照 mime/size 取返回值。返回更新后整组。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> bind(@PathVariable String orgId,
                                                          @PathVariable String storeId,
                                                          @RequestBody BindMediaRequest body,
                                                          ServerHttpRequest request) {
        return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
                .flatMap(account -> Mono.defer(() -> {
                    StoreMediaKind kind = StoreMediaKind.fromRequest(body == null ? null : body.kind());
                    List<String> mediaIds = requireMediaIds(body == null ? null : body.mediaIds());
                    return mediaClient.downloadUrls(orgId, storeId, mediaIds)
                            .flatMap(resolved -> {
                                List<StoreMediaRepository.NewBinding> items = new ArrayList<>();
                                for (String mediaId : mediaIds) {
                                    ResolvedMedia media = resolved.get(mediaId);
                                    if (media == null) {
                                        return Mono.error(new IdentityException(400, "媒体不可用或类型不符"));
                                    }
                                    items.add(new StoreMediaRepository.NewBinding(
                                            mediaId, media.mimeType(), media.sizeBytes()));
                                }
                                return transactions.transactional(
                                        storeMedia.bind(orgId, storeId, kind, items, account.id()).then());
                            });
                }))
                .then(Mono.defer(() -> enriched(orgId, storeId)))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    /** 读整店绑定 + 每项 downloadUrl（fail-soft：单项被滤置 null；上游整体故障由 client 收敛为 503）。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId,
                                                          @PathVariable String storeId,
                                                          ServerHttpRequest request) {
        return requireStoreMediaReadable(request, orgId, storeId)
                .then(Mono.defer(() -> enriched(orgId, storeId)))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    /** MANAGER+ 整类重排（D10）：请求集合必须与当前集合精确相等，否则 repository 层 409；重复 id 入口即 409（不静默去重）。 */
    @PutMapping(path = "/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> reorder(@PathVariable String orgId,
                                                             @PathVariable String storeId,
                                                             @RequestBody ReorderRequest body,
                                                             ServerHttpRequest request) {
        return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
                .flatMap(account -> Mono.defer(() -> {
                    StoreMediaKind kind = StoreMediaKind.fromRequest(body == null ? null : body.kind());
                    List<String> orderedIds = requireMediaIds(body == null ? null : body.orderedMediaIds(), true);
                    return transactions.transactional(storeMedia.reorder(orgId, storeId, kind, orderedIds));
                }))
                .then(Mono.defer(() -> enriched(orgId, storeId)))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    /** MANAGER+ 解绑：只删绑定行不删对象（D6）；未绑定本店 → 404。 */
    @DeleteMapping("/{mediaId}")
    public Mono<ResponseEntity<Map<String, Object>>> unbind(@PathVariable String orgId,
                                                            @PathVariable String storeId,
                                                            @PathVariable String mediaId,
                                                            ServerHttpRequest request) {
        return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
                .flatMap(account -> Mono.defer(() -> {
                    String id = requireUuidOr404(mediaId);
                    return storeMedia.unbind(orgId, storeId, id)
                            .flatMap(deleted -> deleted
                                    ? Mono.just(ResponseEntity.ok(envelope(Map.of("deleted", true))))
                                    : Mono.error(new IdentityException(404, "媒体未绑定该门店")));
                }));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /**
     * 读取：门店任意角色（STAFF 及以上）放行；无门店角色回落 org MEMBER
     * （照 StoreController.requireStoreProfileReadable，保持组织成员既有可见性不回归）。
     */
    private Mono<Void> requireStoreMediaReadable(ServerHttpRequest request, String orgId, String storeId) {
        return storeAuthz.resolveStoreRole(request, orgId, storeId)
                .hasElement()
                .flatMap(hasStoreRole -> hasStoreRole
                        ? Mono.empty()
                        : orgAuthz.requireRole(request, orgId, MembershipRole.MEMBER).then());
    }

    /** 整店绑定 + 一次批量换 URL（fail-soft：缺席项 downloadUrl=null）；上游故障经 client 503。 */
    private Mono<Map<String, Object>> enriched(String orgId, String storeId) {
        return storeMedia.findByOrganizationAndStore(orgId, storeId)
                .collectList()
                .flatMap(bindings -> {
                    if (bindings.isEmpty()) {
                        return Mono.just(toBody(storeId, List.of(), Map.of()));
                    }
                    List<String> mediaIds = bindings.stream()
                            .map(StoreMediaBinding::mediaReferenceId).toList();
                    return mediaClient.downloadUrls(orgId, storeId, mediaIds)
                            .map(resolved -> toBody(storeId, bindings, resolved));
                });
    }

    /**
     * mediaIds 校验：非空、全 UUID、去重后 1..12 个（Jackson 3：List 元素缺失直接 400 由框架给）。
     * bind 路径静默去重（同 id 重复绑本就走 UNIQUE 409，去重不改变语义）；reorder 路径
     * {@code rejectDuplicates=true}：重复 id → 409（与 repository「缺项/多项/重复→409」口径对齐，
     * 不静默吞掉）。
     */
    private static List<String> requireMediaIds(List<String> values) {
        return requireMediaIds(values, false);
    }

    private static List<String> requireMediaIds(List<String> values, boolean rejectDuplicates) {
        if (values == null || values.isEmpty()) {
            throw new IdentityException(400, "媒体列表不能为空");
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IdentityException(400, "媒体不可用或类型不符");
            }
            String normalized;
            try {
                normalized = UUID.fromString(value.trim()).toString();
            } catch (IllegalArgumentException error) {
                throw new IdentityException(400, "媒体不可用或类型不符");
            }
            if (rejectDuplicates && !deduped.add(normalized)) {
                throw new IdentityException(409, "排序列表存在重复媒体");
            }
            deduped.add(normalized);
        }
        if (deduped.size() > MAX_BIND_BATCH) {
            throw new IdentityException(400, "一次最多绑定 12 个媒体");
        }
        return new ArrayList<>(deduped);
    }

    private static String requireUuidOr404(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(404, "媒体未绑定该门店");
        }
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    /** 白名单响应体（管理端点）：含 uploadedByAccountId/createdAt；公开端点另有严格白名单。 */
    private static Map<String, Object> toBody(String storeId, List<StoreMediaBinding> bindings,
                                              Map<String, ResolvedMedia> resolved) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeId", storeId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoreMediaBinding binding : bindings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mediaId", binding.mediaReferenceId());
            item.put("kind", binding.kind());
            item.put("mimeType", binding.mimeType());
            item.put("sizeBytes", binding.sizeBytes());
            item.put("position", binding.position());
            item.put("uploadedByAccountId", binding.uploadedByAccountId());
            item.put("createdAt", binding.createdAt() == null ? null : binding.createdAt().toString());
            ResolvedMedia media = resolved.get(binding.mediaReferenceId());
            item.put("downloadUrl", media == null ? null : media.downloadUrl());
            items.add(item);
        }
        data.put("items", items);
        return data;
    }

    private static Map<String, Object> toBody(StoreMediaUploadTicket ticket) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ticket.id() == null ? null : ticket.id().toString());
        m.put("objectKey", ticket.objectKey());
        m.put("uploadUrl", ticket.uploadUrl() == null ? null : ticket.uploadUrl().toString());
        m.put("method", ticket.method());
        m.put("headers", ticket.headers());
        m.put("expiresAt", ticket.expiresAt() == null ? null : ticket.expiresAt().toString());
        return m;
    }

    /** 开票请求（Jackson 3：数值字段用包装类型，缺失 primitive 直接 400）。 */
    public record CreateTicketRequest(String kind, String contentType, Long sizeBytes) {}

    public record BindMediaRequest(String kind, List<String> mediaIds) {}

    public record ReorderRequest(String kind, List<String> orderedMediaIds) {}
}
