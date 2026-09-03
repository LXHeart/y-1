package com.grassland.intelligence.media;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * KYB 媒体族端点（商家认证证据专属流）—— 从 {@link MediaController} 提取（任务书 #67 Card G）。
 */
@RestController
@RequestMapping("/api/media")
public class KybMediaController {

	private static final String KYB_PURPOSE = MediaPurpose.MERCHANT_KYB.db();
	private static final long MIN_KYB_LEASE_SECONDS = 60;
	private static final long MAX_KYB_LEASE_SECONDS = 30L * 24 * 60 * 60;
	private static final Duration MAX_KYB_SEALED_RETENTION = Duration.ofDays(3650);

	private final IntelligenceCallerResolver callers;
	private final MediaReferenceRepository mediaRefs;
	private final KybMediaRetentionRepository kybRetentions;
	private final ObjectStorageAdapter storage;
	private final MediaController mediaController;
	private final long downloadUrlTtlSeconds;
	private final long maxObjectBytes;

	public KybMediaController(IntelligenceCallerResolver callers, MediaReferenceRepository mediaRefs,
			KybMediaRetentionRepository kybRetentions, ObjectStorageAdapter storage, MediaController mediaController,
			@Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds,
			@Value("${media.max-object-bytes:20971520}") long maxObjectBytes) {
		this.callers = callers;
		this.mediaRefs = mediaRefs;
		this.kybRetentions = kybRetentions;
		this.storage = storage;
		this.mediaController = mediaController;
		this.downloadUrlTtlSeconds = Math.max(downloadUrlTtlSeconds, 1L);
		this.maxObjectBytes = Math.max(maxObjectBytes, 1L);
	}

	/** identity 在完成组织授权后代申请 KYB 上传票据；组织上下文只取服务断言，不信请求体。 */
	@PostMapping("/kyb-upload-tickets")
	public Mono<Map<String, Object>> createKybUploadTicket(@RequestBody CreateKybUploadTicketRequest body,
			ServerWebExchange exchange) {
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> createKybPending(caller, body)).map(KybMediaController::success);
	}

	/** identity 绑定附件或审核请求时创建留存引用。 */
	@PostMapping("/{id}/kyb-retentions")
	public Mono<Map<String, Object>> retainKyb(@PathVariable String id, @RequestBody KybRetentionRequest body,
			ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		UUID referenceId = parseId(body == null ? null : body.referenceId());
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> kybRetentions.retain(mediaId, caller.organizationId(), referenceId)
						.flatMap(retained -> retained ? Mono.just(success(Map.of("retained", true))) : notFound()));
	}

	/**
	 * Idempotent expand-contract endpoint used by identity's durable retention
	 * reconciler.
	 */
	@PutMapping("/{id}/kyb-retentions/{referenceId}")
	public Mono<Map<String, Object>> upsertKybRetention(@PathVariable String id, @PathVariable String referenceId,
			@RequestBody UpsertKybRetentionRequest body, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		UUID token = parseId(referenceId);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> applyKybRetention(mediaId, caller.organizationId(), token, body))
				.switchIfEmpty(notFound()).map(KybMediaController::success);
	}

	/** identity 删除附件或释放审核引用时释放对应 token。 */
	@DeleteMapping("/{id}/kyb-retentions/{referenceId}")
	public Mono<Map<String, Object>> releaseKyb(@PathVariable String id, @PathVariable String referenceId,
			ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		UUID token = parseId(referenceId);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> kybRetentions.release(mediaId, caller.organizationId(), token))
				.map(released -> success(Map.of("released", released)));
	}

	/** identity 专用 KYB 元数据端点；返回校验所需字段，最终授权判断仍由 identity 执行。 */
	@GetMapping("/{id}/kyb-metadata")
	public Mono<Map<String, Object>> kybMetadata(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> kybEvidence(mediaId, caller.organizationId())).map(MediaController::toKybMetadata)
				.map(KybMediaController::success);
	}

	/** identity 专用 KYB 证据下载；与元数据端点共用 tenant/purpose/domain/state 过滤。 */
	@GetMapping("/{id}/kyb-download-url")
	public Mono<Map<String, Object>> kybDownloadUrl(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> kybEvidence(mediaId, caller.organizationId()))
				.map(ref -> new MediaController.MediaServiceDownloadResponse(storage.presignDownload(ref.objectKey(),
						downloadTtl(ref, Instant.now()).toSeconds(), downloadDisposition(ref)), ref.expiresAt()))
				.map(KybMediaController::success);
	}

	private Mono<MediaController.UploadTicketResponse> createKybPending(IntelligenceCallerResolver.Caller caller,
			CreateKybUploadTicketRequest body) {
		if (body == null) {
			throw new IllegalArgumentException("KYB 上传请求不能为空");
		}
		String organizationId = required(caller.organizationId(), 200, "服务断言 organizationId");
		String ownerAccountId = required(body.ownerAccountId(), 200, "ownerAccountId");
		String contentType = normalizeMime(body.contentType());
		if (!KybMediaPolicy.isAllowedMime(contentType)) {
			throw new IllegalArgumentException("KYB 证据仅支持 JPEG、PNG 或 PDF");
		}
		if (body.sizeBytes() == null || body.sizeBytes() < 1 || body.sizeBytes() > maxObjectBytes) {
			throw new IllegalArgumentException("sizeBytes 必须在 1 到 " + maxObjectBytes + " 之间");
		}
		MediaController.UploadSpec spec = new MediaController.UploadSpec(contentType, MediaPurpose.MERCHANT_KYB,
				KYB_PURPOSE, organizationId, body.sizeBytes(), null);
		return mediaController.createPending(ownerAccountId, organizationId, spec);
	}

	private Mono<MediaReference> kybEvidence(UUID id, String organizationId) {
		if (organizationId == null || organizationId.isBlank()) {
			return notFound();
		}
		return mediaRefs.findById(id).filter(ref -> organizationId.equals(ref.organizationId()))
				.filter(ref -> KYB_PURPOSE.equals(ref.purpose())).filter(ref -> KYB_PURPOSE.equals(ref.domainType()))
				.filter(ref -> organizationId.equals(ref.domainId())).filter(ref -> ref.status() == MediaStatus.ACTIVE)
				.filter(ref -> !isExpired(ref, Instant.now())).switchIfEmpty(notFound());
	}

	private Mono<KybMediaRetentionRepository.Retention> applyKybRetention(UUID mediaId, String organizationId,
			UUID referenceId, UpsertKybRetentionRequest body) {
		if (body == null) {
			throw new IllegalArgumentException("KYB 留存请求不能为空");
		}
		String referenceType = required(body.referenceType(), 24, "referenceType");
		if (!Set.of("attachment", "review_request").contains(referenceType)) {
			throw new IllegalArgumentException("referenceType 无效");
		}
		String mode = required(body.mode(), 16, "mode");
		if ("lease".equals(mode)) {
			if (body.leaseSeconds() == null || body.leaseSeconds() < MIN_KYB_LEASE_SECONDS
					|| body.leaseSeconds() > MAX_KYB_LEASE_SECONDS) {
				throw new IllegalArgumentException("leaseSeconds 必须在 60 到 2592000 之间");
			}
			if (body.retainUntil() != null) {
				throw new IllegalArgumentException("lease 模式不能设置 retainUntil");
			}
			return kybRetentions.upsertLease(mediaId, organizationId, referenceId, referenceType,
					Duration.ofSeconds(body.leaseSeconds()));
		}
		if (!"sealed".equals(mode)) {
			throw new IllegalArgumentException("mode 无效");
		}
		Instant now = Instant.now();
		if (body.retainUntil() == null || !body.retainUntil().isAfter(now)
				|| body.retainUntil().isAfter(now.plus(MAX_KYB_SEALED_RETENTION))) {
			throw new IllegalArgumentException("retainUntil 必须在未来十年内");
		}
		if (body.leaseSeconds() != null) {
			throw new IllegalArgumentException("sealed 模式不能设置 leaseSeconds");
		}
		return kybRetentions.seal(mediaId, organizationId, referenceId, referenceType, body.retainUntil());
	}

	private Duration downloadTtl(MediaReference ref, Instant now) {
		if (ref.expiresAt() == null) {
			return Duration.ofSeconds(downloadUrlTtlSeconds);
		}
		long remaining = ref.expiresAt().getEpochSecond() - now.getEpochSecond();
		return Duration.ofSeconds(Math.min(Math.max(1, remaining), downloadUrlTtlSeconds));
	}

	private String downloadDisposition(MediaReference ref) {
		String mime = ref.mimeType();
		if (mime != null && mime.startsWith("image/")) {
			return "inline";
		}
		return "attachment";
	}

	private static boolean isExpired(MediaReference ref, Instant now) {
		return ref.expiresAt() != null && !ref.expiresAt().isAfter(now);
	}

	private static <T> Mono<T> notFound() {
		return Mono.error(new IntelligenceException(404, "媒体不存在"));
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	private static UUID parseId(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("id 不能为空");
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException error) {
			throw new IllegalArgumentException("id 格式无效");
		}
	}

	private static String required(String value, int maxLength, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " 不能为空");
		}
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(fieldName + " 长度超过 " + maxLength);
		}
		return value;
	}

	private static String normalizeMime(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType 不能为空");
		}
		String normalized = contentType.toLowerCase(java.util.Locale.ROOT).split(";")[0].strip();
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("contentType 格式无效");
		}
		return normalized;
	}
}

record CreateKybUploadTicketRequest(String ownerAccountId, String contentType, Long sizeBytes) {
}

record KybRetentionRequest(String referenceId) {
}

record UpsertKybRetentionRequest(String referenceType, String mode, Long leaseSeconds, Instant retainUntil) {
}
