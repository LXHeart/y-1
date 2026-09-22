/**
 * 数字人工作台 API 层（任务书 #105B C105B-04 / 共享契约 K03）。
 *
 * - JSON 请求统一走 {@link request}（解 `{success,data}` 信封，非 2xx 抛 {@link GrasslandHttpError}
 *   保留 status/code）；SSE/文件走 {@link fetchApi}（由调用方拥有响应体）；presigned PUT 走既有
 *   {@link putToPresignedUrl}（cookie 不发对象存储）。**不复制登录**——cookie 注入是 grassland-http
 *   的传输层职责。
 * - 读操作接 AccountTicket.signal（换号/注销即可中断在途读）；写操作不因客户端 abort 推断后端撤销
 *   或退款。requestId 由上层动作生成并复用（重试同键），本层**不做** randomUUID。
 * - 公开金额/序号按 K01：null 原样（不填 0）；超过 JS 安全整数的序号在解码校验中拒绝。
 */
import {
  fetchApi, putToPresignedUrl, request,
} from './grassland-http'
import type {
  Catalog, CatalogView, EventEnvelope, InterruptReceipt, Operation, Page, Preflight, Profile,
  ProfileInput, PublicCatalog, Recording, Session, SessionSnapshot, SessionState, SessionSummary,
  TranscriptEntry, TurnReceipt,
} from '../types/digital-human'
import type { MediaUploadTicket } from '../types/grassland'

/** K01：Seq/Epoch 上界 2^53-1；超界值拒绝，不静默收窄。 */
export const JS_SAFE_INTEGER_MAX = 9007199254740991

export function assertSafeInteger(value: number, field: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${field} 超出 JS 安全整数范围或为负：${value}`)
  }
  return value
}

/** 会话解码校验：null 原样（未知金额/时间不填 0）、序号与 epoch 安全整数（TC105B-04-04）。 */
export function decodeSession(raw: unknown): Session {
  const session = raw as Session
  for (const field of ['lastSeq', 'leaseEpoch', 'mediaEpoch', 'contentEpoch'] as const) {
    if (session[field] != null) assertSafeInteger(session[field], field)
  }
  if (session.billing) {
    // 金额可为 null（pending；K01 未知不填 0）——非 null 时才校验整数。
    for (const field of ['confirmedCents', 'platformCostCents', 'subsidizedCents'] as const) {
      if (session.billing[field] != null) assertSafeInteger(session.billing[field], `billing.${field}`)
    }
    if (session.billing.pendingCount != null) {
      assertSafeInteger(session.billing.pendingCount, 'billing.pendingCount')
    }
  }
  return session
}

export interface DigitalHumanApi {
  /** API01：游客得 PublicCatalog，登录得 Catalog。 */
  getCatalog(signal?: AbortSignal): Promise<CatalogView>
  /** API02 */
  listProfiles(input: { cursor?: string; limit?: number }, signal?: AbortSignal): Promise<Page<Profile>>
  /** API03：requestId 由上层动作生成；同键重试回原 Profile。 */
  createProfile(input: ProfileInput & { requestId: string }): Promise<Profile>
  /** API04 */
  getProfile(id: string, signal?: AbortSignal): Promise<Profile>
  /** API05：完整 ProfileInput + expectedVersion（PUT 语义用 PATCH 端点全表单）。 */
  updateProfile(id: string, input: ProfileInput & { expectedVersion: number; requestId: string }): Promise<Profile>
  /** API06：DELETE 带 JSON requestId（fetch 支持，不用 204）。 */
  deleteProfile(id: string, requestId: string): Promise<{ id: string; status: 'deleted' }>
  /** API07 */
  createPreflight(input: {
    profileId: string; profileVersion: number; inputMode: 'text' | 'microphone'; controllerId: string
  }): Promise<Preflight>
  /** API08 */
  createSession(input: { preflightId: string; requestId: string; saveTranscript: boolean }): Promise<Session>
  /** API09 */
  listSessions(input: {
    cursor?: string; limit?: number; profileId?: string; state?: SessionState
    from?: string; to?: string
  }, signal?: AbortSignal): Promise<Page<SessionSummary>>
  /** API10 */
  getSession(id: string, signal?: AbortSignal): Promise<SessionSnapshot>
  /** API11 */
  pauseSession(id: string, input: { requestId: string; leaseEpoch: number; reason: 'hidden' }): Promise<Session>
  /** API12 */
  resumeSession(id: string, input: {
    requestId: string; leaseEpoch: number; takeover: boolean; controllerId: string
  }): Promise<Session>
  /** API13 */
  heartbeat(id: string, input: { leaseEpoch: number; controllerId: string }): Promise<{
    serverNow: string; leaseExpiresAt: string; pausedUntil: string | null
  }>
  /** API14 */
  endSession(id: string, input: { requestId: string; reason: 'user' }): Promise<Session>
  /** API15 */
  createConnectionGrant(id: string, input: {
    leaseEpoch: number; channel: 'audio'
  }): Promise<{ grant: string; expiresAt: string; wsPath: string }>
  /** API16 */
  submitOffer(id: string, input: {
    requestId: string; leaseEpoch: number; mediaEpoch: number; sdp: string; type: 'offer'
  }): Promise<{ sdp: string; type: 'answer'; mediaEpoch: number; iceServers: unknown[] }>
  /** API17 */
  mediaReady(id: string, input: { requestId: string; leaseEpoch: number; mediaEpoch: number }): Promise<Session>
  /** API18：SSE 走 fetchApi（流由调用方拥有）；afterSeq 与 Last-Event-ID 互斥由调用方保证。 */
  openEvents(id: string, afterSeq?: number, signal?: AbortSignal): Promise<Response>
  /** API19 */
  createTurn(id: string, input: { requestId: string; leaseEpoch: number; text: string }): Promise<TurnReceipt>
  /** API20 */
  interrupt(id: string, input: {
    requestId: string; leaseEpoch: number; turnId: string; turnEpoch: number
  }): Promise<InterruptReceipt>
  /** API21 */
  requestGreeting(id: string, input: { requestId: string; leaseEpoch: number }): Promise<TurnReceipt>
  /** API22 */
  setTranscriptPreference(id: string, input: {
    requestId: string; expectedVersion: number; saveTranscript: boolean
  }): Promise<{ saveTranscript: boolean; version: number }>
  /** API23 */
  saveTranscript(id: string, input: {
    requestId: string; expectedVersion: number
  }): Promise<{ savedUtterances: number; version: number }>
  /** API24 */
  listTranscript(id: string, input: { cursor?: string; limit?: number }, signal?: AbortSignal):
    Promise<Page<TranscriptEntry>>
  /** API25：txt 导出（文件流走 fetchApi）。 */
  exportTranscript(id: string, signal?: AbortSignal): Promise<Response>
  /** API26 */
  deleteTranscript(id: string, requestId: string): Promise<Operation>
  /** API27 */
  createVoicePreview(input: { requestId: string; voiceId: string; catalogVersion: number }): Promise<{
    id: string; state: string; expiresAt: string; errorCode: string | null
  }>
  /** API28 */
  getVoicePreview(id: string, signal?: AbortSignal): Promise<{
    id: string; state: string; expiresAt: string; errorCode: string | null
  }>
  /** API29：音频流（fetchApi，认证流不写素材库）。 */
  getVoicePreviewAudio(id: string, signal?: AbortSignal): Promise<Response>
  /** API30 */
  createAvatar(input: {
    requestId: string; mediaId: string; rightsAccepted: true; rightsVersion: 'dh-avatar-v1'
  }): Promise<Operation>
  /** API31 */
  getAvatar(id: string, signal?: AbortSignal): Promise<unknown>
  /** API32 */
  deleteAvatar(id: string, requestId: string): Promise<Operation>
  /** API33 */
  startRecording(id: string, input: {
    requestId: string; leaseEpoch: number; acknowledgement: true
  }): Promise<Recording>
  /** API34 */
  stopRecording(recordingId: string, input: { requestId: string }): Promise<Recording>
  /** API35 */
  getRecording(recordingId: string, signal?: AbortSignal): Promise<Recording>
  /** API36：认证文件流/Range（fetchApi）。 */
  downloadRecording(recordingId: string, artifact: 'mp4' | 'srt', signal?: AbortSignal): Promise<Response>
  /** API37 */
  saveRecording(recordingId: string, input: {
    requestId: string; title: string; includeSubtitles: boolean
  }): Promise<Operation>
  /** API38 */
  deleteSession(id: string, requestId: string): Promise<Operation>
  /** API39 */
  getOperation(operationId: string, signal?: AbortSignal): Promise<Operation>
  /** API40 */
  playbackReset(id: string, input: { requestId: string; leaseEpoch: number }): Promise<{
    mediaEpoch: number; state: 'connecting'
  }>
  /** presigned PUT 复用既有出口（cookie 不发对象存储）。 */
  putToPresigned(ticket: MediaUploadTicket, file: File): Promise<void>
}

const BASE = '/api/digital-human'

function jsonInit(method: string, body: unknown, signal?: AbortSignal): RequestInit {
  const init: RequestInit = { method, body: JSON.stringify(body) }
  if (signal) init.signal = signal
  return init
}

function readInit(signal?: AbortSignal): RequestInit {
  return signal ? { signal } : {}
}

/** 纯工厂：无 store 耦合；signal 由调用方（useDigitalHumanApi）注入。 */
export function createDigitalHumanApi(): DigitalHumanApi {
  return {
    async getCatalog(signal) {
      return await request<CatalogView>(`${BASE}/catalog`, readInit(signal))
    },
    async listProfiles(input, signal) {
      const query = new URLSearchParams()
      if (input.cursor) query.set('cursor', input.cursor)
      if (input.limit != null) query.set('limit', String(input.limit))
      const suffix = query.size > 0 ? `?${query}` : ''
      return await request<Page<Profile>>(`${BASE}/profiles${suffix}`, readInit(signal))
    },
    async createProfile(input) {
      return await request<Profile>(`${BASE}/profiles`, jsonInit('POST', input))
    },
    async getProfile(id, signal) {
      return await request<Profile>(`${BASE}/profiles/${id}`, readInit(signal))
    },
    async updateProfile(id, input) {
      return await request<Profile>(`${BASE}/profiles/${id}`, jsonInit('PATCH', input))
    },
    async deleteProfile(id, requestId) {
      return await request<{ id: string; status: 'deleted' }>(`${BASE}/profiles/${id}`,
        jsonInit('DELETE', { requestId }))
    },
    async createPreflight(input) {
      return await request<Preflight>(`${BASE}/preflights`, jsonInit('POST', input))
    },
    async createSession(input) {
      // 202 信封由 request 解析；会话序号/epoch 解码校验（TC105B-04-04）。
      return decodeSession(await request<Session>(`${BASE}/sessions`, jsonInit('POST', input)))
    },
    async listSessions(input, signal) {
      const query = new URLSearchParams()
      for (const key of ['cursor', 'limit', 'profileId', 'state', 'from', 'to'] as const) {
        const value = input[key]
        if (value != null && value !== '') query.set(key, String(value))
      }
      const suffix = query.size > 0 ? `?${query}` : ''
      return await request<Page<SessionSummary>>(`${BASE}/sessions${suffix}`, readInit(signal))
    },
    async getSession(id, signal): Promise<SessionSnapshot> {
      // 扁平 SessionSnapshot：直接按 Session 字段集校验（含追加字段）。
      const snapshot = await request<SessionSnapshot>(`${BASE}/sessions/${id}`, readInit(signal))
      return decodeSession(snapshot) as SessionSnapshot
    },
    async pauseSession(id, input) {
      return decodeSession(await request<Session>(`${BASE}/sessions/${id}/pause`, jsonInit('POST', input)))
    },
    async resumeSession(id, input) {
      return decodeSession(await request<Session>(`${BASE}/sessions/${id}/resume`, jsonInit('POST', input)))
    },
    async heartbeat(id, input) {
      return await request<{ serverNow: string; leaseExpiresAt: string; pausedUntil: string | null }>(
        `${BASE}/sessions/${id}/heartbeat`, jsonInit('POST', input))
    },
    async endSession(id, input) {
      return decodeSession(await request<Session>(`${BASE}/sessions/${id}/end`, jsonInit('POST', input)))
    },
    async createConnectionGrant(id, input) {
      return await request<{ grant: string; expiresAt: string; wsPath: string }>(
        `${BASE}/sessions/${id}/connection-grants`, jsonInit('POST', input))
    },
    async submitOffer(id, input) {
      return await request<{ sdp: string; type: 'answer'; mediaEpoch: number; iceServers: unknown[] }>(
        `${BASE}/sessions/${id}/webrtc/offer`, jsonInit('POST', input))
    },
    async mediaReady(id, input) {
      return decodeSession(
        await request<Session>(`${BASE}/sessions/${id}/media-ready`, jsonInit('POST', input)))
    },
    async openEvents(id, afterSeq, signal) {
      // SSE：fetchApi 拥有响应体（Last-Event-ID 头由调用方按重连水位给出）。
      const query = afterSeq != null ? `?afterSeq=${afterSeq}` : ''
      return await fetchApi(`${BASE}/sessions/${id}/events${query}`, readInit(signal))
    },
    async createTurn(id, input) {
      return await request<TurnReceipt>(`${BASE}/sessions/${id}/turns`, jsonInit('POST', input))
    },
    async interrupt(id, input) {
      return await request<InterruptReceipt>(`${BASE}/sessions/${id}/interrupt`, jsonInit('POST', input))
    },
    async requestGreeting(id, input) {
      return await request<TurnReceipt>(`${BASE}/sessions/${id}/greeting`, jsonInit('POST', input))
    },
    async setTranscriptPreference(id, input) {
      return await request<{ saveTranscript: boolean; version: number }>(
        `${BASE}/sessions/${id}/transcript-preference`, jsonInit('PATCH', input))
    },
    async saveTranscript(id, input) {
      return await request<{ savedUtterances: number; version: number }>(
        `${BASE}/sessions/${id}/transcript-save`, jsonInit('POST', input))
    },
    async listTranscript(id, input, signal) {
      const query = new URLSearchParams()
      if (input.cursor) query.set('cursor', input.cursor)
      if (input.limit != null) query.set('limit', String(input.limit))
      const suffix = query.size > 0 ? `?${query}` : ''
      return await request<Page<TranscriptEntry>>(`${BASE}/sessions/${id}/transcript${suffix}`,
        readInit(signal))
    },
    async exportTranscript(id, signal) {
      return await fetchApi(`${BASE}/sessions/${id}/transcript/export?format=txt`, readInit(signal))
    },
    async deleteTranscript(id, requestId) {
      return await request<Operation>(`${BASE}/sessions/${id}/transcript`, jsonInit('DELETE', { requestId }))
    },
    async createVoicePreview(input) {
      return await request<{ id: string; state: string; expiresAt: string; errorCode: string | null }>(
        `${BASE}/voice-previews`, jsonInit('POST', input))
    },
    async getVoicePreview(id, signal) {
      return await request<{ id: string; state: string; expiresAt: string; errorCode: string | null }>(
        `${BASE}/voice-previews/${id}`, readInit(signal))
    },
    async getVoicePreviewAudio(id, signal) {
      return await fetchApi(`${BASE}/voice-previews/${id}/audio`, readInit(signal))
    },
    async createAvatar(input) {
      return await request<Operation>(`${BASE}/avatars`, jsonInit('POST', input))
    },
    async getAvatar(id, signal) {
      return await request(`${BASE}/avatars/${id}`, readInit(signal))
    },
    async deleteAvatar(id, requestId) {
      return await request<Operation>(`${BASE}/avatars/${id}`, jsonInit('DELETE', { requestId }))
    },
    async startRecording(id, input) {
      return await request<Recording>(`${BASE}/sessions/${id}/recordings`, jsonInit('POST', input))
    },
    async stopRecording(recordingId, input) {
      return await request<Recording>(`${BASE}/recordings/${recordingId}/stop`, jsonInit('POST', input))
    },
    async getRecording(recordingId, signal) {
      return await request<Recording>(`${BASE}/recordings/${recordingId}`, readInit(signal))
    },
    async downloadRecording(recordingId, artifact, signal) {
      return await fetchApi(`${BASE}/recordings/${recordingId}/download?artifact=${artifact}`, readInit(signal))
    },
    async saveRecording(recordingId, input) {
      return await request<Operation>(`${BASE}/recordings/${recordingId}/save`, jsonInit('POST', input))
    },
    async deleteSession(id, requestId) {
      return await request<Operation>(`${BASE}/sessions/${id}`, jsonInit('DELETE', { requestId }))
    },
    async getOperation(operationId, signal) {
      return await request<Operation>(`${BASE}/operations/${operationId}`, readInit(signal))
    },
    async playbackReset(id, input) {
      return await request<{ mediaEpoch: number; state: 'connecting' }>(
        `${BASE}/sessions/${id}/playback-reset`, jsonInit('POST', input))
    },
    async putToPresigned(ticket, file) {
      await putToPresignedUrl(ticket, file)
    },
  }
}

/** 游客/登录目录判别：B 类型用 authenticated 判别，不假设字段存在。 */
export function isPersonalCatalog(view: CatalogView): view is Catalog {
  return view.authenticated === true
}

export function isPublicCatalog(view: CatalogView): view is PublicCatalog {
  return view.authenticated === false
}

/** SSE 逐行解析助手（NDJSON/SSE 帧：`id:`/`event:`/`data:` 行）。 */
export async function* readEventStream(response: Response): AsyncGenerator<EventEnvelope> {
  if (!response.body) return
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const dataLine = frame.split('\n').find((line) => line.startsWith('data:'))
        if (dataLine) {
          yield JSON.parse(dataLine.slice(5).trim()) as EventEnvelope
        }
        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    reader.releaseLock()
  }
}
