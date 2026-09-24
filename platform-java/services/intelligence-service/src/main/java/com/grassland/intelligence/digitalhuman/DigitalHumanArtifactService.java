package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.contentlibrary.AssetCategory;
import com.grassland.intelligence.contentlibrary.AssetStatus;
import com.grassland.intelligence.contentlibrary.ContentAsset;
import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.contentlibrary.LibraryType;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactProbe.ArtifactInfo;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactProbe.Expectation;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactProbe.ProbeException;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 下载与幂等素材保存（任务书 #105F C105F-03 / K03 API36～API37、K09、K13.5）。
 *
 * <p>
 * API36 download：owner 统一 404；未完成/failed 409；过期 410；Range 仅单区间（206，非法/越界 416，
 * 不泄内部路径）。ready 段直读 runtime INTERNAL12 临时产物（sha 与 manifest 核对）；saved 段按 K13.5
 * 重新核验资产当前授权（asset 已删/终态拒绝 → 410，走原库生命周期）后从本库对象存储读永久对象。
 *
 * <p>
 * API37 save 两阶段（K13.5 save-GC 唯一先后）：事务一 reserve(recording_save) + 锁内验
 * owner/ready/未过期 + CAS saving（asset_id/subtitle_media_id 计划列 COALESCE 保留）+ 建
 * pending media 行（expires_at 对齐录制窗口；generated 直插不占 upload 配额，绕开 20MiB 上传上限承载
 * 200MiB 受控产物）；锁外真实取物 + sha256 + ffprobe（H264/AAC/时长 ±200ms；损坏/无声轨/错时长/大小不符 确定性
 * failed，同键重放回原失败）；事务二 {@link MediaReferenceRepository#claimAssetActivation}
 * 生命周期锁后新快照重验（GC 先抢到 deleting → 空返回 → save 失败不挂已删对象；save 先挂载 →
 * ASSET_REFERENCE_GUARD 令 GC 保留）→ content_asset(personal/active) +
 * dh_asset_attachment 同事务 落库 → recording saved + operation
 * succeeded(resultRef=assetId)。瞬时失败不落结论（operation 留 pending、行留
 * saving，同键重试按原计划续跑——句柄确定性派生，对象/资产各一份）。
 */
@Component
public class DigitalHumanArtifactService {

	private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final ContentAssetRepository assets;
	private final MediaReferenceRepository mediaRefs;
	private final ObjectProvider<ObjectStorageAdapter> storage;
	private final ArtifactFetchPort fetcher;

	public DigitalHumanArtifactService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations, ContentAssetRepository assets, MediaReferenceRepository mediaRefs,
			ObjectProvider<ObjectStorageAdapter> storage, ObjectProvider<ArtifactFetchPort> fetchPorts,
			@Value("${dh.runtime.base-url:}") String runtimeBaseUrl) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.assets = assets;
		this.mediaRefs = mediaRefs;
		this.storage = storage;
		this.fetcher = fetchPorts.getIfAvailable(() -> DigitalHumanArtifactService.defaultFetchPort(runtimeBaseUrl));
	}

	// ---------- INTERNAL12 受控产物读取端口（可替换 transport） ----------

	/** runtime 产物读取端口（生产=同 dh.runtime.base-url 的 WebClient；IT/隔离栈=fake）。 */
	public interface ArtifactFetchPort {

		Mono<byte[]> fetchArtifact(UUID resourceId, String objectRef);
	}

	static ArtifactFetchPort defaultFetchPort(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return (resourceId, objectRef) -> Mono
					.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
		}
		WebClient client = WebClient.builder().baseUrl(baseUrl).build();
		return (resourceId, objectRef) -> client.get()
				.uri("/internal/v1/artifacts/{resourceId}/{objectRef}", resourceId.toString(), objectRef).retrieve()
				.bodyToMono(byte[].class).timeout(FETCH_TIMEOUT)
				.onErrorMap(failure -> failure instanceof IntelligenceException
						? failure
						: new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
	}

	// ---------- 结果类型 ----------

	/** API37 返回：completedNow=false 表示同键重放已完成保存（200，同一 assetId）。 */
	public record SaveOutcome(DigitalHumanRecords.OperationDto operation, boolean completedNow) {
	}

	/** API36 返回：body 已按 Range 切片；rangeStart 为 null 表示 200 全量。 */
	public record DownloadResponse(byte[] body, String contentType, String filename, Long rangeStart, Long rangeEnd,
			long totalSize) {
	}

	record SavePlan(UUID operationId, UUID recordingId, UUID assetId, UUID videoMediaId, UUID subtitleMediaId,
			String title, boolean includeSubtitles, String videoObjectRef, String videoSha256, long durationMs,
			long sizeBytes, String subtitleObjectRef, String subtitleSha256, OperationRow replayOperation) {
	}

	record StagedArtifacts(byte[] video, String videoSha256, byte[] subtitle, String subtitleSha256) {
	}

	record ArtifactRowView(String id, String owner, String state, String manifestText, OffsetDateTime expiresAt,
			String assetId, String subtitleMediaId) {
	}

	// ---------- API37 save ----------

	public Mono<SaveOutcome> save(PersonalActor actor, UUID recordingId, UUID requestId, String title,
			boolean includeSubtitles) {
		String normalized = title == null ? "" : title.strip();
		if (normalized.isEmpty() || normalized.length() > 100) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "标题长度须为 1～100 字符。"));
		}
		return beginSave(actor, recordingId, requestId, normalized, includeSubtitles)
				.flatMap(plan -> plan.replayOperation() != null
						? Mono.just(new SaveOutcome(toDto(plan.replayOperation()), false))
						: finishSave(actor, plan));
	}

	/** 事务一：reserve + 锁内校验 + saving CAS + pending media 行（K13.5 计划列）。 */
	Mono<SavePlan> beginSave(PersonalActor actor, UUID recordingId, UUID requestId, String title,
			boolean includeSubtitles) {
		Mono<SavePlan> transactional = operations.reserve(actor, OperationKind.recording_save, requestId,
				DigitalHumanOperations.canonicalHash(Map.of("recordingId", recordingId.toString(), "title", title,
						"includeSubtitles", includeSubtitles)),
				recordingId).flatMap(op -> {
					if (op.state() == OperationState.succeeded && op.resultRef() != null) {
						return Mono.just(new SavePlan(UUID.fromString(op.id()), recordingId, null, null, null, title,
								includeSubtitles, null, null, 0, 0, null, null, op));
					}
					if (op.state() == OperationState.failed) {
						return Mono.error(new IntelligenceException(409,
								op.errorCode() == null ? "dh_state_conflict" : op.errorCode(), "该请求号的保存已失败，请刷新后重试。"));
					}
					return findRowForUpdate(actor, recordingId)
							.flatMap(row -> planAndClaim(actor, row, op, recordingId, title, includeSubtitles));
				});
		return transactions.transactional(transactional);
	}

	private Mono<SavePlan> planAndClaim(PersonalActor actor, ArtifactRowView row, OperationRow op, UUID recordingId,
			String title, boolean includeSubtitles) {
		switch (row.state()) {
			case "saved" -> {
				return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段已保存。"));
			}
			case "recording", "finalizing" -> {
				return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制尚未完成，不能保存。"));
			}
			case "failed" -> {
				return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段已失败，不能保存。"));
			}
			case "expired", "deleted" -> {
				return Mono.error(new IntelligenceException(410, "dh_recording_expired", "录制段已过期。"));
			}
			case "ready", "saving" -> {
			}
			default -> {
				return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段状态不允许保存。"));
			}
		}
		if ("ready".equals(row.state()) && row.expiresAt() != null
				&& !row.expiresAt().toInstant().isAfter(Instant.now())) {
			return Mono.error(new IntelligenceException(410, "dh_recording_expired", "录制段已过期。"));
		}
		ManifestView manifest = parseManifest(row.manifestText());
		if (manifest.videoObjectRef() == null || manifest.durationMs() == null) {
			return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段没有可保存的产物。"));
		}
		boolean hasSubtitle = includeSubtitles && manifest.subtitleObjectRef() != null;
		UUID videoMediaId = derivedMediaId(recordingId, "video");
		UUID subtitleMediaId = derivedMediaId(recordingId, "subtitle");
		Mono<Void> mediaRows = mediaRefs.insert(new MediaReference(videoMediaId, actor.accountId(), null,
				MediaPurpose.CONTENT_ASSET.db(), "dh_recording", recordingId.toString(), objectKey(videoMediaId),
				"video/mp4", 0, null, "generated", MediaStatus.PENDING, null,
				row.expiresAt() == null ? null : row.expiresAt().toInstant(), null)).then();
		if (hasSubtitle) {
			mediaRows = mediaRows.then(mediaRefs.insert(new MediaReference(subtitleMediaId, actor.accountId(), null,
					MediaPurpose.CONTENT_ASSET.db(), "dh_recording", recordingId.toString(), objectKey(subtitleMediaId),
					"application/x-subrip", 0, null, "generated", MediaStatus.PENDING, null,
					row.expiresAt() == null ? null : row.expiresAt().toInstant(), null)).then());
		}
		return mediaRows.then(db.sql("""
				UPDATE dh_recording SET state = 'saving',
				    asset_id = COALESCE(asset_id, CAST(:asset AS uuid)),
				    subtitle_media_id = CASE WHEN :hasSub THEN COALESCE(subtitle_media_id, CAST(:sub AS uuid))
				        ELSE subtitle_media_id END,
				    version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner AND state IN ('ready', 'saving')
				RETURNING asset_id::text AS assetId, subtitle_media_id::text AS subtitleMediaId
				""").bind("asset", UUID.randomUUID().toString()).bind("sub", subtitleMediaId.toString())
				.bind("hasSub", hasSubtitle).bind("id", recordingId.toString()).bind("owner", actor.accountId())
				.map((r, meta) -> new String[]{r.get("assetId", String.class), r.get("subtitleMediaId", String.class)})
				.one().switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段状态已变化。")))
				.map(ids -> new SavePlan(UUID.fromString(op.id()), recordingId, UUID.fromString(ids[0]), videoMediaId,
						hasSubtitle && ids[1] != null ? UUID.fromString(ids[1]) : null, title, includeSubtitles,
						manifest.videoObjectRef(), manifest.videoSha256(), manifest.durationMs(), manifest.sizeBytes(),
						hasSubtitle ? manifest.subtitleObjectRef() : null, manifest.subtitleSha256(), null)));
	}

	/** 事务外阶段：INTERNAL12 取物 + sha256 + 真实 ffprobe + 对象写入（boundedElastic）。 */
	Mono<StagedArtifacts> fetchAndStage(SavePlan plan) {
		Mono<byte[]> video = fetcher.fetchArtifact(plan.recordingId(), plan.videoObjectRef())
				.flatMap(bytes -> Mono.fromCallable(() -> {
					validateVideoArtifact(bytes, plan);
					return bytes;
				}).subscribeOn(Schedulers.boundedElastic()));
		if (plan.subtitleObjectRef() == null) {
			return video.map(bytes -> new StagedArtifacts(bytes, MediaChecksums.sha256(bytes), null, null));
		}
		return video.flatMap(
				videoBytes -> fetcher.fetchArtifact(plan.recordingId(), plan.subtitleObjectRef()).map(subtitleBytes -> {
					if (subtitleBytes.length == 0) {
						throw new IntelligenceException(409, "dh_media_invalid", "字幕产物为空。");
					}
					if (plan.subtitleSha256() != null
							&& !plan.subtitleSha256().equalsIgnoreCase(MediaChecksums.sha256(subtitleBytes))) {
						throw new IntelligenceException(409, "dh_media_invalid", "字幕产物校验失败。");
					}
					return new StagedArtifacts(videoBytes, MediaChecksums.sha256(videoBytes), subtitleBytes,
							MediaChecksums.sha256(subtitleBytes));
				}));
	}

	private static void validateVideoArtifact(byte[] bytes, SavePlan plan) {
		String sha = MediaChecksums.sha256(bytes);
		if (plan.videoSha256() != null && !plan.videoSha256().equalsIgnoreCase(sha)) {
			throw new IntelligenceException(409, "dh_media_invalid", "产物校验失败。");
		}
		if (plan.sizeBytes() > 0 && bytes.length != plan.sizeBytes()) {
			throw new IntelligenceException(409, "dh_media_invalid", "产物大小与录制清单不符。");
		}
		Path temp = null;
		try {
			temp = Files.createTempFile("dh-recording-save-", ".mp4");
			Files.write(temp, bytes);
			ArtifactInfo info = DigitalHumanArtifactProbe.probe(temp);
			DigitalHumanArtifactProbe.validate(info, new Expectation(plan.durationMs(), null, null));
		} catch (ProbeException invalid) {
			throw new IntelligenceException(409, "dh_media_invalid", "产物无法解码或编码不符合要求。");
		} catch (IOException environment) {
			throw new IntelligenceException(503, "dh_runtime_unavailable", "受控产物暂存失败，请稍后重试。");
		} finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException ignored) {
					// 临时目录由操作系统回收。
				}
			}
		}
	}

	/** 对象写入：确定性 key，先探后写（同 key 同内容幂等，重试不重复计一次写入）。 */
	Mono<Void> persistObjects(SavePlan plan, StagedArtifacts staged) {
		ObjectStorageAdapter adapter = storage.getIfAvailable();
		if (adapter == null) {
			return Mono.error(new IntelligenceException(503, "dh_storage_unavailable", "对象存储未启用。"));
		}
		ObjectStorageAdapter bounded = adapter;
		return Mono.fromCallable(() -> {
			putOnce(bounded, objectKey(plan.videoMediaId()), staged.video(), "video/mp4");
			if (plan.subtitleMediaId() != null) {
				putOnce(bounded, objectKey(plan.subtitleMediaId()), staged.subtitle(), "application/x-subrip");
			}
			return true;
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	private static void putOnce(ObjectStorageAdapter adapter, String key, byte[] content, String contentType) {
		var existing = adapter.headObject(key);
		if (existing.isPresent() && existing.get().contentLength() == content.length) {
			return;
		}
		adapter.putObject(key, content, contentType);
	}

	/** 事务二：激活 media（GC 竞争点）→ asset/附件 → recording saved → operation succeeded。 */
	Mono<SaveOutcome> commitSave(PersonalActor actor, SavePlan plan, StagedArtifacts staged) {
		Mono<Void> transactional = mediaRefs
				.claimAssetActivation(plan.videoMediaId(), staged.videoSha256(), staged.video().length)
				.switchIfEmpty(Mono.defer(() -> Mono.error(cleanupWon(plan.recordingId()))))
				.flatMap(ignored -> plan.subtitleMediaId() == null
						? Mono.just(true)
						: mediaRefs
								.claimAssetActivation(plan.subtitleMediaId(), staged.subtitleSha256(),
										staged.subtitle().length)
								.switchIfEmpty(Mono.defer(() -> Mono.error(cleanupWon(plan.recordingId()))))
								.thenReturn(true))
				.then(assets.findById(plan.assetId())
						.switchIfEmpty(Mono.defer(() -> createPersonalAsset(actor, plan, staged)))
						.flatMap(created -> plan.subtitleMediaId() == null
								? Mono.just(created)
								: attachSubtitle(actor, plan).thenReturn(created)))
				.then(db.sql("UPDATE dh_recording SET state = 'saved', version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND state = 'saving'")
						.bind("id", plan.recordingId().toString()).then())
				.then(db.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:asset AS uuid),"
						+ " resource_id = CAST(:recording AS uuid), version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND state = 'pending'")
						.bind("id", plan.operationId().toString()).bind("asset", plan.assetId().toString())
						.bind("recording", plan.recordingId().toString()).then());
		return transactions.transactional(transactional).then(operations.read(actor, plan.operationId()))
				.map(op -> new SaveOutcome(toDto(op), true));
	}

	private Mono<ContentAsset> createPersonalAsset(PersonalActor actor, SavePlan plan, StagedArtifacts staged) {
		ContentAsset asset = new ContentAsset(plan.assetId(), plan.videoMediaId(), LibraryType.PERSONAL,
				AssetCategory.OTHER, actor.accountId(), null, plan.title(), List.of(), "video/mp4",
				(long) staged.video().length, null, AssetStatus.ACTIVE, 1, "dh_recording", "personal", null, null, null,
				null, null, null);
		return assets.create(asset);
	}

	private Mono<Void> attachSubtitle(PersonalActor actor, SavePlan plan) {
		return db
				.sql("INSERT INTO dh_asset_attachment(id, owner_account_id, asset_id, media_reference_id, kind)"
						+ " VALUES (CAST(:id AS uuid), :owner, CAST(:asset AS uuid), CAST(:media AS uuid), 'subtitle')"
						+ " ON CONFLICT (asset_id, kind) DO NOTHING")
				.bind("id", UUID.randomUUID().toString()).bind("owner", actor.accountId())
				.bind("asset", plan.assetId().toString()).bind("media", plan.subtitleMediaId().toString()).then();
	}

	/** 收尾（含确定性失败收口）：plan.replayOperation 为空且 op 仍 pending 时执行。 */
	Mono<SaveOutcome> finishSave(PersonalActor actor, SavePlan plan) {
		return fetchAndStage(plan).flatMap(staged -> persistObjects(plan, staged).then(commitSave(actor, plan, staged)))
				.onErrorResume(DigitalHumanArtifactService::isConcludingFailure,
						failure -> failSave(plan, (IntelligenceException) failure));
	}

	/** 确定性产物/窗口失败：recording + operation 同事务收口，同键重放回原失败。 */
	private Mono<SaveOutcome> failSave(SavePlan plan, IntelligenceException failure) {
		String targetState = "dh_recording_expired".equals(failure.code()) ? "expired" : "failed";
		Mono<Void> transactional = db
				.sql("UPDATE dh_recording SET state = :state, error_code = :code, version = version + 1,"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND state = 'saving'")
				.bind("state", targetState).bind("code", failure.code()).bind("id", plan.recordingId().toString())
				.then()
				.then(db.sql("UPDATE dh_operation SET state = 'failed', error_code = :code,"
						+ " resource_id = CAST(:recording AS uuid), version = version + 1,"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND state = 'pending'")
						.bind("code", failure.code()).bind("recording", plan.recordingId().toString())
						.bind("id", plan.operationId().toString()).then());
		return transactions.transactional(transactional).then(Mono.error(failure));
	}

	private static boolean isConcludingFailure(Throwable failure) {
		return failure instanceof IntelligenceException exception
				&& ("dh_media_invalid".equals(exception.code()) || "dh_recording_expired".equals(exception.code()));
	}

	private static IntelligenceException cleanupWon(UUID recordingId) {
		// K13.5：清理先抢到——保存窗口已随 GC 关闭（含到期清理），不挂已删对象。
		return new IntelligenceException(410, "dh_recording_expired", "录制段保存窗口已关闭。");
	}

	// ---------- API36 download ----------

	public Mono<DownloadResponse> download(PersonalActor actor, UUID recordingId, String artifact, String rangeHeader) {
		if (!"mp4".equals(artifact) && !"srt".equals(artifact)) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "artifact 只支持 mp4 或 srt。"));
		}
		boolean subtitle = "srt".equals(artifact);
		return findRow(actor, recordingId).flatMap(row -> {
			switch (row.state()) {
				case "recording", "finalizing", "saving" -> {
					return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制尚未完成。"));
				}
				case "failed" -> {
					return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段产物不可用。"));
				}
				case "expired", "deleted" -> {
					return Mono.error(new IntelligenceException(410, "dh_recording_expired", "录制段已过期。"));
				}
				default -> {
				}
			}
			if ("saved".equals(row.state())) {
				return downloadSaved(actor, row, subtitle, rangeHeader);
			}
			if (row.expiresAt() != null && !row.expiresAt().toInstant().isAfter(Instant.now())) {
				return Mono.error(new IntelligenceException(410, "dh_recording_expired", "录制段已过期。"));
			}
			ManifestView manifest = parseManifest(row.manifestText());
			String objectRef = subtitle ? manifest.subtitleObjectRef() : manifest.videoObjectRef();
			if (objectRef == null) {
				return Mono.error(new IntelligenceException(404, "dh_not_found", "该录制段没有此产物。"));
			}
			return fetcher.fetchArtifact(recordingId, objectRef).map(bytes -> slice(bytes,
					subtitle ? "application/x-subrip" : "video/mp4", recordingId, subtitle, rangeHeader));
		});
	}

	/** saved：K13.5 资产当前授权重验（已删/终态拒绝 → 410 走原库生命周期），永久对象走本库存储。 */
	private Mono<DownloadResponse> downloadSaved(PersonalActor actor, ArtifactRowView row, boolean subtitle,
			String rangeHeader) {
		if (row.assetId() == null) {
			return Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段没有已保存资产。"));
		}
		UUID assetId = UUID.fromString(row.assetId());
		return assets.findById(assetId).flatMap(asset -> {
			if (asset.deletedAt() != null || !List.of(AssetStatus.DRAFT, AssetStatus.PENDING_REVIEW, AssetStatus.ACTIVE)
					.contains(asset.status())) {
				return Mono.error(new IntelligenceException(410, "dh_recording_expired", "资产已按素材库生命周期移除。"));
			}
			if (subtitle && row.subtitleMediaId() == null) {
				return Mono.error(new IntelligenceException(404, "dh_not_found", "该录制段没有字幕产物。"));
			}
			UUID mediaId = subtitle ? UUID.fromString(row.subtitleMediaId()) : derivedMediaId(rowUUID(row), "video");
			ObjectStorageAdapter adapter = storage.getIfAvailable();
			if (adapter == null) {
				return Mono.error(new IntelligenceException(503, "dh_storage_unavailable", "对象存储未启用。"));
			}
			return Mono.fromCallable(() -> adapter.getObject(objectKey(mediaId)))
					.subscribeOn(Schedulers.boundedElastic())
					.onErrorMap(missing -> new IntelligenceException(503, "dh_storage_unavailable", "资产对象暂不可读。"))
					.map(bytes -> slice(bytes, subtitle ? "application/x-subrip" : "video/mp4", rowUUID(row), subtitle,
							rangeHeader));
		}).switchIfEmpty(Mono.error(new IntelligenceException(410, "dh_recording_expired", "资产已按素材库生命周期移除。")));
	}

	private static UUID rowUUID(ArtifactRowView row) {
		return UUID.fromString(row.id());
	}

	/** Range 单区间切片：非法/多区间/越界 → 416（含 Content-Range 证据语义由控制器补）。 */
	private static DownloadResponse slice(byte[] bytes, String contentType, UUID recordingId, boolean subtitle,
			String rangeHeader) {
		String filename = "recording-" + recordingId + (subtitle ? ".srt" : ".mp4");
		RangeSpec range = parseRange(rangeHeader, bytes.length);
		if (range == null) {
			return new DownloadResponse(bytes, contentType, filename, null, null, bytes.length);
		}
		int length = (int) (range.end() - range.start() + 1);
		byte[] sliced = new byte[length];
		System.arraycopy(bytes, (int) range.start(), sliced, 0, length);
		return new DownloadResponse(sliced, contentType, filename, range.start(), range.end(), bytes.length);
	}

	record RangeSpec(long start, long end) {
	}

	static RangeSpec parseRange(String header, long size) {
		if (header == null || header.isBlank()) {
			return null;
		}
		if (!header.startsWith("bytes=")) {
			throw rangeInvalid();
		}
		String spec = header.substring("bytes=".length()).strip();
		if (spec.contains(",") || spec.indexOf('-') != spec.lastIndexOf('-')) {
			throw rangeInvalid();
		}
		int dash = spec.indexOf('-');
		String startPart = spec.substring(0, dash).strip();
		String endPart = spec.substring(dash + 1).strip();
		long start;
		long end;
		try {
			if (startPart.isEmpty()) {
				if (endPart.isEmpty()) {
					throw rangeInvalid();
				}
				long suffix = Long.parseLong(endPart);
				if (suffix <= 0 || size <= 0) {
					throw rangeInvalid();
				}
				long take = Math.min(suffix, size);
				start = size - take;
				end = size - 1;
			} else {
				start = Long.parseLong(startPart);
				if (start < 0) {
					throw rangeInvalid();
				}
				end = endPart.isEmpty() ? size - 1 : Long.parseLong(endPart);
				if (end < start) {
					throw rangeInvalid();
				}
			}
		} catch (NumberFormatException malformed) {
			throw rangeInvalid();
		}
		if (start >= size) {
			throw rangeInvalid();
		}
		if (end >= size) {
			end = size - 1;
		}
		return new RangeSpec(start, end);
	}

	private static IntelligenceException rangeInvalid() {
		return new IntelligenceException(416, "dh_range_not_satisfiable", "Range 必须是单个可满足的字节区间。");
	}

	// ---------- manifest 与行读取 ----------

	record ManifestView(String videoObjectRef, String videoSha256, Long durationMs, Long sizeBytes,
			String subtitleObjectRef, String subtitleSha256) {
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	private static ManifestView parseManifest(String manifestText) {
		if (manifestText == null) {
			return new ManifestView(null, null, null, null, null, null);
		}
		try {
			var root = JSON.readTree(manifestText);
			var video = root.path("video");
			var subtitle = root.path("subtitle");
			return new ManifestView(video.isObject() ? video.path("objectRef").asText(null) : null,
					video.isObject() ? video.path("sha256").asText(null) : null,
					video.isObject() && video.path("durationMs").isNumber() ? video.path("durationMs").asLong() : null,
					video.isObject() && video.path("sizeBytes").isNumber() ? video.path("sizeBytes").asLong() : null,
					subtitle.isObject() ? subtitle.path("objectRef").asText(null) : null,
					subtitle.isObject() ? subtitle.path("sha256").asText(null) : null);
		} catch (Exception invalid) {
			return new ManifestView(null, null, null, null, null, null);
		}
	}

	private static final String ROW_COLUMNS = "id::text AS id, owner_account_id AS owner, state,"
			+ " manifest::text AS manifestText, expires_at, asset_id::text AS assetId,"
			+ " subtitle_media_id::text AS subtitleMediaId";

	private Mono<ArtifactRowView> findRow(PersonalActor actor, UUID recordingId) {
		return db
				.sql("SELECT " + ROW_COLUMNS + " FROM dh_recording WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", recordingId.toString()).bind("owner", actor.accountId())
				.map(DigitalHumanArtifactService::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<ArtifactRowView> findRowForUpdate(PersonalActor actor, UUID recordingId) {
		return db
				.sql("SELECT " + ROW_COLUMNS + " FROM dh_recording WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner FOR UPDATE")
				.bind("id", recordingId.toString()).bind("owner", actor.accountId())
				.map(DigitalHumanArtifactService::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private static ArtifactRowView mapRow(io.r2dbc.spi.Readable r) {
		return new ArtifactRowView(r.get("id", String.class), r.get("owner", String.class),
				r.get("state", String.class), r.get("manifestText", String.class),
				r.get("expires_at", OffsetDateTime.class), r.get("assetId", String.class),
				r.get("subtitleMediaId", String.class));
	}

	// ---------- 句柄派生与 DTO ----------

	static UUID derivedMediaId(UUID recordingId, String kind) {
		return UUID.nameUUIDFromBytes(("dh-recording:" + kind + ":" + recordingId).getBytes(StandardCharsets.UTF_8));
	}

	static String objectKey(UUID mediaId) {
		return "media/content_asset/" + mediaId;
	}

	private static DigitalHumanRecords.OperationDto toDto(OperationRow row) {
		return new DigitalHumanRecords.OperationDto(row.id(), row.kind().name(), row.state(), row.resourceId(),
				row.resultRef(), row.errorCode(), row.createdAt(), row.updatedAt());
	}
}
