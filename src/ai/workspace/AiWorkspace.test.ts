// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AiWorkspace from './AiWorkspace.vue'

async function open(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: ['create','write','images','videos','projects','assets','assistant','tools','settings','video-clone'].map(name => ({ path: name === 'create' ? '/' : `/${name}`, name, component: { template:'<div />' } })) })
  await router.push(path)
  const wrapper = mount(AiWorkspace, { props: { authenticated: false, entry: null }, global: { plugins: [router], stubs: {
    AiCreationCenter: { name: 'AiCreationCenter', props:['section','writingOnly','entry'], emits:['section-change'], template:'<div data-testid="center">{{ section }}<button @click="$emit(\'section-change\',\'image-studio\')">编辑所选图片</button></div>' },
    VoiceChatView: { template:'<div data-testid="chat" />' },
    ReferenceProjects: { template:'<div data-testid="references" />' },
  } } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ success: true, data: { provider: '60s', items: [{ rank: 1, title: '周末城市漫步' }] } }))))
})
afterEach(() => vi.unstubAllGlobals())

describe('作品入口与现有工作流交接', () => {
  it('普通首页展示主行动与平台矩阵，旧来源深链仍进入原创作面', async () => {
    const { wrapper, router } = await open('/')
    expect(wrapper.text()).toContain('想做点什么？一句话说清就行')
    expect(wrapper.findAll('.workspace-platform-entry h3').map(item => item.text())).toEqual(['小红书', '抖音', '大众点评', '快手', '视频号', 'Bilibili', '公众号', '知乎', '朋友圈'])
    expect(wrapper.text()).toContain('周末城市漫步')
    expect(wrapper.find('[data-testid="center"]').exists()).toBe(false)
    await router.push('/?capability=video&taskId=task-1')
    await flushPromises()
    expect(wrapper.get('[data-testid="center"]').text()).toContain('create')
    expect(router.currentRoute.value.query.taskId).toBe('task-1')
    wrapper.unmount()
  })
  it('平台入口直达对应创作大页（handoff 带默认内容形式）', async () => {
    const { wrapper } = await open('/')
    const expected: Array<[string, string, string]> = [
      ['xiaohongshu', 'graphic', 'xhs-studio'],
      ['douyin', 'graphic', 'article'],
      ['dianping', 'graphic', 'image'],
      ['kuaishou', 'video', 'video-production'],
      ['wechat-channels', 'video', 'video-production'],
      ['bilibili', 'video', 'video-production'],
      ['wechat-official', 'graphic', 'article'],
      ['zhihu', 'graphic', 'zhihu-studio'],
      ['moments', 'image-text', 'moments'],
    ]
    const cards = wrapper.findAll('.workspace-platform-entry')
    expect(cards).toHaveLength(expected.length)
    for (let index = 0; index < expected.length; index++) {
      const [platform, form, targetView] = expected[index]
      await cards[index].trigger('click')
      const events = wrapper.emitted('start-workflow')
      expect(events).toBeTruthy()
      const handoff = events![events!.length - 1][0]
      expect(handoff).toMatchObject({ platformId: platform, contentFormId: form, targetView, source: { type: 'independent' } })
    }
    wrapper.unmount()
  })
  it('热点在站内导航时带入主题，深链同样恢复平台与选题', async () => {
    const { wrapper, router } = await open('/')
    await wrapper.get('.workspace-hot-list a').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'AiCreationCenter' }).props('entry')).toMatchObject({ source: { type: 'hot-topic', title: '周末城市漫步' }, prefill: { topic: '周末城市漫步' } })
    await router.push('/?entry=hot&title=新的选题&platform=zhihu')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'AiCreationCenter' }).props('entry')).toMatchObject({ platformId: 'zhihu', source: { type: 'hot-topic', title: '新的选题' } })
    wrapper.unmount()
  })
  it('图片页标签、浏览器后退与工作流同步', async () => {
    const { wrapper, router } = await open('/images')
    expect(wrapper.get('[data-testid="center"]').text()).toContain('image-gen')
    await router.push('/images?tab=edit')
    await flushPromises()
    expect(wrapper.get('[data-testid="center"]').text()).toContain('image-studio')
    router.back()
    await new Promise(resolve => setTimeout(resolve, 0))
    await flushPromises()
    expect(wrapper.get('[data-testid="center"]').text()).toContain('image-gen')
    wrapper.unmount()
  })
  it('素材库内部编辑动作跳到同一图片编辑器并保留来源引用', async () => {
    const { wrapper, router } = await open('/assets?taskId=task-1')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('images')
    expect(router.currentRoute.value.query).toMatchObject({ tab:'edit', taskId:'task-1' })
    wrapper.unmount()
  })
  it('助手文字语音与内容检查归入同一入口', async () => {
    const { wrapper, router } = await open('/assistant')
    expect(wrapper.find('[data-testid="chat"]').exists()).toBe(true)
    await router.push('/assistant?tab=review')
    await flushPromises()
    expect(wrapper.find('[data-testid="chat"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="center"]').text()).toContain('assistant')
    wrapper.unmount()
  })
  it('未知工具参数不会误开设置或模型调用页面', async () => {
    const { wrapper } = await open('/tools?tab=unknown')
    expect(wrapper.text()).toContain('工具箱')
    expect(wrapper.find('[data-testid="center"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
