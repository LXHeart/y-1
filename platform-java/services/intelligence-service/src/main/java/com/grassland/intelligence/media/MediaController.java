package com.grassland.intelligence.media;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.speech.SpeechAudioPolicy;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * media-reference 鉴权三步上传 / 签名读 / 删除（草场 Slice 8 第二步）。
 *
 * <p>
 * 对象 key 不是授权凭据：bucket 非公开，读先校验 owner，再签发短时 GET URL。非 owner 与不存在均返回 404， 避免把媒体
 * id 变成存在性探测口。仅在 {@code object-storage.enabled=true}
 * 时装配；LocalGeneratedImageStore 是文章生成图兼容兜底，不对浏览器开放通用直传。
 *
 * <p>
 * 服务间断点（草场 Slice 11 Stage
 * 1）：{@link #serviceMetadata}/{@link #serviceDownloadUrl} 仅 marketplace 服务
 * principal 可调，供履约附件中转读。附件由推荐官上传、商家经 marketplace 查看，owner-only {@link #read}
 * 无法覆盖此跨账号场景；这里以 purpose={@code engagement_attachment} 为唯一放行用途缩小暴露面，
 * active/未过期/不存在一律 404。owner 级 IDOR 守卫由 marketplace 挂接时自查（比对 metadata 返回的
 * ownerAccountId）。
 */
@RestController
@RequestMapping("/api/media")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class MediaController {

	private static final Logger log = LoggerFactory.getLogger(MediaController.class);
	private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif",
			"video/mp4", "video/quicktime", "video/webm", "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav",
			"audio/webm", "audio/ogg", "application/pdf", "text/csv");
	/**
	 * 非图片 MIME → 文件扩展名，用于推导 attachment; filename=<id>.<ext>（白名单与 ALLOWED_MIME_TYPES
	 * 同源）。
	 */
	private static final Map<String, String> EXTENSIONS = Map.ofEntries(Map.entry("video/mp4", "mp4"),
			Map.entry("video/quicktime", "mov"), Map.entry("video/webm", "webm"), Map.entry("audio/mpeg", "mp3"),
			Map.entry("audio/mp4", "m4a"), Map.entry("audio/wav", "wav"), Map.entry("audio/x-wav", "wav"),
			Map.entry("audio/webm", "webm"), Map.entry("audio/ogg", "ogg"), Map.entry("application/pdf", "pdf"),
			Map.entry("text/csv", "csv"));
	/** 服务间断点唯一放行的用途（履约附件）；其余用途经此路径一律 404，缩小暴露面。 */
	private static final String SERVICE_ATTACHMENT_PURPOSE = MediaPurpose.ENGAGEMENT_ATTACHMENT.db();
	private static final String KYB_PURPOSE = MediaPurpose.MERCHANT_KYB.db();
	/** 推荐官头像用途（任务书 #29+#30 D6）：账号级资产，仅图片 MIME + 独立大小帽。 */
	private static final String AVATAR_PURPOSE = MediaPurpose.AVATAR.db();
	private static final Set<String> AVATAR_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final long AVATAR_MAX_BYTES = 5L * 1024 * 1024;
	/** 组织品牌 Logo（#32 D5）：org 级资产，仅图片 MIME + 独立大小帽，票据只能由 identity 服务断言代开。 */
	private static final String BRAND_LOGO_PURPOSE = MediaPurpose.BRAND_LOGO.db();
	private static final Set<String> BRAND_LOGO_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
	private static final long BRAND_LOGO_MAX_BYTES = 2L * 1024 * 1024;
	private static final String SPEECH_AUDIO_PURPOSE = MediaPurpose.SPEECH_AUDIO.db();
	private static final Set<String> SPEECH_AUDIO_MIME_TYPES = Set.of("audio/mpeg", "audio/mp4", "audio/wav",
			"audio/x-wav", "audio/webm", "audio/ogg");
	private static final long SPEECH_AUDIO_MAX_BYTES = 25L * 1024 * 1024;
	/** 门店媒体库（#42 D1/D7）：org+门店级资产，图片/视频双白名单分型大小帽，票据只能由 identity 服务断言代开。 */
	private static final String STORE_MEDIA_PURPOSE = MediaPurpose.STORE_MEDIA.db();
	private static final String STORE_MEDIA_DOMAIN_TYPE = "store";
	private static final Set<String> STORE_MEDIA_IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final long STORE_MEDIA_IMAGE_MAX_BYTES = 10L * 1024 * 1024;
	private static final Set<String> STORE_MEDIA_VIDEO_MIME_TYPES = Set.of("video/mp4", "video/quicktime",
			"video/webm");
	private static final long STORE_MEDIA_VIDEO_MAX_BYTES = 20L * 1024 * 1024;
	private static final int STORE_MEDIA_MAX_DOWNLOAD_IDS = 50;
	private static final long MIN_ASSET_TTL_SECONDS = 60;
	private static final long MAX_ASSET_TTL_SECONDS = 30L * 24 * 60 * 60;
	private static final long MIN_KYB_LEASE_SECONDS = 60;
	private static final long MAX_KYB_LEASE_SECONDS = 30L * 24 * 60 * 60;
	private static final Duration MAX_KYB_SEALED_RETENTION = Duration.ofDays(3650);

	private final IntelligenceCallerResolver callers;
	private final MediaReferenceRepository mediaRefs;
	private final KybMediaRetentionRepository kybRetentions;
	private final OutboxRepository outbox;
	private final ObjectStorageAdapter storage;
	private final TransactionalOperator transactions;
	private final long uploadUrlTtlSeconds;
	private final long downloadUrlTtlSeconds;
	private final long maxObjectBytes;
	private final long maxObjectsPerOwner;
	private final long maxTotalBytesPerOwner;

	public MediaController(IntelligenceCallerResolver callers, MediaReferenceRepository mediaRefs,
			KybMediaRetentionRepository kybRetentions, OutboxRepository outbox, ObjectStorageAdapter storage,
			TransactionalOperator transactions, @Value("${media.upload-url-ttl-seconds:900}") long uploadUrlTtlSeconds,
			@Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds,
			@Value("${media.max-object-bytes:20971520}") long maxObjectBytes,
			@Value("${media.max-objects-per-owner:20}") long maxObjectsPerOwner,
			@Value("${media.max-total-bytes-per-owner:419430400}") long maxTotalBytesPerOwner) {
		this.callers = callers;
		this.mediaRefs = mediaRefs;
		this.kybRetentions = kybRetentions;
		this.outbox = outbox;
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
	public Mono<Map<String, Object>> createUploadTicket(@RequestBody CreateUploadTicketRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> createPending(caller.accountId(), caller.organizationId(), validate(body)))
				.map(MediaController::success);
	}

	/** identity 在完成组织授权后代申请 KYB 上传票据；组织上下文只取服务断言，不信请求体。 */
	@PostMapping("/kyb-upload-tickets")
	public Mono<Map<String, Object>> createKybUploadTicket(@RequestBody CreateKybUploadTicketRequest body,
			ServerWebExchange exchange) {
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> createKybPending(caller, body)).map(MediaController::success);
	}

	/** identity 完成组织授权（ADMIN+）后代申请品牌 Logo 上传票据（#32 D6）；组织上下文只取服务断言。 */
	@PostMapping("/brand-logo-upload-tickets")
	public Mono<Map<String, Object>> createBrandLogoUploadTicket(@RequestBody CreateBrandLogoUploadTicketRequest body,
			ServerWebExchange exchange) {
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> createBrandLogoPending(caller, body)).map(MediaController::success);
	}

	/**
	 * identity 校验门店 MANAGER 权限后代开门店媒体上传票据（#42 D2）；组织上下文只取服务断言，storeId 落 domain 锚。
	 */
	@PostMapping("/store-media-upload-tickets")
	public Mono<Map<String, Object>> createStoreMediaUploadTicket(@RequestBody CreateStoreMediaUploadTicketRequest body,
			ServerWebExchange exchange) {
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> createStoreMediaPending(caller, body)).map(MediaController::success);
	}

	/** 第三步：取得 finalizing 所有权，以临时 key 校验对象，再服务端写入从未暴露 PUT 权限的最终 key。 */
	@PostMapping("/{id}/confirm")
	public Mono<Map<String, Object>> confirm(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.resolve(exchange.getRequest()).flatMap(caller -> owned(mediaId, caller.accountId()))
				.flatMap(this::confirmOwned).map(MediaController::toMetadata).map(MediaController::success);
	}

	/** 授权签名读：只给 owner 的 active、未过期资产签发短时 GET URL。 */
	@GetMapping("/{id}")
	public Mono<Map<String, Object>> read(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.resolve(exchange.getRequest()).flatMap(caller -> ownedActive(mediaId, caller.accountId()))
				.map(ref -> new MediaReadResponse(
						ref.id(), ref.purpose(), ref.domainType(), ref.domainId(), ref.mimeType(), ref.sizeBytes(),
						ref.checksum(), ref.createdAt(), ref.expiresAt(), storage.presignDownload(ref.objectKey(),
								downloadTtl(ref, Instant.now()), downloadDisposition(ref))))
				.map(MediaController::success);
	}

	/** 先 claim deleting，再幂等删除最终/临时两把 key，最后写 deleted_at 审计。 */
	@DeleteMapping("/{id}")
	public Mono<Map<String, Object>> delete(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.resolve(exchange.getRequest())
				.flatMap(
						caller -> owned(mediaId, caller.accountId()).flatMap(ref -> isKyb(ref)
								? kybRetentions.isRetained(mediaId)
										.flatMap(retained -> retained
												? Mono.<MediaReference>error(
														new IntelligenceException(409, "KYB 审核证据不可删除"))
												: claimDelete(mediaId, caller.accountId()))
								: claimDelete(mediaId, caller.accountId())))
				.flatMap(this::deleteClaimed).thenReturn(success(Map.of("deleted", true)));
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
				.switchIfEmpty(notFound()).map(MediaController::success);
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

	/**
	 * 服务间断点（草场 Slice 11 Stage 1）：仅 marketplace 服务 principal 可调，返回履约附件元数据。 不做 owner
	 * 校验（principal 已受信）；以 purpose=engagement_attachment + active + 未过期为放行条件，
	 * 不符/不存在统一 404。ownerAccountId 供 marketplace 挂接时做 IDOR 守卫（owner==提交人）。
	 */
	@GetMapping("/{id}/metadata")
	public Mono<Map<String, Object>> serviceMetadata(@PathVariable String id, @RequestParam String domainType,
			@RequestParam String domainId, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.MARKETPLACE_SERVICE)
				.flatMap(caller -> serviceAttachment(mediaId, domainType, domainId))
				.map(MediaController::toServiceMetadata).map(MediaController::success);
	}

	/** identity 专用 KYB 元数据端点；返回校验所需字段，最终授权判断仍由 identity 执行。 */
	@GetMapping("/{id}/kyb-metadata")
	public Mono<Map<String, Object>> kybMetadata(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> kybEvidence(mediaId, caller.organizationId())).map(MediaController::toKybMetadata)
				.map(MediaController::success);
	}

	/** identity 专用 KYB 证据下载；与元数据端点共用 tenant/purpose/domain/state 过滤。 */
	@GetMapping("/{id}/kyb-download-url")
	public Mono<Map<String, Object>> kybDownloadUrl(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> kybEvidence(mediaId, caller.organizationId()))
				.map(ref -> new MediaServiceDownloadResponse(storage.presignDownload(ref.objectKey(),
						downloadTtl(ref, Instant.now()), downloadDisposition(ref)), ref.expiresAt()))
				.map(MediaController::success);
	}

	/**
	 * 服务间断点（草场 Slice 11 Stage 1）：仅 marketplace 服务 principal 可调，为履约附件签发短时下载 URL。 复用
	 * {@link #downloadTtl}/{@link #downloadDisposition}，与 owner-only {@link #read}
	 * 同源签名语义； {@code expiresAt} 为媒体资产本身的 TTL（与 {@link #serviceMetadata}
	 * 一致），presigned URL 自带短时过期， 超时由调用方重新请求本端点。
	 */
	@GetMapping("/{id}/download-url")
	public Mono<Map<String, Object>> serviceDownloadUrl(@PathVariable String id, @RequestParam String domainType,
			@RequestParam String domainId, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.MARKETPLACE_SERVICE)
				.flatMap(caller -> serviceAttachment(mediaId, domainType, domainId))
				.map(ref -> new MediaServiceDownloadResponse(storage.presignDownload(ref.objectKey(),
						downloadTtl(ref, Instant.now()), downloadDisposition(ref)), ref.expiresAt()))
				.map(MediaController::success);
	}

	/**
	 * identity 专用头像元数据端点（任务书 #29+#30 D6）：返回复验所需字段，归属判断仍由 identity 执行。 头像是账号级资产（无
	 * org 维度），放行条件仅 purpose=avatar + active + 未过期；不符/不存在统一 404。
	 */
	@GetMapping("/{id}/avatar-metadata")
	public Mono<Map<String, Object>> avatarMetadata(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> avatarAsset(mediaId)).map(MediaController::toAvatarMetadata)
				.map(MediaController::success);
	}

	/** identity 专用头像下载：公开/自己读头像时换短 TTL presigned GET（与元数据端点共用放行过滤）。 */
	@GetMapping("/{id}/avatar-download-url")
	public Mono<Map<String, Object>> avatarDownloadUrl(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> avatarAsset(mediaId))
				.map(ref -> new MediaServiceDownloadResponse(storage.presignDownload(ref.objectKey(),
						downloadTtl(ref, Instant.now()), downloadDisposition(ref)), ref.expiresAt()))
				.map(MediaController::success);
	}

	/** identity 专用品牌 Logo 下载/校验端点（#32 D7）：org 级四重过滤，不符/不存在统一 404。 */
	@GetMapping("/{id}/brand-logo-url")
	public Mono<Map<String, Object>> brandLogoDownloadUrl(@PathVariable String id, ServerWebExchange exchange) {
		UUID mediaId = parseId(id);
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> brandLogoAsset(mediaId,
						required(caller.organizationId(), 200, "服务断言 organizationId")))
				.map(ref -> new MediaServiceDownloadResponse(storage.presignDownload(ref.objectKey(),
						downloadTtl(ref, Instant.now()), downloadDisposition(ref)), ref.expiresAt()))
				.map(MediaController::success);
	}

	/**
	 * 门店媒体批量换 URL（#42 D5）：仅 identity 服务断言可调。四重过滤（purpose=store_media ∧
	 * organization_id=断言 org ∧ domain_type='store' ∧ domain_id=storeId ∧ active ∧
	 * 未过期）， 过滤失败的项直接不出现在响应里（子集语义，不逐项报错）；URL TTL/disposition 复用
	 * {@link #downloadTtl}/{@link #downloadDisposition}（图片内联、视频 attachment）。
	 */
	@PostMapping("/store-media-download-urls")
	public Mono<Map<String, Object>> storeMediaDownloadUrls(@RequestBody StoreMediaDownloadUrlsRequest body,
			ServerWebExchange exchange) {
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
				.flatMap(caller -> resolveStoreMediaDownloads(caller, body)).map(MediaController::success);
	}

	private Mono<UploadTicketResponse> createPending(String ownerAccountId, String organizationId, UploadSpec spec) {
		UUID id = UUID.randomUUID();
		String objectKey = "media/" + spec.purpose().db() + "/" + id;
		String uploadKey = "media-pending/" + id;
		UploadTicket ticket = storage.presignUpload(
				new PresignRequest(uploadKey, spec.contentType(), uploadUrlTtlSeconds, Map.of(), spec.sizeBytes()));
		MediaReference pending = new MediaReference(id, ownerAccountId, organizationId, spec.purpose().db(),
				spec.domainType(), spec.domainId(), objectKey, uploadKey, spec.contentType(), spec.sizeBytes(), null,
				"upload", MediaStatus.PENDING, null, spec.expiresAt(), null);
		// 单条 INSERT...SELECT 内 advisory 锁 + 配额校验，事务保证并发 ticket 串行化。
		Mono<MediaReference> reserve = mediaRefs
				.insertIfQuotaAllowed(pending, maxObjectsPerOwner, maxTotalBytesPerOwner)
				.switchIfEmpty(Mono.error(new IntelligenceException(429, "媒体配额已达上限，请先删除不再使用的媒体")));
		return transactions
				.transactional(
						reserve.flatMap(saved -> outbox.append(MediaLifecycleEvents.reserved(saved)).thenReturn(saved)))
				.map(saved -> new UploadTicketResponse(saved.id(), ticket.objectKey(), ticket.uploadUrl(),
						ticket.method(), ticket.headers(), ticket.expiresAt()));
	}

	private Mono<UploadTicketResponse> createKybPending(IntelligenceCallerResolver.Caller caller,
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
		UploadSpec spec = new UploadSpec(contentType, MediaPurpose.MERCHANT_KYB, KYB_PURPOSE, organizationId,
				body.sizeBytes(), null);
		return createPending(ownerAccountId, organizationId, spec);
	}

	/** 品牌 Logo 票据（#32 D6）：照 KYB 代开结构，D5 白名单/大小帽在开票时就拒，不落 pending。 */
	private Mono<UploadTicketResponse> createBrandLogoPending(IntelligenceCallerResolver.Caller caller,
			CreateBrandLogoUploadTicketRequest body) {
		if (body == null) {
			throw new IllegalArgumentException("品牌 Logo 上传请求不能为空");
		}
		String organizationId = required(caller.organizationId(), 200, "服务断言 organizationId");
		String ownerAccountId = required(body.ownerAccountId(), 200, "ownerAccountId");
		String contentType = normalizeMime(body.contentType());
		if (!BRAND_LOGO_MIME_TYPES.contains(contentType)) {
			throw new IllegalArgumentException("品牌 Logo 仅支持 PNG、JPEG 或 WebP 图片");
		}
		if (body.sizeBytes() == null || body.sizeBytes() < 1 || body.sizeBytes() > BRAND_LOGO_MAX_BYTES) {
			throw new IllegalArgumentException("sizeBytes 必须在 1 到 " + BRAND_LOGO_MAX_BYTES + " 之间");
		}
		UploadSpec spec = new UploadSpec(contentType, MediaPurpose.BRAND_LOGO, BRAND_LOGO_PURPOSE, organizationId,
				body.sizeBytes(), null);
		return createPending(ownerAccountId, organizationId, spec);
	}

	/** 门店媒体票据（#42 D2/D7）：照品牌 Logo 代开结构，图片/视频双白名单与分型大小帽在开票时就拒，不落 pending。 */
	private Mono<UploadTicketResponse> createStoreMediaPending(IntelligenceCallerResolver.Caller caller,
			CreateStoreMediaUploadTicketRequest body) {
		if (body == null) {
			throw new IllegalArgumentException("门店媒体上传请求不能为空");
		}
		String organizationId = required(caller.organizationId(), 200, "服务断言 organizationId");
		String ownerAccountId = required(body.ownerAccountId(), 200, "ownerAccountId");
		String storeId = requireUuid(body.storeId(), "storeId");
		String contentType = normalizeMime(body.contentType());
		long maxBytes;
		if (STORE_MEDIA_IMAGE_MIME_TYPES.contains(contentType)) {
			maxBytes = STORE_MEDIA_IMAGE_MAX_BYTES;
		} else if (STORE_MEDIA_VIDEO_MIME_TYPES.contains(contentType)) {
			maxBytes = STORE_MEDIA_VIDEO_MAX_BYTES;
		} else {
			throw new IllegalArgumentException("门店媒体仅支持 JPEG、PNG、WebP 图片或 MP4、MOV、WebM 视频");
		}
		if (body.sizeBytes() == null || body.sizeBytes() < 1 || body.sizeBytes() > maxBytes) {
			throw new IllegalArgumentException("sizeBytes 必须在 1 到 " + maxBytes + " 之间");
		}
		// domain_type='store' + domain_id=storeId + organization_id=断言 org，是批量换 URL
		// 四重过滤的锚。
		UploadSpec spec = new UploadSpec(contentType, MediaPurpose.STORE_MEDIA, STORE_MEDIA_DOMAIN_TYPE, storeId,
				body.sizeBytes(), null);
		return createPending(ownerAccountId, organizationId, spec);
	}

	/** 门店媒体批量换 URL（#42 D5）：请求校验后单条 SQL 四重过滤，逐项 presign，子集语义。 */
	private Mono<StoreMediaDownloadUrlsResponse> resolveStoreMediaDownloads(IntelligenceCallerResolver.Caller caller,
			StoreMediaDownloadUrlsRequest body) {
		if (body == null) {
			throw new IllegalArgumentException("门店媒体下载请求不能为空");
		}
		String organizationId = required(caller.organizationId(), 200, "服务断言 organizationId");
		String storeId = requireUuid(body.storeId(), "storeId");
		List<UUID> mediaIds = parseStoreMediaIds(body.mediaIds());
		Instant now = Instant.now();
		return mediaRefs.findActiveStoreMedia(mediaIds, STORE_MEDIA_PURPOSE, organizationId, storeId)
				.map(ref -> new StoreMediaDownloadItem(ref.id(), ref.mimeType(), ref.sizeBytes(),
						storage.presignDownload(ref.objectKey(), downloadTtl(ref, now), downloadDisposition(ref)),
						ref.expiresAt()))
				.collectList().map(StoreMediaDownloadUrlsResponse::new);
	}

	private Mono<MediaReference> confirmOwned(MediaReference ref) {
		if (isExpired(ref, Instant.now())) {
			return notFound();
		}
		if (ref.status() == MediaStatus.ACTIVE) {
			return Mono.just(ref);
		}
		if (ref.status() == MediaStatus.FINALIZING) {
			return mediaRefs.findById(ref.id()).filter(current -> current.status() == MediaStatus.ACTIVE)
					.switchIfEmpty(Mono.error(new IntelligenceException(409, "媒体正在确认，请稍后重试")));
		}
		if (ref.status() != MediaStatus.PENDING) {
			return notFound();
		}
		return mediaRefs.claimFinalize(ref.id()).flatMap(this::finalizeClaimed).switchIfEmpty(
				Mono.defer(() -> mediaRefs.findById(ref.id()).filter(current -> current.status() == MediaStatus.ACTIVE)
						.switchIfEmpty(Mono.error(new IntelligenceException(409, "媒体正在确认，请稍后重试")))));
	}

	private Mono<MediaReference> finalizeClaimed(MediaReference claimed) {
		if (claimed.uploadKey() == null) {
			return releaseFinalize(claimed.id()).then(Mono.error(new IntelligenceException(409, "媒体上传状态无效，请重新申请")));
		}
		return headObject(claimed.uploadKey()).switchIfEmpty(Mono.error(new IntelligenceException(404, "媒体对象不存在")))
				.flatMap(head -> validateStoredObject(claimed, head)).then(getObject(claimed.uploadKey()))
				.flatMap(bytes -> validateDownloadedBytes(claimed, bytes))
				.flatMap(bytes -> putObject(claimed.objectKey(), bytes, claimed.mimeType())
						.then(Mono.defer(() -> transactions.transactional(mediaRefs
								.completeFinalize(claimed.id(), claimed.mimeType(), bytes.length,
										MediaChecksums.sha256(bytes))
								.flatMap(active -> outbox.append(MediaLifecycleEvents.activated(active))
										.thenReturn(active)))))
						.switchIfEmpty(Mono.error(new IntelligenceException(409, "媒体状态已变化，请刷新后重试"))))
				.flatMap(active -> deleteObject(claimed.uploadKey()).onErrorResume(error -> {
					log.warn(
							"media temporary object deletion failed after confirm: "
									+ "mediaId={}, failureStage=storage_delete_after_confirm, "
									+ "exceptionType={}, errorCategory=storage_delete_failed",
							claimed.id(), exceptionType(error));
					return Mono.empty();
				}).thenReturn(active))
				.onErrorResume(error -> releaseFinalize(claimed.id()).onErrorResume(releaseError -> {
					log.warn(
							"media finalizing release failed: "
									+ "mediaId={}, failureStage=release_finalize, exceptionType={}, "
									+ "errorCategory=release_finalize_failed",
							claimed.id(), exceptionType(releaseError));
					return Mono.empty();
				}).then(Mono.error(error)));
	}

	private Mono<StoredObject> validateStoredObject(MediaReference ref, StoredObject head) {
		if (head.contentLength() != ref.sizeBytes() || head.contentLength() > maxBytesFor(ref)) {
			return discardInvalidUpload(ref, "媒体文件大小与上传凭据不一致");
		}
		if (head.contentType() == null || !ref.mimeType().equalsIgnoreCase(head.contentType())) {
			return discardInvalidUpload(ref, "媒体 MIME 与上传凭据不一致");
		}
		return Mono.just(head);
	}

	private Mono<byte[]> validateDownloadedBytes(MediaReference ref, byte[] bytes) {
		if (bytes.length != ref.sizeBytes() || bytes.length > maxBytesFor(ref)) {
			return discardInvalidUpload(ref, "媒体文件大小与上传凭据不一致").then(Mono.empty());
		}
		if (KYB_PURPOSE.equals(ref.purpose()) && !KybMediaPolicy.hasExpectedSignature(ref.mimeType(), bytes)) {
			return discardInvalidUpload(ref, "KYB 证据文件签名与 MIME 不一致").then(Mono.empty());
		}
		if (SPEECH_AUDIO_PURPOSE.equals(ref.purpose())
				&& !SpeechAudioPolicy.hasExpectedSignature(ref.mimeType(), bytes)) {
			return discardInvalidUpload(ref, "语音音频文件签名与 MIME 不一致").then(Mono.empty());
		}
		return Mono.just(bytes);
	}

	private long maxBytesFor(MediaReference ref) {
		return SPEECH_AUDIO_PURPOSE.equals(ref.purpose()) ? SPEECH_AUDIO_MAX_BYTES : maxObjectBytes;
	}

	private <T> Mono<T> discardInvalidUpload(MediaReference ref, String message) {
		return mediaRefs.claimCleanup(ref.id()).defaultIfEmpty(ref).flatMap(this::deleteClaimed)
				.then(Mono.error(new IllegalArgumentException(message)));
	}

	private Mono<MediaReference> releaseFinalize(UUID id) {
		return mediaRefs.releaseFinalize(id).then(Mono.empty());
	}

	private Mono<Void> deleteClaimed(MediaReference ref) {
		return mediaRefs.releaseQuota(ref.id()).then(deleteObject(ref.objectKey()))
				.then(deleteObjectIfPresent(ref.uploadKey()))
				.then(Mono.defer(() -> transactions.transactional(mediaRefs.completeDelete(ref.id())
						.flatMap(completed -> completed
								? outbox.append(MediaLifecycleEvents.deleted(ref, "deleted")).thenReturn(true)
								: Mono.just(false)))))
				.flatMap(completed -> completed
						? Mono.<Void>empty()
						: Mono.error(new IntelligenceException(409, "媒体状态已变化，请刷新后重试")))
				// 释放/对象删除/complete 任一失败：跳过 completeDelete，行留 deleting，交由 MediaCleanup
				// 重试整个释放+删除（quota_released 标志保证释放幂等）。此时 claimDelete 已生效，媒体对用户已 404。
				.onErrorResume(error -> {
					log.warn("media delete finalization deferred to cleanup: "
							+ "mediaId={}, failureStage=delete_finalization, exceptionType={}, "
							+ "errorCategory=delete_finalization_failed", ref.id(), exceptionType(error));
					return Mono.empty();
				});
	}

	private static String exceptionType(Throwable error) {
		String simpleName = error == null ? null : error.getClass().getSimpleName();
		return simpleName == null || simpleName.isBlank() ? "Unknown" : simpleName;
	}

	private Mono<MediaReference> owned(UUID id, String accountId) {
		return mediaRefs.findById(id).filter(ref -> accountId.equals(ref.ownerAccountId()))
				.filter(ref -> ref.status() != MediaStatus.DELETED && ref.status() != MediaStatus.DELETING)
				.switchIfEmpty(notFound());
	}

	private Mono<MediaReference> ownedActive(UUID id, String accountId) {
		return owned(id, accountId).filter(ref -> ref.status() == MediaStatus.ACTIVE)
				.filter(ref -> !isExpired(ref, Instant.now())).switchIfEmpty(notFound());
	}

	/**
	 * 服务间断点的附件过滤：仅 purpose=engagement_attachment + active + 未过期；不符/不存在统一 404。 不校验
	 * owner（principal 受信）；owner 级 IDOR 守卫在 marketplace 挂接时完成。
	 */
	private Mono<MediaReference> serviceAttachment(UUID id, String domainType, String domainId) {
		if (!"application".equals(domainType) || domainId == null || domainId.isBlank()) {
			return notFound();
		}
		return mediaRefs.findById(id).filter(ref -> SERVICE_ATTACHMENT_PURPOSE.equals(ref.purpose()))
				.filter(ref -> domainType.equals(ref.domainType())).filter(ref -> domainId.equals(ref.domainId()))
				.filter(ref -> ref.status() == MediaStatus.ACTIVE).filter(ref -> !isExpired(ref, Instant.now()))
				.switchIfEmpty(notFound());
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

	/** 头像放行过滤：purpose=avatar + active + 未过期（账号级资产，无 org/domain 维度）。 */
	private Mono<MediaReference> avatarAsset(UUID id) {
		return mediaRefs.findById(id).filter(ref -> AVATAR_PURPOSE.equals(ref.purpose()))
				.filter(ref -> ref.status() == MediaStatus.ACTIVE).filter(ref -> !isExpired(ref, Instant.now()))
				.switchIfEmpty(notFound());
	}

	/** 品牌 Logo 放行过滤（#32 D7）：org 级四重归属 + active + 未过期 + MIME 白名单；不符/不存在统一 404。 */
	private Mono<MediaReference> brandLogoAsset(UUID mediaId, String organizationId) {
		return mediaRefs.findById(mediaId)
				.filter(ref -> BRAND_LOGO_PURPOSE.equals(ref.purpose()) && organizationId.equals(ref.organizationId())
						&& BRAND_LOGO_PURPOSE.equals(ref.domainType()) && organizationId.equals(ref.domainId())
						&& ref.status() == MediaStatus.ACTIVE && BRAND_LOGO_MIME_TYPES.contains(ref.mimeType())
						&& !isExpired(ref, Instant.now()))
				.switchIfEmpty(notFound());
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

	private Mono<StoredObject> headObject(String key) {
		return Mono.fromCallable(() -> storage.headObject(key)).subscribeOn(Schedulers.boundedElastic())
				.flatMap(Mono::justOrEmpty);
	}

	private Mono<byte[]> getObject(String key) {
		return Mono.fromCallable(() -> storage.getObject(key)).subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<Void> putObject(String key, byte[] bytes, String contentType) {
		return Mono.fromRunnable(() -> storage.putObject(key, bytes, contentType))
				.subscribeOn(Schedulers.boundedElastic()).then();
	}

	private Mono<Void> deleteObject(String key) {
		return Mono.fromRunnable(() -> storage.deleteObject(key)).subscribeOn(Schedulers.boundedElastic()).then();
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
		// BRAND_LOGO（#32 D5）、STORE_MEDIA（#42 D2）与 KYB 同列黑名单：只能由 identity 服务断言代开，客户端直连一律
		// 400。
		if (purpose == null || purpose == MediaPurpose.ARTICLE_GENERATED || purpose == MediaPurpose.MERCHANT_KYB
				|| purpose == MediaPurpose.BRAND_LOGO || purpose == MediaPurpose.STORE_MEDIA) {
			throw new IllegalArgumentException("媒体用途无效");
		}
		if (purpose == MediaPurpose.AVATAR) {
			// 头像仅图片（D6）：白名单外的 MIME 与超大文件在开票时就拒，不落 pending。
			if (!AVATAR_MIME_TYPES.contains(contentType)) {
				throw new IllegalArgumentException("头像仅支持 JPEG、PNG 或 WebP 图片");
			}
			if (body.sizeBytes() != null && body.sizeBytes() > AVATAR_MAX_BYTES) {
				throw new IllegalArgumentException("头像大小不得超过 " + AVATAR_MAX_BYTES + " 字节");
			}
		}
		if (purpose == MediaPurpose.SPEECH_AUDIO && !SPEECH_AUDIO_MIME_TYPES.contains(contentType)) {
			throw new IllegalArgumentException("语音音频仅支持 MP3、M4A、WAV、WebM 或 OGG");
		}
		String domainType = optional(body.domainType(), 64, "domainType");
		String domainId = optional(body.domainId(), 200, "domainId");
		if ((domainType == null) != (domainId == null)) {
			throw new IllegalArgumentException("domainType 与 domainId 必须同时提供");
		}
		long uploadMaxBytes = purpose == MediaPurpose.SPEECH_AUDIO ? SPEECH_AUDIO_MAX_BYTES : maxObjectBytes;
		if (body.sizeBytes() == null || body.sizeBytes() < 1 || body.sizeBytes() > uploadMaxBytes) {
			throw new IllegalArgumentException("sizeBytes 必须在 1 到 " + uploadMaxBytes + " 之间");
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

	private static String required(String raw, int maxLength, String field) {
		String value = optional(raw, maxLength, field);
		if (value == null) {
			throw new IllegalArgumentException(field + " 不能为空");
		}
		return value;
	}

	/** 必填 UUID 字段：缺失/非 UUID 格式一律 400（storeId 等外部引用句柄）。返回规范化小写形式。 */
	private static String requireUuid(String raw, String field) {
		String value = required(raw, 64, field);
		try {
			return UUID.fromString(value).toString();
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException(field + " 必须是 UUID 格式");
		}
	}

	/** 批量换 URL 的 mediaIds：非空、逐项 UUID、去重后 ≤50，任一非法 400。 */
	private static List<UUID> parseStoreMediaIds(List<String> mediaIds) {
		if (mediaIds == null || mediaIds.isEmpty()) {
			throw new IllegalArgumentException("mediaIds 不能为空");
		}
		LinkedHashSet<UUID> unique = new LinkedHashSet<>();
		for (String raw : mediaIds) {
			if (raw == null || raw.isBlank()) {
				throw new IllegalArgumentException("mediaIds 含非法项");
			}
			try {
				unique.add(UUID.fromString(raw.trim()));
			} catch (IllegalArgumentException ignored) {
				throw new IllegalArgumentException("mediaIds 含非法 UUID");
			}
		}
		if (unique.size() > STORE_MEDIA_MAX_DOWNLOAD_IDS) {
			throw new IllegalArgumentException("mediaIds 去重后一次最多 " + STORE_MEDIA_MAX_DOWNLOAD_IDS + " 个");
		}
		return List.copyOf(unique);
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

	/**
	 * 非图片类型注入 attachment; filename=&lt;id&gt;.&lt;ext&gt;，强制浏览器下载；图片返回 null（内联渲染）。
	 */
	private static String downloadDisposition(MediaReference ref) {
		String mime = ref.mimeType();
		if (mime == null || mime.startsWith("image/")) {
			return null;
		}
		String ext = EXTENSIONS.get(mime);
		return "attachment; filename=\"" + ref.id() + (ext != null ? "." + ext : "") + "\"";
	}

	private static boolean isExpired(MediaReference ref, Instant now) {
		return ref.expiresAt() != null && !ref.expiresAt().isAfter(now);
	}

	private static boolean isKyb(MediaReference ref) {
		return KYB_PURPOSE.equals(ref.purpose());
	}

	private Mono<MediaReference> claimDelete(UUID mediaId, String ownerAccountId) {
		return mediaRefs.claimDelete(mediaId, ownerAccountId).switchIfEmpty(notFound());
	}

	private static <T> Mono<T> notFound() {
		return Mono.error(new IntelligenceException(404, "媒体不存在"));
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	private static MediaMetadataResponse toMetadata(MediaReference ref) {
		return new MediaMetadataResponse(ref.id(), ref.ownerAccountId(), ref.organizationId(), ref.purpose(),
				ref.domainType(), ref.domainId(), ref.mimeType(), ref.sizeBytes(), ref.checksum(), ref.source(),
				ref.status().db(), ref.createdAt(), ref.expiresAt(), ref.deletedAt());
	}

	/** 服务间断点（Slice 11 Stage 1）的附件元数据视图：仅暴露中转读所需字段，含 ownerAccountId 供 IDOR 守卫。 */
	private static MediaServiceMetadataResponse toServiceMetadata(MediaReference ref) {
		return new MediaServiceMetadataResponse(ref.id(), ref.ownerAccountId(), ref.purpose(), ref.domainType(),
				ref.domainId(), ref.status().db(), ref.checksum(), ref.mimeType(), ref.sizeBytes(), ref.expiresAt());
	}

	private static MediaKybMetadataResponse toKybMetadata(MediaReference ref) {
		return new MediaKybMetadataResponse(ref.id(), ref.ownerAccountId(), ref.organizationId(), ref.purpose(),
				ref.domainType(), ref.domainId(), ref.status().db(), ref.mimeType(), ref.sizeBytes(), ref.expiresAt());
	}

	private static MediaAvatarMetadataResponse toAvatarMetadata(MediaReference ref) {
		return new MediaAvatarMetadataResponse(ref.id(), ref.ownerAccountId(), ref.purpose(), ref.status().db(),
				ref.mimeType(), ref.sizeBytes(), ref.expiresAt());
	}

	public record CreateUploadTicketRequest(String contentType, String purpose, String domainType, String domainId,
			Long sizeBytes, Long ttlSeconds) {
	}

	public record CreateKybUploadTicketRequest(String ownerAccountId, String contentType, Long sizeBytes) {
	}

	public record CreateBrandLogoUploadTicketRequest(String ownerAccountId, String contentType, Long sizeBytes) {
	}

	/** 门店媒体代开票据请求（#42 Stage 1）；Jackson 3 可选数值字段必须包装类型。 */
	public record CreateStoreMediaUploadTicketRequest(String ownerAccountId, String storeId, String contentType,
			Long sizeBytes) {
	}

	/** 门店媒体批量换 URL 请求（#42 Stage 1）：storeId UUID、mediaIds 非空去重后 ≤50。 */
	public record StoreMediaDownloadUrlsRequest(String storeId, List<String> mediaIds) {
	}

	public record KybRetentionRequest(String referenceId) {
	}

	public record UpsertKybRetentionRequest(String referenceType, String mode, Long leaseSeconds, Instant retainUntil) {
	}

	public record UploadTicketResponse(UUID id, String objectKey, URI uploadUrl, String method,
			Map<String, String> headers, Instant expiresAt) {
	}

	public record MediaMetadataResponse(UUID id, String ownerAccountId, String organizationId, String purpose,
			String domainType, String domainId, String mimeType, long sizeBytes, String checksum, String source,
			String status, Instant createdAt, Instant expiresAt, Instant deletedAt) {
	}

	public record MediaReadResponse(UUID id, String purpose, String domainType, String domainId, String mimeType,
			long sizeBytes, String checksum, Instant createdAt, Instant expiresAt, URI downloadUrl) {
	}

	/** 服务间断点（Slice 11 Stage 1）附件元数据响应。 */
	public record MediaServiceMetadataResponse(UUID id, String ownerAccountId, String purpose, String domainType,
			String domainId, String status, String checksum, String mimeType, long sizeBytes, Instant expiresAt) {
	}

	/** identity 校验 KYB 附件所需的最小权威元数据视图。 */
	public record MediaKybMetadataResponse(UUID id, String ownerAccountId, String organizationId, String purpose,
			String domainType, String domainId, String status, String mimeType, long sizeBytes, Instant expiresAt) {
	}

	/** identity 复验推荐官头像所需的最小权威元数据视图（账号级，无 org/domain 维度）。 */
	public record MediaAvatarMetadataResponse(UUID id, String ownerAccountId, String purpose, String status,
			String mimeType, long sizeBytes, Instant expiresAt) {
	}

	/**
	 * 服务间断点（Slice 11 Stage 1）附件下载 URL 响应。{@code expiresAt} 为媒体资产 TTL，非 URL 过期时间。
	 */
	public record MediaServiceDownloadResponse(URI downloadUrl, Instant expiresAt) {
	}

	/**
	 * 门店媒体批量换 URL 单项响应（#42 D5）。{@code expiresAt} 为媒体资产 TTL，非 URL 过期时间（同
	 * {@link MediaServiceDownloadResponse} 口径）。
	 */
	public record StoreMediaDownloadItem(UUID id, String mimeType, long sizeBytes, URI downloadUrl, Instant expiresAt) {
	}

	/** 门店媒体批量换 URL 响应（#42 D5）：仅含通过四重过滤的子集。 */
	public record StoreMediaDownloadUrlsResponse(List<StoreMediaDownloadItem> items) {
	}

	private record UploadSpec(String contentType, MediaPurpose purpose, String domainType, String domainId,
			long sizeBytes, Instant expiresAt) {
	}
}
