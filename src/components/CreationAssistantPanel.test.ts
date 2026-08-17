// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import CreationAssistantPanel from './CreationAssistantPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function envelope(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

function sse(frames: Array<Record<string, unknown>>): Response {
  const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
  lines.push('data: [DONE]', '')
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(lines.join('\n')))
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

const draft = {
  id: 'd-1', title: '草稿一', sourceType: 'independent', status: 'draft', version: 1,
  createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z',
  content: '这是一段足够长的正文内容',
}

describe('CreationAssistantPanel', () => {
  test('未登录时提示登录且不拉草稿', async () => {
    const fetchMock = vi.fn(async () => envelope({ items: [] }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: false } })
    await flushPromises()

    expect(wrapper.text()).toContain('登录后可使用创作助手')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('登录后渲染草稿列表，打开后正文进编辑框', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/creation-drafts/d-1')) return envelope(draft)
      return envelope({ items: [draft] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    expect(wrapper.text()).toContain('草稿一')

    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()

    expect(wrapper.find<HTMLTextAreaElement>('.as-textarea').element.value)
      .toBe('这是一段足够长的正文内容')
  })

  test('版本历史默认比较最新两版并标出变化字段', async () => {
    const current = { ...draft, version: 3, title: '当前标题', content: '第三版正文' }
    const versions = [
      { version: 3, createdAt: '2026-08-17T03:00:00Z', title: '当前标题' },
      { version: 2, createdAt: '2026-08-17T02:00:00Z', title: '旧标题' },
      { version: 1, createdAt: '2026-08-17T01:00:00Z', title: '初始标题' },
    ]
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/versions?limit=20')) return envelope({ items: versions, nextCursor: null })
      if (url.endsWith('/versions/3')) return envelope({
        ...current, createdAt: versions[0].createdAt,
      })
      if (url.endsWith('/versions/2')) return envelope({
        ...current, version: 2, createdAt: versions[1].createdAt, title: '旧标题', content: '第二版正文',
      })
      if (url.endsWith('/d-1')) return envelope(current)
      return envelope({ items: [current] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()
    await wrapper.find('.as-history-btn').trigger('click')
    await flushPromises()

    expect(wrapper.get('.version-history').text()).toContain('当前版本')
    expect(wrapper.findAll('.vh-column-head')).toHaveLength(2)
    expect(wrapper.findAll('.vh-changed').length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('第三版正文')
    expect(wrapper.text()).toContain('第二版正文')
  })

  test('版本历史可改选任意两版并载入旧版走原 PUT 保存路径', async () => {
    const current = { ...draft, version: 3, title: '当前标题', content: '第三版正文', outline: '当前大纲' }
    const versions = [3, 2, 1].map(version => ({
      version, createdAt: `2026-08-17T0${version}:00:00Z`, title: `标题 v${version}`,
    }))
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        const body = JSON.parse(init.body as string)
        return envelope({ ...current, ...body, outline: body.outline ?? undefined, version: 4 })
      }
      if (url.endsWith('/versions?limit=20')) return envelope({ items: versions, nextCursor: null })
      const match = url.match(/\/versions\/(\d+)$/)
      if (match) {
        const version = Number(match[1])
        return envelope({
          ...current,
          version,
          createdAt: `2026-08-17T0${version}:00:00Z`,
          title: `标题 v${version}`,
          content: `正文 v${version}`,
          ...(version === 1 ? { outline: undefined } : {}),
        })
      }
      if (url.endsWith('/d-1')) return envelope(current)
      return envelope({ items: [current] })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()
    await wrapper.find('.as-history-btn').trigger('click')
    await flushPromises()

    const checkboxes = wrapper.findAll<HTMLInputElement>('.vh-item input')
    await checkboxes[1].setValue(false)
    await checkboxes[2].setValue(true)
    await flushPromises()
    expect(wrapper.text()).toContain('正文 v1')

    const restore = wrapper.findAll('.vh-restore').find(button =>
      button.element.closest('.vh-column-head')?.textContent?.includes('v1'))
    expect(restore).toBeDefined()
    await restore!.trigger('click')
    await flushPromises()

    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(put).toBeDefined()
    expect(JSON.parse((put![1] as RequestInit).body as string)).toMatchObject({
      expectedVersion: 3,
      title: '标题 v1',
      content: '正文 v1',
      outline: null,
    })
    expect(wrapper.find<HTMLTextAreaElement>('.as-textarea').element.value).toBe('正文 v1')
    expect(wrapper.find('.version-history').exists()).toBe(false)
  })

  test.each([
    {
      label: '任务',
      source: { type: 'task' as const, taskId: 'task-1', applicationId: 'app-1', taskVersion: 4 },
      topic: '新品探店',
      expected: { sourceType: 'task', taskId: 'task-1', taskVersion: 4, topic: '新品探店' },
    },
    {
      label: '门店',
      source: { type: 'store' as const, organizationId: 'org-1', storeId: 'store-1' },
      topic: '夏日菜单',
      expected: { sourceType: 'store', storeId: 'store-1', topic: '夏日菜单' },
    },
    {
      label: '热点',
      source: { type: 'hot-topic' as const, title: '城市夜经济', topicId: 'hot-1' },
      topic: '夜经济里的小店机会',
      expected: { sourceType: 'hot-topic', topic: '夜经济里的小店机会' },
    },
  ])('$label 来源新建草稿保留完整可持久化上下文', async ({ source, topic, expected }) => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope({ ...draft, ...expected })
      return envelope({ items: [] })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(CreationAssistantPanel, {
      props: {
        authenticated: true,
        platform: 'xiaohongshu',
        contentForm: 'graphic',
        source,
        topic,
      },
    })
    await flushPromises()

    await wrapper.find('.as-btn').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post).toBeDefined()
    expect(JSON.parse((post![1] as RequestInit).body as string)).toMatchObject({
      ...expected,
      platform: 'xiaohongshu',
      contentForm: 'graphic',
    })
  })

  test('没有 CreationSource 时不以任务要求猜测草稿来源', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draft)
      return envelope({ items: [] })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(CreationAssistantPanel, {
      props: { authenticated: true, taskRequirements: '必须出现门店名' },
    })
    await flushPromises()
    await wrapper.find('.as-btn').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    const body = JSON.parse((post![1] as RequestInit).body as string)
    expect(body.sourceType).toBe('independent')
  })

  test('评分结果按维度渲染并显示综合分', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/score')) {
        return sse([
          { type: 'score', dimension: '标题吸引力', score: 8, advice: '更具体些' },
          { type: 'overall', score: 7 },
        ])
      }
      if (url.includes('/api/creation-drafts/d-1') && init?.method !== 'PUT') return envelope(draft)
      return envelope({ items: [draft] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()

    const tabs = wrapper.findAll('.as-tab')
    await tabs[2].trigger('click')
    await wrapper.findAll('.as-btn')[0].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('综合得分 7')
    expect(wrapper.text()).toContain('标题吸引力')
    expect(wrapper.text()).toContain('更具体些')
  })

  test('切换草稿会清除上一份草稿的评分结果', async () => {
    const secondDraft = { ...draft, id: 'd-2', title: '草稿二', content: '第二份足够长的正文内容' }
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/score')) {
        return sse([
          { type: 'score', dimension: '标题吸引力', score: 8 },
          { type: 'overall', score: 8 },
        ])
      }
      if (url.endsWith('/d-1')) return envelope(draft)
      if (url.endsWith('/d-2')) return envelope(secondDraft)
      return envelope({ items: [draft, secondDraft] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.findAll('.as-item-open')[0].trigger('click')
    await flushPromises()
    await wrapper.findAll('.as-tab')[2].trigger('click')
    await wrapper.findAll('.as-btn')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('综合得分 8')

    await wrapper.findAll('.as-tab')[0].trigger('click')
    await wrapper.findAll('.as-item-open')[1].trigger('click')
    await flushPromises()
    await wrapper.findAll('.as-tab')[2].trigger('click')
    expect(wrapper.text()).not.toContain('综合得分 8')
  })

  test('修改同一草稿正文会清除旧评分结果', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/score')) {
        return sse([
          { type: 'score', dimension: '标题吸引力', score: 8 },
          { type: 'overall', score: 8 },
        ])
      }
      if (url.endsWith('/d-1') && init?.method !== 'PUT') return envelope(draft)
      if (init?.method === 'PUT') return envelope({ ...draft, version: 2 })
      return envelope({ items: [draft] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()
    await wrapper.findAll('.as-tab')[2].trigger('click')
    await wrapper.findAll('.as-btn')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('综合得分 8')

    await wrapper.findAll('.as-tab')[0].trigger('click')
    await wrapper.find('.as-textarea').setValue('修改后的正文内容足够长')
    await wrapper.findAll('.as-tab')[2].trigger('click')
    expect(wrapper.text()).not.toContain('综合得分 8')
    wrapper.unmount()
    await flushPromises()
  })

  test('优化建议按流式内容渲染', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/suggest')) return sse([{ content: '把开头写得更具体' }])
      if (url.includes('/api/creation-drafts/d-1') && init?.method !== 'PUT') return envelope(draft)
      return envelope({ items: [draft] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()
    await wrapper.findAll('.as-tab')[2].trigger('click')
    const suggestButton = wrapper.findAll('.as-btn').find((item) => item.text().includes('优化建议'))!
    await suggestButton.trigger('click')
    await flushPromises()

    expect(wrapper.get('.as-suggestion').text()).toBe('把开头写得更具体')
  })

  test('引导 brief 的推测字段打 AI 推测标记', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/guide')) {
        return sse([{
          type: 'brief', angle: '性价比', audience: '上班族', structure: '总分总',
          inferredFields: 'audience',
        }])
      }
      return envelope({ items: [] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.findAll('.as-tab')[1].trigger('click')

    await wrapper.find('.as-input').setValue('想写咖啡店探店')
    await wrapper.find('.as-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('性价比')
    const inferred = wrapper.findAll('.as-inferred')
    expect(inferred).toHaveLength(1)
    expect(wrapper.find('.as-brief').text()).toContain('上班族')
  })

  test('引导简报可写入当前草稿大纲并可重置对话', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/guide')) {
        return sse([{
          type: 'brief', angle: '性价比', audience: '上班族', structure: '总分总',
          inferredFields: '',
        }])
      }
      if (init?.method === 'PUT') {
        const body = JSON.parse(init.body as string)
        return envelope({ ...draft, ...body, version: 2 })
      }
      if (url.includes('/api/creation-drafts/d-1')) return envelope(draft)
      return envelope({ items: [draft] })
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.useFakeTimers()

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.find('.as-item-open').trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.findAll('.as-tab')[1].trigger('click')
    await wrapper.find('.as-input').setValue('写一篇探店')
    await wrapper.find('.as-btn').trigger('click')
    await vi.advanceTimersByTimeAsync(0)

    const applyButton = wrapper.findAll('.as-btn').find((item) => item.text().includes('写入当前草稿'))!
    await applyButton.trigger('click')
    await vi.advanceTimersByTimeAsync(1500)
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(JSON.parse((put![1] as RequestInit).body as string).outline)
      .toBe('角度：性价比\n受众：上班族\n结构：总分总')

    const resetButton = wrapper.findAll('.as-btn').find((item) => item.text().includes('重新开始'))!
    await resetButton.trigger('click')
    expect(wrapper.text()).not.toContain('已整理出创作简报')
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(0)
    vi.useRealTimers()
  })

  test('没有任务要求时不显示任务覆盖检查', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/creation-drafts/d-1')) return envelope(draft)
      return envelope({ items: [draft] })
    }))

    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()
    await wrapper.findAll('.as-tab')[2].trigger('click')

    expect(wrapper.text()).not.toContain('任务覆盖检查')
  })

  test('带任务要求时可跑覆盖检查并列出未覆盖项', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/task-coverage')) {
        return sse([
          { type: 'gap', requirement: '必须出现门店名', status: 'missing', hint: '开头点名' },
          { type: 'covered', covered: false },
        ])
      }
      if (url.includes('/api/creation-drafts/d-1') && init?.method !== 'PUT') return envelope(draft)
      return envelope({ items: [draft] })
    }))

    const wrapper = mount(CreationAssistantPanel, {
      props: { authenticated: true, taskRequirements: '必须出现门店名' },
    })
    await flushPromises()
    await wrapper.find('.as-item-open').trigger('click')
    await flushPromises()
    await wrapper.findAll('.as-tab')[2].trigger('click')

    const coverageBtn = wrapper.findAll('.as-btn').find((btn) => btn.text().includes('任务覆盖检查'))
    expect(coverageBtn).toBeDefined()
    await coverageBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('还有未覆盖的任务要求')
    expect(wrapper.text()).toContain('必须出现门店名')
  })

  test('冲突态给出重新载入入口', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        return new Response(JSON.stringify({ success: false, error: '草稿已被其他设备修改' }), {
          status: 409, headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.includes('/api/creation-drafts/d-1')) return envelope(draft)
      return envelope({ items: [draft] })
    }))

    vi.useFakeTimers()
    const wrapper = mount(CreationAssistantPanel, { props: { authenticated: true } })
    await vi.advanceTimersByTimeAsync(0)
    await wrapper.find('.as-item-open').trigger('click')
    await vi.advanceTimersByTimeAsync(0)

    await wrapper.find('.as-textarea').setValue('改了正文')
    await vi.advanceTimersByTimeAsync(1600)
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('版本冲突')
    expect(wrapper.text()).toContain('重新载入')
    expect(wrapper.get<HTMLButtonElement>('.as-history-btn').element.disabled).toBe(true)
    await wrapper.find('.as-conflict .as-link').trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.text()).not.toContain('版本冲突')
    vi.useRealTimers()
  })
})
