// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import VideoStudioView from './VideoStudioView.vue'
import { useCreationWorkspace } from '../../../lib/creation-workspace'
import type { CreationProject } from '../../../types/creation'

/**
 * 任务书 #92 C-05：视频工作区恢复、结果资产化与任务回跳。
 * TC-C05-001 恢复 / TC-C05-002 资产去重+幂等 / TC-C05-003 任务回跳 / TC-C05-004 结果素材展示。
 */

/** C107-22：入口卡只消费 useRouter().push——整模块以测试替身注入，不拖真实路由表。 */
const pushRoute = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushRoute }) }))

const uploadContentAssetFile = vi.fn()
const createContentAsset = vi.fn()
const jumpToGrassland = vi.fn()
const listSpeechTranscriptions = vi.fn()
const studioError = { value: '' }
// C104-04：账号会话 mock 可变（换号/清理场景按测试驱动）。
const sessionFixture = { ownerAccountId: 'acct-video', current: true }

vi.mock('../../../composables/useGrassland', () => ({
  useGrassland: () => ({
    uploadContentAssetFile,
    createContentAsset,
    error: { value: '' },
  }),
}))
vi.mock('../../../composables/useCrossAppToken', () => ({
  useCrossAppJump: () => ({ jumpToGrassland }),
}))
// C103-10：字幕暂存键按账号命名空间（组件挂载不依赖真实 pinia）。
vi.mock('../../../stores/account-session', () => ({
  useAccountSessionStore: () => ({
    get ownerAccountId() { return sessionFixture.ownerAccountId },
    capture: () => ({
      accountId: sessionFixture.ownerAccountId,
      epoch: 1,
      signal: new AbortController().signal,
    }),
    isCurrent: () => sessionFixture.current,
  }),
}))
vi.mock('../../../composables/useAiStudio', () => ({
  useAiStudio: () => ({
    transcribe: vi.fn(),
    bgmAdvice: vi.fn(),
    listSpeechTranscriptions,
    error: studioError,
  }),
}))

function videoProject(overrides: Record<string, unknown> = {}): CreationProject {
  return {
    id: 'draft-video', title: '门店门头视频', capability: 'video', status: 'in_progress', version: 6,
    workspace: {
      capability: 'video', currentStep: 'cover',
      inputs: { coverSource: 'ai', coverTitle: '暖色调门头', coverRatio: '9:16', aiCoverPrompt: '暖色调 门头 特写' },
      source: { taskId: 'task-9' },
    },
    resultAssetIds: [], runIds: [], updatedAt: '2026-09-07T10:00:00Z',
    ...overrides,
  } as CreationProject
}

async function mountStudio() {
  const wrapper = mount(VideoStudioView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  studioError.value = ''
  pushRoute.mockReset()
  listSpeechTranscriptions.mockReset()
  uploadContentAssetFile.mockReset()
  createContentAsset.mockReset()
  jumpToGrassland.mockReset()
  useCreationWorkspace().setPendingContinue(null)
  // 保存链路走 drafts API 信封
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ success: true, data: {} }), {
    headers: { 'Content-Type': 'application/json' },
  })))
  // happy-dom canvas 无 2d/toBlob：渲染全桩（不测像素，只测链路）
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (
    this: HTMLCanvasElement, callback: (blob: Blob | null) => void,
  ) {
    callback(new Blob(['png'], { type: 'image/png' }))
  })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(new Proxy({}, {
    get: () => vi.fn(),
  }) as unknown as CanvasRenderingContext2D)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
enableAutoUnmount(afterEach)

describe('视频工坊工作区（任务书 #92 C-05）', () => {
  test('#106 复核：字幕历史失败与空结果不应永远显示加载中，支持重试', async () => {
    listSpeechTranscriptions.mockImplementation(async () => {
      studioError.value = 'upstream unavailable'
      return []
    })
    const wrapper = await mountStudio()
    await wrapper.findAll('.vs-tab').find(t => t.text() === '字幕工作台')!.trigger('click')
    await wrapper.findAll('button').find(t => t.text() === '从历史记录选择')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="subtitle-history-error"]').text()).toContain('upstream unavailable')
    expect(wrapper.find('[data-testid="subtitle-history-loading"]').exists()).toBe(false)
    listSpeechTranscriptions.mockImplementation(async () => { studioError.value = ''; return [] })
    await wrapper.get('[data-testid="subtitle-history-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="subtitle-history-empty"]').text()).toContain('暂无转写记录')
  })
  test('TC-C05-001 恢复：子区步骤、封面字段与任务来源回填（AC-401）', async () => {
    useCreationWorkspace().setPendingContinue(videoProject())
    const wrapper = await mountStudio()
    // 步骤恢复到 cover 子区
    const active = wrapper.findAll('.vs-tab').find((tab) => tab.classes().includes('active'))
    expect(active?.text()).toBe('封面工作台')
    // 字段恢复（AI 封面路径的提示词输入随 coverSource='ai' 渲染）
    const promptInput = wrapper.find('input[placeholder="如：秋日暖阳下的咖啡店"]').element as HTMLInputElement
    expect(promptInput.value).toBe('暖色调 门头 特写')
    // 任务来源可见（回跳按钮挂 taskReturnId）
    expect(wrapper.find('[data-testid="back-to-task"]').exists()).toBe(true)
    expect(useCreationWorkspace().pendingContinue.value).toBeNull()
  })

  // 任务书 #101 C101-15：封面预设只提供可复制描述（不接 studio 计划/计费/来源）
  test('C101-15 封面预设：折叠组展开后可把预设描述填入主题；旧封面流程不受影响', async () => {
    useCreationWorkspace().setPendingContinue(videoProject())
    const wrapper = await mountStudio()
    // 高级选项默认折叠
    expect(wrapper.find('[data-test="cover-recipe-style"]').exists()).toBe(false)
    await wrapper.find('[data-test="cover-recipe-toggle"]').trigger('click')
    expect(wrapper.find('[data-test="cover-recipe-style"]').exists()).toBe(true)
    await wrapper.find('[data-test="video-cover-apply-preset"]').trigger('click')
    const promptInput = wrapper.find('input[placeholder="如：秋日暖阳下的咖啡店"]').element as HTMLInputElement
    expect(promptInput.value).toContain('暖色调 门头 特写；')
    // 旧封面路径回归：抽帧/本地上传/AI 三入口仍在
    for (const label of ['视频抽帧', '本地图片', 'AI 生图']) {
      expect(wrapper.findAll('button').some((button) => button.text() === label)).toBe(true)
    }
  })

  test('TC-C05-002 存入素材库：上传+登记后资产 ID 去重写入 draft；重复点击幂等（AC-402）', async () => {
    const savedPuts: Array<Record<string, any>> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url).startsWith('/api/creation-drafts') && init?.method === 'PUT') {
        savedPuts.push(JSON.parse(String(init.body)))
      }
      return new Response(JSON.stringify({ success: true, data: videoProject() }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }))
    uploadContentAssetFile.mockResolvedValue('media-1')
    createContentAsset.mockResolvedValue({ id: 'asset-1', title: '视频封面' })

    useCreationWorkspace().setPendingContinue(videoProject())
    const wrapper = await mountStudio()
    // 导出区受底图门控：注入已加载底图让「存入素材库」可用（渲染本身已被桩掉）
    ;(wrapper.vm as unknown as { coverBaseImage: unknown }).coverBaseImage = {
      naturalWidth: 1080, naturalHeight: 1920, src: 'fixture',
    } as HTMLImageElement
    await flushPromises()
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '存入素材库')!
    expect(saveButton).toBeTruthy()
    await saveButton.trigger('click')
    await flushPromises()
    expect(uploadContentAssetFile).toHaveBeenCalledTimes(1)
    expect(createContentAsset).toHaveBeenCalledWith(expect.objectContaining({
      libraryType: 'personal', mediaId: 'media-1', title: '暖色调门头',
    }))
    // 资产 ID 已随 PUT 载荷去重提交
    const putWithAssets = savedPuts.filter((body) => Array.isArray(body.resultAssetIds))
    expect(putWithAssets.length).toBeGreaterThanOrEqual(1)
    expect(putWithAssets[putWithAssets.length - 1].resultAssetIds).toEqual(['asset-1'])
    expect(wrapper.get('[data-testid="result-assets-chip"]').text()).toContain('已存 1 项')

    // 幂等：同一封面重复点击不再上传/登记/追加
    await saveButton.trigger('click')
    await flushPromises()
    expect(uploadContentAssetFile).toHaveBeenCalledTimes(1)
    expect(createContentAsset).toHaveBeenCalledTimes(1)
  })

  test('TC-C05-003 任务回跳：带 taskId 跳草场详情深链，跳转失败停留（AC-403）', async () => {
    jumpToGrassland.mockResolvedValue(undefined)
    useCreationWorkspace().setPendingContinue(videoProject())
    const wrapper = await mountStudio()
    await wrapper.get('[data-testid="back-to-task"]').trigger('click')
    await flushPromises()
    expect(jumpToGrassland).toHaveBeenCalledWith('/?task=task-9')
    // 跳转被拒（异常）时不泄露详情、按钮复位可重试
    jumpToGrassland.mockRejectedValue(new Error('blocked'))
    await wrapper.get('[data-testid="back-to-task"]').trigger('click')
    await flushPromises()
    expect(jumpToGrassland).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="back-to-task"]').attributes('disabled')).toBeUndefined()
  })

  test('TC-C05-004 无任务来源不显示回跳；存量结果素材以计数 chip 呈现（不可用态交素材库）', async () => {
    useCreationWorkspace().setPendingContinue(videoProject({
      workspace: { capability: 'video', currentStep: 'templates', inputs: {} },
      resultAssetIds: ['asset-stale-1', 'asset-stale-2'],
    }))
    const wrapper = await mountStudio()
    expect(wrapper.find('[data-testid="back-to-task"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="result-assets-chip"]').text()).toContain('已存 2 项')
  })
})

describe('视频克隆入口（任务书 #107-3 C107-22 / TC107-22-03）', () => {
  function capsResponse(enabled: boolean): Response {
    return new Response(JSON.stringify({
      success: true,
      data: { enabled, version: enabled ? '0.2.13' : null, features: [], templates: [] },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }

  test('功能开启：入口卡可跳转 video-clone（ai 壳路由名，不落 index 路径）', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/hypit/capabilities')) return capsResponse(true)
      return new Response(JSON.stringify({ success: true, data: {} }), {
        headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = await mountStudio()
    const enter = wrapper.get('[data-testid="video-clone-enter"]')
    await enter.trigger('click')
    expect(pushRoute).toHaveBeenCalledWith({ name: 'video-clone' })
  })

  test('功能关闭：如实说明未开放，且 capabilities 只查一次（无循环失败请求）', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/hypit/capabilities')) return capsResponse(false)
      return new Response(JSON.stringify({ success: true, data: {} }), {
        headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountStudio()
    expect(wrapper.get('[data-testid="video-clone-entry-disabled"]').text()).toContain('暂未开放')
    expect(wrapper.find('[data-testid="video-clone-enter"]').exists()).toBe(false)
    const capsCalls = () => fetchMock.mock.calls.filter(([input]) => String(input).includes('/capabilities')).length
    expect(capsCalls()).toBe(1)
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(capsCalls()).toBe(1)
    expect(pushRoute).not.toHaveBeenCalled()
  })
})

describe('字幕暂存与账号缓存（任务书 #104 C104-04 / TC104-04-08）', () => {
  const oldCues = [
    { id: 'cue-old', start: 0, end: 2.5, text: '旧账号暂存的字幕' },
  ]

  function transcriptionItem(): Record<string, unknown> {
    return { id: 't9', transcriptText: '新转写的正文内容', durationMs: 9000, createdAt: '2026-09-01T00:00:00Z' }
  }

  async function selectFromHistory() {
    listSpeechTranscriptions.mockResolvedValue([transcriptionItem()])
    const wrapper = await mountStudio()
    const tabs = wrapper.findAll('.vs-tab')
    await tabs.find((tab) => tab.text().includes('字幕'))!.trigger('click')
    await wrapper.findAll('button').find((btn) => btn.text().includes('历史'))!.trigger('click')
    await flushPromises()
    const item = wrapper.find('.vs-history-item')
    expect(item.exists()).toBe(true)
    await item.trigger('click')
    await flushPromises()
    return wrapper
  }

  test('同合法会话恢复旧暂存：无墓碑时历史字幕暂存可回读（截断格式精确导入）', async () => {
    localStorage.setItem('subtitle-cues-acct-video:t9', JSON.stringify(oldCues))
    const wrapper = await selectFromHistory()
    // 恢复走 readAccountKey：旧暂存覆盖启发式拆分并渲染到时间轴（cue 文本在 input value 里）。
    const cueTexts = wrapper.findAll('.vs-cue-row input[type="text"]')
      .map((input) => (input.element as HTMLInputElement).value)
    expect(cueTexts).toContain('旧账号暂存的字幕')
    expect(cueTexts.join(' ')).not.toContain('新转写的正文内容')
    // v2 元数据随导入写入（登记簿接管后可随账号清理）。
    expect(localStorage.getItem('grassland:apc:v2:' + JSON.stringify(['acct-video', 'local', 'subtitle-cues-acct-video:t9']))).toBeTruthy()
  })

  test('换号清理后不复活：墓碑拦截旧值，恢复退回新转写内容且旧值被删除', async () => {
    localStorage.setItem('subtitle-cues-acct-video:t9', JSON.stringify(oldCues))
    // 换号清理（另一账号会话主动清 acct-video：写墓碑 + 删值）。
    const { clearAccountCache } = await import('../../../lib/account-private-cache')
    clearAccountCache('acct-video')
    sessionFixture.ownerAccountId = 'acct-other'

    const wrapper = await selectFromHistory()
    // 墓碑之后不猜新缓存：恢复退回新转写的启发式拆分，不渲染旧账号文本。
    const cueTexts = wrapper.findAll('.vs-cue-row input[type="text"]')
      .map((input) => (input.element as HTMLInputElement).value)
    expect(cueTexts.join(' ')).toContain('新转写的正文内容')
    expect(cueTexts.join(' ')).not.toContain('旧账号暂存的字幕')
    // 旧账号值已物理删除；新账号键空间不包含旧键。
    expect(localStorage.getItem('subtitle-cues-acct-video:t9')).toBeNull()
    expect(localStorage.getItem('grassland:apc:gen:' + JSON.stringify(['acct-video']))).toBeTruthy()
  })
})
