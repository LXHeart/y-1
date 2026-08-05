// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiCreationCenter from './AiCreationCenter.vue'
import type { CreationEntry } from '../types/ai-creation'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function buttons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button')
}

function button(wrapper: ReturnType<typeof mount>, label: string) {
  return buttons(wrapper).find((item) => item.text().includes(label))!
}

function choiceButton(wrapper: ReturnType<typeof mount>, groupLabel: string, label: string) {
  return wrapper
    .get(`[aria-label="${groupLabel}"]`)
    .findAll('button')
    .find((item) => {
      const strong = item.find('strong')
      return strong.exists() ? strong.text().trim() === label : item.text().trim() === label
    })!
}

describe('AI 内容创作中心', () => {
  test('第一步只展示九个发布平台，不先展示独立工具', () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(9)
    expect(wrapper.text()).toContain('选择发布平台')
    expect(wrapper.text()).not.toContain('爆款文章')
    expect(wrapper.text()).not.toContain('视频提取分析')
  })

  test('公众号图文独立创作发出文章工作流和完整 handoff', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="wechat-official"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('秋季新品发布')
    await button(wrapper, '开始创作').trigger('click')

    const handoff = wrapper.emitted('start-workflow')?.[0]?.[0] as Record<string, unknown>
    expect(handoff).toMatchObject({
      platformId: 'wechat-official', contentFormId: 'graphic', source: { type: 'independent' },
      workflowId: 'longform', targetView: 'article', prefill: { topic: '秋季新品发布' },
    })
    expect(typeof handoff.revision).toBe('number')
  })

  test('每次开始创作都签发新 revision，允许同一工作流接收新预填', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="wechat-official"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('第一版')
    await button(wrapper, '开始创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('第二版')
    await button(wrapper, '开始创作').trigger('click')

    const emitted = wrapper.emitted('start-workflow') as Array<[CreationEntry]>
    expect(emitted[1][0].revision).toBeGreaterThan(emitted[0][0].revision)
    expect(emitted[1][0].prefill?.topic).toBe('第二版')
  })

  test('未登录选择任务或门店来源时请求登录，不请求业务数据', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '从任务创作').trigger('click')

    expect(wrapper.emitted('request-login')).toHaveLength(1)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('不支持的已规划组合显示尚未接入且不能开始', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('门店探店')

    expect(wrapper.text()).toContain('该创作路径尚未接入')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
  })

  test('工作台传入任务入口后锁定任务平台和形式并带入引用', async () => {
    const entry: CreationEntry = {
      revision: 7,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      source: { type: 'task', taskId: 'task-1', applicationId: 'app-1', taskVersion: 3 },
      prefill: { topic: '新品探店', instructions: '突出招牌菜，不提竞品' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry } })
    await flushPromises()

    expect(wrapper.text()).toContain('新品探店')
    expect(wrapper.text()).toContain('任务版本 3')
    expect(wrapper.text()).toContain('突出招牌菜，不提竞品')
    expect(wrapper.get('[data-testid="selection-summary"]').text()).toContain('小红书')

    await button(wrapper, '开始创作').trigger('click')
    const handoff = wrapper.emitted('start-workflow')?.[0]?.[0] as CreationEntry
    expect(handoff).toMatchObject({ ...entry, revision: expect.any(Number) })
    expect(handoff.revision).toBeGreaterThan(entry.revision)
  })

  test('任务未提供内容形式时保留任务引用并要求用户显式选择', async () => {
    const entry: CreationEntry = {
      revision: 8,
      platformId: 'xiaohongshu',
      contentFormId: null,
      source: { type: 'task', taskId: 'task-2', applicationId: 'app-2', taskVersion: 1 },
      prefill: { topic: '新品试吃' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry } })
    await flushPromises()

    expect(wrapper.get('[data-platform-id="xiaohongshu"]').attributes('disabled')).toBeDefined()
    expect(choiceButton(wrapper, '内容形式', '视频').attributes('disabled')).toBeUndefined()
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'xiaohongshu',
      contentFormId: 'video',
      source: { type: 'task', taskId: 'task-2', applicationId: 'app-2', taskVersion: 1 },
      targetView: 'video-production',
    })
  })

  test('热点来源在补选平台和形式后仍保留热点上下文', async () => {
    const entry: CreationEntry = {
      revision: 9,
      platformId: null,
      contentFormId: null,
      source: { type: 'hot-topic', title: '城市夜经济升温', topicId: 'hot-1' },
      prefill: { topic: '城市夜经济升温' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry } })

    await wrapper.get('[data-platform-id="zhihu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'zhihu',
      contentFormId: 'graphic',
      source: { type: 'hot-topic', title: '城市夜经济升温', topicId: 'hot-1' },
      prefill: { topic: '城市夜经济升温' },
    })
  })

  test('参考素材要求输入链接，并把链接交给对应视频分析工作流', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="bilibili"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '参考素材').trigger('click')

    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea[name="reference-url"]').setValue('https://www.bilibili.com/video/BV1example')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'bilibili',
      source: { type: 'reference', sourceUrl: 'https://www.bilibili.com/video/BV1example' },
      targetView: 'video',
    })
  })

  test('门店资料请求完成前禁止开始创作', async () => {
    let resolveProfile: ((value: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = null
      if (url === '/api/organizations') data = [{ id: 'org-1', name: '云朵餐饮' }]
      if (url === '/api/organizations/org-1/stores') data = [{ id: 'store-1', organizationId: 'org-1', name: '云朵面馆' }]
      if (url.endsWith('/profile')) {
        return new Promise<Response>((resolve) => { resolveProfile = resolve })
      }
      return new Response(JSON.stringify({ success: true, data }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })
    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从门店创作').trigger('click')
    await flushPromises()
    await wrapper.get('select[name="organization"]').setValue('org-1')
    await flushPromises()
    await wrapper.get('select[name="store"]').setValue('store-1')

    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
    resolveProfile?.(new Response(JSON.stringify({
      success: true,
      data: { storeId: 'store-1', address: null, description: null },
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    await flushPromises()
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeUndefined()
  })

  test('门店来源只加载当前账号组织和其门店，选择后读取门店资料', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = null
      if (url === '/api/organizations') data = [{ id: 'org-1', name: '云朵餐饮', ownerAccountId: 'acct-1', permissionTier: 'basic_publish', industry: 'catering', createdAt: null }]
      if (url === '/api/organizations/org-1/stores') data = [{ id: 'store-1', organizationId: 'org-1', name: '云朵面馆', status: 'active', createdAt: null }]
      if (url === '/api/organizations/org-1/stores/store-1/profile') data = { storeId: 'store-1', address: '{"address":"人民路 8 号"}', description: '手工面与现熬汤底', phone: null, businessHours: null, status: 'active', createdAt: null }
      return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })

    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从门店创作').trigger('click')
    await flushPromises()
    await wrapper.get('select[name="organization"]').setValue('org-1')
    await flushPromises()
    await wrapper.get('select[name="store"]').setValue('store-1')
    await flushPromises()
    await button(wrapper, '开始创作').trigger('click')

    expect(calls.map(([url]) => url)).toEqual([
      '/api/organizations',
      '/api/organizations/org-1/stores',
      '/api/organizations/org-1/stores/store-1/profile',
    ])
    expect(calls.every(([, init]) => init?.credentials === 'include')).toBe(true)
    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      source: { type: 'store', organizationId: 'org-1', storeId: 'store-1' },
      targetView: 'video-production',
      prefill: { storeName: '云朵面馆', address: '人民路 8 号', storeDescription: '手工面与现熬汤底' },
    })
  })
})
