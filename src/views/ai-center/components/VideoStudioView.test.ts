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

const uploadContentAssetFile = vi.fn()
const createContentAsset = vi.fn()
const jumpToGrassland = vi.fn()

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
vi.mock('../../../composables/useAiStudio', () => ({
  useAiStudio: () => ({
    transcribe: vi.fn(),
    bgmAdvice: vi.fn(),
    error: { value: '' },
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
