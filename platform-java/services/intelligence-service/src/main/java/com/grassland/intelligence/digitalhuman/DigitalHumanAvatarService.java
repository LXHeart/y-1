package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanMediaRepository.AvatarRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanMediaRepository.CleanupRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarItem;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarSource;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 自有图片形象服务（任务书 #105F C105F-01 / 共享契约 K03 API30～API32、K09、K14.3、§9.1）。
 *
 * <p>
 * 编排顺序（先验证输入/owner/状态，再副作用）：权利声明 → 目录开关 → 媒体归属/个人/可用/MIME → 本地
 * <b>真实解码</b>（JPEG/PNG 魔数、单帧、≤4096²、炸弹防护——content-type/扩展名不作解码证明）→
 * 第三方检测/准备（{@link DigitalHumanRenderService#prepareAvatarFor}：0/多脸 422
 * dh_image_rejected、 额外收费 409）→ 事务落 dh_avatar(processing) + 派生清理登记 → 提交后异步
 * INTERNAL10 无模型规范化 （结果经 INTERNAL11 回执收口 ready/failed）。
 *
 * <p>
 * 失败语义（K04）：确定性拒绝（权利/媒体/解码/第三方 4xx）保 failed 行 + failed receipt，同键重放回原失败；
 * 网络超时/未知不落结论（operation 留 pending，重放按原键续跑，迟到 INTERNAL11 由 processing
 * 之外的状态墓碑拦截）。 删除：活动会话引用 409；无活动先 revoked（立即拒新建）再后台逐派生清（runtime INTERNAL13 +
 * 第三方远端 删除有界重试——仅删本地缓存不算清理完成）；原始 media 生命周期仍由原库决定。
 *
 * <p>
 * 网络边界可替换（runtime 控制端口 / 第三方适配 fake 承担）；DB 锁/owner/幂等键全部真实（IT 用真实 Postgres）。
 */
@Component
public class DigitalHumanAvatarService {

	/** K09：原图上界 10MiB（E22：10MiB+1 拒绝）。 */
	static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
	static final int MAX_DIMENSION = 4096;
	static final String RIGHTS_VERSION = "dh-avatar-v1";
	private static final Set<String> IMAGE_MIME = Set.of("image/jpeg", "image/png");
	private static final String PERSONAL_AVATAR_NAME = "自定义形象";
	private static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(30);

	private final DigitalHumanMediaRepository media;
	private final DigitalHumanOperations operations;
	private final DigitalHumanRenderService renders;
	private final MediaReferenceRepository mediaRefs;
	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final ObjectProvider<ObjectStorageAdapter> storage;
	private final AvatarRuntimePort runtime;

	public DigitalHumanAvatarService(DigitalHumanMediaRepository media, DigitalHumanOperations operations,
			DigitalHumanRenderService renders, MediaReferenceRepository mediaRefs, DatabaseClient db,
			TransactionalOperator transactions, ObjectProvider<ObjectStorageAdapter> storage,
			ObjectProvider<AvatarRuntimePort> runtimePorts, @Value("${dh.runtime.base-url:}") String runtimeBaseUrl) {
		this.media = media;
		this.operations = operations;
		this.renders = renders;
		this.mediaRefs = mediaRefs;
		this.db = db;
		this.transactions = transactions;
		this.storage = storage;
		this.runtime = runtimePorts.getIfAvailable(() -> DigitalHumanAvatarService.defaultRuntimePort(runtimeBaseUrl));
	}

	// ---------- runtime 控制端口（INTERNAL10/13；可替换 transport） ----------

	/** INTERNAL10 受理回执（202 {jobId,state:processing}）。 */
	public record PrepareAccepted(String jobId, String state) {
	}

	/**
	 * INTERNAL13 删除回执（{complete,remainingHandles}；complete=false 时 remaining 非空）。
	 */
	public record DeleteOutcome(boolean complete, List<String> remainingHandles) {
	}

	/** runtime 控制协议端口（生产=同 dh.runtime.base-url 的 WebClient；IT/隔离栈=fake）。 */
	public interface AvatarRuntimePort {

		Mono<PrepareAccepted> prepareAvatar(UUID avatarId, int revision, byte[] source, String sourceSha256,
				String backendId);

		Mono<DeleteOutcome> deleteAvatarObjects(UUID avatarId, int revision);
	}

	static AvatarRuntimePort defaultRuntimePort(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return new AvatarRuntimePort() {
				@Override
				public Mono<PrepareAccepted> prepareAvatar(UUID avatarId, int revision, byte[] source,
						String sourceSha256, String backendId) {
					return unavailable();
				}

				@Override
				public Mono<DeleteOutcome> deleteAvatarObjects(UUID avatarId, int revision) {
					return unavailable();
				}
			};
		}
		var client = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(baseUrl).build();
		return new AvatarRuntimePort() {
			@Override
			public Mono<PrepareAccepted> prepareAvatar(UUID avatarId, int revision, byte[] source, String sourceSha256,
					String backendId) {
				String encoded = java.util.Base64.getEncoder().encodeToString(source);
				return client.post().uri("/internal/v1/avatars/{id}/prepare", avatarId)
						.bodyValue(Map.of("commandId", UUID.randomUUID().toString(), "payloadHash", sourceSha256,
								"avatarRevision", revision, "sourceBytesBase64", encoded, "sourceSha256", sourceSha256,
								"backendId", backendId == null ? "" : backendId))
						.retrieve().bodyToMono(PrepareAccepted.class).timeout(PREPARE_TIMEOUT);
			}

			@Override
			public Mono<DeleteOutcome> deleteAvatarObjects(UUID avatarId, int revision) {
				return client.post().uri("/internal/v1/resources/{id}/delete", avatarId)
						.bodyValue(Map.of("commandId", UUID.randomUUID().toString(), "payloadHash",
								"delete-" + avatarId, "kind", "avatar", "revision", revision))
						.retrieve().bodyToMono(DeleteOutcome.class).timeout(Duration.ofSeconds(10));
			}
		};
	}

	private static <T> Mono<T> unavailable() {
		return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人运行时未配置或不可用。"));
	}

	// ---------- API30：create ----------

	/**
	 * 上传自有图片形象：202 processing（异步 INTERNAL11 收口 ready/failed）。同键同体重放返回原 AvatarItem
	 * 当前状态；确定性失败当次返回错误并留 failed 行与 failed receipt。
	 */
	public Mono<AvatarItem> create(PersonalActor actor, UUID mediaId, boolean rightsAccepted, String rightsVersion,
			UUID requestId, boolean autoDispatch) {
		if (requestId == null) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
		}
		if (!rightsAccepted || !RIGHTS_VERSION.equals(rightsVersion)) {
			return Mono.error(
					new IntelligenceException(422, "dh_invalid_input", "需勾选并确认当前版本的形象使用授权（" + RIGHTS_VERSION + "）。"));
		}
		Map<String, Object> hashFields = new java.util.TreeMap<>(
				Map.of("mediaId", mediaId.toString(), "rightsAccepted", true, "rightsVersion", rightsVersion));
		String payloadHash = DigitalHumanOperations.canonicalHash(hashFields);
		return transactions
				.transactional(operations.reserve(actor, OperationKind.avatar_create, requestId, payloadHash, null))
				.flatMap(operation -> {
					if (operation.resourceId() != null) {
						return media
								.findAvatarByOperationResource(UUID.fromString(operation.resourceId()),
										actor.accountId())
								.flatMap(this::replayAvatar)
								.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "形象不存在。")));
					}
					return prepareAndPersist(actor, mediaId, operation, autoDispatch);
				});
	}

	private Mono<AvatarItem> replayAvatar(AvatarRow row) {
		if ("deleted".equals(row.status())) {
			return Mono.error(new IntelligenceException(404, "dh_not_found", "形象不存在。"));
		}
		return Mono.just(toItem(row));
	}

	private record PreparedSource(UUID avatarId, byte[] sourceBytes, String sourceSha256, String providerResourceRef,
			List<String> compatibleBackendIds, String backendId) {
	}

	private Mono<AvatarItem> prepareAndPersist(PersonalActor actor, UUID mediaId, OperationRow operation,
			boolean autoDispatch) {
		UUID avatarId = UUID.randomUUID();
		return validateMediaAndImage(actor, mediaId)
				.flatMap(validated -> prepareRemote(actor, avatarId, mediaId, validated.bytes(), operation)
						.flatMap(prepared -> transactions
								.transactional(persistProcessingAvatar(actor, avatarId, mediaId, operation, prepared))
								.map(row -> {
									if (autoDispatch) {
										dispatchPrepare(row, prepared.sourceBytes())
												.subscribeOn(Schedulers.boundedElastic()).subscribe();
									}
									return toItem(row);
								})));
	}

	private record ValidatedMedia(byte[] bytes) {
	}

	/** 目录开关 + 媒体归属/个人/可用/MIME/大小 + 本地真实解码（无模型、确定性失败不留行）。 */
	private Mono<ValidatedMedia> validateMediaAndImage(PersonalActor actor, UUID mediaId) {
		return media.customAvatarEnabled().flatMap(enabled -> {
			if (!enabled) {
				return Mono.error(new IntelligenceException(404, "dh_feature_disabled", "自定义形象暂未开放。"));
			}
			return mediaRefs.findById(mediaId)
					.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "源媒体不存在。")))
					.flatMap(ref -> validateMedia(actor, ref).then(readBytes(ref)).map(bytes -> {
						// 真实解码（魔数/单帧/尺寸/完整性）；失败 422 dh_image_rejected。
						decodeStillImage(bytes);
						return new ValidatedMedia(bytes);
					}));
		});
	}

	private Mono<Void> validateMedia(PersonalActor actor, MediaReference ref) {
		if (!actor.accountId().equals(ref.ownerAccountId()) || ref.organizationId() != null) {
			return Mono.error(new IntelligenceException(404, "dh_not_found", "源媒体不存在。"));
		}
		if (ref.status() != MediaStatus.ACTIVE) {
			return Mono.error(reject("源媒体尚未完成上传或已不可用。"));
		}
		if (!IMAGE_MIME.contains(ref.mimeType())) {
			return Mono.error(reject("仅支持 JPEG/PNG 图片。"));
		}
		if (ref.sizeBytes() <= 0 || ref.sizeBytes() > MAX_SOURCE_BYTES) {
			return Mono.error(reject("图片大小需在 10MiB 以内。"));
		}
		return Mono.empty();
	}

	private Mono<byte[]> readBytes(MediaReference ref) {
		ObjectStorageAdapter adapter = storage.getIfAvailable();
		if (adapter == null) {
			return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "媒体存储未配置。"));
		}
		return Mono.fromCallable(() -> adapter.getObject(ref.objectKey())).subscribeOn(Schedulers.boundedElastic())
				.flatMap(bytes -> bytes == null || bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES
						? Mono.error(reject("图片内容不可读或超限。"))
						: Mono.just(bytes));
	}

	/**
	 * 第三方检测/准备。确定性拒绝（422/409）先落 failed 行+receipt 再抛原错误（同键重放回原失败，不二次 调
	 * provider）；传输/超时错误原样上抛（operation 留 pending，重放续跑——不把超时当无副作用）。
	 */
	private Mono<PreparedSource> prepareRemote(PersonalActor actor, UUID avatarId, UUID mediaId, byte[] bytes,
			OperationRow operation) {
		String sha = sha256(bytes);
		return renders
				.prepareAvatarFor(actor,
						new DigitalHumanRenderService.AvatarPrepareCommand(avatarId, actor.accountId(), bytes, sha, 1))
				.map(preparation -> new PreparedSource(avatarId, bytes, sha, preparation.providerResourceRef(),
						preparation.compatibleBackendIds(),
						preparation.compatibleBackendIds().isEmpty()
								? null
								: preparation.compatibleBackendIds().get(0)))
				.onErrorResume(error -> {
					if (!(error instanceof IntelligenceException exception) || exception.status() >= 500) {
						return Mono.error(error);
					}
					String code = exception.code();
					return transactions.transactional(persistFailedAvatar(actor, avatarId, mediaId, operation, code))
							.then(Mono.error(error));
				});
	}

	private Mono<AvatarRow> persistProcessingAvatar(PersonalActor actor, UUID avatarId, UUID mediaId,
			OperationRow operation, PreparedSource prepared) {
		String refsJson = providerRefsJson(prepared.providerResourceRef(), prepared.compatibleBackendIds());
		return media
				.insertAvatar(new AvatarRow(avatarId.toString(), actor.accountId(), mediaId.toString(), "personal",
						"processing", 1, null, RIGHTS_VERSION, Instant.now(), "rights:" + RIGHTS_VERSION, refsJson,
						null, 1, null, null))
				.flatMap(row -> media.registerCleanup(actor.accountId(), "avatar", avatarId, null, "avatar_create")
						.then(operations.attachResource(UUID.fromString(operation.id()), avatarId))
						.then(completeOperation(UUID.fromString(operation.id()), avatarId, null)).thenReturn(row));
	}

	private Mono<Void> persistFailedAvatar(PersonalActor actor, UUID avatarId, UUID mediaId, OperationRow operation,
			String errorCode) {
		return media
				.insertAvatar(new AvatarRow(avatarId.toString(), actor.accountId(), mediaId.toString(), "personal",
						"failed", 1, null, RIGHTS_VERSION, Instant.now(), "rights:" + RIGHTS_VERSION, null, errorCode,
						1, null, null))
				.then(operations.attachResource(UUID.fromString(operation.id()), avatarId))
				.then(completeOperation(UUID.fromString(operation.id()), avatarId, errorCode));
	}

	// ---------- INTERNAL11：artifact 回执 ----------

	public record ArtifactAcceptance(boolean accepted, String reason) {
	}

	/**
	 * INTERNAL11（kind=avatar）：owner 从待处理行解析（不采信 runtime 声明）。仅 processing 受理
	 * ready/failed；revoked/deleted/failed 为墓碑——迟到结果 accepted=false 且不落任何状态。manifest
	 * 逐 file 先登记清理再置终态；errorCode 携带部分 manifest 时已写对象仍登记并可清理（TC105F-01-04）。
	 */
	public Mono<ArtifactAcceptance> applyAvatarArtifact(UUID resourceId, int revision, String manifestJson,
			String errorCode) {
		if (resourceId == null) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "resourceId 必填。"));
		}
		if (manifestJson == null && errorCode == null) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "manifest 与 errorCode 至少一项。"));
		}
		return media.findAvatarById(resourceId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "形象不存在。"))).flatMap(row -> {
					if (!"processing".equals(row.status())) {
						return Mono.just(new ArtifactAcceptance(false, "avatar_" + row.status()));
					}
					if (row.revision() != revision) {
						return Mono.just(new ArtifactAcceptance(false, "revision_mismatch"));
					}
					List<String> objectRefs = manifestJson == null ? List.of() : objectRefsOf(manifestJson);
					Mono<Void> register = Mono.empty();
					for (String ref : objectRefs) {
						register = register.then(media.registerCleanup(row.ownerAccountId(), "avatar_object",
								UUID.fromString(row.id()), ref, "avatar_manifest"));
					}
					return transactions
							.transactional(register.then(media.updateAvatarStatus(resourceId,
									errorCode == null ? "ready" : "failed", errorCode, manifestJson, null)))
							.thenReturn(new ArtifactAcceptance(true, errorCode == null ? "ready" : "failed"));
				});
	}

	// ---------- API31：get ----------

	/** 非本人/不存在/已删统一 404（K04）。 */
	public Mono<AvatarItem> get(PersonalActor actor, UUID avatarId) {
		return media.findAvatarByOperationResource(avatarId, actor.accountId()).flatMap(this::replayAvatar)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "形象不存在。")));
	}

	// ---------- API32：delete ----------

	/** 删除：活动会话引用 409；无活动 revoked 立即生效，后台逐派生清（{@link #processCleanup}）。 */
	public Mono<AvatarRow> delete(PersonalActor actor, UUID avatarId, UUID requestId) {
		if (requestId == null) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
		}
		Map<String, Object> hashFields = new java.util.TreeMap<>(Map.of("avatarId", avatarId.toString()));
		String payloadHash = DigitalHumanOperations.canonicalHash(hashFields);
		return transactions
				.transactional(operations.reserve(actor, OperationKind.avatar_delete, requestId, payloadHash, null))
				.flatMap(operation -> media.findAvatarByOperationResource(avatarId, actor.accountId())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "形象不存在。")))
						.flatMap(row -> {
							if (operation.resourceId() != null) {
								// 同键重放：原终态直接返回（不重复登记清理）。
								return Mono.just(row);
							}
							if ("revoked".equals(row.status()) || "deleted".equals(row.status())) {
								// 新 requestId 对已撤销资源：幂等补齐 receipt 后返回终态。
								return transactions.transactional(
										operations.attachResource(UUID.fromString(operation.id()), avatarId).then(
												completeOperation(UUID.fromString(operation.id()), avatarId, null)))
										.thenReturn(row);
							}
							return media.hasActiveSessionReference(avatarId, actor.accountId())
									.flatMap(active -> active
											? Mono.<AvatarRow>error(new IntelligenceException(409, "dh_state_conflict",
													"形象仍被活动会话引用，无法删除。"))
											: revokeAndRegister(actor, row, operation));
						}));
	}

	private Mono<AvatarRow> revokeAndRegister(PersonalActor actor, AvatarRow row, OperationRow operation) {
		UUID avatarId = UUID.fromString(row.id());
		return transactions.transactional(media.updateAvatarStatus(avatarId, "revoked", null, null, null)
				.then(operations.attachResource(UUID.fromString(operation.id()), avatarId))
				.then(completeOperation(UUID.fromString(operation.id()), avatarId, null))
				.then(registerExternalCleanup(row))).flatMap(revoked -> {
					// 后台逐派生清（本卡真实批次方法立即尝试一次；G 定期 worker 兜底重试）。
					processCleanup(avatarId).subscribeOn(Schedulers.boundedElastic()).subscribe();
					return Mono.just(revoked);
				}).then(media.findAvatarByOperationResource(avatarId, actor.accountId()));
	}

	private Mono<Void> registerExternalCleanup(AvatarRow row) {
		String external = readProviderRef(row.providerResourceRefs());
		if (external == null) {
			return Mono.empty();
		}
		return media.registerCleanup(row.ownerAccountId(), "avatar_external", UUID.fromString(row.id()), external,
				"avatar_revoke");
	}

	/**
	 * 派生清理批次（真实对象清理，不只改 DB state）：runtime INTERNAL13 删本地派生 → 第三方远端删除
	 * （confirmed=false 记 failed+next_attempt_at 有界重试，不报假零）→ 全部收口后 avatar→deleted。原始
	 * media 引用不动（原库生命周期）。全程响应式（boundedElastic 上调度）。
	 */
	public Mono<Void> processCleanup(UUID avatarId) {
		return media.findAvatarById(avatarId).flatMap(row -> allCleanupRows(avatarId).flatMap(rows -> {
			if (rows.stream().allMatch(r -> "deleted".equals(r.state()) || "retained".equals(r.state()))) {
				return finishDeletion(avatarId);
			}
			return deleteLocalObjects(avatarId, row.revision())
					.flatMap(outcome -> deleteExternal(avatarId, row).then(finishDeletion(avatarId)));
		})).then();
	}

	private Mono<List<CleanupRow>> allCleanupRows(UUID avatarId) {
		return media.listCleanupForResource("avatar", avatarId)
				.flatMap(local -> media.listCleanupForResource("avatar_object", avatarId)
						.flatMap(objects -> media.listCleanupForResource("avatar_external", avatarId).map(external -> {
							List<CleanupRow> all = new ArrayList<>(local);
							all.addAll(objects);
							all.addAll(external);
							return all;
						})));
	}

	private Mono<DeleteOutcome> deleteLocalObjects(UUID avatarId, int revision) {
		return runtime.deleteAvatarObjects(avatarId, revision)
				.onErrorResume(error -> Mono.just(new DeleteOutcome(false, List.of("avatar:" + avatarId))))
				.flatMap(outcome -> markAvatarScope(avatarId, outcome));
	}

	/** avatar 与 avatar_object 两类登记同批收口（runtime 删的是整个沙箱目录）。 */
	private Mono<DeleteOutcome> markAvatarScope(UUID avatarId, DeleteOutcome outcome) {
		return media.listCleanupForResource("avatar", avatarId)
				.flatMap(rows -> media.listCleanupForResource("avatar_object", avatarId).map(objects -> {
					List<CleanupRow> all = new ArrayList<>(rows);
					all.addAll(objects);
					return all;
				})).flatMap(all -> {
					List<String> ids = all.stream().filter(r -> !"deleted".equals(r.state())).map(CleanupRow::id)
							.toList();
					if (outcome.complete()) {
						return media.markCleanupDeleted(ids).thenReturn(outcome);
					}
					return media.markCleanupFailed(ids, "dh_runtime_unavailable", Instant.now().plusSeconds(60))
							.thenReturn(outcome);
				});
	}

	private Mono<Void> deleteExternal(UUID avatarId, AvatarRow row) {
		String external = readProviderRef(row.providerResourceRefs());
		if (external == null) {
			return Mono.empty();
		}
		return renders.deleteRemoteAvatar(external)
				.flatMap(deletion -> deletion.confirmed()
						? markExternal(avatarId, true, null)
						: markExternal(avatarId, false, "dh_remote_delete_unconfirmed"))
				.onErrorResume(error -> markExternal(avatarId, false, errorCode(error)));
	}

	private Mono<Void> markExternal(UUID avatarId, boolean deleted, String errorCode) {
		return media.listCleanupForResource("avatar_external", avatarId).flatMap(rows -> {
			List<String> ids = rows.stream().filter(r -> !"deleted".equals(r.state())).map(CleanupRow::id).toList();
			if (deleted) {
				return media.markCleanupDeleted(ids).then();
			}
			return media.markCleanupFailed(ids, errorCode, Instant.now().plusSeconds(120)).then();
		});
	}

	private Mono<Void> finishDeletion(UUID avatarId) {
		return media.listCleanupForResource("avatar_object", avatarId)
				.flatMap(objects -> media.listCleanupForResource("avatar_external", avatarId).map(external -> {
					List<CleanupRow> all = new ArrayList<>(objects);
					all.addAll(external);
					return all;
				}))
				.flatMap(all -> all.stream()
						.noneMatch(r -> !"deleted".equals(r.state()) && !"retained".equals(r.state()))
								? media.updateAvatarStatus(avatarId, "deleted", null, null, null)
								: Mono.<Void>empty());
	}

	// ---------- INTERNAL10 异步派发 ----------

	/**
	 * 提交后异步 INTERNAL10。确定性失败（runtime 拒绝/503）保 failed；<b>超时不落结论</b>（runtime 可能
	 * 已受理，迟到 INTERNAL11 在 processing 态可正常收口——K04 不把超时当无副作用）。
	 */
	public Mono<Void> dispatchPrepare(AvatarRow row, byte[] sourceBytes) {
		String backendId = readBackendId(row.providerResourceRefs());
		return runtime
				.prepareAvatar(UUID.fromString(row.id()), row.revision(), sourceBytes, sha256(sourceBytes), backendId)
				.timeout(PREPARE_TIMEOUT).then().onErrorResume(error -> {
					if (error instanceof TimeoutException || error.getCause() instanceof TimeoutException) {
						return Mono.empty();
					}
					return media.updateAvatarStatus(UUID.fromString(row.id()), "failed", errorCode(error), null, null)
							.then();
				});
	}

	// ---------- 本地真实解码（无模型；content-type/扩展名不作证明） ----------

	/** 解码结果（真实格式与宽高）。 */
	public record DecodedStill(String format, int width, int height) {
	}

	/**
	 * JPEG/PNG 魔数 + ImageIO 真实解码：单帧、≤4096×4096、先查尺寸防炸弹再全量解码验完整性。 任何失败 → 422
	 * dh_image_rejected（不自动换脸/抠人）。
	 */
	public static DecodedStill decodeStillImage(byte[] bytes) {
		if (bytes == null || bytes.length < 8) {
			throw reject("图片内容为空。");
		}
		String format = sniffFormat(bytes);
		if (format == null) {
			throw reject("仅支持 JPEG/PNG 图片（实际内容与声明不符）。");
		}
		try (var input = new ByteArrayInputStream(bytes)) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
			if (!readers.hasNext()) {
				throw reject("无法解码的图片。");
			}
			ImageReader reader = readers.next();
			try {
				// seekForwardOnly=false：MemoryCacheImageInputStream 可回读，且 JPEG reader 禁止
				// seekForwardOnly=true 与 getNumImages(allowSearch=true) 组合。
				reader.setInput(new javax.imageio.stream.MemoryCacheImageInputStream(input), false, true);
				int images = reader.getNumImages(true);
				if (images != 1) {
					throw reject("仅支持单帧图片。");
				}
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
						|| (long) width * height > (long) MAX_DIMENSION * MAX_DIMENSION) {
					throw reject("图片尺寸需在 4096×4096 以内。");
				}
				BufferedImage decoded = reader.read(0);
				if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
					throw reject("图片内容损坏或被截断。");
				}
				return new DecodedStill(format, width, height);
			} finally {
				reader.dispose();
			}
		} catch (IntelligenceException direct) {
			throw direct;
		} catch (Exception failure) {
			throw reject("图片内容损坏或被截断。");
		}
	}

	private static String sniffFormat(byte[] bytes) {
		if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "jpeg";
		}
		if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
			return "png";
		}
		return null;
	}

	// ---------- 工具 ----------

	static IntelligenceException reject(String message) {
		return new IntelligenceException(422, "dh_image_rejected", message);
	}

	static String sha256(byte[] bytes) {
		try {
			var digest = java.security.MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}

	static String providerRefsJson(String providerResourceRef, List<String> compatibleBackendIds) {
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
			node.put("providerResourceRef", providerResourceRef);
			node.set("compatibleBackendIds", new com.fasterxml.jackson.databind.ObjectMapper()
					.valueToTree(compatibleBackendIds == null ? List.of() : compatibleBackendIds));
			return node.toString();
		} catch (Exception failure) {
			return "{\"providerResourceRef\":null,\"compatibleBackendIds\":[]}";
		}
	}

	static String readProviderRef(String providerResourceRefsJson) {
		if (providerResourceRefsJson == null || providerResourceRefsJson.isBlank()) {
			return null;
		}
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(providerResourceRefsJson);
			return node.path("providerResourceRef").isTextual() ? node.path("providerResourceRef").asText() : null;
		} catch (Exception failure) {
			return null;
		}
	}

	static String readBackendId(String providerResourceRefsJson) {
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(providerResourceRefsJson == null ? "{}" : providerResourceRefsJson);
			var backends = node.path("compatibleBackendIds");
			return backends.isArray() && !backends.isEmpty() ? backends.get(0).asText() : null;
		} catch (Exception failure) {
			return null;
		}
	}

	static List<String> objectRefsOf(String manifestJson) {
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifestJson);
			List<String> refs = new ArrayList<>();
			for (var file : node.path("files")) {
				if (file.path("objectRef").isTextual()) {
					refs.add(file.get("objectRef").asText());
				}
			}
			return List.copyOf(refs);
		} catch (Exception failure) {
			throw new IntelligenceException(422, "dh_invalid_input", "manifest 结构不合法。");
		}
	}

	static AvatarItem toItem(AvatarRow row) {
		return new AvatarItem(row.id(), row.revision(), PERSONAL_AVATAR_NAME, row.sourceMediaId(),
				AvatarSource.personal, AvatarState.valueOf(row.status()), compatibleBackends(row), row.errorCode());
	}

	private static List<String> compatibleBackends(AvatarRow row) {
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(row.providerResourceRefs() == null ? "{}" : row.providerResourceRefs());
			List<String> backends = new ArrayList<>();
			for (var item : node.path("compatibleBackendIds")) {
				backends.add(item.asText());
			}
			return List.copyOf(backends);
		} catch (Exception failure) {
			return List.of();
		}
	}

	private Mono<Void> completeOperation(UUID operationId, UUID resourceId, String errorCode) {
		var spec = db
				.sql("UPDATE dh_operation SET state = :state, result_ref = CAST(:r AS uuid),"
						+ " error_code = :errorCode, version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).bind("r", resourceId.toString())
				.bind("state", errorCode == null ? "succeeded" : "failed");
		spec = errorCode == null ? spec.bindNull("errorCode", String.class) : spec.bind("errorCode", errorCode);
		return spec.then();
	}

	static String errorCode(Throwable error) {
		return error instanceof IntelligenceException exception && exception.code() != null
				? exception.code()
				: "dh_runtime_unavailable";
	}
}
