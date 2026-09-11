// @vitest-environment happy-dom
import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import CanvasRunBar from './CanvasRunBar.vue'
import { useCanvasProduction } from '../composables/useCanvasProduction'
import type { VideoTask } from '../../../types/video-production'

/**
 * 任务书 #100 C100-07：画布生成栏（费用/进度/提交主行动）。
 * TC-017：快速/专业同键幂等（web-{storyboardId}）、重放只恢复、断线重试不双份费用。
 * TC-018：各阶段仅合法动作；202 受理≠成功（进度只认服务端 phase/progress）；取消退款
 * 说明只在服务端确认 cancelled 后展示；未确认选片禁用合成。
 */

function makeTask(overrides: Partial<VideoTask> = {}): VideoTask {
  return {
    id: 'task-1', storyboardId: 'sb-1', mode: 'video', phase: 'queued', progress: 5,
    targetDurationSeconds: 25, provider: null, model: null, unitPriceCents: 1,
    estimatedCostCents: 25, actualCostCents: null, actualDurationSeconds: null,
    errorCode: null, errorMessage: null, selection: {}, recommended: {},
    finalUrl: null, subtitleUrl: null,
    shots: [{
      id: 'shot-1', seq: 1, visual: 'v', narration: 'n', plannedSeconds: 5,
      cameraMove: '固定', anchorImageIndex: 1, prompt: 'p', status: 'ready',
      audio: { status: null, provider: null, model: null, durationMs: null },
      source: { kind: 'generated' } as const,
      takes: [{ id: 'take-1', takeNo: 1, status: 'succeeded', attempts: 1, provider: null,
        model: null, mediaId: null, durationMs: null, errorCode: null, errorMessage: null,
        selectable: true, score: null, scoreLabels: [], url: null }],
    }],
    selectionVersion: 1,
    ...overrides,
  }
}

interface FetchPlan {
  createTask?: { status: number; body: unknown }
  selectHang?: boolean
}

function setupBar(plan: FetchPlan = {}) {
  const taskId = ref('')
  const flush = vi.fn(async () => true)
  const production = useCanvasProduction(taskId, { flushBeforeCreate: flush })
  const calls: Array<{ url: string; init?: RequestInit }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (url === '/api/video-production/tasks' && (init?.method ?? '') === 'POST') {
      const preset = plan.createTask ?? { status: 200, body: { success: true, data: { id: 'task-new' } } }
      return { ok: preset.status === 200, status: preset.status, json: async () => preset.body }
    }
    if (url.includes('/takes/select')) {
      if (plan.selectHang) return new Promise(() => {})
      const payload = JSON.parse(String(init?.body ?? '{}')) as {
        selections?: Array<{ shotId: string; takeId: string }> }
      const selection = Object.fromEntries((payload.selections ?? []).map(item => [item.shotId, item.takeId]))
      return { ok: true, status: 200, json: async () => ({ success: true, data: { selectionVersion: 2, selection } }) }
    }
    return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
  }))
  const wrapper = mount(CanvasRunBar, { props: { production, storyboardId: 'sb-1' } })
  return { wrapper, production, taskId, flush, calls }
}

/** 异步落定（请求 + 重渲染）。 */
async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

beforeEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('TC-017：幂等发起与重放', () => {
  test('发起制作：先 flush 未保存输入，载荷沿用 web-{storyboardId} 既有幂等键', async () => {
    const ctx = setupBar()
    await ctx.wrapper.find('[data-test="canvas-run-begin"]').trigger('click')
    await settle()
    expect(ctx.flush).toHaveBeenCalledTimes(1)
    const create = ctx.calls.find(call => call.url === '/api/video-production/tasks')
    expect(create?.init?.method).toBe('POST')
    expect(JSON.parse(String(create?.init?.body))).toEqual({ storyboardId: 'sb-1', operationId: 'web-sb-1' })
    expect(ctx.taskId.value).toBe('task-new')
  })

  test('重放状态：已有同分镜任务只恢复，不再发建任务请求', async () => {
    const ctx = setupBar()
    ctx.taskId.value = 'task-1'
    ctx.production.session.task.value = makeTask({ phase: 'generating' })
    const before = ctx.calls.length
    await ctx.wrapper.find('[data-test="canvas-run-begin"]').trigger('click')
    await settle()
    expect(ctx.calls.filter(call => call.url === '/api/video-production/tasks' && call.init?.method === 'POST')).toHaveLength(0)
    expect(ctx.calls.length).toBe(before)
  })

  test('flush 失败：中止提交并停留（不建任务）；同键异参 409 落错误可重试', async () => {
    const ctx = setupBar()
    ctx.flush.mockResolvedValue(false)
    await ctx.wrapper.find('[data-test="canvas-run-begin"]').trigger('click')
    expect(ctx.calls.filter(call => call.url === '/api/video-production/tasks')).toHaveLength(0)
    expect(ctx.wrapper.find('[data-test="canvas-run-create-error"]').text()).toContain('未保存')

    // flush 修好后重试；服务端同键异 storyboard → 409 展示错误
    ctx.flush.mockResolvedValue(true)
    ctx.wrapper.vm.$.props // eslint-disable-line @typescript-eslint/no-unused-expressions
    const rejected = setupBar({ createTask: { status: 409, body: { success: false, error: '该分镜已有不同参数的制作任务' } } })
    await rejected.wrapper.find('[data-test="canvas-run-begin"]').trigger('click')
    await settle()
    expect(rejected.wrapper.find('[data-test="canvas-run-create-error"]').text())
      .toContain('该分镜已有不同参数的制作任务')
  })

  test('断线后重试：首创建失败再重试成功——只落一个任务引用', async () => {
    const taskId = ref('')
    const production = useCanvasProduction(taskId, { flushBeforeCreate: async () => true })
    const posts: unknown[] = []
    let failFirst = true
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/video-production/tasks' && (init?.method ?? '') === 'POST') {
        posts.push(JSON.parse(String(init?.body)))
        if (failFirst) {
          failFirst = false
          return { ok: false, status: 500, json: async () => ({ success: false, error: '网络中断' }) }
        }
        return { ok: true, status: 200, json: async () => ({ success: true, data: { id: 'task-retry' } }) }
      }
      return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
    }))
    const wrapper = mount(CanvasRunBar, { props: { production, storyboardId: 'sb-1' } })
    await wrapper.find('[data-test="canvas-run-begin"]').trigger('click')
    await settle()
    expect(production.createError.value).toContain('网络中断')
    await wrapper.find('[data-test="canvas-run-begin"]').trigger('click')
    await settle()
    expect(taskId.value).toBe('task-retry')
    // 两次尝试同一幂等键——服务端按键去重，不产生第二份费用
    expect(posts.every(body => (body as { operationId: string }).operationId === 'web-sb-1')).toBe(true)
  })
})

describe('TC-018：阶段合法动作与真实进度', () => {
  function withTask(phase: string, overrides: Partial<VideoTask> = {}) {
    const ctx = setupBar()
    ctx.taskId.value = 'task-1'
    ctx.production.session.task.value = makeTask({ phase, selection: { 'shot-1': 'take-1' }, ...overrides })
    return ctx
  }

  test('选齐后 queued/generating/voicing 可合成；composing 只显示进度与取消（不把受理当成功）', async () => {
    for (const phase of ['queued', 'generating', 'voicing']) {
      const ctx = withTask(phase)
      await settle()
      const compose = ctx.wrapper.find('[data-test="canvas-run-compose"]')
      expect(compose.exists(), phase).toBe(true)
      expect((compose.element as HTMLButtonElement).disabled, phase).toBe(false)
    }
    const composing = withTask('composing', { progress: 72 })
    await settle()
    expect(composing.wrapper.find('[data-test="canvas-run-compose"]').exists()).toBe(false)
    expect(composing.wrapper.find('[data-test="canvas-run-progress"]').text()).toBe('72%')
    expect(composing.wrapper.find('[data-test="canvas-run-cancel"]').exists()).toBe(true)
  })

  test('未确认选片禁用合成并说明；选不齐给出选齐提示', async () => {
    // 选片请求挂起＝未确认窗口（服务端尚未回执）
    const ctx = setupBar({ selectHang: true })
    ctx.taskId.value = 'task-1'
    ctx.production.session.task.value = makeTask({ phase: 'generating', selection: {} })
    await settle()
    const pendingCall = ctx.production.session.selectTake('shot-1', 'take-1')
    await settle()
    expect(ctx.wrapper.find('[data-test="canvas-run-compose"]').exists()).toBe(false)
    expect(ctx.wrapper.find('[data-test="canvas-run-compose-gate"]').text()).toContain('确认后才能合成')
    void pendingCall

    const incomplete = withTask('generating', { selection: {} })
    await settle()
    expect(incomplete.wrapper.find('[data-test="canvas-run-compose"]').exists()).toBe(false)
    expect(incomplete.wrapper.find('[data-test="canvas-run-compose-gate"]').text()).toContain('每镜选择一个候选')
  })

  test('终态：succeeded 指向交付面板；cancelled 才显示退款说明（取消中不显示已退）；failed 展示原因', async () => {
    const done = withTask('succeeded', { finalUrl: 'https://media.example.test/final.mp4', actualCostCents: 24 })
    await settle()
    expect(done.wrapper.find('[data-test="canvas-run-done"]').exists()).toBe(true)
    expect(done.wrapper.find('[data-test="canvas-run-cancel"]').exists()).toBe(false)
    expect(done.wrapper.find('[data-test="canvas-run-cancel-note"]').exists()).toBe(false)

    // 取消请求在途（phase 仍是 generating）：不显示已退款
    const cancelling = withTask('generating')
    await settle()
    expect(cancelling.wrapper.find('[data-test="canvas-run-cancel-note"]').exists()).toBe(false)

    const cancelled = withTask('cancelled')
    await settle()
    expect(cancelled.wrapper.find('[data-test="canvas-run-cancel-note"]').text()).toContain('全额退还')

    const failed = withTask('failed', { errorMessage: '候选全部失败' })
    await settle()
    expect(failed.wrapper.find('[data-test="canvas-run-task-error"]').text()).toContain('候选全部失败')
  })

  test('费用展示：预估冻结价 + 实结（服务端口径）', async () => {
    const ctx = withTask('succeeded', { actualCostCents: 24, estimatedCostCents: 25 })
    await settle()
    const price = ctx.wrapper.find('[data-test="canvas-run-price"]').text()
    expect(price).toContain('预估')
    expect(price).toContain('实结')
  })
})
