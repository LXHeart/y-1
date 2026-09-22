package com.grassland.intelligence.digitalhuman;

import java.time.Instant;
import java.util.List;

/**
 * 数字人域 Java record/enum 单一载体（任务书 #105B C105B-01 / 共享契约 K13.7）。
 *
 * <p>
 * 本文件承载 K02～K08 的公开 DTO、枚举与行类型（public 静态嵌套类型，import 明确）；阶段内其它类不得为单个 返回值另建文件。DTO
 * 字段与 {@code contracts/digital-human.v1.json} 逐一对齐：金额 cents、时长 ms、UTC
 * 时间、Seq/Epoch 0～2^53-1；未知用量/金额为 {@code null} 并标 pending，不填 0。
 */
public final class DigitalHumanRecords {

	private DigitalHumanRecords() {
	}

	// ---------- K02 枚举 ----------

	public enum Tone {
		natural, professional, friendly
	}

	public enum InputMode {
		text, microphone
	}

	public enum ModelSource {
		platform, own
	}

	public enum ProfileStatus {
		active, deleted
	}

	public enum AvatarSource {
		preset, personal
	}

	public enum AvatarState {
		processing, ready, failed, revoked
	}

	public enum BackendTransport {
		mock, remote
	}

	public enum BackendState {
		unavailable, test_only, approved
	}

	// ---------- K03/K05 会话与轮次枚举 ----------

	/**
	 * K04
	 * 状态机：preparing→queued→connecting→ready⇄listening/responding/paused/reconnecting→ending→ended/failed。
	 */
	public enum SessionState {
		preparing, queued, connecting, ready, listening, responding, paused, reconnecting, ending, ended, failed;

		public boolean terminal() {
			return this == ended || this == failed;
		}

		/** K13.4：占全局槽的状态（queued 不占）。 */
		public boolean occupiesSlot() {
			return this != queued && !terminal();
		}
	}

	public enum TurnInputKind {
		text, audio, greeting
	}

	public enum TurnState {
		accepted, transcribing, generating, speaking, completed, interrupted, failed, unknown;

		public boolean terminal() {
			return this == completed || this == interrupted || this == failed || this == unknown;
		}
	}

	// ---------- K03/K13.7 操作幂等 ----------

	/** K13.7 固定 kind 集；只读与 heartbeat 不建 operation。 */
	public enum OperationKind {
		profile_create, profile_update, profile_delete, session_create, session_pause, session_resume, session_end, session_delete, turn_create, turn_interrupt, greeting, transcript_preference, transcript_save, transcript_delete, preview_create, avatar_create, avatar_delete, recording_start, recording_stop, recording_save, admin_config, admin_terminate, admin_reconcile, playback_reset
	}

	public enum OperationState {
		pending, running, succeeded, failed, unknown
	}

	// ---------- K08 用量与结算 ----------

	public enum InvocationStage {
		stt, llm, tts, preview, render
	}

	public enum InvocationState {
		reserved, preparing, prepared, dispatched, succeeded, failed, cancelled, unknown
	}

	public enum SettlementState {
		not_required, pending, settled, failed
	}

	public enum PreviewState {
		processing, ready, failed, expired
	}

	public enum TranscriptRole {
		user, assistant
	}

	public enum TranscriptStatus {
		complete, interrupted, truncated
	}

	/** K08 UsageUnits：未知用量 null，不填 0；quality=confirmed|pending。 */
	public record UsageUnits(Long inputTokens, Long outputTokens, Long audioInputMs, Long audioOutputMs,
			Long textCodePoints, Long renderMs, String providerRequestId, String quality) {
	}

	// ---------- K02 公开 DTO ----------

	public record ProfileInput(String name, String persona, String greeting, Tone tone, String avatarId, String voiceId,
			int catalogVersion) {
	}

	/**
	 * K02 Profile = ProfileInput + id/version/status/createdAt/updatedAt（record
	 * 无继承，扁平展开）。
	 */
	public record Profile(String id, String name, String persona, String greeting, Tone tone, String avatarId,
			String voiceId, int catalogVersion, int version, ProfileStatus status, Instant createdAt,
			Instant updatedAt) {
	}

	public record Page<T>(List<T> items, String nextCursor) {
	}

	public record AvatarItem(String id, int revision, String name, String previewMediaId, AvatarSource source,
			AvatarState state, List<String> compatibleBackendIds, String reasonCode) {
	}

	public record VoiceItem(String id, String name, String language, String providerModelRef,
			List<String> compatibleBackendIds, boolean enabled) {
	}

	/** K02 backend：只返回批准公共字段，不含网络地址/绝对文件位置。 */
	public record BackendItem(String id, String model, String platformConfigId, String platformModelVersion,
			BackendTransport transport, BackendState state, boolean supportsCustomAvatar, boolean supportsRecording,
			int width, int height, int fps, int measuredCapacity, String evidenceRef) {
	}

	public record Catalog(boolean authenticated, int version, boolean enabled, boolean newSessionsAllowed,
			boolean recordingEnabled, boolean customAvatarEnabled, List<AvatarItem> avatars, List<VoiceItem> voices,
			List<BackendItem> backends, Limits limits, String billingNoticeVersion) {
	}

	/** K13.1 游客目录：仅 {authenticated:false,enabled,description}，无私有项。 */
	public record PublicCatalog(boolean authenticated, boolean enabled, String description) {
	}

	/** K01 限制键子集（目录随发，单位 ms/条）。 */
	public record Limits(int maxSessionsPerAccount, int maxSessionsGlobal, int maxQueuedGlobal, long sessionDurationMs,
			long idleTimeoutMs, long resumeWindowMs, int contextPairs, int maxRecordingMs) {
	}

	// ---------- K03 Preflight / Session / Turn DTO ----------

	public record BillingItem(String stage, String modelSource, String modelLabel, String chargeTo,
			String priceTableVersion, String unit, Long estimatedCents) {
	}

	/** K03 API07 Preflight：报价为上限估计，不写确定收费。 */
	public record Preflight(String id, Instant expiresAt, String profileId, int profileVersion, int catalogVersion,
			InputMode inputMode, ModelSource modelSource, String llmModelLabel, String sttModelLabel,
			String ttsModelLabel, String renderModelLabel, String priceTableVersion, String billingNoticeVersion,
			Limits limits, long estimatedMaxCents, long platformCostCapCents, List<BillingItem> billingItems,
			long renderChargeCents, String backendId, String controllerId) {
	}

	/** K03 Session 计费快照：pendingCount 表示未知用量笔数。 */
	public record SessionBilling(long confirmedCents, long platformCostCents, long subsidizedCents, int pendingCount,
			String priceTableVersion) {
	}

	public record Session(String id, String profileId, int profileRevision, SessionState state, long leaseEpoch,
			long mediaEpoch, String controllerId, Instant serverNow, Instant createdAt, Instant readyAt,
			Instant expiresAt, Instant pausedUntil, Instant leaseExpiresAt, long lastSeq, boolean saveTranscript,
			int transcriptVersion, long contentEpoch, SessionBilling billing, String errorCode) {
	}

	public record SessionSnapshot(Session session, List<String> allowedActions, boolean replayComplete,
			Long replayFromSeq) {
	}

	public record SessionSummary(String id, String profileId, String profileNameAtCreation, SessionState state,
			Instant createdAt, Instant endedAt, boolean hasSavedTranscript, int recordingCount, int savedAssetCount,
			SessionBilling billing) {
	}

	public record TurnReceipt(String id, String sessionId, String requestId, long turnEpoch, TurnState state) {
	}

	public record InterruptReceipt(String turnId, boolean effective, long nextMediaEpoch, SessionState state) {
	}

	public record TranscriptEntry(String id, long utteranceSeq, TranscriptRole role, String text,
			TranscriptStatus status, Instant startedAt, Instant endedAt) {
	}

	/** K03 API39 Operation：owner 授权只读，无正文。 */
	public record OperationDto(String id, String kind, OperationState state, String resourceId, String resultRef,
			String errorCode, Instant createdAt, Instant updatedAt) {
	}

	/** K03 Recording（F 阶段首用；saved 后 asset 独立）。 */
	public record Recording(String id, String sessionId, String state, boolean partial, long durationMs, long sizeBytes,
			Instant startedAt, Instant endedAt, Instant expiresAt, String assetId, boolean subtitleAvailable,
			String errorCode) {
	}

	// ---------- K05 行类型（仓储映射；与 DDL 列一一对应） ----------

	public record ProfileRow(String id, String ownerAccountId, String name, int activeRevision, ProfileStatus status,
			Instant deletedAt, int version, Instant createdAt, Instant updatedAt) {
	}

	public record ProfileRevisionRow(String id, String ownerAccountId, String profileId, int revision, String persona,
			String greeting, Tone tone, String avatarId, int avatarRevision, String voiceId, int catalogVersion,
			int version, Instant createdAt, Instant updatedAt) {
	}

	public record CatalogRow(int singletonId, int version, String configJson, String updatedBy, Instant createdAt,
			Instant updatedAt) {
	}

	public record OperationRow(String id, String ownerAccountId, OperationKind kind, String requestId,
			String payloadHash, String resourceId, OperationState state, String resultRef, String errorCode,
			Instant retryAt, String leaseOwner, Instant leaseUntil, int version, Instant createdAt, Instant updatedAt) {
	}

	public record SessionRow(String id, String ownerAccountId, String profileId, int profileRevision,
			String profileNameAtCreation, Instant deletedAt, String backendId, String preflightId, String workerId,
			SessionState state, Instant stateEnteredAt, Instant lastBrowserHeartbeatAt, Instant workerLeaseExpiresAt,
			Instant lastActivityAt, long nextTurnEpoch, long renderMs, String configSnapshot, long leaseEpoch,
			long mediaEpoch, String controllerId, long lastSeq, Instant readyAt, Instant expiresAt, Instant pausedUntil,
			Instant leaseExpiresAt, Instant endedAt, boolean saveTranscript, int transcriptVersion, long contentEpoch,
			boolean contentDeleted, boolean cleanupPending, String errorCode, int version, Instant createdAt,
			Instant updatedAt) {
	}

	public record TurnRow(String id, String ownerAccountId, String sessionId, String requestId, long turnEpoch,
			TurnInputKind inputKind, TurnState state, Instant startedAt, Instant endedAt, String errorCode, int version,
			Instant createdAt, Instant updatedAt) {
	}

	public record EventRow(String sessionId, long seq, String eventId, String eventType, String payload,
			String ownerAccountId, Instant createdAt) {
	}

	public record TranscriptRow(String id, String ownerAccountId, String sessionId, String utteranceId,
			long utteranceSeq, TranscriptRole role, String finalText, TranscriptStatus status, Instant startedAt,
			Instant endedAt, long contentEpoch, int version, Instant createdAt, Instant updatedAt) {
	}

	public record InvocationRow(String id, String ownerAccountId, String sessionId, String turnId,
			InvocationStage stage, String resourceId, int segmentIndex, String operationId, String aiRunId,
			InvocationState state, SettlementState settlementState, String providerSnapshot, String budgetSnapshot,
			String usageJson, String providerRunId, String requestHash, Instant deadlineAt, Instant nextAttemptAt,
			int version, Instant createdAt, Instant updatedAt) {
	}

	public record PreviewRow(String id, String ownerAccountId, String voiceId, int catalogVersion, PreviewState state,
			Instant expiresAt, String invocationId, String errorCode, int version, Instant createdAt,
			Instant updatedAt) {
	}

	public record AdminAuditRow(String id, String actorAccountId, String action, String resourceId, String requestId,
			String reason, String metadataJson, Instant createdAt) {
	}
}
