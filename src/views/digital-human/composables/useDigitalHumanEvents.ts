/**
 * 数字人 SSE 事件流（任务书 #105E C105E-03 / 共享契约 K06）。
 *
 * - `parseSse`：纯函数增量解析（TextDecoder streaming，多行 data 合并、CRLF/LF 双边界、注释行忽略、
 *   单事件 ≤64KiB）——跨 chunk 中文不乱码是硬契约（TC105E-03-01）。
 * - `useDigitalHumanEvents`：连接管理。维护<b>状态水位</b>与<b>字幕回放水位</b>两条独立水位
 *   （K06：snapshot 的 id=S 只推进状态水位，不推进字幕水位）；严格按 eventId 去重；seq 缺口 →
 *   重开拉 snapshot 并标记「部分实时字幕未恢复」（不伪造丢失字幕、不重发 turn）；换号后旧事件丢弃。
 */
import { onScopeDispose, ref, type Ref } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { EventEnvelope } from '../../../types/digital-human'

/** K06/K13.1：单条正文/事件最大 64KiB。 */
export const SSE_MAX_EVENT_BYTES = 64 * 1024

/** K06：type 在 SSE `event:` 行（data 信封不含 type）；帧内缺省按 snapshot 处理。 */
export type DhEvent = EventEnvelope & { type: string }

/** 找到下一帧边界（\n\n 或 \r\n\r\n，容忍混用）；返回 [起始索引, 边界长度] 或 null。 */
function nextBoundary(buffer: string): [number, number] | null {
  const lf = buffer.indexOf('\n\n')
  const crlf = buffer.indexOf('\r\n\r\n')
  if (lf < 0 && crlf < 0) return null
  if (crlf < 0 || (lf >= 0 && lf < crlf)) return [lf, 2]
  return [crlf, 4]
}

/** 单帧 → 事件（data 行按 SSE 规范以 \n 拼接；event: 行=type；id:/注释行忽略）。 */
function parseFrame(frame: string): DhEvent | null {
  const dataLines: string[] = []
  let type: string | null = null
  for (const rawLine of frame.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '' || line.startsWith(':')) continue
    if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    else if (line.startsWith('event:') && type == null) type = line.slice(6).replace(/^ /, '')
  }
  if (dataLines.length === 0) return null
  return { ...(JSON.parse(dataLines.join('\n')) as EventEnvelope), type: type ?? 'session.snapshot' }
}

/** SSE 流解析：输入字节 chunk（可任意切分/多字节跨界），输出完整事件。 */
export async function* parseSse(chunks: AsyncIterable<Uint8Array>): AsyncGenerator<DhEvent> {
  const decoder = new TextDecoder()
  let buffer = ''
  for await (const chunk of chunks) {
    buffer += decoder.decode(chunk, { stream: true })
    for (;;) {
      const boundary = nextBoundary(buffer)
      if (boundary == null) break
      const [index, length] = boundary
      const frame = buffer.slice(0, index)
      buffer = buffer.slice(index + length)
      if (frame.length > SSE_MAX_EVENT_BYTES) {
        throw new Error('SSE 事件超过 64KiB 上限')
      }
      const event = parseFrame(frame)
      if (event) yield event
    }
    if (buffer.length > SSE_MAX_EVENT_BYTES) {
      throw new Error('SSE 事件超过 64KiB 上限')
    }
  }
  // 流结束：收尾解码并交付最后一个无边界帧（服务端一般以边界+注释心跳收尾，这里兜底）。
  buffer += decoder.decode()
  if (buffer.trim().length > 0) {
    const event = parseFrame(buffer)
    if (event) yield event
  }
}

export type SseStatus = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'closed' | 'error'

/** 把 Uint8Array 队列适配成字节 AsyncIterable（测试与 reader 共用）。 */
export async function* bytesOf(body: ReadableStream<Uint8Array> | null): AsyncIterable<Uint8Array> {
  if (!body) return
  const reader = body.getReader()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) return
      if (value) yield value
    }
  } finally {
    reader.releaseLock()
  }
}

export function useDigitalHumanEvents(api: DigitalHumanApi, account: AccountSessionPort): {
  status: Ref<SseStatus>
  /** 状态水位（snapshot/durable 事件推进）。 */
  stateSeq: Ref<number>
  /** 字幕回放水位（仅 transcript/正文类事件推进；snapshot 不推进它）。 */
  contentSeq: Ref<number>
  replayComplete: Ref<boolean>
  /** 缺口后的诚实提示；出现即代表「部分实时字幕未恢复」。 */
  gapNotice: Ref<boolean>
  connect: (sessionId: string) => Promise<void>
  close: () => void
  /** 事件订阅（session/media/usage/transcript 消费方注册；换号后旧事件不会送达）。 */
  onEvent: (handler: (event: DhEvent) => void) => () => void
} {
  const status = ref<SseStatus>('idle')
  const stateSeq = ref(0)
  const contentSeq = ref(0)
  const replayComplete = ref(false)
  const gapNotice = ref(false)

  const handlers = new Set<(event: DhEvent) => void>()
  const seenEventIds = new Set<string>()

  let controller: AbortController | null = null
  let generation = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  /** 缺口主动中断（区别于 close 的用户中断：中断后要重连续订拉 snapshot）。 */
  let gapReconnect = false
  /** connect 时捕获的账号票据：流上每个事件回写前对照（换号后旧事件全部丢弃）。 */
  let connectTicket: ReturnType<AccountSessionPort['capture']> | null = null

  function onEvent(handler: (event: DhEvent) => void): () => void {
    handlers.add(handler)
    return () => handlers.delete(handler)
  }

  /** 事件分类：正文类（推进字幕水位）vs 状态/用量类（推进状态水位）。 */
  function isContentEvent(event: DhEvent): boolean {
    return ['transcript.final', 'assistant.delta', 'speech.segment'].includes(event.type)
  }

  async function connect(sessionId: string): Promise<void> {
    generation += 1
    const run = generation
    connectTicket = account.capture()
    controller = new AbortController()
    status.value = 'connecting'
    // gapNotice 不在此清：一旦发生字幕缺口，本连接生命周期内如实保留提示（不伪造已恢复）。
    try {
      // K06：先建立 live 订阅读取 S 与重放，缺口由服务端 snapshot 帧（replayComplete:false）表达；
      // 客户端以 afterSeq=当前状态水位重连续订。
      const response = await api.openEvents(sessionId, stateSeq.value > 0 ? stateSeq.value : undefined, controller.signal)
      if (run !== generation || !account.isCurrent(connectTicket)) return
      status.value = 'live'
      for await (const event of parseSse(bytesOf(response.body))) {
        if (run !== generation || !account.isCurrent(connectTicket)) return
        applyEvent(event)
        if (gapReconnect) break // 缺口主动断流（真实环境走 abort，这里兜底 break）
      }
      if (run !== generation) return
      if (gapReconnect) {
        gapReconnect = false
        status.value = 'reconnecting'
        scheduleReconnect(sessionId)
        return
      }
      // 服务端正常收流（会话终态）→ closed；异常断开走 catch 重连。
      status.value = 'closed'
    } catch {
      if (run !== generation) return
      if (controller?.signal.aborted) {
        if (gapReconnect) {
          gapReconnect = false
          scheduleReconnect(sessionId)
          return
        }
        status.value = 'closed'
        return
      }
      status.value = 'reconnecting'
      scheduleReconnect(sessionId)
    }
  }

  function scheduleReconnect(sessionId: string): void {
    if (reconnectTimer != null) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void connect(sessionId)
    }, 2000)
  }

  function applyEvent(event: DhEvent): void {
    if (seenEventIds.has(event.eventId)) return // 严格 eventId 去重（K06）
    seenEventIds.add(event.eventId)

    const incoming = event.seq
    const payload = event.payload as Record<string, unknown> | null
    const isSnapshotFrame = event.type === 'session.snapshot'
      || (payload != null && typeof payload === 'object' && 'replayComplete' in payload)

    if (connectTicket != null && !account.isCurrent(connectTicket)) return // 换号防线

    // snapshot 帧先落基线（K06：snapshot 的 seq 直接推进状态水位），不参与缺口判定——
    // 重连回放的 snapshot 本身就是对缺口的权威回答。
    if (isSnapshotFrame && payload != null && typeof payload === 'object' && 'replayComplete' in payload) {
      replayComplete.value = (payload as { replayComplete?: boolean }).replayComplete !== false
      if (Number.isFinite(incoming) && incoming > stateSeq.value) stateSeq.value = incoming
      for (const handler of handlers) handler(event)
      return
    }

    if (Number.isFinite(incoming)) {
      if (incoming <= stateSeq.value && incoming <= contentSeq.value) {
        // 旧 seq（两条水位都不再需要）：忽略。
        return
      }
      if (incoming > stateSeq.value + 1 && stateSeq.value > 0 && replayComplete.value) {
        // live 阶段出现缺口：只查状态（重连拉 snapshot），不重发 turn；字幕缺口如实提示。
        gapNotice.value = true
        status.value = 'reconnecting'
        gapReconnect = true
        controller?.abort()
        return
      }
    }

    if (Number.isFinite(incoming) && incoming > stateSeq.value) stateSeq.value = incoming
    if (isContentEvent(event) && Number.isFinite(incoming) && incoming > contentSeq.value) {
      contentSeq.value = incoming
    }

    for (const handler of handlers) handler(event)
  }

  function close(): void {
    generation += 1
    gapReconnect = false
    connectTicket = null
    if (reconnectTimer != null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    controller?.abort()
    controller = null
    status.value = 'closed'
  }

  onScopeDispose(close)

  return {
    status, stateSeq, contentSeq, replayComplete, gapNotice, connect, close, onEvent,
  }
}
