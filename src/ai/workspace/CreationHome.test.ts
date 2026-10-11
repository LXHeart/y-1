// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CreationHome from './CreationHome.vue'

async function openHome(authenticated = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', name: 'create', component: { template: '<div />' } },
    ...['projects', 'assets', 'tools', 'settings', 'images', 'article', 'moments', 'video-clone'].map(name => ({
      path: `/${name}`, name, component: { template: '<div />' },
    })),
  ] })
  await router.push('/')
  const wrapper = mount(CreationHome, {
    props: { authenticated },
    global: { plugins: [router, createPinia()] },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ success: true, data: { provider: '60s', items: [{ rank: 1, title: '周末城市漫步' }] } }))))
})
afterEach(() => vi.unstubAllGlobals())

describe('创作首页重设计：一句话主行动', () => {
  it('主行动按平台 × 形式组装 handoff，主题作为 prefill 带入创作大页', async () => {
    const { wrapper } = await openHome()
    await wrapper.get('#workspace-hero-topic').setValue('给社区咖啡馆写种草笔记')
    await wrapper.get('.workspace-hero-submit').trigger('click')
    const events = wrapper.emitted('start-workflow')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1][0]).toMatchObject({
      platformId: 'xiaohongshu', contentFormId: 'graphic', targetView: 'xhs-studio',
      source: { type: 'independent' }, prefill: { topic: '给社区咖啡馆写种草笔记' },
    })
    wrapper.unmount()
  })
  it('内容形式选项跟随平台能力，切到朋友圈只剩图片 + 文字 / 视频 + 文字', async () => {
    const { wrapper } = await openHome()
    await wrapper.get('#workspace-hero-platform').setValue('moments')
    const options = wrapper.findAll('#workspace-hero-form option').map(option => option.text())
    expect(options).toEqual(['图片 + 文字', '视频 + 文字'])
    await wrapper.get('.workspace-hero-submit').trigger('click')
    const events = wrapper.emitted('start-workflow')
    const handoff = events![events!.length - 1][0]
    expect(handoff).toMatchObject({ platformId: 'moments', contentFormId: 'image-text', targetView: 'moments' })
    wrapper.unmount()
  })
  it('示例与 Ctrl+Enter：点示例填入主题并聚焦，快捷键直接开始', async () => {
    const { wrapper } = await openHome()
    await wrapper.get('.workspace-example-chip').trigger('click')
    expect((wrapper.get('#workspace-hero-topic').element as HTMLTextAreaElement).value).toContain('城市漫步')
    await wrapper.get('#workspace-hero-topic').trigger('keydown', { key: 'Enter', ctrlKey: true })
    const events = wrapper.emitted('start-workflow')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1][0]).toMatchObject({ prefill: { topic: '周末城市漫步路线合集，人少免费还出片' } })
    wrapper.unmount()
  })
})

describe('创作首页重设计：内容形式 × 平台矩阵', () => {
  it('默认列出全部平台；筛选「视频」只保留能做的并按该形式开始', async () => {
    const { wrapper } = await openHome()
    expect(wrapper.findAll('.workspace-platform-entry')).toHaveLength(9)
    const tabs = wrapper.findAll('.workspace-form-tabs button')
    expect(tabs.map(tab => tab.text())).toEqual(['全部', '图文', '视频', '图片 + 文字', '视频 + 文字'])
    await tabs.filter(tab => tab.text() === '视频')[0].trigger('click')
    const cards = wrapper.findAll('.workspace-platform-entry')
    expect(cards.map(card => card.find('h3').text())).toEqual(['小红书', '抖音', '大众点评', '快手', '视频号', 'Bilibili'])
    expect(wrapper.text()).toContain('支持视频的 6 个平台')
    await cards[3].trigger('click') // 快手在筛选结果中只支持视频
    const events = wrapper.emitted('start-workflow')
    expect(events![events!.length - 1][0]).toMatchObject({ platformId: 'kuaishou', contentFormId: 'video', targetView: 'video-production' })
    wrapper.unmount()
  })
  it('筛选「图片 + 文字」只剩朋友圈，卡片按所选形式开始', async () => {
    const { wrapper } = await openHome()
    const tab = wrapper.findAll('.workspace-form-tabs button').filter(item => item.text() === '图片 + 文字')[0]
    await tab.trigger('click')
    const cards = wrapper.findAll('.workspace-platform-entry')
    expect(cards.map(card => card.find('h3').text())).toEqual(['朋友圈'])
    await cards[0].trigger('click')
    const events = wrapper.emitted('start-workflow')
    expect(events![events!.length - 1][0]).toMatchObject({ platformId: 'moments', contentFormId: 'image-text', targetView: 'moments' })
    wrapper.unmount()
  })
  it('平台卡与主行动共用同一句话主题', async () => {
    const { wrapper } = await openHome()
    await wrapper.get('#workspace-hero-topic').setValue('独居生活秩序')
    await wrapper.findAll('.workspace-platform-entry')[6].trigger('click') // 公众号：仅图文
    const events = wrapper.emitted('start-workflow')
    expect(events![events!.length - 1][0]).toMatchObject({ platformId: 'wechat-official', prefill: { topic: '独居生活秩序' } })
    wrapper.unmount()
  })
})

describe('创作首页重设计：账号态', () => {
  it('匿名不显示继续创作，侧栏收敛为登录引导并可发起登录', async () => {
    const { wrapper } = await openHome(false)
    expect(wrapper.find('.workspace-resume').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录后可查看积分余额')
    await wrapper.get('.workspace-rail-card .gl-btn-primary').trigger('click')
    expect(wrapper.emitted('request-login')).toBeTruthy()
    wrapper.unmount()
  })
  it('登录后展示继续创作，点击继续按草稿能力路由恢复现场', async () => {
    const project = {
      id: 'p-1', title: '知乎复盘', capability: 'article', platform: 'zhihu', status: 'draft',
      version: 1, workspace: {}, resultAssetIds: [], runIds: [], updatedAt: new Date().toISOString(),
    }
    const envelope = (data: unknown) => new Response(JSON.stringify({ success: true, data }))
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      return envelope(url.includes('/api/creation-drafts/p-1') ? project : { items: [project], nextCursor: null })
    }))
    const { wrapper, router } = await openHome(true)
    expect(wrapper.text()).toContain('知乎复盘')
    await wrapper.get('.workspace-resume-card .gl-btn-primary').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('article')
    expect(router.currentRoute.value.query).toEqual({ draft: 'p-1' })
    wrapper.unmount()
  })
})
