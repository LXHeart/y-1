// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import AiWorkspaceNavigation from './AiWorkspaceNavigation.vue'
import appRouter from '../router'
import { SECTION_DESTINATIONS, WORKSPACE_LINKS, workspaceSection } from '../workspace/navigation'

describe('统一创作工作区导航', () => {
  it('首页、项目、素材、助手、工具与设置均有可解析的真实路由', () => {
    for (const link of WORKSPACE_LINKS) expect(appRouter.resolve({ name: link.name }).matched.length).toBeGreaterThan(0)
    for (const destination of Object.values(SECTION_DESTINATIONS)) expect(appRouter.resolve(destination).matched.length).toBeGreaterThan(0)
  })
  it('退役路径不再挂载实时客户端，丢弃会话参数', async () => {
    await appRouter.push('/digital-human?session=old-session&profileId=old-profile')
    expect(appRouter.currentRoute.value.name).toBe('videos')
    expect(appRouter.currentRoute.value.query).toEqual({ retired: 'realtime' })
  })
  it('旧语音聊天入口继续打开统一助手', async () => {
    await appRouter.push('/voice-chat')
    expect(appRouter.currentRoute.value.name).toBe('assistant')
  })
  it('旧复刻工程深链仍然定位原工程', async () => {
    await appRouter.push('/hypit/project-1?step=review')
    expect(appRouter.currentRoute.value.name).toBe('video-clone-project')
    expect(appRouter.currentRoute.value.params.projectId).toBe('project-1')
  })
  it('子页面有且只有一个主导航选中项，项目内切换生成记录保持项目选中', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: WORKSPACE_LINKS.map(item => ({ path: item.name === 'create' ? '/' : `/${item.name}`, name: item.name, component: { template: '<div />' } })) })
    await router.push('/projects?tab=runs')
    const wrapper = mount(AiWorkspaceNavigation, { global: { plugins: [router] } })
    expect(wrapper.findAll('[aria-current="page"]')).toHaveLength(1)
    expect(wrapper.get('[aria-current="page"]').text()).toBe('我的项目')
    await wrapper.get('[data-testid="nav-assets"]').trigger('click')
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(router.currentRoute.value.name).toBe('assets')
    wrapper.unmount()
  })
  it.each([
    ['projects', undefined, 'recent'], ['projects', 'runs', 'runs'],
    ['images', 'edit', 'image-studio'], ['images', undefined, 'image-gen'],
    ['assistant', 'review', 'assistant'], ['assistant', undefined, null],
    ['tools', 'speech', 'speech'], ['tools', 'unexpected', null], ['settings', undefined, 'keys'],
  ])('工作区 %s / %s 对应正确的现有功能', (name, tab, section) => {
    expect(workspaceSection(name, tab)).toBe(section)
  })
})
