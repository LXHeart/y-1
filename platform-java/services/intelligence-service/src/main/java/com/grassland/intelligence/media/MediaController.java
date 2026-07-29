package com.grassland.intelligence.media;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * media-reference 鉴权三步上传 / 签名读 / 删除（草场 Slice 8 第二步）。
 *
 * <p>对象 key 不是授权凭据：bucket 非公开，读先校验 owner，再签发短时 GET URL。非 owner 与不存在均返回 404，
 * 避免把媒体 id 变成存在性探测口。仅在 {@code object-storage.enabled=true} 时装配；LocalGeneratedImageStore
 * 是文章生成图兼容兜底，不对浏览器开放通用直传。
 */
@RestController
@RequestMapping("/api/media")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "video/mp4", "video/quicktime", "video/webm",
            "audio/mpeg", "audio/mp4", "audio/wav", "audio/webm",
            "application/pdf", "text/csv");
    /** 非图片 MIME → 文件扩展名，用于推导 attachment; filename=<id>.<ext>（白名单与 ALLOWED_MIME_TYPES 同源）。 */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm",
            "audio/mpeg", "mp3",
            "audio/mp4", "m4a",
            "audio/wav", "wav",
            "audio/webm", "webm",
            "application/pdf", "pdf",
            "text/csv", "csv");
    private static final long MIN_ASSET_TTL_SECONDS = 60;
    private static final long MAX_ASSET_TTL_SECONDS = 30L * 24 * 60 * 60;

    private final IntelligenceCallerResolver callers;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectStorageAdapter storage;
    private final TransactionalOperator transactions;
    private final long uploadUrlTtlSeconds;
    private final long downloadUrlTtlSeconds;
    private final long maxObjectBytes;
    private final long maxObjectsPerOwner;
    private final long maxTotalBytesPerOwner;

    public MediaController(
            IntelligenceCallerResolver callers,
            MediaReferenceRepository mediaRefs,
            ObjectStorageAdapter storage,
            TransactionalOperator transactions,
            @Value("${media.upload-url-ttl-seconds:900}") long uploadUrlTtlSeconds,
            @Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds,
            @Value("${media.max-object-bytes:20971520}") long maxObjectBytes,
            @Value("${media.max-objects-per-owner:20}") long maxObjectsPerOwner,
            @Value("${media.max-total-bytes-per-owner:419430400}") long maxTotalBytesPerOwner) {
        this.callers = callers;
        this.mediaRefs = mediaRefs;
        this.storage = storage;
        this.transactions = transactions;
        this.uploadUrlTtlSeconds = Math.max(uploadUrlTtlSeconds, 1L);
        this.downloadUrlTtlSeconds = Math.max(downloadUrlTtlSeconds, 1L);
        this.maxObjectBytes = Math.max(maxObjectBytes, 1L);
        this.maxObjectsPerOwner = Math.max(maxObjectsPerOwner, 1L);
        this.maxTotalBytesPerOwner = Math.max(maxTotalBytesPerOwner, this.maxObjectBytes);
    }

    /** 第一步：鉴权后申请短时 presigned PUT，并原子预留 owner 配额、落 pending media_reference。 */
    @PostMapping("/upload-tickets")
    public Mono<Map<String, Object>> createUploadTicket(
            @RequestBody CreateUploadTicketRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> createPending(caller, validate(body)))
                .map(MediaController::success);
    }

    /** 第三步：取得 finalizing 所有权，以临时 key 校验对象，再服务端写入从未暴露 PUT 权限的最终 key。 */
    @PostMapping("/{id}/confirm")
    public Mono<Map<String, Object>> confirm(@PathVariable String id, ServerWebExchange exchange) {
        UUID mediaId = parseId(id);
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> owned(mediaId, caller.accountId()))
                .flatMap(this::confirmOwned)
                .map(MediaController::toMetadata)
                .map(MediaController::success);
    }

    /** 授权签名读：只给 owner 的 active、未过期资产签发短时 GET URL。 */
    @GetMapping("/{id}")
    public Mono<Map<String, Object>> read(@PathVariable String id, ServerWebExchange exchange) {
        UUID mediaId = parseId(id);
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> ownedActive(mediaId, caller.accountId()))
                .map(ref -> new MediaReadResponse(
                        ref.id(), ref.purpose(), ref.domainType(), ref.domainId(), ref.mimeType(),
                        ref.sizeBytes(), ref.checksum(), ref.createdAt(), ref.expiresAt(),
                        storage.presignDownload(
                                ref.objectKey(), downloadTtl(ref, Instant.now()), downloadDisposition(ref))))
                .map(MediaController::success);
    }

    /** 先 claim deleting，再幂等删除最终/临时两把 key，最后写 deleted_at 审计。 */
    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id, ServerWebExchange exchange) {
        UUID mediaId = parseId(id);
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> mediaRefs.claimDelete(mediaId, caller.accountId())
                        .switchIfEmpty(notFound()))
                .flatMap(this::deleteClaimed)
                .thenReturn(success(Map.of("deleted", true)));
    }

    private Mono<UploadTicketResponse> createPending(
            IntelligenceCallerResolver.Caller caller, UploadSpec spec) {
        UUID id = UUID.randomUUID();
        String objectKey = "media/" + spec.purpose().db() + "/" + id;
        String uploadKey = "media-pending/" + id;
        UploadTicket ticket = storage.presignUpload(new PresignRequest(
                uploadKey, spec.contentType(), uploadUrlTtlSeconds, Map.of(), spec.sizeBytes()));
        MediaReference pending = new MediaReference(
                id, caller.accountId(), caller.organizationId(), spec.purpose().db(),
                spec.domainType(), spec.domainId(), objectKey, uploadKey, spec.contentType(),
                spec.sizeBytes(), null, "upload", MediaStatus.PENDING, null, spec.expiresAt(), null);
        // 单条 INSERT...SELECT 内 advisory 锁 + 配额校验，事务保证并发 ticket 串行化。
        Mono<MediaReference> reserve = mediaRefs.insertIfQuotaAllowed(
                        pending, maxObjectsPerOwner, maxTotalBytesPerOwner)
                .switchIfEmpty(Mono.error(new IntelligenceException(429, "媒体配额已达上限，请先删除不再使用的媒体")));
        return transactions.transactional(reserve)
                .map(saved -> new UploadTicketResponse(
                        saved.id(), ticket.objectKey(), ticket.uploadUrl(), ticket.method(),
                        ticket.headers(), ticket.expiresAt()));
    }

    private Mono<MediaReference> confirmOwned(MediaReference ref) {
        if (isExpired(ref, Instant.now())) {
            return notFound();
        }
        if (ref.status() == MediaStatus.ACTIVE) {
            return Mono.just(ref);
        }
        if (ref.status() == MediaStatus.FINALIZING) {
            return mediaRefs.findById(ref.id())
                    .filter(current -> current.status() == MediaStatus.ACTIVE)
                    .switchIfEmpty(Mono.error(new IntelligenceException(409, "媒体正在确认，请稍后重试")));
        }
        if (ref.status() != MediaStatus.PENDING) {
            return notFound();
        }
        return mediaRefs.claimFinalize(ref.id())
                .flatMap(this::finalizeClaimed)
                .switchIfEmpty(Mono.defer(() -> mediaRefs.findById(ref.id())
                        .filter(current -> current.status() == MediaStatus.ACTIVE)
                        .switchIfEmpty(Mono.error(new IntelligenceException(409, "媒体正在确认，请稍后重试")))));
    }

    private Mono<MediaReference> finalizeClaimed(MediaReference claimed) {
        if (claimed.uploadKey() == null) {
            return releaseFinalize(claimed.id())
                    .then(Mono.error(new IntelligenceException(409, "媒体上传状态无效，请重新申请")));
        }
        return headObject(claimed.uploadKey())
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "媒体对象不存在")))
                .flatMap(head -> validateStoredObject(claimed, head))
                .then(getObject(claimed.uploadKey()))
                .flatMap(bytes -> validateDownloadedBytes(claimed, bytes))
                .flatMap(bytes -> putObject(claimed.objectKey(), bytes, claimed.mimeType())
                        .then(Mono.defer(() -> mediaRefs.completeFinalize(
                                claimed.id(), claimed.mimeType(), bytes.length, MediaChecksums.sha256(bytes))))
                        .switchIfEmpty(Mono.error(new IntelligenceException(409, "媒体状态已变化，请刷新后重试"))))
                .flatMap(active -> deleteObject(claimed.uploadKey())
                        .onErrorResume(error -> {
                            log.warn("media temporary object deletion failed after confirm: mediaId={}, uploadKey={}",
                                    claimed.id(), claimed.uploadKey(), error);
                            return Mono.empty();
                        })
                        .thenReturn(active))
                .onErrorResume(error -> releaseFinalize(claimed.id())
                        .onErrorResume(releaseError -> {
                            log.warn("media finalizing release failed: mediaId={}", claimed.id(), releaseError);
                            return Mono.empty();
                        })
                        .then(Mono.error(error)));
    }

    private Mono<StoredObject> validateStoredObject(MediaReference ref, StoredObject head) {
        if (head.contentLength() != ref.sizeBytes() || head.contentLength() > maxObjectBytes) {
            return discardInvalidUpload(ref, "媒体文件大小与上传凭据不一致");
        }
        if (head.contentType() == null || !ref.mimeType().equalsIgnoreCase(head.contentType())) {
            return discardInvalidUpload(ref, "媒体 MIME 与上传凭据不一致");
        }
        return Mono.just(head);
    }

    private Mono<byte[]> validateDownloadedBytes(MediaReference ref, byte[] bytes) {
        if (bytes.length != ref.sizeBytes() || bytes.length > maxObjectBytes) {
            return discardInvalidUpload(ref, "媒体文件大小与上传凭据不一致").then(Mono.empty());
        }
        return Mono.just(bytes);
    }

    private <T> Mono<T> discardInvalidUpload(MediaReference ref, String message) {
        return mediaRefs.claimCleanup(ref.id())
                .defaultIfEmpty(ref)
                .flatMap(this::deleteClaimed)
                .then(Mono.error(new IllegalArgumentException(message)));
    }

    private Mono<MediaReference> releaseFinalize(UUID id) {
        return mediaRefs.releaseFinalize(id).then(Mono.empty());
    }

    private Mono<Void> deleteClaimed(MediaReference ref) {
        return mediaRefs.releaseQuota(ref.id())
                .then(deleteObject(ref.objectKey()))
                .then(deleteObjectIfPresent(ref.uploadKey()))
                .then(Mono.defer(() -> mediaRefs.completeDelete(ref.id())))
                .flatMap(completed -> completed
                        ? Mono.<Void>empty()
                        : Mono.error(new IntelligenceException(409, "媒体状态已变化，请刷新后重试")))
                // 释放/对象删除/complete 任一失败：跳过 completeDelete，行留 deleting，交由 MediaCleanup
                // 重试整个释放+删除（quota_released 标志保证释放幂等）。此时 claimDelete 已生效，媒体对用户已 404。
                .onErrorResume(error -> {
                    log.warn("media delete finalization deferred to cleanup: mediaId={}", ref.id(), error);
                    return Mono.empty();
                });
    }

    private Mono<MediaReference> owned(UUID id, String accountId) {
        return mediaRefs.findById(id)
                .filter(ref -> accountId.equals(ref.ownerAccountId()))
                .filter(ref -> ref.status() != MediaStatus.DELETED && ref.status() != MediaStatus.DELETING)
                .switchIfEmpty(notFound());
    }

    private Mono<MediaReference> ownedActive(UUID id, String accountId) {
        return owned(id, accountId)
                .filter(ref -> ref.status() == MediaStatus.ACTIVE)
                .filter(ref -> !isExpired(ref, Instant.now()))
                .switchIfEmpty(notFound());
    }

    private Mono<StoredObject> headObject(String key) {
        return Mono.fromCallable(() -> storage.headObject(key))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    private Mono<byte[]> getObject(String key) {
        return Mono.fromCallable(() -> storage.getObject(key))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> putObject(String key, byte[] bytes, String contentType) {
        return Mono.fromRunnable(() -> storage.putObject(key, bytes, contentType))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Void> deleteObject(String key) {
        return Mono.fromRunnable(() -> storage.deleteObject(key))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Void> deleteObjectIfPresent(String key) {
        return key == null ? Mono.empty() : deleteObject(key);
    }

    private UploadSpec validate(CreateUploadTicketRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("上传请求不能为空");
        }
        String contentType = normalizeMime(body.contentType());
        MediaPurpose purpose = MediaPurpose.fromRequest(body.purpose());
        if (purpose == null || purpose == MediaPurpose.ARTICLE_GENERATED) {
            throw new IllegalArgumentException("媒体用途无效");
        }
        String domainType = optional(body.domainType(), 64, "domainType");
        String domainId = optional(body.domainId(), 200, "domainId");
        if ((domainType == null) != (domainId == null)) {
            throw new IllegalArgumentException("domainType 与 domainId 必须同时提供");
        }
        if (body.sizeBytes() == null || body.sizeBytes() < 1 || body.sizeBytes() > maxObjectBytes) {
            throw new IllegalArgumentException("sizeBytes 必须在 1 到 " + maxObjectBytes + " 之间");
        }
        Instant expiresAt = null;
        if (body.ttlSeconds() != null) {
            long ttl = body.ttlSeconds();
            if (ttl < MIN_ASSET_TTL_SECONDS || ttl > MAX_ASSET_TTL_SECONDS) {
                throw new IllegalArgumentException("ttlSeconds 必须在 60 到 2592000 之间");
            }
            expiresAt = Instant.now().plusSeconds(ttl);
        }
        return new UploadSpec(contentType, purpose, domainType, domainId, body.sizeBytes(), expiresAt);
    }

    private static String normalizeMime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("contentType 不能为空");
        }
        String normalized = MediaType.parseMediaType(raw.trim()).toString().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MIME_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("不支持该媒体 MIME 类型");
        }
        return normalized;
    }

    private static String optional(String raw, int maxLength, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " 过长");
        }
        return value;
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            throw new IntelligenceException(404, "媒体不存在");
        }
    }

    private long downloadTtl(MediaReference ref, Instant now) {
        if (ref.expiresAt() == null) {
            return downloadUrlTtlSeconds;
        }
        long remaining = Math.max(Duration.between(now, ref.expiresAt()).toSeconds(), 1L);
        return Math.min(downloadUrlTtlSeconds, remaining);
    }

    /** 非图片类型注入 attachment; filename=&lt;id&gt;.&lt;ext&gt;，强制浏览器下载；图片返回 null（内联渲染）。 */
    private static String downloadDisposition(MediaReference ref) {
        String mime = ref.mimeType();
        if (mime == null || mime.startsWith("image/")) {
            return null;
        }
        String ext = EXTENSIONS.get(mime);
        return "attachment; filename=\"" + ref.id() + (ext != null ? "." + ext : "") + "\"";
    }

    private static boolean isExpired(MediaReference ref, Instant now) {
        return ref.expiresAt() != null && ref.expiresAt().isBefore(now);
    }

    private static <T> Mono<T> notFound() {
        return Mono.error(new IntelligenceException(404, "媒体不存在"));
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    private static MediaMetadataResponse toMetadata(MediaReference ref) {
        return new MediaMetadataResponse(
                ref.id(), ref.ownerAccountId(), ref.organizationId(), ref.purpose(),
                ref.domainType(), ref.domainId(), ref.mimeType(), ref.sizeBytes(), ref.checksum(),
                ref.source(), ref.status().db(), ref.createdAt(), ref.expiresAt(), ref.deletedAt());
    }

    public record CreateUploadTicketRequest(
            String contentType, String purpose, String domainType,
            String domainId, Long sizeBytes, Long ttlSeconds) {}

    public record UploadTicketResponse(
            UUID id, String objectKey, URI uploadUrl, String method,
            Map<String, String> headers, Instant expiresAt) {}

    public record MediaMetadataResponse(
            UUID id, String ownerAccountId, String organizationId, String purpose,
            String domainType, String domainId, String mimeType, long sizeBytes,
            String checksum, String source, String status, Instant createdAt,
            Instant expiresAt, Instant deletedAt) {}

    public record MediaReadResponse(
            UUID id, String purpose, String domainType, String domainId,
            String mimeType, long sizeBytes, String checksum, Instant createdAt,
            Instant expiresAt, URI downloadUrl) {}

    private record UploadSpec(
            String contentType, MediaPurpose purpose, String domainType,
            String domainId, long sizeBytes, Instant expiresAt) {}
}
