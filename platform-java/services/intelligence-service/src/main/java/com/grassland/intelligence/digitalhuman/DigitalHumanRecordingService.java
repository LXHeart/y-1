package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Recording;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 输出AV录制编排（任务书 #105F C105F-02 / K03 API33～35、K04、K09、K13.6）。
 *
 * <p>
 * API33 start：权利确认（acknowledgement）→ 目录 recordingEnabled（否则 422
 * dh_recording_unsupported）→ 会话归属/状态/租约代次（锁内）→ 同会话唯一活动段 + 总段数 ≤2 → 落
 * dh_recording(recording)，提交后派发 INTERNAL02
 * startRecording（recordingId+300s/200MiB 上限）。 确定性拒绝 → failed 行 + failed
 * receipt；runtime 超时/未知不落结论（行留 recording，同键重放重派发）。
 *
 * <p>
 * API34 stop：CAS recording→finalizing（同键/并发停止幂等）；终态 200。提交后派发
 * stopRecording——产物经 INTERNAL11（kind=recording）回执落
 * ready/failed（{@link #applyRecordingArtifact}：finalizing-only 墓碑、manifest
 * 落库、逐对象清理登记先于状态收口）。 API35 get：owner 内 404 统一。
 */
@Component
public class DigitalHumanRecordingService {

	static final long MAX_SEGMENT_DURATION_MS = 300_000;
	static final long MAX_SEGMENT_BYTES = 200L * 1024 * 1024;
	private static final Duration RECORDING_TTL = Duration.ofHours(24);
	private static final Set<String> RECORDABLE_STATES = Set.of("ready", "listening", "responding");
	private static final ObjectMapper JSON = new ObjectMapper();

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final DigitalHumanMediaRepository media;
	private final RecordingRuntimePort runtime;

	public DigitalHumanRecordingService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations, DigitalHumanMediaRepository media,
			ObjectProvider<RecordingRuntimePort> runtimePorts,
			@Value("${dh.runtime.base-url:}") String runtimeBaseUrl) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.media = media;
		this.runtime = runtimePorts
				.getIfAvailable(() -> DigitalHumanRecordingService.defaultRuntimePort(runtimeBaseUrl));
	}

	// ---------- runtime 控制端口（INTERNAL02 录制命令；可替换 transport） ----------

	/** runtime 控制协议端口（生产=同 dh.runtime.base-url 的 WebClient；IT/隔离栈=fake）。 */
	public interface RecordingRuntimePort {

		Mono<Void> startRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
				long maxDurationMs, long maxBytes);

		Mono<Void> stopRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
				String reasonCode);
	}

	static RecordingRuntimePort defaultRuntimePort(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return new RecordingRuntimePort() {
				@Override
				public Mono<Void> startRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
						long maxDurationMs, long maxBytes) {
					return unavailable();
				}

				@Override
				public Mono<Void> stopRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
						String reasonCode) {
					return unavailable();
				}
			};
		}
		var client = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(baseUrl).build();
		return new RecordingRuntimePort() {
			@Override
			public Mono<Void> startRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
					long maxDurationMs, long maxBytes) {
				return client.post().uri("/internal/v1/sessions/{id}/commands", sessionId)
						.bodyValue(Map.of("commandId", commandId.toString(), "payloadHash",
								Integer.toHexString((sessionId + recordingId).hashCode()), "leaseEpoch", leaseEpoch,
								"command", "startRecording", "payload",
								Map.of("recordingId", recordingId.toString(), "maxDurationMs", maxDurationMs,
										"maxBytes", maxBytes)))
						.retrieve().toBodilessEntity().timeout(Duration.ofSeconds(5)).then();
			}

			@Override
			public Mono<Void> stopRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
					String reasonCode) {
				return client.post().uri("/internal/v1/sessions/{id}/commands", sessionId)
						.bodyValue(Map.of("commandId", commandId.toString(), "payloadHash",
								Integer.toHexString((sessionId + recordingId + reasonCode).hashCode()), "leaseEpoch",
								leaseEpoch, "command", "stopRecording", "payload",
								Map.of("recordingId", recordingId.toString(), "reasonCode", reasonCode)))
						.retrieve().toBodilessEntity().timeout(Duration.ofSeconds(5)).then();
			}
		};
	}

	private static Mono<Void> unavailable() {
		return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。"));
	}

	// ---------- 结果类型 ----------

	public record StartResult(Recording recording, boolean createdNow) {
	}

	public record StopResult(Recording recording, boolean stoppedNow) {
	}

	public record ArtifactAcceptance(boolean accepted, String reason) {
	}

	record SessionView(String id, String state, long leaseEpoch) {
	}

	// ---------- API33 start ----------

	public Mono<StartResult> start(PersonalActor actor, UUID sessionId, UUID requestId, long leaseEpoch,
			boolean acknowledgement) {
		Mono<StartResult> transactional = operations.reserve(actor, OperationKind.recording_start, requestId,
				DigitalHumanOperations.canonicalHash(Map.of("sessionId", sessionId.toString(), "leaseEpoch", leaseEpoch,
						"acknowledgement", acknowledgement)),
				null).flatMap(operation -> {
					if (operation.state() == OperationState.succeeded && operation.resultRef() != null) {
						return findRecording(actor, UUID.fromString(operation.resultRef()))
								.map(row -> new StartResult(row, false));
					}
					if (!acknowledgement) {
						return Mono.error(new IntelligenceException(422, "dh_invalid_input", "录制需明确确认字幕授权。"));
					}
					return recordingEnabled().then(lockSession(actor, sessionId)).flatMap(session -> {
						if (!RECORDABLE_STATES.contains(session.state())) {
							return Mono.error(new IntelligenceException(409, "dh_state_conflict", "会话当前状态不可开始录制。"));
						}
						if (session.leaseEpoch() != leaseEpoch) {
							return Mono.error(new IntelligenceException(409, "dh_lease_stale", "会话控制权已变化，请刷新。"));
						}
						return segmentCounts(sessionId).flatMap(counts -> {
							if (counts[0] > 0) {
								return Mono.error(new IntelligenceException(409, "dh_state_conflict", "已有进行中的录制段。"));
							}
							if (counts[1] >= 2) {
								return Mono
										.error(new IntelligenceException(409, "dh_state_conflict", "同会话录制段已达上限（2 段）。"));
							}
							return insertRecording(actor, sessionId, requestId).flatMap(inserted -> operations
									.attachResource(UUID.fromString(operation.id()), UUID.fromString(inserted.id()))
									.then(completeOperation(UUID.fromString(operation.id()),
											UUID.fromString(inserted.id())))
									.thenReturn(new StartResult(inserted, true)));
						});
					});
				}).onErrorMap(DigitalHumanRecordingService::mapIntegrity);
		return transactions.transactional(transactional).flatMap(result -> {
			if (!result.createdNow()) {
				// 同键重放：行仍在 recording（此前派发超时/未知）→ 幂等重派发，不重复建段。
				if ("recording".equals(result.recording().state())) {
					return redispatchStart(result, sessionId, requestId, leaseEpoch);
				}
				return Mono.just(result);
			}
			return runtime
					.startRecording(sessionId.toString(), UUID.fromString(result.recording().id()), requestId,
							leaseEpoch, MAX_SEGMENT_DURATION_MS, MAX_SEGMENT_BYTES)
					.onErrorResume(failure -> Mono.error(translate(failure))).thenReturn(result)
					.onErrorResume(failure -> markDispatchFailed(UUID.fromString(result.recording().id()),
							result.recording().state(), failure).then(Mono.error(failure)));
		});
	}

	private Mono<StartResult> redispatchStart(StartResult result, UUID sessionId, UUID requestId, long leaseEpoch) {
		return runtime
				.startRecording(sessionId.toString(), UUID.fromString(result.recording().id()), requestId, leaseEpoch,
						MAX_SEGMENT_DURATION_MS, MAX_SEGMENT_BYTES)
				.onErrorResume(ignored -> Mono.empty()).thenReturn(result);
	}

	// ---------- API34 stop ----------

	public Mono<StopResult> stop(PersonalActor actor, UUID recordingId, UUID requestId) {
		Mono<StopResult> transactional = operations.reserve(actor, OperationKind.recording_stop, requestId,
				DigitalHumanOperations.canonicalHash(Map.of("recordingId", recordingId.toString())), recordingId)
				.flatMap(operation -> {
					if (operation.state() == OperationState.succeeded) {
						return findRecording(actor, recordingId).map(row -> new StopResult(row, false));
					}
					return findRecordingForUpdate(actor, recordingId).flatMap(row -> {
						if ("recording".equals(row.state())) {
							return casState(recordingId, "recording", "finalizing").flatMap(
									finalizing -> completeOperation(UUID.fromString(operation.id()), recordingId)
											.thenReturn(new StopResult(finalizing, true)));
						}
						return completeOperation(UUID.fromString(operation.id()), recordingId)
								.thenReturn(new StopResult(toDto(row), false));
					});
				});
		return transactions.transactional(transactional).flatMap(result -> {
			// finalizing 的派发尽力而为：回执不可达不回滚终态意图；同键重放会再派发（见上）。
			if ("finalizing".equals(result.recording().state())) {
				return dispatchStop(actor, recordingId, requestId).then(findRecording(actor, recordingId))
						.map(row -> new StopResult(row, result.stoppedNow()));
			}
			return Mono.just(result);
		});
	}

	private Mono<Void> dispatchStop(PersonalActor actor, UUID recordingId, UUID requestId) {
		return sessionOf(recordingId).flatMap(
				session -> runtime.stopRecording(session.id(), recordingId, requestId, session.leaseEpoch(), "user")
						.onErrorResume(ignored -> Mono.empty()));
	}

	private Mono<SessionView> sessionOf(UUID recordingId) {
		return db.sql("""
				SELECT s.id::text AS id, s.state, s.lease_epoch FROM dh_session s
				JOIN dh_recording r ON r.session_id = s.id WHERE r.id = CAST(:id AS uuid)
				""").bind("id", recordingId.toString()).map((row, meta) -> new SessionView(row.get("id", String.class),
				row.get("state", String.class), row.get("lease_epoch", Long.class))).one();
	}

	// ---------- API35 get ----------

	public Mono<Recording> get(PersonalActor actor, UUID recordingId) {
		return findRecording(actor, recordingId);
	}

	// ---------- INTERNAL11 kind=recording 回执 ----------

	/**
	 * 产物回执：finalizing-only（迟到/重复由状态墓碑拦截，accepted=false 如实返回）。manifest 落库、
	 * 逐对象清理登记先于状态收口（先登记再写对象口径的收口侧）；errorCode 与部分 manifest 可共存。
	 */
	public Mono<ArtifactAcceptance> applyRecordingArtifact(UUID recordingId, int revision, String manifestJson,
			String errorCode) {
		if (revision < 1) {
			return Mono.just(new ArtifactAcceptance(false, "invalid-revision"));
		}
		return findRowById(recordingId).switchIfEmpty(Mono.just(new RecordingRowView(recordingId.toString(), null, null,
				"missing", false, null, null, null, null, null, null, null))).flatMap(row -> {
					if (!"finalizing".equals(row.state())) {
						return Mono.just(new ArtifactAcceptance(false, "state-" + row.state()));
					}
					ParsedManifest manifest = parseManifest(manifestJson);
					return registerManifestCleanup(row.owner(), recordingId, manifest, errorCode)
							.then(persistArtifact(recordingId, manifest, manifestJson, errorCode))
							.thenReturn(new ArtifactAcceptance(true, errorCode == null ? "ready" : "failed"));
				});
	}

	// ---------- 私有：校验与落库 ----------

	private Mono<Boolean> recordingEnabled() {
		return db.sql("SELECT config_json::text AS config FROM dh_catalog WHERE singleton_id = 1")
				.map((row, meta) -> row.get("config", String.class)).one().map(config -> {
					try {
						return JSON.readTree(config == null ? "{}" : config).path("recordingEnabled").asBoolean(false);
					} catch (Exception invalid) {
						return false;
					}
				}).defaultIfEmpty(false)
				.flatMap(enabled -> enabled
						? Mono.just(true)
						: Mono.error(new IntelligenceException(422, "dh_recording_unsupported", "录制能力未开放。")));
	}

	private Mono<SessionView> lockSession(PersonalActor actor, UUID sessionId) {
		return db
				.sql("SELECT id::text AS id, state, lease_epoch FROM dh_session"
						+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner FOR UPDATE")
				.bind("id", sessionId.toString()).bind("owner", actor.accountId())
				.map((row, meta) -> new SessionView(row.get("id", String.class), row.get("state", String.class),
						row.get("lease_epoch", Long.class)))
				.one().switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。")));
	}

	/** [活动段数, 总段数]（锁内同事务调用；K05 部分唯一由 DB 最终裁定）。 */
	private Mono<long[]> segmentCounts(UUID sessionId) {
		return db
				.sql("SELECT count(*) FILTER (WHERE state IN ('recording','finalizing')) AS active,"
						+ " count(*) AS total FROM dh_recording WHERE session_id = CAST(:s AS uuid)")
				.bind("s", sessionId.toString())
				.map((row, meta) -> new long[]{row.get("active", Long.class), row.get("total", Long.class)}).one()
				.defaultIfEmpty(new long[]{0L, 0L});
	}

	private Mono<Recording> insertRecording(PersonalActor actor, UUID sessionId, UUID requestId) {
		String id = UUID.randomUUID().toString();
		return db.sql("""
				INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,
				    start_program_ms)
				VALUES (CAST(:id AS uuid), :owner, CAST(:s AS uuid), CAST(:cmd AS uuid), 'recording', false, 0)
				""").bind("id", id).bind("owner", actor.accountId()).bind("s", sessionId.toString())
				.bind("cmd", requestId.toString()).then().then(findRecording(actor, UUID.fromString(id)));
	}

	private Mono<Void> completeOperation(UUID operationId, UUID recordingId) {
		return db
				.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:r AS uuid),"
						+ " resource_id = CAST(:r AS uuid), version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).bind("r", recordingId.toString()).then();
	}

	private Mono<Recording> casState(UUID recordingId, String expected, String target) {
		return db
				.sql("UPDATE dh_recording SET state = :target, version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND state = :expected RETURNING id::text")
				.bind("id", recordingId.toString()).bind("expected", expected).bind("target", target)
				.map(row -> row.get("id", String.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_state_conflict", "录制段状态已变化。")))
				.then(findRecordingById(recordingId));
	}

	private Mono<Void> markDispatchFailed(UUID recordingId, String stateBefore, Throwable failure) {
		if (failure instanceof IntelligenceException exception && exception.status() < 500) {
			// 确定性拒绝（runtime 4xx）：failed 行；超时/5xx 不落结论（行留 recording，重放重派发）。
			return db
					.sql("UPDATE dh_recording SET state = 'failed', error_code = :code, version = version + 1,"
							+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND state = :expected")
					.bind("id", recordingId.toString()).bind("code", exception.code()).bind("expected", stateBefore)
					.then();
		}
		return Mono.empty();
	}

	private static IntelligenceException translate(Throwable failure) {
		if (failure instanceof IntelligenceException exception) {
			return exception;
		}
		return new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。");
	}

	private static Throwable mapIntegrity(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof io.r2dbc.spi.R2dbcException r && "23505".equals(r.getSqlState())) {
				String message = String.valueOf(r.getMessage());
				if (message.contains("uq_dh_recording_session_active")) {
					return new IntelligenceException(409, "dh_state_conflict", "已有进行中的录制段。");
				}
				if (message.contains("uq_dh_recording_start_command")) {
					return new IntelligenceException(409, "dh_request_conflict", "该请求号已创建过录制段。");
				}
			}
			current = current.getCause();
		}
		return error;
	}

	// ---------- manifest 解析与收口 ----------

	record ParsedManifest(Long startProgramMs, Long endProgramMs, Boolean partial, VideoManifest video,
			String subtitleObjectRef) {

		static final ParsedManifest EMPTY = new ParsedManifest(null, null, null, null, null);
	}

	record VideoManifest(long durationMs, long sizeBytes, String objectRef, String sha256) {
	}

	private static ParsedManifest parseManifest(String manifestJson) {
		if (manifestJson == null) {
			return ParsedManifest.EMPTY;
		}
		try {
			JsonNode root = JSON.readTree(manifestJson);
			JsonNode video = root.path("video");
			JsonNode subtitle = root.path("subtitle");
			return new ParsedManifest(
					root.path("startProgramMs").isNumber() ? root.path("startProgramMs").asLong() : null,
					root.path("endProgramMs").isNumber() ? root.path("endProgramMs").asLong() : null,
					root.path("partial").isBoolean() ? root.path("partial").asBoolean() : null,
					video.isObject() && video.hasNonNull("objectRef")
							? new VideoManifest(video.path("durationMs").asLong(0), video.path("sizeBytes").asLong(0),
									video.path("objectRef").asText(), video.path("sha256").asText(null))
							: null,
					subtitle.isObject() ? subtitle.path("objectRef").asText(null) : null);
		} catch (Exception invalid) {
			return ParsedManifest.EMPTY;
		}
	}

	private Mono<Void> registerManifestCleanup(String owner, UUID recordingId, ParsedManifest manifest,
			String errorCode) {
		Mono<Void> chain = Mono.empty();
		if (manifest.video() != null) {
			chain = chain.then(media.registerCleanup(owner, "recording_object", recordingId,
					manifest.video().objectRef(), "recording-artifact"));
		}
		if (manifest.subtitleObjectRef() != null) {
			chain = chain.then(media.registerCleanup(owner, "recording_object", recordingId,
					manifest.subtitleObjectRef(), "recording-artifact"));
		}
		return chain;
	}

	private Mono<Void> persistArtifact(UUID recordingId, ParsedManifest manifest, String manifestJson,
			String errorCode) {
		boolean failed = errorCode != null || manifest.video() == null;
		String state = failed ? "failed" : "ready";
		String effectiveError = failed ? (errorCode != null ? errorCode : "dh_recording_failed") : null;
		Long durationMs = manifest.video() == null ? null : manifest.video().durationMs();
		Long sizeBytes = manifest.video() == null ? null : manifest.video().sizeBytes();
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE dh_recording SET state = :state, error_code = :code,
				    partial = COALESCE(:partial, partial), start_program_ms = COALESCE(:startMs, start_program_ms),
				    end_program_ms = :endMs, duration_ms = :durationMs, size_bytes = :sizeBytes,
				    manifest = COALESCE(CAST(:manifest AS jsonb), manifest),
				    expires_at = CASE WHEN :state = 'ready' THEN now() + interval '24 hours' ELSE expires_at END,
				    version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND state = 'finalizing'
				""").bind("state", state).bind("id", recordingId.toString());
		spec = manifestJson == null ? spec.bindNull("manifest", String.class) : spec.bind("manifest", manifestJson);
		spec = effectiveError == null ? spec.bindNull("code", String.class) : spec.bind("code", effectiveError);
		spec = manifest.partial() == null
				? spec.bindNull("partial", Boolean.class)
				: spec.bind("partial", manifest.partial());
		spec = manifest.startProgramMs() == null
				? spec.bindNull("startMs", Long.class)
				: spec.bind("startMs", manifest.startProgramMs());
		spec = manifest.endProgramMs() == null
				? spec.bindNull("endMs", Long.class)
				: spec.bind("endMs", manifest.endProgramMs());
		spec = durationMs == null ? spec.bindNull("durationMs", Long.class) : spec.bind("durationMs", durationMs);
		spec = sizeBytes == null ? spec.bindNull("sizeBytes", Long.class) : spec.bind("sizeBytes", sizeBytes);
		return spec.fetch().rowsUpdated().doOnNext(rows -> {
			if (rows != null && rows == 0) {
				throw new IntelligenceException(409, "dh_state_conflict", "录制段状态已变化。");
			}
		}).then();
	}

	// ---------- 读路径 ----------

	private Mono<Recording> findRecording(PersonalActor actor, UUID recordingId) {
		return db
				.sql("SELECT " + RECORDING_COLUMNS + " FROM dh_recording WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", recordingId.toString()).bind("owner", actor.accountId())
				.map(DigitalHumanRecordingService::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.map(DigitalHumanRecordingService::toDto);
	}

	private Mono<Recording> findRecordingById(UUID recordingId) {
		return db.sql("SELECT " + RECORDING_COLUMNS + " FROM dh_recording WHERE id = CAST(:id AS uuid)")
				.bind("id", recordingId.toString()).map(DigitalHumanRecordingService::mapRow).one()
				.map(DigitalHumanRecordingService::toDto);
	}

	private Mono<RecordingRowView> findRecordingForUpdate(PersonalActor actor, UUID recordingId) {
		return db
				.sql("SELECT " + RECORDING_COLUMNS + " FROM dh_recording WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner FOR UPDATE")
				.bind("id", recordingId.toString()).bind("owner", actor.accountId())
				.map(DigitalHumanRecordingService::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<RecordingRowView> findRowById(UUID recordingId) {
		return db.sql("SELECT " + RECORDING_COLUMNS + " FROM dh_recording WHERE id = CAST(:id AS uuid)")
				.bind("id", recordingId.toString()).map(DigitalHumanRecordingService::mapRow).one();
	}

	private static final String RECORDING_COLUMNS = "id::text AS id, owner_account_id AS owner, session_id::text"
			+ " AS sessionId, state, partial, duration_ms AS durationMs, size_bytes AS sizeBytes,"
			+ " manifest::text AS manifestText, created_at, updated_at, expires_at, error_code AS errorCode";

	record RecordingRowView(String id, String owner, String sessionId, String state, boolean partial, Long durationMs,
			Long sizeBytes, String manifestText, OffsetDateTime createdAt, OffsetDateTime updatedAt,
			OffsetDateTime expiresAt, String errorCode) {
	}

	private static RecordingRowView mapRow(io.r2dbc.spi.Readable r) {
		return new RecordingRowView(r.get("id", String.class), r.get("owner", String.class),
				r.get("sessionId", String.class), r.get("state", String.class),
				Boolean.TRUE.equals(r.get("partial", Boolean.class)), r.get("durationMs", Long.class),
				r.get("sizeBytes", Long.class), r.get("manifestText", String.class),
				r.get("created_at", OffsetDateTime.class), r.get("updated_at", OffsetDateTime.class),
				r.get("expires_at", OffsetDateTime.class), r.get("errorCode", String.class));
	}

	private static Recording toDto(RecordingRowView row) {
		boolean subtitleAvailable = false;
		if (row.manifestText() != null) {
			try {
				subtitleAvailable = JSON.readTree(row.manifestText()).path("subtitle").isObject();
			} catch (Exception ignored) {
				subtitleAvailable = false;
			}
		}
		return new Recording(row.id(), row.sessionId(), row.state(), row.partial(),
				row.durationMs() == null ? 0L : row.durationMs(), row.sizeBytes() == null ? 0L : row.sizeBytes(),
				toInstant(row.createdAt()), toInstant(row.updatedAt()), toInstant(row.expiresAt()), null,
				subtitleAvailable, row.errorCode());
	}

	private static java.time.Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
