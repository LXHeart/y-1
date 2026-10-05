// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import AiWorkspace from './AiWorkspace.vue'

async function open(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: ['create','write','images','videos','projects','assets','assistant','tools','settings'].map(name => ({ path: name === 'create' ? '/' : `/${name}`, name, component: { template:'<div />' } })) })
  await router.push(path)
  const wrapper = mount(AiWorkspace, { props: { authenticated: false, entry: null }, global: { plugins: [router], stubs: {
    AiCreationCenter: { props:['section','writingOnly'], emits:['section-change'], template:'<div data-testid="center">{{ section }}<button @click="$emit(\'section-change\',\'image-studio\')">编辑所选图片</button></div>' },
    VoiceChatView: { template:'<div data-testid="chat" />' },
    ReferenceProjects: { template:'<div data-testid="references" />' },
  } } })
  await flushPromises()
  return { wrapper, router }
}

describe('作品入口与现有工作流交接', () => {
  it('普通首页展示作品分类，旧来源深链仍进入原创作面', async () => {
    const { wrapper, router } = await open('/')
    expect(wrapper.text()).toContain('今天想创作什么')
    expect(wrapper.find('[data-testid="center"]').exists()).toBe(false)
    await router.push('/?capability=video&taskId=task-1')
    await flushPromises()
    expect(wrapper.get('[data-testid="center"]').text()).toContain('create')
    expect(router.currentRoute.value.query.taskId).toBe('task-1')
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
