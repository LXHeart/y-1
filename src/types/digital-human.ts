/**
 * 数字人工作台公开 DTO 类型（任务书 #105B C105B-04 / 共享契约 K01～K03、K08）。
 *
 * 与 `contracts/digital-human.v1.json` 逐字段对齐：金额 cents、时长 ms、UTC RFC3339、Seq/Epoch
 * 0～2^53-1；未知用量/金额为 `null` 并标 pending（不填 0，不把 null 变 0）。不使用 any /
 * Record<string, unknown> 当公开 DTO。
 */

// ---------- K02 基础枚举与目录 ----------

export type Tone = 'natural' | 'professional' | 'friendly'
export type InputMode = 'text' | 'microphone'
export type ModelSource = 'platform' | 'own'
export type ProfileStatus = 'active' | 'deleted'
export type AvatarSource = 'preset' | 'personal'
export type AvatarState = 'processing' | 'ready' | 'failed' | 'revoked'
export type BackendTransport = 'mock' | 'remote'
export type BackendState = 'unavailable' | 'test_only' | 'approved'

export interface ProfileInput {
  name: string
  persona: string
  greeting: string
  tone: Tone
  avatarId: string
  voiceId: string
  catalogVersion: number
}

export interface Profile extends ProfileInput {
  id: string
  version: number
  status: ProfileStatus
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  items: T[]
  nextCursor: string | null
}

export interface AvatarItem {
  id: string
  revision: number
  name: string
  previewMediaId: string
  source: AvatarSource
  state: AvatarState
  compatibleBackendIds: string[]
  reasonCode: string | null
}

export interface VoiceItem {
  id: string
  name: string
  language: 'zh-CN'
  providerModelRef: string
  compatibleBackendIds: string[]
  enabled: boolean
}

export interface BackendItem {
  id: string
  model: string
  platformConfigId: string
  /** remote 必填、mock 为 null（K02）。 */
  platformModelVersion: string | null
  transport: BackendTransport
  state: BackendState
  supportsCustomAvatar: boolean
  supportsRecording: boolean
  width: number
  height: number
  fps: number
  measuredCapacity: number
  evidenceRef: string | null
}

export interface Limits {
  maxSessionsPerAccount: number
  maxSessionsGlobal: number
  maxQueuedGlobal: number
  sessionDurationMs: number
  idleTimeoutMs: number
  resumeWindowMs: number
  contextPairs: number
  maxRecordingMs: number
}

export interface Catalog {
  authenticated: true
  version: number
  enabled: boolean
  newSessionsAllowed: boolean
  recordingEnabled: boolean
  customAvatarEnabled: boolean
  avatars: AvatarItem[]
  voices: VoiceItem[]
  backends: BackendItem[]
  limits: Limits
  billingNoticeVersion: string
}

/** K13.1 游客目录判别形态：仅 {authenticated:false,enabled,description}，无私有项。 */
export interface PublicCatalog {
  authenticated: false
  enabled: boolean
  description: string
}

export type CatalogView = Catalog | PublicCatalog

// ---------- K03 Preflight / Session / Turn ----------

export interface BillingItem {
  stage: 'llm' | 'stt' | 'tts' | 'render'
  modelSource: ModelSource
  modelLabel: string
  chargeTo: 'user' | 'platform' | 'provider_direct'
  priceTableVersion: string
  unit: 'token' | 'second'
  /** own 的 provider_direct 项为 null（平台不知供应商自有账单）。 */
  estimatedCents: number | null
}

export interface Preflight {
  id: string
  expiresAt: string
  profileId: string
  profileVersion: number
  catalogVersion: number
  inputMode: InputMode
  modelSource: ModelSource
  llmModelLabel: string
  sttModelLabel: string
  ttsModelLabel: string
  renderModelLabel: string
  priceTableVersion: string
  billingNoticeVersion: string
  limits: Limits
  estimatedMaxCents: number
  platformCostCapCents: number
  billingItems: BillingItem[]
  renderChargeCents: number
  backendId: string
  controllerId: string
}

export interface SessionBilling {
  confirmedCents: number
  platformCostCents: number
  subsidizedCents: number
  pendingCount: number
  priceTableVersion: string
}

export type SessionState =
  | 'preparing' | 'queued' | 'connecting' | 'ready' | 'listening'
  | 'responding' | 'paused' | 'reconnecting' | 'ending' | 'ended' | 'failed'

export interface Session {
  id: string
  profileId: string
  profileRevision: number
  state: SessionState
  leaseEpoch: number
  mediaEpoch: number
  controllerId: string
  serverNow: string
  createdAt: string
  readyAt: string | null
  expiresAt: string | null
  pausedUntil: string | null
  leaseExpiresAt: string
  lastSeq: number
  saveTranscript: boolean
  transcriptVersion: number
  contentEpoch: number
  billing: SessionBilling
  errorCode: string | null
}

export type AllowedAction =
  | 'startTurn' | 'startAudio' | 'interrupt' | 'pause' | 'resume' | 'end'
  | 'saveTranscript' | 'deleteTranscript' | 'startRecording' | 'stopRecording'

/** K03：SessionSnapshot = Session 全字段「追加」三个恢复字段（机器契约为扁平结构，不嵌套 session）。 */
export interface SessionSnapshot extends Session {
  allowedActions: AllowedAction[]
  replayComplete: boolean
  replayFromSeq: number | null
}

export interface SessionSummary {
  id: string
  profileId: string
  profileNameAtCreation: string
  state: SessionState
  createdAt: string
  endedAt: string | null
  hasSavedTranscript: boolean
  recordingCount: number
  savedAssetCount: number
  billing: SessionBilling
}

export type TurnState = 'accepted' | 'transcribing' | 'generating' | 'speaking'
  | 'completed' | 'interrupted' | 'failed' | 'unknown'

export interface TurnReceipt {
  id: string
  sessionId: string
  requestId: string
  turnEpoch: number
  state: TurnState
}

export interface InterruptReceipt {
  turnId: string
  effective: boolean
  nextMediaEpoch: number
  state: SessionState
}

export type TranscriptRole = 'user' | 'assistant'
export type TranscriptStatus = 'complete' | 'interrupted' | 'truncated'

export interface TranscriptEntry {
  id: string
  utteranceSeq: number
  role: TranscriptRole
  text: string
  status: TranscriptStatus
  startedAt: string
  endedAt: string
}

export type OperationKind =
  | 'profile_create' | 'profile_update' | 'profile_delete' | 'session_create' | 'session_pause'
  | 'session_resume' | 'session_end' | 'session_delete' | 'turn_create' | 'turn_interrupt'
  | 'greeting' | 'transcript_preference' | 'transcript_save' | 'transcript_delete' | 'preview_create'
  | 'avatar_create' | 'avatar_delete' | 'recording_start' | 'recording_stop' | 'recording_save'
  | 'admin_config' | 'admin_terminate' | 'admin_reconcile' | 'playback_reset'

export type OperationState = 'pending' | 'running' | 'succeeded' | 'failed' | 'unknown'

export interface Operation {
  id: string
  kind: OperationKind
  state: OperationState
  resourceId: string | null
  resultRef: string | null
  errorCode: string | null
  createdAt: string
  updatedAt: string
}

export type RecordingState = 'recording' | 'finalizing' | 'ready' | 'saving' | 'saved' | 'failed'
  | 'expired' | 'deleted'

export interface Recording {
  id: string
  sessionId: string
  state: RecordingState
  partial: boolean
  durationMs: number
  sizeBytes: number
  startedAt: string
  endedAt: string | null
  expiresAt: string | null
  assetId: string | null
  subtitleAvailable: boolean
  errorCode: string | null
}

export type PreviewState = 'processing' | 'ready' | 'failed' | 'expired'

export interface Preview {
  id: string
  state: PreviewState
  expiresAt: string
  errorCode: string | null
}

// ---------- K08 用量 ----------

export interface UsageUnits {
  inputTokens: number | null
  outputTokens: number | null
  audioInputMs: number | null
  audioOutputMs: number | null
  textCodePoints: number | null
  renderMs: number | null
  providerRequestId: string | null
  quality: 'confirmed' | 'pending'
}

// ---------- K06 SSE 事件 ----------

export type DhEventType =
  | 'session.snapshot' | 'session.state' | 'turn.accepted' | 'transcript.final' | 'assistant.delta'
  | 'speech.segment' | 'turn.completed' | 'media.reset' | 'usage.updated' | 'recording.updated'
  | 'session.error'

export interface EventEnvelope {
  v: 1
  eventId: string
  sessionId: string
  leaseEpoch: number
  mediaEpoch: number
  turnId: string | null
  turnEpoch: number | null
  seq: number
  occurredAt: string
  payload: Record<string, unknown> & { reasonCode?: string }
}
