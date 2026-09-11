import { ref } from 'vue'
import type { Ref } from 'vue'
import { fetchApi } from './grassland-http'

/**
 * 任务书 #100 C100-05：任务 SSE/轮询/看门狗生命周期（自 useVideoProduction 抽出，语义沿用 #65 卡5）。
 *
 * §6.7：GET /events、Accept:text/event-stream；60s 无有效事件降级、2s 轮询、60s 再连接、5s 看门狗。
 * 事件只作刷新提示——任务详情与单调版本才是权威，乱序事件不回退状态。
 * 终态（succeeded/failed/cancelled）任一通道收口：SSE 由后端 complete，轮询在 tick 内自停。
 */
export interface VideoTaskEventsOptions {
  /** 当前任务 id（空串＝无任务，不开通道、不发请求）。 */
  taskId: () => string
  /** 终态判定（succeeded/failed/cancelled 任一即收口）。 */
  isTerminal: () => boolean
  /** 快照拉取（SSE 事件合并触发与轮询 tick 共用；不改状态）。 */
  refresh: () => Promise<void>
}

export interface VideoTaskEvents {
  /** SSE 断流后处于 2s 轮询降级态。 */
  degraded: Ref<boolean>
  /** 看门狗是否活跃（SSE 通道或降级探测持有；合成后「无通道才开轮询」的判定）。 */
  hasChannel(): boolean
  /** 打开 SSE + 看门狗；空任务/终态 no-op，已活跃不重复开。 */
  start(): void
  /** 全停：中止流、清看门狗与轮询计时器（服务端任务不受影响）。 */
  stop(): void
  /** 无通道时的轮询兜底（合成请求发出后）。 */
  fallbackPolling(): void
}

export function createVideoTaskEvents(options: VideoTaskEventsOptions): VideoTaskEvents {
  /** 心跳周期 30s；2 个周期（60s）无帧回落轮询；降级后每 60s 尝试升回。 */
  const SSE_DEGRADE_AFTER_MS = 60_000
  const SSE_RECONNECT_AFTER_MS = 60_000
  const WATCHDOG_INTERVAL_MS = 5_000
  const EVENT_REFRESH_COALESCE_MS = 300
  const POLL_INTERVAL_MS = 2_000

  const degraded = ref(false)
  let controller: AbortController | null = null
  let watchdog: ReturnType<typeof setInterval> | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let lastFrameAt = 0
  let lastReconnectAt = 0
  let refreshTimer: ReturnType<typeof setTimeout> | null = null

  function hasChannel(): boolean {
    return watchdog !== null
  }

  function stopPolling(): void {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function stopStream(clearWatchdog = true): void {
    controller?.abort()
    controller = null
    if (refreshTimer) {
      clearTimeout(refreshTimer)
      refreshTimer = null
    }
    if (clearWatchdog && watchdog) {
      clearInterval(watchdog)
      watchdog = null
    }
  }

  function startPolling(): void {
    stopPolling()
    const tick = async () => {
      await options.refresh()
      if (options.isTerminal()) {
        stop()
        return
      }
      pollTimer = setTimeout(tick, POLL_INTERVAL_MS)
    }
    pollTimer = setTimeout(tick, POLL_INTERVAL_MS)
  }

  /** 事件合并刷新：窗口内多个事件只拉一次快照。 */
  function scheduleEventRefresh(): void {
    if (refreshTimer) return
    refreshTimer = setTimeout(async () => {
      refreshTimer = null
      await options.refresh()
      if (options.isTerminal()) stop()
    }, EVENT_REFRESH_COALESCE_MS)
  }

  /** 断流/异常 → 回落 2s 轮询并标记 degraded；watchdog 周期尝试升回。 */
  function degradeToPolling(): void {
    if (!options.taskId() || options.isTerminal()) return
    degraded.value = true
    lastReconnectAt = Date.now()
    stopStream(false)
    startPolling()
  }

  function watchdogTick(): void {
    if (!options.taskId() || options.isTerminal()) {
      stop()
      return
    }
    if (!degraded.value) {
      if (Date.now() - lastFrameAt > SSE_DEGRADE_AFTER_MS) {
        controller?.abort()
        degradeToPolling()
      }
      return
    }
    if (Date.now() - lastReconnectAt > SSE_RECONNECT_AFTER_MS) {
      // 升回：重开 SSE（心跳续命则留在事件通道；2 周期无帧由同一 watchdog 再降级）
      stopPolling()
      start()
    }
  }

  async function consumeTaskEvents(stream: AbortController): Promise<void> {
    const taskId = options.taskId()
    if (!taskId) return
    try {
      const response = await fetchApi(`/api/video-production/tasks/${taskId}/events`, {
        headers: { Accept: 'text/event-stream' },
        signal: stream.signal,
      })
      if (!response.ok || !response.body) {
        throw new Error('事件流打开失败')
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        if (stream.signal.aborted) {
          reader.cancel().catch(() => undefined)
          break
        }
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''
        for (const line of lines) {
          if (!line.startsWith('data: ')) continue
          const payload = line.slice(6).trim()
          if (!payload || payload === '[DONE]') continue
          lastFrameAt = Date.now()
          let type = ''
          try {
            type = (JSON.parse(payload) as { type?: string }).type ?? ''
          } catch {
            continue
          }
          // 心跳只续命；其余事件合并触发一次快照拉取（渲染以快照为准）
          if (type !== 'heartbeat') {
            scheduleEventRefresh()
          }
        }
      }
      // 流正常收口（终态后端 complete）——补一次快照，不降级
      scheduleEventRefresh()
    } catch {
      if (!stream.signal.aborted) {
        degradeToPolling()
      }
    } finally {
      if (controller === stream) {
        controller = null
      }
    }
  }

  function start(): void {
    if (!options.taskId() || options.isTerminal()) return
    stopStream()
    degraded.value = false
    lastFrameAt = Date.now()
    const stream = new AbortController()
    controller = stream
    void consumeTaskEvents(stream)
    if (!watchdog) watchdog = setInterval(watchdogTick, WATCHDOG_INTERVAL_MS)
  }

  function stop(): void {
    stopStream()
    stopPolling()
  }

  return { degraded, hasChannel, start, stop, fallbackPolling: startPolling }
}
