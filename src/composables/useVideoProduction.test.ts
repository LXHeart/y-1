// @vitest-environment happy-dom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useVideoProduction, clampTargetDuration } from './useVideoProduction'
import type { TaskTake } from './useVideoProduction'
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
  vi.stubGlobal('fetch', vi.fn(async (url: string, _init?: RequestInit) => {
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
  test('添加镜头封顶 30 个（#65 卡1 放宽）、删除后重排 seq、时长编辑钳制 4-6', async () => {
    const composable = await setup()
    for (let index = 0; index < 32; index += 1) {
      composable.addShot()
    }
    expect(composable.shots.value.length).toBe(30)
    expect(composable.canAddShot.value).toBe(false)

    composable.removeShot(0)
    expect(composable.shots.value.length).toBe(29)
    expect(composable.shots.value[0].seq).toBe(1)

    composable.updateShot(0, { plannedSeconds: 99 })
    expect(composable.shots.value[0].plannedSeconds).toBe(6)
    composable.updateShot(0, { plannedSeconds: 1 })
    expect(composable.shots.value[0].plannedSeconds).toBe(4)
    composable.updateShot(0, { visual: '手工镜头' })
    expect(composable.shots.value[0].visual).toBe('手工镜头')
  })

  test('#66 写通：带 id 的镜头编辑 PUT content（合并后全字段）；无 id 本地镜头不发', async () => {
    // SSE 帧带 id → 服务端镜头；setup 后驱动一次真实生成流
    const composable = await setup({ storyboardFrames: [
      { type: 'meta', storyboardId: 'sb-1', targetDurationSeconds: 30 },
      shotFrame(1, { id: 'shot-srv-1' }),
      shotFrame(2, { id: 'shot-srv-2' }),
    ] })
    composable.form.value.shopName = '店'
    composable.form.value.targetPlatform = 'douyin'
    composable.images.value = [{ id: 'i1', dataUrl: 'data:image/png;base64,AA', name: 'a.png' }]
    await composable.generateStoryboard()
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init })
      return { ok: true, status: 200, json: async () => ({ success: true }) }
    }))

    // 本地新增（无 id）：不发 PUT
    composable.addShot()
    const localIndex = composable.shots.value.length - 1
    composable.updateShot(localIndex, { visual: '纯本地' })
    expect(calls.filter(call => call.url.includes('/content'))).toEqual([])

    // 服务端镜头（有 id）：PUT 合并后的完整字段
    composable.updateShot(0, { plannedSeconds: 6, cameraMove: '环绕' })
    const put = calls.find(call => call.url === '/api/video-production/shots/shot-srv-1/content')
    expect(put?.init?.method).toBe('PUT')
    expect(JSON.parse(String(put?.init?.body))).toEqual({
      visual: '画面1', narration: '旁白1', plannedSeconds: 6, cameraMove: '环绕', anchorImageIndex: 1,
    })
  })
})

describe('#65 卡1/卡3：时长 / 分辨率 / 预估价', () => {
  test('clampTargetDuration：边界 15/180、非 5 倍数就近取档、非法回默认', () => {
    expect(clampTargetDuration(15)).toBe(15)
    expect(clampTargetDuration(180)).toBe(180)
    expect(clampTargetDuration(17)).toBe(15)
    expect(clampTargetDuration(62)).toBe(60)
    expect(clampTargetDuration(200)).toBe(180)
    expect(clampTargetDuration(3)).toBe(15)
    expect(clampTargetDuration(Number.NaN)).toBe(30)
  })

  test('B 站缺省横版、其余竖版；显式 resolution 优先并随请求发出', async () => {
    const composable = await setup()
    composable.form.value.shopName = '店'
    composable.images.value = [{ id: 'i1', dataUrl: 'data:image/png;base64,AA', name: 'a.png' }]

    composable.form.value.targetPlatform = 'bilibili'
    expect(composable.resolvedResolution.value).toBe('1920x1080')
    expect(composable.isLandscape.value).toBe(true)

    composable.form.value.targetPlatform = 'douyin'
    expect(composable.resolvedResolution.value).toBe('1080x1920')
    expect(composable.isLandscape.value).toBe(false)

    composable.form.value.resolution = '1920x1080'
    expect(composable.resolvedResolution.value).toBe('1920x1080')

    await composable.generateStoryboard()
    const body = JSON.parse(String((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
      .find((call) => call[0] === '/api/video-production/storyboard')?.[1]?.body))
    expect(body.resolution).toBe('1920x1080')
  })

  test('竖版 >60 秒出软提示、≤60 不提示；预估价随时长重算', async () => {
    const composable = await setup({
      capabilities: {
        mode: 'video',
        video: { available: true, provider: 'sandbox', model: 'm', unitPriceCents: 10, reason: '' },
        tts: { available: true, model: 't', reason: '' },
      },
    })
    await composable.loadCapabilities()

    composable.form.value.targetPlatform = 'douyin'
    composable.form.value.targetDurationSeconds = 60
    expect(composable.verticalDurationHint.value).toBe('')
    expect(composable.estimatedPriceCents.value).toBe(600)

    composable.form.value.targetDurationSeconds = 180
    expect(composable.verticalDurationHint.value).toContain('60 秒')
    expect(composable.estimatedPriceCents.value).toBe(1800)

    // 横版（B 站）不出竖版提示
    composable.form.value.targetPlatform = 'bilibili'
    expect(composable.verticalDurationHint.value).toBe('')
  })
})

describe('#65 卡2/卡3：AI 补图首帧三态', () => {
  test('成功回填 anchorUrl/角标数据；失败落 anchorErrors 不阻断；loading 期间防重入', async () => {
    const anchorPayloads: Array<Record<string, unknown> | undefined> = [
      { success: true, data: { mediaId: 'm-1', shot: { id: 'shot-9', anchorSource: 'ai', anchorMediaId: 'm-1', anchorUrl: 'https://signed/1' } } },
    ]
    let anchorStatus = 200
    vi.stubGlobal('fetch', vi.fn(async (url: string, _init?: RequestInit) => {
      if (url === '/api/video-production/capabilities') {
        return { ok: true, status: 200, json: async () => ({ mode: 'video', video: { available: true }, tts: { available: true } }) }
      }
      if (String(url).includes('/anchor:generate')) {
        const payload = anchorPayloads[anchorPayloads.length - 1]
        if (anchorStatus >= 400 || !payload) {
          return { ok: false, status: anchorStatus, json: async () => ({ error: '该镜头已绑定用户锚定图' }) }
        }
        return { ok: true, status: anchorStatus, json: async () => payload }
      }
      return sseResponse([
        { type: 'meta', storyboardId: 'sb-1', targetDurationSeconds: 15 },
        shotFrame(1, { id: 'shot-9', anchorImageIndex: 0 }),
      ])
    }))
    const composable = useVideoProduction()
    composable.form.value.shopName = '店'
    composable.form.value.targetPlatform = 'douyin'
    composable.images.value = [{ id: 'i1', dataUrl: 'data:image/png;base64,AA', name: 'a.png' }]
    await composable.generateStoryboard()
    expect(composable.shots.value[0].id).toBe('shot-9')

    // 成功：本地回填角标数据
    await composable.generateAnchorImage('shot-9')
    expect(composable.shots.value[0].anchorUrl).toBe('https://signed/1')
    expect(composable.shots.value[0].anchorSource).toBe('ai')
    expect(composable.anchorErrors.value['shot-9']).toBe('')
    expect(composable.anchorGenerating.value['shot-9']).toBe(false)

    // 失败：错误文案落地、不抛出
    anchorStatus = 409
    await composable.generateAnchorImage('shot-9')
    expect(composable.anchorErrors.value['shot-9']).toContain('锚定图')
    expect(composable.shots.value[0].anchorUrl).toBe('https://signed/1')
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
            errorMessage: null, selectable: true, score: null, scoreLabels: [],
            url: 'https://media.example.test/a' },
          { id: takeB, takeNo: 2, status: 'failed', attempts: 2, provider: 'sandbox',
            model: 'sandbox-video-v1', mediaId: null, durationMs: null, errorCode: 'provider_failed',
            errorMessage: 'x', selectable: false, score: null, scoreLabels: [], url: null },
        ] as TaskTake[],
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
    // 任务书 4.6 推荐预选：recommended 自动并入 selection，无需用户先点「一键采用推荐」
    expect(composable.selectionComplete.value).toBe(true)
    expect(composable.generationInProgress.value).toBe(false)
  })

  test('推荐预选合并：recommended 补缺、重抽失效回退推荐', async () => {
    const composable = setupTask()
    await composable.beginGeneration()
    // 补缺：详情 recommended={shot-1: take-1} → selection 预选 take-1
    expect(composable.task.value?.selection[shotId]).toBe(takeA)

    // 失效回退：重抽后旧选择（takeB 不可选）清掉，回退 recommended takeA
    composable.task.value = {
      ...composable.task.value!,
      selection: { [shotId]: takeB },
    }
    await composable.refreshTask()
    expect(composable.task.value?.selection[shotId]).toBe(takeA)
  })

  test('推荐预选合并：服务端已持久化的显式选择优先于 recommended', async () => {
    const takeBReady = taskDetail()
    takeBReady.shots[0]!.takes[1] = {
      ...takeBReady.shots[0]!.takes[1]!, status: 'succeeded', selectable: true }
    const composable = setupTask({
      shots: takeBReady.shots,
      selection: { [shotId]: takeB },
    })
    await composable.beginGeneration()
    expect(composable.task.value?.selection[shotId]).toBe(takeB)
    expect(composable.selectionComplete.value).toBe(true)
  })

  test('任务终态：cancelled/failed 置 taskTerminal，合成与取消入口随之收起', async () => {
    const composable = setupTask({ phase: 'cancelled' })
    await composable.beginGeneration()
    expect(composable.taskTerminal.value).toBe(true)

    const failed = setupTask({ phase: 'failed', errorMessage: '全部候选失败' })
    await failed.beginGeneration()
    expect(failed.taskTerminal.value).toBe(true)
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

  test('#66 D1/D2：评分与标签随详情透传，未评候选为 null/空数组（角标显隐数据面）', async () => {
    const scored = taskDetail()
    scored.shots[0]!.takes[0] = {
      ...scored.shots[0]!.takes[0]!,
      score: 85,
      scoreLabels: ['画质偏低', '与锚定图差异大'],
    }
    const composable = setupTask({ shots: scored.shots })
    await composable.beginGeneration()

    const takeWithScore = composable.task.value?.shots[0]?.takes[0]
    expect(takeWithScore?.score).toBe(85)
    expect(takeWithScore?.scoreLabels).toEqual(['画质偏低', '与锚定图差异大'])

    // 未评分候选（评分失败/未触发）不显角标的数据形态
    const unscored = composable.task.value?.shots[0]?.takes[1]
    expect(unscored?.score ?? null).toBeNull()
    expect(unscored?.scoreLabels ?? []).toEqual([])
  })

  test('#66 D1：推荐高亮跟随评分最高——服务端 recommended 直并入预选', async () => {
    const bothSelectable = taskDetail()
    bothSelectable.shots[0]!.takes[1] = {
      ...bothSelectable.shots[0]!.takes[1]!, status: 'succeeded', selectable: true,
      score: 92, scoreLabels: [],
    }
    // 服务端推荐已升级为「评分最高」：take-2（92 分）胜出
    const composable = setupTask({
      shots: bothSelectable.shots,
      recommended: { [shotId]: takeB },
    })
    await composable.beginGeneration()
    expect(composable.task.value?.selection[shotId]).toBe(takeB)
    expect(composable.selectionComplete.value).toBe(true)
  })

  test('重抽与合成：POST 端点载荷正确，合成完成后停轮询', async () => {
    vi.spyOn(globalThis, 'setTimeout').mockImplementation((() => 1) as unknown as typeof setTimeout)
    const composable = await setupTask({ phase: 'composing' })
    await composable.regenerateShot(shotId)
    expect(fetchCalls.some((call) => call.url.includes(`/shots/${shotId}/regenerate`) && call.init?.method === 'POST'))

    // 停掉后台轮询再手工推进阶段（goBackToStoryboard 会 stopPolling）
    composable.goBackToStoryboard()
    composable.task.value = { ...(composable.task.value ?? taskDetail()), phase: 'generating' } as typeof composable.task.value
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

describe('#65 卡5：任务 SSE 消费与轮询降级', () => {
  const taskId = 'task-sse'
  const shotId = 'shot-sse'

  function detail(phase: string) {
    return {
      id: taskId, storyboardId: 'sb-1', mode: 'video', phase, progress: 40,
      targetDurationSeconds: 30, provider: 'sandbox', model: 'm', unitPriceCents: 1,
      estimatedCostCents: 30, actualCostCents: null, actualDurationSeconds: null,
      errorCode: null, errorMessage: null, selection: {}, recommended: {},
      finalUrl: null, subtitleUrl: null, shots: [],
    }
  }

  /** events 端点返回可控 ReadableStream（enqueue 由用例驱动）。 */
  function sseTaskHarness(phaseRef: { phase: string }) {
    let streamController: ReadableStreamDefaultController<Uint8Array> | null = null
    const eventsFetches: Array<AbortSignal | null | undefined> = []
    const detailFetches: string[] = []
    const composable = useVideoProduction()
    composable.shots.value = [{
      seq: 1, visual: 'v', narration: 'n', plannedSeconds: 5,
      cameraMove: '固定机位', anchorImageIndex: 1, prompt: 'p',
    }]
    composable.storyboardId.value = 'sb-1'
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      fetchUrls.push(url)
      if (url === '/api/video-production/tasks' && init?.method === 'POST') {
        return { ok: true, status: 200, json: async () => ({ success: true, data: { id: taskId } }) }
      }
      if (url === `/api/video-production/tasks/${taskId}`) {
        detailFetches.push(phaseRef.phase)
        return { ok: true, status: 200, json: async () => ({ success: true, data: detail(phaseRef.phase) }) }
      }
      if (url.endsWith('/events')) {
        eventsFetches.push(init?.signal)
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            streamController = controller
          },
        })
        return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
      }
      return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
    }))
    return {
      composable, detailFetches, eventsFetches,
      emit(frame: Record<string, unknown>) {
        streamController?.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(frame)}\n\n`))
      },
      endStream() {
        streamController?.close()
      },
    }
  }

  test('事件到达只触发快照拉取（不直接改状态）；终态后通道收口', async () => {
    const phaseRef = { phase: 'generating' }
    const harness = sseTaskHarness(phaseRef)
    await harness.composable.beginGeneration()
    expect(harness.eventsFetches.length).toBe(1)

    harness.emit({ type: 'take', shotId, takeId: 't-1', status: 'succeeded' })
    harness.emit({ type: 'shot', shotId, status: 'done' })
    await new Promise((resolve) => setTimeout(resolve, 450))
    // 合并刷新：两次事件只拉一次快照；task 状态来自快照
    expect(harness.detailFetches.length).toBeGreaterThanOrEqual(1)
    expect(harness.composable.task.value?.id).toBe(taskId)

    phaseRef.phase = 'succeeded'
    harness.emit({ type: 'phase', phase: 'succeeded' })
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(harness.composable.stage.value).toBe('compose')
    harness.composable.reset()
  })

  test('心跳超时（60s 无帧）回落轮询并标记 degraded；恢复后自动升回 SSE', async () => {
    vi.useFakeTimers()
    try {
      const phaseRef = { phase: 'generating' }
      const harness = sseTaskHarness(phaseRef)
      await harness.composable.beginGeneration()
      expect(harness.composable.eventsDegraded.value).toBe(false)

      // 65s 无帧（watchdog 每 5s 巡检）→ 降级轮询
      await vi.advanceTimersByTimeAsync(65_000)
      expect(harness.composable.eventsDegraded.value).toBe(true)
      // 轮询通道开表：2s 一拍拉快照
      await vi.advanceTimersByTimeAsync(4_000)
      expect(harness.detailFetches.length).toBeGreaterThanOrEqual(2)

      // 再过 61s → watchdog 尝试升回：重开 SSE、停轮询
      await vi.advanceTimersByTimeAsync(61_000)
      expect(harness.composable.eventsDegraded.value).toBe(false)
      expect(harness.eventsFetches.length).toBeGreaterThanOrEqual(2)

      harness.composable.reset()
    } finally {
      vi.useRealTimers()
    }
  })

  test('卸载（reset）关闭事件通道：AbortController 中止', async () => {
    const phaseRef = { phase: 'generating' }
    const harness = sseTaskHarness(phaseRef)
    await harness.composable.beginGeneration()
    expect(harness.eventsFetches[0]).toBeDefined()

    harness.composable.reset()
    expect(harness.eventsFetches[0]?.aborted).toBe(true)
  })
})
