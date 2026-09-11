// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, ref } from 'vue'
import type { Ref } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useVideoTaskSession } from './useVideoTaskSession'
import type { VideoTaskSessionHost } from './useVideoTaskSession'
import type { VideoTask } from '../types/video-production'

/**
 * 任务书 #100 C100-05：共享任务会话（§6.8）。
 * TC-013：同账号同 task 双视图并发 acquire——最多一条 SSE/轮询通道；最后释放停止
 * 浏览器连接但不取消服务端任务；重新激活恢复同一会话。
 * TC-014：SSE 停顿 60s 降级 2s 轮询、60s 再连接；详情与单调版本权威，乱序不回退。
 * 全部网络经 mock fetch；SSE 用真实 Response（getReader 流式路径）。
 */

function detailOf(id: string, overrides: Partial<VideoTask> = {}): VideoTask {
  return {
    id, storyboardId: 'sb-1', mode: 'video', phase: 'generating', progress: 40,
    targetDurationSeconds: 30, provider: 'sandbox', model: 'm', unitPriceCents: 1,
    estimatedCostCents: 30, actualCostCents: null, actualDurationSeconds: null,
    errorCode: null, errorMessage: null, selection: {}, recommended: {},
    finalUrl: null, subtitleUrl: null, shots: [],
    selectionVersion: 0,
    ...overrides,
  }
}

interface Harness {
  mountHost(taskId: Ref<string>): { handle: VideoTaskSessionHost; unmount(): void }
  eventsFetches: Array<AbortSignal | null | undefined>
  detailFetches: string[]
  posts: string[]
  currentDetail: () => VideoTask | null
  setCurrentDetail(detail: VideoTask | null): void
  selectResult: { status: number; body: unknown }
}

function createHarness(): Harness {
  const eventsFetches: Array<AbortSignal | null | undefined> = []
  const detailFetches: string[] = []
  const posts: string[] = []
  let current: VideoTask | null = null
  const selectResult = { status: 200, body: { success: true, data: { selectionVersion: 1, selection: {} } } }

  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/events')) {
      eventsFetches.push(init?.signal)
      const stream = new ReadableStream<Uint8Array>({ start() {} })
      return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    }
    if (/^\/api\/video-production\/tasks\/[^/]+$/.test(url)) {
      detailFetches.push(String(url))
      if (!current) return { ok: false, status: 404, json: async () => ({ success: false, error: '不存在' }) }
      return { ok: true, status: 200, json: async () => ({ success: true, data: current }) }
    }
    if (init?.method === 'POST') {
      posts.push(String(url))
      if (url.endsWith('/takes/select')) {
        return { ok: selectResult.status === 200, status: selectResult.status, json: async () => selectResult.body }
      }
      return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
    }
    return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
  }))

  // taskId 引用按 §6.8 在装配期传入并可变——直接持有调用方的 ref 本体
  const mountHost = (taskId: Ref<string>) => {
    let handle!: VideoTaskSessionHost
    const Host = defineComponent({
      setup() {
        handle = useVideoTaskSession(taskId)
        return () => null
      },
    })
    const wrapper = mount(Host, { global: { plugins: [createPinia()] } })
    return { handle, unmount: () => wrapper.unmount() }
  }

  return {
    mountHost, eventsFetches, detailFetches, posts,
    currentDetail: () => current,
    setCurrentDetail: (detail) => { current = detail },
    selectResult,
  }
}

/** 共享 pinia 的挂载（跨句柄池化必须同一 pinia 实例）。 */
function createSharedHarness(): Harness & { mountShared(taskId: Ref<string>): { handle: VideoTaskSessionHost; unmount(): void } } {
  const base = createHarness()
  const sharedPinia = createPinia()
  const mountShared = (taskId: Ref<string>) => {
    let handle!: VideoTaskSessionHost
    const Host = defineComponent({
      setup() {
        handle = useVideoTaskSession(taskId)
        return () => null
      },
    })
    const wrapper = mount(Host, { global: { plugins: [sharedPinia] } })
    return { handle, unmount: () => wrapper.unmount() }
  }
  return { ...base, mountShared }
}

beforeEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('TC-013：双视图共享一条通道', () => {
  test('并发 acquire 只开一条 SSE；逐个释放；最后释放停止连接且不取消服务端任务；重新激活恢复', async () => {
    const harness = createSharedHarness()
    const taskId = ref('task-shared')
    harness.setCurrentDetail(detailOf('task-shared'))

    const host1 = harness.mountShared(taskId)
    await host1.handle.refreshTask()
    expect(harness.eventsFetches.length).toBe(1)
    expect(host1.handle.task.value?.id).toBe('task-shared')

    // 第二视图（专业模式）接入：共享会话，不另开通道
    const host2 = harness.mountShared(taskId)
    expect(host2.handle.task.value?.id).toBe('task-shared')
    expect(harness.eventsFetches.length).toBe(1)

    // 释放一个消费者：通道继续
    host1.unmount()
    expect(harness.eventsFetches[0]?.aborted).toBeFalsy()

    // 释放最后一个：停止浏览器连接；服务端任务不取消（无 /cancel）
    host2.unmount()
    expect(harness.eventsFetches[0]?.aborted).toBe(true)
    expect(harness.posts.some((url) => url.endsWith('/cancel'))).toBe(false)

    // 重新激活：同会话恢复（任务态仍在池中），通道重开
    const host3 = harness.mountShared(taskId)
    expect(host3.handle.task.value?.id).toBe('task-shared')
    await host3.handle.refreshTask()
    expect(harness.eventsFetches.length).toBe(2)
    host3.unmount()
  })

  test('acquire 同 consumerId 幂等、release 未知 ID 无副作用', async () => {
    const harness = createSharedHarness()
    harness.setCurrentDetail(detailOf('task-idem'))
    const host = harness.mountShared(ref('task-idem'))
    await host.handle.refreshTask()
    const eventsAfterBind = harness.eventsFetches.length

    host.handle.acquire('named')
    host.handle.acquire('named')
    expect(harness.eventsFetches.length).toBe(eventsAfterBind)

    host.handle.release('named')
    host.handle.release('named') // 第二次已不在集合——无副作用
    host.handle.release('never-acquired') // 未知 ID——无副作用
    // 句柄自身消费者仍在，通道保持
    expect(harness.eventsFetches[0]?.aborted).toBeFalsy()
    host.unmount()
    expect(harness.eventsFetches[0]?.aborted).toBe(true)
  })

  test('taskId 改变：先释放旧会话（通道停）再绑定新会话', async () => {
    const harness = createSharedHarness()
    harness.setCurrentDetail(detailOf('task-a'))
    const taskId = ref('task-a')
    let handle!: VideoTaskSessionHost
    const Host = defineComponent({
      setup() {
        handle = useVideoTaskSession(taskId)
        return () => null
      },
    })
    const wrapper = mount(Host, { global: { plugins: [createPinia()] } })
    await handle.refreshTask()
    expect(harness.eventsFetches.length).toBe(1)

    taskId.value = 'task-b'
    // 旧通道已停
    expect(harness.eventsFetches[0]?.aborted).toBe(true)
    // 新会话空任务——refresh 前不发详情请求；切换后落地并开新通道
    harness.setCurrentDetail(detailOf('task-b'))
    await handle.refreshTask()
    expect(handle.task.value?.id).toBe('task-b')
    expect(harness.eventsFetches.length).toBe(2)
    wrapper.unmount()
  })

  test('空 taskId 不发任何请求；写方法静默 no-op', async () => {
    const harness = createHarness()
    const host = harness.mountHost(ref(''))
    await host.handle.refreshTask()
    await host.handle.selectTake('s1', 't1')
    await host.handle.composeTask()
    expect(harness.detailFetches.length).toBe(0)
    expect(harness.eventsFetches.length).toBe(0)
    expect(harness.posts.length).toBe(0)
    host.unmount()
  })

  test('写失败落 taskError 并保留已确认数据（§6.8）', async () => {
    const harness = createSharedHarness()
    const shot = {
      id: 's1', seq: 1, visual: 'v', narration: 'n', plannedSeconds: 5, cameraMove: '固定', anchorImageIndex: 1,
      prompt: 'p', status: 'ready',
      audio: { status: null, provider: null, model: null, durationMs: null },
      takes: [{ id: 't1', takeNo: 1, status: 'succeeded', attempts: 1, provider: null, model: null,
        mediaId: null, durationMs: null, errorCode: null, errorMessage: null, selectable: true,
        score: null, scoreLabels: [], url: null }],
    }
    harness.setCurrentDetail(detailOf('task-write', {
      selectionVersion: 5, selection: { s1: 't1' }, shots: [shot] as never,
    }))
    const host = harness.mountShared(ref('task-write'))
    await host.handle.refreshTask()
    expect(host.handle.task.value?.selection.s1).toBe('t1')

    harness.selectResult.status = 500
    harness.selectResult.body = { success: false, error: '服务暂不可用' }
    await host.handle.selectTake('s1', 't1')
    expect(host.handle.taskError.value).toContain('服务暂不可用')
    // 已确认数据不被失败请求清掉
    expect(host.handle.task.value?.selection.s1).toBe('t1')
    expect(host.handle.pendingSelectionCount.value).toBe(0)
    host.unmount()
  })
})

describe('TC-014：SSE 停顿降级/再连接与版本权威', () => {
  const shotWithT1 = {
    id: 's1', seq: 1, visual: 'v', narration: 'n', plannedSeconds: 5, cameraMove: '固定', anchorImageIndex: 1,
    prompt: 'p', status: 'ready',
    audio: { status: null, provider: null, model: null, durationMs: null },
    takes: [{ id: 't1', takeNo: 1, status: 'succeeded', attempts: 1, provider: null, model: null,
      mediaId: null, durationMs: null, errorCode: null, errorMessage: null, selectable: true,
      score: null, scoreLabels: [], url: null }],
  }

  test('60s 无帧降级 2s 轮询；乱序详情（低 selectionVersion）不回退；60s 后升回 SSE', async () => {
    vi.useFakeTimers()
    try {
      const harness = createSharedHarness()
      harness.setCurrentDetail(detailOf('task-degrade', {
        selectionVersion: 5, selection: { s1: 't1' }, shots: [shotWithT1] as never,
      }))
      const host = harness.mountShared(ref('task-degrade'))
      await host.handle.refreshTask()
      expect(harness.eventsFetches.length).toBe(1)
      expect(host.handle.eventsDegraded.value).toBe(false)

      // 65s 无帧（watchdog 每 5s 巡检）→ 降级轮询
      await vi.advanceTimersByTimeAsync(65_000)
      expect(host.handle.eventsDegraded.value).toBe(true)
      const pollsAtDegrade = harness.detailFetches.length
      expect(pollsAtDegrade).toBeGreaterThanOrEqual(1)

      // 轮询期间收到乱序旧详情（selectionVersion 3、旧选择）：版本闸不回退
      harness.setCurrentDetail(detailOf('task-degrade', {
        selectionVersion: 3, selection: { s1: 't0' }, shots: [shotWithT1] as never,
      }))
      await vi.advanceTimersByTimeAsync(6_000)
      expect(harness.detailFetches.length).toBeGreaterThan(pollsAtDegrade)
      expect(host.handle.task.value?.selection.s1).toBe('t1')
      expect(host.handle.task.value?.selectionVersion).toBe(5)

      // 降级满 60s（严格大于）→ watchdog 升回 SSE；5s 巡检粒度补足余量
      await vi.advanceTimersByTimeAsync(66_000)
      expect(host.handle.eventsDegraded.value).toBe(false)
      expect(harness.eventsFetches.length).toBeGreaterThanOrEqual(2)
      host.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  test('降级轮询中任务转终态：轮询自停，不再发详情请求', async () => {
    vi.useFakeTimers()
    try {
      const harness = createSharedHarness()
      harness.setCurrentDetail(detailOf('task-final'))
      const host = harness.mountShared(ref('task-final'))
      await host.handle.refreshTask()

      await vi.advanceTimersByTimeAsync(65_000)
      expect(host.handle.eventsDegraded.value).toBe(true)
      await vi.advanceTimersByTimeAsync(4_000)
      const before = harness.detailFetches.length
      expect(before).toBeGreaterThanOrEqual(2)

      harness.setCurrentDetail(detailOf('task-final', { phase: 'succeeded', progress: 100 }))
      await vi.advanceTimersByTimeAsync(6_000) // 观察到终态的最后一拍（合法请求）
      const settled = harness.detailFetches.length
      await vi.advanceTimersByTimeAsync(20_000)
      expect(harness.detailFetches.length).toBe(settled)
      expect(host.handle.eventsDegraded.value).toBe(true) // 升回探测也已被终态收口
      host.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
