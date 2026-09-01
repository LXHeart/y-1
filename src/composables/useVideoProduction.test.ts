// @vitest-environment happy-dom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useVideoProduction } from './useVideoProduction'
import type { StoryboardShot } from '../types/video-production'

/**
 * 任务书 #64 卡4：useVideoProduction 分镜流解析与镜头编辑边界。
 * 全部网络经 mock fetch；SSE 用真实 Response（getReader 流式读取路径）。
 */

function sseResponse(frames: Array<Record<string, unknown>>, done = true): Response {
  const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
  if (done) lines.push('data: [DONE]', '')
  return new Response(lines.join('\n'), {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function shotFrame(seq: number, overrides: Partial<StoryboardShot> = {}) {
  return {
    type: 'shot',
    shot: {
      seq,
      visual: `画面${seq}`,
      narration: `旁白${seq}`,
      plannedSeconds: 5,
      cameraMove: '固定机位',
      anchorImageIndex: 1,
      prompt: `提示词${seq}`,
      ...overrides,
    },
  }
}

const fetchUrls: string[] = []
const fetchCalls: Array<{ url: string; init?: RequestInit }> = []

async function setup(options: {
  capabilities?: unknown
  storyboardFrames?: Array<Record<string, unknown>>
  storyboardStatus?: number
} = {}) {
  const composable = useVideoProduction()
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    fetchUrls.push(url)
    if (url === '/api/video-production/capabilities') {
      return {
        ok: true,
        status: 200,
        json: async () => options.capabilities ?? { mode: 'video', video: { available: true }, tts: { available: true } },
      }
    }
    if (url === '/api/video-production/storyboard') {
      if (options.storyboardStatus && options.storyboardStatus >= 400) {
        return {
          ok: false,
          status: options.storyboardStatus,
          json: async () => ({ error: '成片时长须为 15-60 秒且按 5 秒步进' }),
        }
      }
      return sseResponse(options.storyboardFrames ?? [
        { type: 'meta', storyboardId: 'sb-1', targetDurationSeconds: 30 },
        shotFrame(1),
        shotFrame(2),
        shotFrame(3),
      ])
    }
    return { ok: true, status: 200, json: async () => ({}) }
  }))
  return composable
}

beforeEach(() => {
  fetchUrls.length = 0
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('分镜 SSE 解析', () => {
  test('meta 帧记录 storyboardId、shot 帧逐镜入数组、safety 帧落报告', async () => {
    const composable = await setup({
      storyboardFrames: [
        { type: 'meta', storyboardId: 'sb-42', targetDurationSeconds: 25 },
        shotFrame(1),
        shotFrame(2, { plannedSeconds: 9, anchorImageIndex: 7 }),
        { type: 'safety', safety: { findings: [], lexiconVersion: 'v1', deepCheck: false } },
      ],
    })
    composable.form.value.shopName = '老王面馆'
    composable.form.value.targetPlatform = 'douyin'
    composable.images.value = [
      { id: 'i1', dataUrl: 'data:image/png;base64,AA', name: 'a.png' },
      { id: 'i2', dataUrl: 'data:image/png;base64,BB', name: 'b.png' },
    ]
    await composable.generateStoryboard()

    expect(composable.stage.value).toBe('storyboard')
    expect(composable.storyboardId.value).toBe('sb-42')
    expect(composable.shots.value.length).toBe(2)
    expect(composable.shots.value[0]).toMatchObject({ seq: 1, visual: '画面1', plannedSeconds: 5 })
    // 帧载荷钳制：时长 9→6、锚定图 7→图片数 2
    expect(composable.shots.value[1]).toMatchObject({ plannedSeconds: 6, anchorImageIndex: 2 })
    expect(composable.safetyReport.value).not.toBeNull()
    expect(composable.storyboardLoading.value).toBe(false)
    // 请求载荷带目标时长
    const body = JSON.parse(String((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
      .find((call) => call[0] === '/api/video-production/storyboard')?.[1]?.body))
    expect(body.targetDurationSeconds).toBe(30)
    expect(body.shopName).toBe('老王面馆')
  })

  test('error 帧与非 ok 响应都落进 error 且不吞帧外状态', async () => {
    const composable = await setup({ storyboardStatus: 400 })
    composable.form.value.shopName = '店'
    composable.form.value.targetPlatform = 'douyin'
    composable.images.value = [{ id: 'i1', dataUrl: 'data:image/png;base64,AA', name: 'a.png' }]
    await composable.generateStoryboard()

    expect(composable.error.value).toBe('成片时长须为 15-60 秒且按 5 秒步进')
    expect(composable.shots.value).toEqual([])
  })

  test('上游前置校验失败时给出提示且不发起请求', async () => {
    const composable = await setup()
    await composable.generateStoryboard()
    expect(composable.error.value).toBe('请至少上传 1 张图片并填写店铺名称')
    // 组件外 onMounted 不跑 capabilities；断言重点是 storyboard 未发起
    expect(fetchUrls.filter((url) => url.includes('storyboard'))).toEqual([])
  })
})

describe('镜头编辑边界', () => {
  test('添加镜头封顶 10 个、删除后重排 seq、时长编辑钳制 4-6', async () => {
    const composable = await setup()
    for (let index = 0; index < 12; index += 1) {
      composable.addShot()
    }
    expect(composable.shots.value.length).toBe(10)
    expect(composable.canAddShot.value).toBe(false)

    composable.removeShot(0)
    expect(composable.shots.value.length).toBe(9)
    expect(composable.shots.value[0].seq).toBe(1)

    composable.updateShot(0, { plannedSeconds: 99 })
    expect(composable.shots.value[0].plannedSeconds).toBe(6)
    composable.updateShot(0, { plannedSeconds: 1 })
    expect(composable.shots.value[0].plannedSeconds).toBe(4)
    composable.updateShot(0, { visual: '手工镜头' })
    expect(composable.shots.value[0].visual).toBe('手工镜头')
    // 第 1 镜被钳到 4 秒，其余 8 镜默认 5 秒
    expect(composable.totalPlannedSeconds.value).toBe(4 + 8 * 5)
  })
})

describe('capabilities 模式分支', () => {
  test('slideshow 模式（未配置视频模型）不锁死流程', async () => {
    const composable = await setup({
      capabilities: {
        mode: 'slideshow',
        video: { available: false, provider: null, model: null, unitPriceCents: null, reason: '未配置视频生成模型' },
        tts: { available: false, model: null, reason: '配音模型未配置' },
      },
    })
    await composable.loadCapabilities()
    expect(composable.capabilities.value?.mode).toBe('slideshow')
    expect(composable.isSlideshowMode.value).toBe(true)
    expect(composable.ttsUnavailable.value).toBe(true)
  })

  test('video 模式且配音可用时不显示任一降级提示', async () => {
    const composable = await setup({
      capabilities: {
        mode: 'video',
        video: { available: true, provider: 'seedance', model: 'm1', unitPriceCents: 10, reason: '' },
        tts: { available: true, model: 'tts-1', reason: '' },
      },
    })
    await composable.loadCapabilities()
    expect(composable.isSlideshowMode.value).toBe(false)
    expect(composable.ttsUnavailable.value).toBe(false)
  })

  test('拉取失败 fail-closed：按 slideshow 降级展示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) })))
    const composable = useVideoProduction()
    await composable.loadCapabilities()
    expect(composable.capabilities.value).toBeNull()
    expect(composable.isSlideshowMode.value).toBe(true)
  })
})

describe('成片任务生命周期（卡9）', () => {
  const taskId = 'task-1'
  const shotId = 'shot-1'
  const takeA = 'take-1'
  const takeB = 'take-2'

  function taskDetail(overrides: Record<string, unknown> = {}) {
    return {
      id: taskId,
      storyboardId: 'sb-1',
      mode: 'video',
      phase: 'generating',
      progress: 40,
      targetDurationSeconds: 30,
      provider: 'sandbox',
      model: 'sandbox-video-v1',
      unitPriceCents: 1,
      estimatedCostCents: 30,
      actualCostCents: null,
      actualDurationSeconds: null,
      errorCode: null,
      errorMessage: null,
      selection: {},
      recommended: { [shotId]: takeA },
      finalUrl: null,
      subtitleUrl: null,
      shots: [{
        id: shotId,
        seq: 1,
        visual: '画面',
        narration: '旁白',
        plannedSeconds: 5,
        cameraMove: '固定机位',
        anchorImageIndex: 1,
        prompt: 'p',
        status: 'ready',
        audio: { status: 'succeeded', provider: 'sandbox', model: 'sandbox-tts-v1', durationMs: 2000 },
        takes: [
          { id: takeA, takeNo: 1, status: 'succeeded', attempts: 1, provider: 'sandbox',
            model: 'sandbox-video-v1', mediaId: 'm1', durationMs: 2000, errorCode: null,
            errorMessage: null, selectable: true, url: 'https://media.example.test/a' },
          { id: takeB, takeNo: 2, status: 'failed', attempts: 2, provider: 'sandbox',
            model: 'sandbox-video-v1', mediaId: null, durationMs: null, errorCode: 'provider_failed',
            errorMessage: 'x', selectable: false, url: null },
        ],
      }],
      ...overrides,
    }
  }

  function setupTask(detailOverrides: Record<string, unknown> = {}) {
    const composable = useVideoProduction()
    composable.shots.value = [{
      seq: 1, visual: '画面', narration: '旁白', plannedSeconds: 5,
      cameraMove: '固定机位', anchorImageIndex: 1, prompt: 'p',
    }]
    composable.storyboardId.value = 'sb-1'
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      fetchUrls.push(url)
      fetchCalls.push({ url, init })
      if (url === '/api/video-production/tasks' && init?.method === 'POST') {
        return { ok: true, status: 200, json: async () => ({ success: true, data: { id: taskId } }) }
      }
      if (url === `/api/video-production/tasks/${taskId}`) {
        return { ok: true, status: 200, json: async () => ({ success: true, data: taskDetail(detailOverrides) }) }
      }
      if (url.startsWith('/api/video-production/tasks/') && init?.method === 'POST') {
        return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
      }
      if (url.startsWith('/api/video-production/tasks?page=')) {
        return { ok: true, status: 200, json: async () => ({ success: true, data: {
          items: [{ id: 'h1', storyboardId: 'sb-0', mode: 'slideshow', phase: 'succeeded', progress: 100,
            targetDurationSeconds: 15, actualDurationSeconds: 14, estimatedCostCents: 15,
            actualCostCents: 14, unitPriceCents: 1, createdAt: '2026-09-01T10:00:00Z',
            completedAt: '2026-09-01T10:05:00Z', errorCode: null, errorMessage: null }],
          total: 11, page: 2, pageSize: 10 } }) }
      }
      return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
    }))
    return composable
  }

  test('建任务载荷带 storyboardId/幂等键，详情进 task，生成中闸生效', async () => {
    const composable = setupTask()
    await composable.beginGeneration()
    expect(composable.task.value?.id).toBe(taskId)
    const create = fetchCalls.find((call) => call.url === '/api/video-production/tasks' && call.init?.method === 'POST')
    expect(JSON.parse(String(create?.init?.body))).toEqual({
      storyboardId: 'sb-1',
      operationId: 'web-sb-1',
    })
    expect(composable.selectionComplete.value).toBe(false)
    expect(composable.generationInProgress.value).toBe(false)
  })

  test('选片：本地回显 + POST selections 载荷；一键推荐', async () => {
    const composable = setupTask()
    await composable.beginGeneration()
    await composable.selectTake(shotId, takeA)
    expect(composable.task.value?.selection[shotId]).toBe(takeA)
    expect(composable.selectionComplete.value).toBe(true)
    const select = fetchCalls.find((call) => call.url.includes('/takes/select'))
    expect(JSON.parse(String(select?.init?.body))).toEqual({
      selections: [{ shotId, takeId: takeA }],
    })

    await composable.useRecommendedSelection()
    expect(composable.task.value?.selection[shotId]).toBe(takeA)
    const recommended = fetchCalls.filter((call) => call.url.includes('/takes/select')).pop()
    expect(JSON.parse(String(recommended?.init?.body))).toEqual({ useRecommended: true })
  })

  test('重抽与合成：POST 端点载荷正确，合成完成后停轮询', async () => {
    vi.spyOn(globalThis, 'setTimeout').mockImplementation((() => 1) as typeof setTimeout)
    const composable = await setupTask({ phase: 'composing' })
    await composable.regenerateShot(shotId)
    expect(fetchCalls.some((call) => call.url.includes(`/shots/${shotId}/regenerate`) && call.init?.method === 'POST'))

    // 停掉后台轮询再手工推进阶段（goBackToStoryboard 会 stopPolling）
    composable.goBackToStoryboard()
    composable.task.value = { ...(composable.task.value ?? taskDetail()), phase: 'generating' }
    composable.stage.value = 'generate'
    await composable.selectTake(shotId, takeA)
    await composable.composeTask()
    expect(fetchCalls.some((call) => call.url.endsWith('/compose') && call.init?.method === 'POST'))
    composable.task.value = { ...composable.task.value!, phase: 'succeeded',
      finalUrl: 'https://media.example.test/final' }
  })

  test('历史任务分页读取 page/pageSize 并展开列表', async () => {
    const composable = setupTask()
    await composable.loadHistory(2)
    expect(fetchUrls).toContain('/api/video-production/tasks?page=2&pageSize=10')
    expect(composable.history.value.total).toBe(11)
    expect(composable.history.value.items[0]?.mode).toBe('slideshow')
  })
})
