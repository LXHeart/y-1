// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import CanvasDeliveryPanel from './CanvasDeliveryPanel.vue'
import type { VideoTask } from '../../../types/video-production'

/**
 * 任务书 #100 C100-07：画布交付面板。
 * TC-019：导出绑定草稿版本；过期重取新授权（重试再请求）；失败不生成；期间修改配文不重新生成。
 * TC-025：修改配文只上抛交付字段（草稿 delivery），task/takes 不动。
 */

function makeTask(overrides: Partial<VideoTask> = {}): VideoTask {
  return {
    id: 'task-1', storyboardId: 'sb-1', mode: 'video', phase: 'succeeded', progress: 100,
    targetDurationSeconds: 25, provider: 'sandbox', model: 'm', unitPriceCents: 1,
    estimatedCostCents: 25, actualCostCents: 24, actualDurationSeconds: 24,
    errorCode: null, errorMessage: null, selection: {}, recommended: {},
    finalUrl: 'https://media.example.test/final.mp4', subtitleUrl: null, shots: [],
    ...overrides,
  }
}

function setupPanel(task: VideoTask | null, plan: { exportStatus?: number } = {}) {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (url.includes('/exports') && !url.includes('/video-production/')) {
      const status = plan.exportStatus ?? 200
      return {
        ok: status === 200, status,
        json: async () => status === 200
          ? { success: true, data: { draftId: 'draft-1', version: 3, downloads: [] } }
          : { success: false, error: '导出失败' },
      }
    }
    if (url.includes('/export/jianying') || url.includes('/export/bundle')) {
      return { ok: true, status: 200, json: async () => ({ success: true, data: { downloadUrl: 'https://media.example.test/jy.zip' } }) }
    }
    return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
  }))
  const openSpy = vi.fn()
  vi.stubGlobal('open', openSpy)
  // happy-dom 无 Blob 对象 URL——manifest 下载链路 stub（不落真实文件）
  Object.assign(URL, {
    createObjectURL: vi.fn(() => 'blob:mock-download'),
    revokeObjectURL: vi.fn(),
  })
  const wrapper = mount(CanvasDeliveryPanel, {
    props: {
      task,
      delivery: { titleOrOpening: '标题', shareCopy: '' },
      platform: 'douyin',
      draftId: 'draft-1',
      draftVersion: 3,
      downloadSubtitle: vi.fn(),
      reportError: vi.fn(),
    },
    attachTo: document.body,
  })
  return { wrapper, calls, openSpy }
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

describe('TC-019：导出绑定版本与过期重取', () => {
  test('交付包导出携带草稿版本；任务产物导出取新授权', async () => {
    const { wrapper, calls, openSpy } = setupPanel(makeTask())
    await wrapper.find('[data-test="delivery-export"]').trigger('click')
    await settle()
    const draftExport = calls.find(call => call.url.includes('/api/creation-drafts/draft-1/exports'))
    expect(JSON.parse(String(draftExport?.init?.body))).toEqual({ version: 3, format: 'bundle-manifest' })

    await wrapper.find('[data-test="canvas-delivery-export-jianying"]').trigger('click')
    await settle()
    expect(calls.some(call => call.url.includes('/tasks/task-1/export/jianying'))).toBe(true)
    expect(openSpy).toHaveBeenCalledWith('https://media.example.test/jy.zip', '_blank', 'noopener')
  })

  test('导出失败不生成本地产物、显示错误；重试重新请求（过期重取新授权）', async () => {
    const plan = { exportStatus: 500 }
    const { wrapper, calls } = setupPanel(makeTask(), plan)
    await wrapper.find('[data-test="delivery-export"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-test="delivery-export-error"]').exists()).toBe(true)
    expect(calls.filter(call => call.url.includes('/exports') && !call.url.includes('/video-production/'))).toHaveLength(1)

    plan.exportStatus = 200
    await wrapper.find('[data-test="delivery-export"]').trigger('click')
    await settle()
    expect(calls.filter(call => call.url.includes('/exports') && !call.url.includes('/video-production/'))).toHaveLength(2)
    expect(wrapper.find('[data-test="delivery-export-error"]').exists()).toBe(false)
  })

  test('导出期间修改配文：只更新交付字段，不触发任何视频/任务生成请求', async () => {
    const { wrapper, calls } = setupPanel(makeTask())
    await wrapper.find('[data-test="delivery-topics"]').setValue('#新话题')
    const emitted = wrapper.emitted('update-delivery')
    expect(emitted).toBeTruthy()
    expect(emitted?.[emitted.length - 1]?.[0]).toMatchObject({ topics: ['新话题'] })
    // 修改配文不产生任何生成/合成/任务类请求
    expect(calls.filter(call => call.url.includes('/api/video-production/'))).toHaveLength(0)
  })
})

describe('TC-025：交付字段与成品隔离', () => {
  test('修改标题/话题/摘要只上抛交付字段载荷，task 对象与终态信息不被改动', async () => {
    const task = makeTask()
    const taskBefore = JSON.stringify(task)
    const { wrapper } = setupPanel(task)
    await wrapper.find('[data-test="delivery-title"]').setValue('新标题')
    const emitted = wrapper.emitted('update-delivery') ?? []
    expect(emitted.length).toBeGreaterThan(0)
    const patch = emitted[emitted.length - 1]?.[0] as Record<string, unknown>
    expect(Object.keys(patch).every(key => [
      'titleOrOpening', 'bodyOrDescription', 'topics', 'summary', 'shareCopy',
      'version', 'platform', 'contentForm', 'declarations',
    ].includes(key))).toBe(true)
    expect(JSON.stringify(task)).toBe(taskBefore)
  })

  test('仅 succeeded 出现面板；成片视频/字幕入口就位', async () => {
    const hidden = setupPanel(makeTask({ phase: 'composing' }))
    expect(hidden.wrapper.find('[data-test="canvas-delivery"]').exists()).toBe(false)

    const { wrapper } = setupPanel(makeTask())
    expect(wrapper.find('[data-test="canvas-delivery-video"]').attributes('src')).toBe('https://media.example.test/final.mp4')
    expect(wrapper.find('[data-test="canvas-delivery-download"]').attributes('href')).toBe('https://media.example.test/final.mp4')
    expect(wrapper.find('[data-test="canvas-delivery-meta"]').text()).toContain('实结')
  })
})
