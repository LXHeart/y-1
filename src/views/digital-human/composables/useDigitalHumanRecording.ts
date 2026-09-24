/**
 * 录制域 composable（任务书 #105F C105F-04 / 共享契约 K03 API33-37、K09、K13.5）。
 *
 * - start：确认弹窗后的显式 acknowledgement=true（组件负责确认文案）；同段连点/在途只发一次；
 *   网络失败重试沿同一 requestId（后端幂等回原行，不重复建段）。
 * - stop：finalizing 起串行轮询——每次请求完成后 1 秒再发下一跳，至多 90 次；用尽停止并提示
 *   手动刷新。隐藏/换号终止轮询：账号票据 + 轮询代次双闸，迟到回包不落状态、不恢复 timer
 *   （TC105F-04-04）。
 * - save：title 1~100 本地校验；同段重试沿同一 requestId → 同一 assetId；坏产物/过期按服务端
 *   确定结果置 failed/expired（不冒充可保存）。
 * - download：API36 认证流，blob → ObjectURL → 点击后立即撤销。
 */
import { ref, shallowRef } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { Recording, Session } from '../../../types/digital-human'

export const RECORDING_POLL_INTERVAL_MS = 1000
export const RECORDING_POLL_MAX_ATTEMPTS = 90
/** API33 服务端可录状态（K05）：只有这三态可 start。 */
export const RECORDABLE_SESSION_STATES: readonly string[] = ['ready', 'listening', 'responding']
const POLLING_STATES: readonly string[] = ['recording', 'finalizing', 'saving']

function messageOf(failure: unknown): string {
  if (failure instanceof GrasslandHttpError) return failure.message
  if (failure instanceof Error && failure.message) return failure.message
  return '网络异常，请稍后重试。'
}

export function useDigitalHumanRecording(
  api: DigitalHumanApi,
  account: AccountSessionPort,
  options: { session: () => Session | null; canRecord: () => boolean },
) {
  const recording = shallowRef<Recording | null>(null)
  const starting = ref(false)
  const stopping = ref(false)
  const saving = ref(false)
  const downloading = ref(false)
  const error = ref<string | null>(null)
  const savedAssetId = ref<string | null>(null)
  const pollExhausted = ref(false)
  let polling = false
  let pollGeneration = 0
  let startKey: { sessionId: string; requestId: string } | null = null
  let saveKey: { recordingId: string; requestId: string } | null = null

  function delay(ms: number): Promise<void> {
    return new Promise((resolve) => { setTimeout(resolve, ms) })
  }

  async function start(): Promise<boolean> {
    const session = options.session()
    if (!session || starting.value || stopping.value) return false
    if (recording.value != null && POLLING_STATES.includes(recording.value.state)) return false
    if (!options.canRecord()) {
      error.value = '录制能力未开放。'
      return false
    }
    if (!RECORDABLE_SESSION_STATES.includes(session.state)) {
      error.value = '当前会话状态不可开始录制。'
      return false
    }
    starting.value = true
    error.value = null
    // 失败重试沿同一 requestId（同键同体幂等；换会话才换键）。
    if (startKey == null || startKey.sessionId !== session.id) {
      startKey = { sessionId: session.id, requestId: crypto.randomUUID() }
    }
    const ticket = account.capture()
    try {
      const result = await api.startRecording(session.id, {
        requestId: startKey.requestId,
        leaseEpoch: session.leaseEpoch,
        acknowledgement: true,
      })
      if (!account.isCurrent(ticket)) return false
      recording.value = result
      savedAssetId.value = null
      saveKey = null
      pollExhausted.value = false
      return true
    } catch (failure) {
      if (account.isCurrent(ticket)) error.value = messageOf(failure)
      return false
    } finally {
      starting.value = false
    }
  }

  async function stop(): Promise<boolean> {
    const current = recording.value
    if (!current || current.state !== 'recording' || stopping.value) return false
    stopping.value = true
    error.value = null
    const ticket = account.capture()
    try {
      const result = await api.stopRecording(current.id, { requestId: crypto.randomUUID() })
      if (!account.isCurrent(ticket)) return false
      recording.value = result
      void pollUntilSettled(current.id)
      return true
    } catch (failure) {
      if (account.isCurrent(ticket)) {
        error.value = messageOf(failure)
        // 网络失败时段可能已在服务端收尾：仍对齐一次事实，不本地猜终态。
        void pollUntilSettled(current.id)
      }
      return false
    } finally {
      stopping.value = false
    }
  }

  /** 串行轮询：请求完成 → 1 秒 → 下一跳；换号/隐藏（代次变化）或票据失效即终止。 */
  async function pollUntilSettled(recordingId: string): Promise<void> {
    if (polling) return
    polling = true
    pollExhausted.value = false
    const generation = pollGeneration
    try {
      for (let attempt = 0; attempt < RECORDING_POLL_MAX_ATTEMPTS; attempt += 1) {
        if (generation !== pollGeneration) return
        const ticket = account.capture()
        let next: Recording | null = null
        try {
          next = await api.getRecording(recordingId, ticket.signal)
        } catch {
          next = null // 单次失败继续（不无限吞错：次数上限兜底）
        }
        if (generation !== pollGeneration || !account.isCurrent(ticket)) return
        if (recording.value?.id !== recordingId) return // 已被 reset/新段接管
        if (next != null) {
          recording.value = next
          if (!POLLING_STATES.includes(next.state)) return
        }
        await delay(RECORDING_POLL_INTERVAL_MS)
      }
      pollExhausted.value = true // 90 次未收口：停轮询，等手动刷新
    } finally {
      polling = false
    }
  }

  async function save(title: string, includeSubtitles: boolean): Promise<boolean> {
    const current = recording.value
    if (!current || saving.value) return false
    if (current.state === 'saved') {
      error.value = null
      return true
    }
    if (current.state !== 'ready') {
      error.value = '录制段尚未就绪或已失效，不能保存。'
      return false
    }
    const normalized = title.trim()
    if (normalized.length < 1 || normalized.length > 100) {
      error.value = '标题长度须为 1～100 字符。'
      return false
    }
    saving.value = true
    error.value = null
    if (saveKey == null || saveKey.recordingId !== current.id) {
      saveKey = { recordingId: current.id, requestId: crypto.randomUUID() }
    }
    const ticket = account.capture()
    try {
      const operation = await api.saveRecording(current.id, {
        requestId: saveKey.requestId,
        title: normalized,
        includeSubtitles,
      })
      if (!account.isCurrent(ticket)) return false
      savedAssetId.value = operation.resultRef
      recording.value = {
        ...current,
        state: 'saved',
        assetId: operation.resultRef,
      }
      return true
    } catch (failure) {
      if (account.isCurrent(ticket)) {
        error.value = messageOf(failure)
        if (failure instanceof GrasslandHttpError) {
          if (failure.code === 'dh_media_invalid') {
            recording.value = { ...current, state: 'failed', errorCode: 'dh_media_invalid' }
          } else if (failure.status === 410 || failure.code === 'dh_recording_expired') {
            recording.value = { ...current, state: 'expired', errorCode: 'dh_recording_expired' }
          }
        }
      }
      return false
    } finally {
      saving.value = false
    }
  }

  async function download(artifact: 'mp4' | 'srt'): Promise<boolean> {
    const current = recording.value
    if (!current || downloading.value) return false
    if (artifact === 'srt' && !current.subtitleAvailable) {
      error.value = '该录制段没有字幕产物。'
      return false
    }
    downloading.value = true
    error.value = null
    const ticket = account.capture()
    try {
      const response = await api.downloadRecording(current.id, artifact, ticket.signal)
      if (!account.isCurrent(ticket)) return false
      if (!response.ok) {
        error.value = `下载失败（${response.status}）。`
        return false
      }
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `recording-${current.id.slice(0, 8)}.${artifact}`
      anchor.click()
      URL.revokeObjectURL(url)
      return true
    } catch {
      if (account.isCurrent(ticket)) error.value = '下载失败，请稍后重试。'
      return false
    } finally {
      downloading.value = false
    }
  }

  /** 手动刷新（轮询用尽后）：单次拉取并按当前票据落状态。 */
  async function refresh(): Promise<boolean> {
    const current = recording.value
    if (!current) return false
    const ticket = account.capture()
    try {
      const next = await api.getRecording(current.id, ticket.signal)
      if (!account.isCurrent(ticket)) return false
      recording.value = next
      pollExhausted.value = false
      return true
    } catch (failure) {
      if (account.isCurrent(ticket)) error.value = messageOf(failure)
      return false
    }
  }

  /** 隐藏/卸载：终止当前轮询（代次失效，在途迟到回包全丢弃）。 */
  function dispose(): void {
    pollGeneration += 1
  }

  /** 换会话/重新开始：清本地段状态与幂等键。 */
  function reset(): void {
    dispose()
    recording.value = null
    starting.value = false
    stopping.value = false
    saving.value = false
    downloading.value = false
    error.value = null
    savedAssetId.value = null
    pollExhausted.value = false
    startKey = null
    saveKey = null
  }

  return {
    recording, starting, stopping, saving, downloading, error, savedAssetId, pollExhausted,
    start, stop, save, download, refresh, dispose, reset,
  }
}
