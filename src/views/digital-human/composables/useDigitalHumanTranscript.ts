/**
 * 字幕域 composable（任务书 #105E C105E-05 / 共享契约 K03 API22-26、K06、K13.4）。
 *
 * - 展示：final 与「生成中」delta 分开（不把 delta 拼成完整播报）；中断/截断有标记。
 * - 同意：save 默认 false；toggle true 只影响之后的 final 落库；关闭不删已保存。
 * - 删除：API26 → 墓碑（contentEpoch 推进）；旧 contentEpoch 的迟到事件不复活（TC105E-05-02）。
 * - 保存窗口：本地观测到 ended 起 10 分钟（bufferAfterEndMs）；过期禁用、不从内存重新上传；
 *   刷新后窗口未知时首次以服务端 410/409 为准并如实解释。
 * - 导出：仅已保存文本（API25）；ObjectURL 下载后撤销；失败保留重试。正文不进任何持久存储。
 */
import { ref } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { Session, TranscriptEntry } from '../../../types/digital-human'
import type { DhEvent } from './useDigitalHumanEvents'

export const SAVE_WINDOW_MS = 10 * 60 * 1000 // K01 bufferAfterEndMs

export interface LiveGeneration {
  turnId: string | null
  /** 累积中的 delta 文本（展示为「生成中」，不当 final）。 */
  text: string
  interrupted: boolean
}

export function useDigitalHumanTranscript(
  api: DigitalHumanApi,
  account: AccountSessionPort,
  options: {
    session: () => Session | null
    now?: () => number
  } = { session: () => null },
) {
  const entries = ref<TranscriptEntry[]>([])
  const live = ref<LiveGeneration | null>(null)
  const loading = ref(false)
  const saved = ref(false)
  const transcriptVersion = ref(1)
  const tombstoneEpoch = ref(0)
  const error = ref<string | null>(null)
  const endedObservedAt = ref<number | null>(null)
  const now = options.now ?? (() => Date.now())
  const saveWindowDeadline = ref<number | null>(null)
  const saveWindowExpired = ref(false)
  let expireTimer: ReturnType<typeof setTimeout> | null = null

  /** 到点自动翻转（computed 无法感知非响应式时钟；真实时间到点驱动 UI 失效）。 */
  function armExpireTimer(): void {
    if (expireTimer != null) clearTimeout(expireTimer)
    const deadline = saveWindowDeadline.value
    if (deadline == null) return
    const remaining = deadline - now()
    expireTimer = setTimeout(() => { saveWindowExpired.value = true }, Math.max(0, remaining))
  }

  function currentSession(): Session | null {
    return options.session()
  }

  async function reload(): Promise<void> {
    const session = currentSession()
    if (!session) return
    const ticket = account.capture()
    loading.value = true
    try {
      const page = await api.listTranscript(session.id, {}, ticket.signal)
      if (!account.isCurrent(ticket)) return
      entries.value = page.items
    } catch (failure) {
      // 未保存/无同意时列表可能为空或 404——保留本地展示，不误报错误。
      if (account.isCurrent(ticket) && failure instanceof GrasslandHttpError && failure.status >= 500) {
        error.value = failure.message
      }
    } finally {
      if (account.isCurrent(ticket)) loading.value = false
    }
  }

  /** SSE 消费：delta/final/speech.segment；删除墓碑后旧 epoch 全部丢弃。 */
  function applyEvent(event: DhEvent): void {
    const session = currentSession()
    if (!session || event.sessionId !== session.id) return
    const payload = event.payload as Record<string, unknown>
    const eventEpoch = typeof payload.contentEpoch === 'number' ? payload.contentEpoch : session.contentEpoch
    if (eventEpoch < tombstoneEpoch.value) return // 墓碑：旧 epoch 不复活（TC105E-05-02）
    if (eventEpoch < session.contentEpoch) tombstoneEpoch.value = session.contentEpoch

    if (event.type === 'assistant.delta') {
      const delta = typeof payload.delta === 'string' ? payload.delta : ''
      const turnId = event.turnId
      if (live.value == null || live.value.turnId !== turnId) {
        live.value = { turnId, text: delta, interrupted: false }
      } else {
        live.value = { ...live.value, text: live.value.text + delta }
      }
      return
    }
    if (event.type === 'transcript.final') {
      // final 落地：清对应 live（delta 不再展示），条目带 status（interrupted 不当完整播报）。
      if (live.value != null && (event.turnId == null || live.value.turnId === event.turnId)) {
        live.value = null
      }
      const entry: TranscriptEntry = {
        id: typeof payload.utteranceId === 'string' ? payload.utteranceId : event.eventId,
        utteranceSeq: typeof payload.utteranceSeq === 'number' ? payload.utteranceSeq : event.seq,
        role: payload.role === 'assistant' ? 'assistant' : 'user',
        text: typeof payload.text === 'string' ? payload.text : '',
        status: payload.status === 'interrupted' ? 'interrupted' : payload.status === 'truncated' ? 'truncated' : 'complete',
        startedAt: typeof payload.startedAt === 'string' ? payload.startedAt : event.occurredAt,
        endedAt: typeof payload.endedAt === 'string' ? payload.endedAt : event.occurredAt,
      }
      entries.value = [...entries.value.filter((item) => item.id !== entry.id), entry]
        .sort((left, right) => left.utteranceSeq - right.utteranceSeq)
      return
    }
    if (event.type === 'turn.completed' && payload.status === 'interrupted') {
      if (live.value != null) live.value = { ...live.value, interrupted: true }
    }
  }

  /** 本地观测到会话结束：保存窗口起点（刷新后未知，以服务端拒绝为准）。 */
  function observeEnded(): void {
    if (endedObservedAt.value == null) {
      endedObservedAt.value = now()
      saveWindowDeadline.value = endedObservedAt.value + SAVE_WINDOW_MS
      saveWindowExpired.value = false
      armExpireTimer()
    }
  }

  async function setSave(value: boolean): Promise<boolean> {
    const session = currentSession()
    if (!session) return false
    const ticket = account.capture()
    try {
      const result = await api.setTranscriptPreference(session.id, {
        requestId: crypto.randomUUID(), expectedVersion: transcriptVersion.value, saveTranscript: value,
      })
      if (!account.isCurrent(ticket)) return false
      saved.value = result.saveTranscript
      transcriptVersion.value = result.version
      return true
    } catch (failure) {
      if (account.isCurrent(ticket) && failure instanceof GrasslandHttpError
        && failure.code === 'dh_version_conflict') {
        // 版本被他处更新：拉权威状态对齐（不猜）。
        await reloadSessionAlignment()
      }
      return false
    }
  }

  async function reloadSessionAlignment(): Promise<void> {
    const session = currentSession()
    if (!session) return
    const ticket = account.capture()
    try {
      const snapshot = await api.getSession(session.id, ticket.signal)
      if (!account.isCurrent(ticket)) return
      saved.value = snapshot.saveTranscript
      transcriptVersion.value = snapshot.transcriptVersion
      tombstoneEpoch.value = Math.max(tombstoneEpoch.value, snapshot.contentEpoch)
    } catch {
      // 对齐失败保留下次动作再试
    }
  }

  async function saveNow(): Promise<boolean> {
    const session = currentSession()
    if (!session) return false
    if (saveWindowExpired.value) return false // 过期不从内存重新上传（E-05 步骤3）
    const ticket = account.capture()
    try {
      await api.saveTranscript(session.id, {
        requestId: crypto.randomUUID(), expectedVersion: transcriptVersion.value,
      })
      return account.isCurrent(ticket)
    } catch (failure) {
      if (account.isCurrent(ticket) && failure instanceof GrasslandHttpError
        && (failure.status === 410 || failure.code === 'dh_content_expired' || failure.code === 'dh_content_deleted')) {
        // 窗口已过/内容已删：如实置过期，按钮失效。
        saveWindowExpired.value = true
      }
      return false
    }
  }

  async function exportTxt(): Promise<boolean> {
    const session = currentSession()
    if (!session) return false
    const ticket = account.capture()
    try {
      const response = await api.exportTranscript(session.id, ticket.signal)
      if (!account.isCurrent(ticket)) return false
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `transcript-${session.id.slice(0, 8)}.txt`
      anchor.click()
      URL.revokeObjectURL(url) // 下载触发后立即撤销，不留可引用对象
      return true
    } catch {
      return false // 失败保留内容与手动重试（不自动循环）
    }
  }

  async function deleteSaved(): Promise<boolean> {
    const session = currentSession()
    if (!session) return false
    const ticket = account.capture()
    const requestEpoch = Math.max(tombstoneEpoch.value, session.contentEpoch)
    try {
      await api.deleteTranscript(session.id, crypto.randomUUID())
      if (!account.isCurrent(ticket)) return false
      // 墓碑：立刻禁读、旧 epoch 迟到事件不复活；条目清空但删除说明保留展示。
      tombstoneEpoch.value = requestEpoch + 1
      entries.value = []
      live.value = null
      return true
    } catch {
      return false
    }
  }

  function clear(): void {
    entries.value = []
    live.value = null
    loading.value = false
    saved.value = false
    transcriptVersion.value = 1
    tombstoneEpoch.value = 0
    endedObservedAt.value = null
    saveWindowDeadline.value = null
    saveWindowExpired.value = false
    if (expireTimer != null) {
      clearTimeout(expireTimer)
      expireTimer = null
    }
    error.value = null
  }

  return {
    entries, live, loading, saved, transcriptVersion, tombstoneEpoch, saveWindowDeadline,
    saveWindowExpired, error,
    setSave, saveNow, exportTxt, deleteSaved, applyEvent, observeEnded, reload, clear,
  }
}
