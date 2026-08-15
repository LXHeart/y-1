// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import MomentsCreationView from './MomentsCreationView.vue'
import type { CreationHandoff } from '../../types/ai-creation'

/**
 * MomentsCreationView 特征测试：表单骨架、规范提示条、生成契约与结果渲染、handoff 预填。
 */

function sse(frames: Array<Record<string, unknown>>): Response {
  return new Response(new ReadableStream({
    start(controller) {
      const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
      lines.push('data: [DONE]', '')
      controller.enqueue(new TextEncoder().encode(lines.join('\n')))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

const fetchMock = vi.fn()

function handoff(overrides: Partial<CreationHandoff> = {}): CreationHandoff {
  return {
    revision: 1,
    platformId: 'moments',
    contentFormId: 'image-text',
    source: { type: 'independent' },
    workflowId: 'moments-image-text',
    targetView: 'moments',
    prefill: {},
    ...overrides,
  } as CreationHandoff
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountView(props: { creationHandoff?: CreationHandoff | null } = {}) {
  return mount(MomentsCreationView, {
    props: { creationHandoff: props.creationHandoff ?? null },
  })
}

describe('MomentsCreationView 表单骨架', () => {
  test('渲染主题输入、四种风格、生成按钮初始禁用', () => {
    const wrapper = mountView()
    expect(wrapper.find('input[data-test="moments-topic"]').exists()).toBe(true)
    const labels = wrapper.findAll('label[data-test="moments-style"]').map((l) => l.text())
    expect(labels).toEqual(['生活化', '活动通知', '到店体验', '朋友分享'])
    const button = wrapper.find('button[data-test="moments-generate"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeDefined()
  })

  test('渲染朋友圈规范提示条', () => {
    const wrapper = mountView()
    expect(wrapper.find('[data-test="moments-rule"]').text()).toContain('朋友圈')
  })

  test('素材图上传入口存在且限 9 张', () => {
    const wrapper = mountView()
    const input = wrapper.find('input[data-test="moments-images"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('multiple')).toBeDefined()
  })
})

describe('MomentsCreationView 生成流程', () => {
  test('填主题+选风格后生成：请求契约与结果渲染', async () => {
    fetchMock.mockResolvedValueOnce(sse([
      { type: 'progress', message: '正在生成朋友圈内容…' },
      {
        type: 'result',
        copy: '开业大吉，周末来店里坐坐☕',
        imageOrder: [{ index: 1, reason: '封面招牌' }],
        captions: [{ index: 1, text: '门店招牌' }],
      },
    ]))
    const wrapper = mountView()
    await wrapper.find('input[data-test="moments-topic"]').setValue('新店开业')
    await wrapper.findAll('input[data-test="moments-style-input"]')[2].setValue()
    await wrapper.find('button[data-test="moments-generate"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/moments-generation/generate')
    const body = JSON.parse(init.body as string)
    expect(body.topic).toBe('新店开业')
    expect(body.style).toBe('store-visit')

    expect(wrapper.find('[data-test="moments-copy"]').exists()).toBe(true)
    expect((wrapper.find('[data-test="moments-copy"]').element as HTMLTextAreaElement).value)
      .toBe('开业大吉，周末来店里坐坐☕')
    expect(wrapper.find('[data-test="moments-order"]').text()).toContain('封面招牌')
    expect(wrapper.text()).toContain('复制')
  })

  test('error 帧 → 错误提示且无结果区', async () => {
    fetchMock.mockResolvedValueOnce(sse([{ type: 'error', error: '朋友圈内容生成失败' }]))
    const wrapper = mountView()
    await wrapper.find('input[data-test="moments-topic"]').setValue('主题')
    await wrapper.findAll('input[data-test="moments-style-input"]')[0].setValue()
    await wrapper.find('button[data-test="moments-generate"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="moments-error"]').text()).toContain('朋友圈内容生成失败')
    expect(wrapper.find('[data-test="moments-copy"]').exists()).toBe(false)
  })

  test('handoff 预填主题与感受', async () => {
    const wrapper = mountView({
      creationHandoff: handoff({ prefill: { topic: '预填主题', instructions: '突出周末活动' } }),
    })
    await flushPromises()
    expect((wrapper.find('input[data-test="moments-topic"]').element as HTMLInputElement).value)
      .toBe('预填主题')
    expect((wrapper.find('textarea[data-test="moments-feelings"]').element as HTMLTextAreaElement).value)
      .toBe('突出周末活动')
  })

  test('任务 handoff：生成请求带 taskMode 与 contextSnapshotId', async () => {
    fetchMock.mockResolvedValueOnce(sse([
      { type: 'result', copy: '任务文案', imageOrder: [], captions: [] },
    ]))
    const wrapper = mountView({
      creationHandoff: handoff({
        source: { type: 'task', taskId: 'task-1', applicationId: 'app-1', taskVersion: 2 },
        contextSnapshotId: 'snap-1',
      }),
    })
    await flushPromises()
    await wrapper.find('input[data-test="moments-topic"]').setValue('任务主题')
    await wrapper.findAll('input[data-test="moments-style-input"]')[1].setValue()
    await wrapper.find('button[data-test="moments-generate"]').trigger('click')
    await flushPromises()

    const body = JSON.parse((fetchMock.mock.calls[0] as [string, RequestInit])[1].body as string)
    expect(body.taskMode).toBe(true)
    expect(body.contextSnapshotId).toBe('snap-1')
  })
})
