// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import App from './App.vue'
import AiCreationCenter from './components/AiCreationCenter.vue'
import { useAuth } from './composables/useAuth'
import type { CreationHandoff } from './types/ai-creation'

vi.mock('./components/AnalysisSettingsModal.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/ComedyWritingView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/AdminView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/OpsConsole.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/GrasslandWorkbench.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/HomeView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/ImageAnalysisView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/ImageGenerationView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/NotificationBell.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/VideoAnalysisView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/LoginModal.vue', () => ({
  default: { props: ['visible'], template: '<div v-if="visible" role="dialog" />' },
}))
vi.mock('./components/ArticleCreationView.vue', () => ({
  default: {
    props: ['creationHandoff'],
    template: `
      <section>
        <textarea class="topic-input" :value="creationHandoff?.prefill?.topic || ''" />
        <button class="platform-btn-active">微信公众号</button>
      </section>
    `,
  },
}))
vi.mock('./components/VideoProductionView.vue', () => ({
  default: {
    props: ['creationHandoff'],
    template: `
      <section>
        <input id="vp-shop-name" :value="creationHandoff?.prefill?.storeName || ''" />
        <input id="vp-platform" :value="creationHandoff?.platformId || ''" />
        <input id="vp-address" :value="creationHandoff?.prefill?.address || ''" />
        <textarea id="vp-desc" :value="creationHandoff?.prefill?.storeDescription || ''" />
        <textarea id="vp-prompt" :value="creationHandoff?.prefill?.instructions || ''" />
      </section>
    `,
  },
}))

enableAutoUnmount(afterEach)

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function installFetchStub(): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/auth/me') return response({ success: false, error: '未登录' }, 401)
    if (url === '/api/video-production/capabilities') {
      return response({ success: true, data: { videoGeneration: { available: false, reason: '暂未开放' } } })
    }
    if (url === '/api/douyin/session') return response({ success: true, data: { status: 'anonymous' } })
    return response({ success: true, data: [] })
  }))
}

afterEach(() => vi.unstubAllGlobals())

describe('App AI 创作中心集成', () => {
  test('默认展示平台优先入口，旧独立工具只在二级菜单中出现', async () => {
    installFetchStub()
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(9)
    expect(wrapper.get('.brand-title').text()).toBe('AI 内容创作中心')
    expect(wrapper.get('nav[aria-label="功能选择"]').attributes('role')).toBeUndefined()
    expect(wrapper.find('.legacy-tools-menu').exists()).toBe(false)

    await wrapper.get('button[aria-expanded="false"]').trigger('click')
    expect(wrapper.get('.legacy-tools-menu').text()).toContain('爆款文章')
  })

  test('登录账号变化时重建 KeepAlive 缓存，清除上一账号的创作页面状态', async () => {
    installFetchStub()
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    const previousCenter = wrapper.getComponent(AiCreationCenter).vm

    useAuth().currentUser.value = { id: 'account-b', email: 'b@example.com', role: 'user' }
    await flushPromises()

    expect(wrapper.getComponent(AiCreationCenter).vm).not.toBe(previousCenter)
    expect(wrapper.find('[data-platform-id="xiaohongshu"].selected').exists()).toBe(false)
    useAuth().currentUser.value = null
  })

  test('文章 handoff 切换到既有工作流并预填主题与平台', async () => {
    installFetchStub()
    const wrapper = mount(App)
    await flushPromises()
    const handoff: CreationHandoff = {
      revision: 101,
      platformId: 'wechat-official',
      contentFormId: 'graphic',
      source: { type: 'independent' },
      workflowId: 'longform',
      targetView: 'article',
      prefill: { topic: '秋季新品发布' },
    }

    wrapper.getComponent(AiCreationCenter).vm.$emit('start-workflow', handoff)
    await flushPromises()

    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('秋季新品发布')
    expect(wrapper.get('.platform-btn-active').text()).toContain('微信公众号')
  })

  test('视频脚本 handoff 带入门店资料和可编辑创作要求', async () => {
    installFetchStub()
    const wrapper = mount(App)
    await flushPromises()
    const handoff: CreationHandoff = {
      revision: 102,
      platformId: 'xiaohongshu',
      contentFormId: 'video',
      source: { type: 'store', organizationId: 'org-1', storeId: 'store-1' },
      workflowId: 'video-script',
      targetView: 'video-production',
      prefill: {
        topic: '晚市新品',
        instructions: '突出手工现做',
        storeName: '云朵面馆',
        address: '人民路 8 号',
        storeDescription: '手工面与现熬汤底',
      },
    }

    wrapper.getComponent(AiCreationCenter).vm.$emit('start-workflow', handoff)
    await flushPromises()

    expect((wrapper.get('#vp-shop-name').element as HTMLInputElement).value).toBe('云朵面馆')
    expect((wrapper.get('#vp-platform').element as HTMLInputElement).value).toBe('xiaohongshu')
    expect((wrapper.get('#vp-address').element as HTMLInputElement).value).toBe('人民路 8 号')
    expect((wrapper.get('#vp-desc').element as HTMLTextAreaElement).value).toBe('手工面与现熬汤底')
    expect((wrapper.get('#vp-prompt').element as HTMLTextAreaElement).value).toContain('突出手工现做')
  })
})
