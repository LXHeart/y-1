// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import App from './App.vue'
import AiCreationCenter from './views/ai-center/AiCreationCenter.vue'
import ArticleCreationView from './views/article/ArticleCreationView.vue'
import router from './router'
import { useAuth } from './composables/useAuth'
import type { CreationHandoff } from './types/ai-creation'

// ── 视图 mock（路由从 views/ 目录导入） ──
vi.mock('./views/ai-center/AiCreationCenter.vue', () => ({ __esModule: true, default: { template: '<div />' } }))
vi.mock('./views/article/ArticleCreationView.vue', () => ({ __esModule: true,
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
vi.mock('./views/comedy/ComedyWritingView.vue', () => ({ __esModule: true, default: { template: '<div />' } }))
vi.mock('./views/grassland/GrasslandWorkbench.vue', () => ({ __esModule: true,
  default: { template: '<div data-testid="grassland-workbench" />' },
}))
vi.mock('./views/home/GrasslandHomeView.vue', () => ({ __esModule: true,
  default: { template: '<div data-testid="grassland-home" />' },
}))
vi.mock('./views/image/ImageAnalysisView.vue', () => ({ __esModule: true,
  default: {
    props: ['creationHandoff'],
    template: `
      <section>
        <input id="ia-platform" :value="creationHandoff?.platformId || ''" />
        <textarea id="ia-feelings">{{ [creationHandoff?.prefill?.topic, creationHandoff?.prefill?.instructions].filter(Boolean).join(' | ') }}</textarea>
      </section>
    `,
  },
}))
vi.mock('./views/video/VideoAnalysisView.vue', () => ({ __esModule: true,
  default: {
    props: ['creationHandoff'],
    template: `
      <section>
        <input id="va-platform" :value="creationHandoff?.platformId || ''" />
        <textarea id="va-source-url">{{ creationHandoff?.source?.type === 'reference' ? creationHandoff.source.sourceUrl || '' : '' }}</textarea>
      </section>
    `,
  },
}))
vi.mock('./views/video-production/VideoProductionView.vue', () => ({ __esModule: true,
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
vi.mock('./views/commerce/ConsumerCommerceView.vue', () => ({ __esModule: true, default: { template: '<div />' } }))

// ── 共享组件 mock ──
vi.mock('./components/AnalysisSettingsModal.vue', () => ({ __esModule: true, default: { template: '<div />' } }))
vi.mock('./components/NotificationBell.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./components/LoginModal.vue', () => ({ __esModule: true,
  default: {
    props: ['visible'],
    emits: ['register'],
    template: `<div v-if="visible" role="dialog">
      <button data-testid="complete-registration" @click="$emit('register', {
        email: 'new@example.com', displayName: '新用户', password: 'password123',
        confirmPassword: 'password123', verificationCode: '123456', initialIdentity: 'recommender'
      })">完成注册</button>
    </div>`,
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

/** 挂载 App 前须先完成 router 初始导航，否则 <router-view> 不渲染。 */
async function mountApp() {
  await router.push('/ai-center')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('App AI 创作中心集成', () => {
  // 治理端（运营处置/管理后台）已拆到独立入口 ops.html（src/ops），用户端 SPA 不再包含
  // 治理路由——即使 platform_admin 登录，用户端导航也不出现治理入口。角色分流的
  // 正向断言在 src/ops/OpsApp.test.ts。
  test('用户端不含治理入口，platform_admin 登录后导航也不出现运营处置/管理', async () => {
    const admin = {
      id: 'admin-1', email: 'admin@example.com', displayName: '管理员',
      role: 'admin', roles: ['platform_admin'],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') return response({ success: true, data: { user: admin } })
      if (url === '/api/video-production/capabilities') {
        return response({ success: true, data: { videoGeneration: { available: false } } })
      }
      if (url === '/api/douyin/session') return response({ success: true, data: { status: 'anonymous' } })
      return response({ success: true, data: [] })
    }))
    const auth = useAuth()
    await auth.loadCurrentUser(true)
    const wrapper = await mountApp()

    const navigation = wrapper.get('nav[aria-label="功能选择"]')
    expect(navigation.text()).not.toContain('运营处置')
    expect(navigation.text()).not.toContain('管理后台')
    expect(navigation.text()).not.toContain('管理')

    auth.currentUser.value = { id: 'user-1', email: 'user@example.com', role: 'user', roles: [] }
    await flushPromises()
    expect(navigation.text()).not.toContain('运营处置')
    auth.currentUser.value = null
  })

  test('品牌是草场平台；主导航不再露出独立工具入口', async () => {
    installFetchStub()
    const wrapper = await mountApp()

    // PRD §一：平台名是草场，AI 内容创作中心是内置共享能力而非门面
    expect(wrapper.get('.brand-title').text()).toBe('草场')
    const navigation = wrapper.get('nav[aria-label="功能选择"]').text()
    expect(navigation).toContain('主页')
    expect(navigation).toContain('AI 内容创作中心')
    expect(navigation).toContain('到店消费')
    // 未登录不露出需要身份/工作台的入口
    expect(navigation).not.toContain('工作台')
    expect(navigation).not.toContain('举报投诉')
    // 旧「更多工具」下拉整体移除（工具视图收编为 AI 中心工作流目的地）
    expect(wrapper.find('.legacy-tools-menu').exists()).toBe(false)
    expect(wrapper.find('button[aria-controls="legacy-tools-panel"]').exists()).toBe(false)
  })

  test('登录后主导航随活动身份展示工作台与举报投诉', async () => {
    const user = { id: 'u-1', email: 'u@example.com', role: 'user', roles: [] }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') return response({ success: true, data: { user } })
      if (url === '/api/me/identities') {
        return response({ success: true, data: [
          { id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' },
          { id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' },
        ] })
      }
      if (url === '/api/douyin/session') return response({ success: true, data: { status: 'anonymous' } })
      return response({ success: true, data: [] })
    }))
    const auth = useAuth()
    await auth.loadCurrentUser(true)
    const wrapper = await mountApp()
    await flushPromises()

    // 双身份账号默认商家（merchant 优先），标签随活动身份
    expect(wrapper.get('[data-testid="nav-workbench"]').text()).toContain('商家工作台')
    expect(wrapper.get('nav[aria-label="功能选择"]').text()).toContain('举报投诉')
    useAuth().currentUser.value = null
  })

  test('注册成功后携带初始身份并进入首次资料完善工作台', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/auth/register') {
        expect(JSON.parse(String(init?.body))).toMatchObject({ initialIdentity: 'recommender' })
        return response({
          success: true,
          data: { user: { id: 'new-user', email: 'new@example.com', displayName: '新用户', role: 'user' } },
        }, 201)
      }
      if (url === '/api/auth/me') return response({ success: false, error: '未登录' }, 401)
      if (url === '/api/video-production/capabilities') {
        return response({ success: true, data: { videoGeneration: { available: false } } })
      }
      if (url === '/api/douyin/session') return response({ success: true, data: { status: 'anonymous' } })
      if (url === '/api/credits/balance') return response({ success: true, data: { balance: 3 } })
      return response({ success: true, data: [] })
    })
    vi.stubGlobal('fetch', fetchMock)
    useAuth().currentUser.value = null

    const wrapper = await mountApp()
    await wrapper.get('.auth-trigger-primary').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="complete-registration"]').trigger('click')
    await flushPromises()
    await router.push('/grassland')
    await flushPromises()

    expect(wrapper.find('[data-testid="grassland-workbench"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('请先完善推荐官主页资料')
    useAuth().currentUser.value = null
  })

  // KeepAlive 缓存按 creationContextEpoch 键控（DefaultLayout），账号变化时整体重建——
  // 换账号回落草场主页（平台门面），且回到工具视图时实例是新建的（上一账号缓存已清）。
  test('登录账号变化时清除上一账号的创作页面状态', async () => {
    installFetchStub()
    const wrapper = await mountApp()

    // 先离开创作中心（实例进 KeepAlive 缓存）
    await router.push('/article')
    await flushPromises()
    expect(wrapper.findComponent(AiCreationCenter).exists()).toBe(false)
    const previousArticle = wrapper.getComponent(ArticleCreationView).vm

    useAuth().currentUser.value = { id: 'account-b', email: 'b@example.com', role: 'user', roles: [] }
    await flushPromises()

    // 非商城视图换账号回落草场主页
    expect(router.currentRoute.value.path).toBe('/')
    expect(wrapper.find('[data-testid="grassland-home"]').exists()).toBe(true)

    // 回到工具视图：KeepAlive 缓存已按账号重建，不再是上一账号的实例
    await router.push('/article')
    await flushPromises()
    expect(wrapper.getComponent(ArticleCreationView).vm).not.toBe(previousArticle)
    useAuth().currentUser.value = null
  })

  // 整页加载（刷新/收藏深链）时 loadCurrentUser 走 null→id，与会话内登录无法凭前后值区分：
  // 首次引导完成前的账号解析不得触发「回落 /ai-center」，否则深链永远不可达（视觉审查 ⑱）。
  test('整页加载已登录会话时，深链不被拉回 /ai-center', async () => {
    const user = { id: 'deep-1', email: 'deep@example.com', role: 'user', roles: [] }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') return response({ success: true, data: { user } })
      if (url === '/api/douyin/session') return response({ success: true, data: { status: 'anonymous' } })
      return response({ success: true, data: [] })
    }))
    useAuth().currentUser.value = null
    // 前序用例会把 store 的 loaded 置真，布局的非强制 loadCurrentUser 会被短路——
    // 复位后才是在测「整页加载首载」的真实路径
    useAuth().loaded.value = false

    await router.push('/article')
    await router.isReady()
    mount(App, { global: { plugins: [router] } })
    await flushPromises()

    expect(useAuth().currentUser.value?.id).toBe('deep-1')
    expect(router.currentRoute.value.path).toBe('/article')
    useAuth().currentUser.value = null
  })

  test('文章 handoff 切换到既有工作流并预填主题与平台', async () => {
    installFetchStub()
    const wrapper = await mountApp()
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
    await router.isReady()

    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('秋季新品发布')
    expect(wrapper.get('.platform-btn-active').text()).toContain('微信公众号')
  })

  test('视频脚本 handoff 带入门店资料和可编辑创作要求', async () => {
    installFetchStub()
    const wrapper = await mountApp()
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
    await router.isReady()

    expect((wrapper.get('#vp-shop-name').element as HTMLInputElement).value).toBe('云朵面馆')
    expect((wrapper.get('#vp-platform').element as HTMLInputElement).value).toBe('xiaohongshu')
    expect((wrapper.get('#vp-address').element as HTMLInputElement).value).toBe('人民路 8 号')
    expect((wrapper.get('#vp-desc').element as HTMLTextAreaElement).value).toBe('手工面与现熬汤底')
    expect((wrapper.get('#vp-prompt').element as HTMLTextAreaElement).value).toContain('突出手工现做')
  })

  test('热点选题 handoff 把热点主题带入视频制作视图', async () => {
    installFetchStub()
    const wrapper = await mountApp()
    const handoff: CreationHandoff = {
      revision: 103,
      platformId: 'douyin',
      contentFormId: 'video',
      source: { type: 'hot-topic', title: '城市夜经济升温' },
      workflowId: 'video-script',
      targetView: 'video-production',
      prefill: { topic: '城市夜经济升温' },
    }

    wrapper.getComponent(AiCreationCenter).vm.$emit('start-workflow', handoff)
    await flushPromises()
    await router.isReady()

    expect(wrapper.findComponent(AiCreationCenter).exists()).toBe(false)
    expect((wrapper.get('#vp-platform').element as HTMLInputElement).value).toBe('douyin')
  })

  test('reference handoff 落到视频参考提取视图并带入平台与参考链接', async () => {
    installFetchStub()
    const wrapper = await mountApp()
    const handoff: CreationHandoff = {
      revision: 104,
      platformId: 'bilibili',
      contentFormId: 'video',
      source: { type: 'reference', sourceUrl: 'https://www.bilibili.com/video/BV1example' },
      workflowId: 'reference-analyze',
      targetView: 'video',
    }

    wrapper.getComponent(AiCreationCenter).vm.$emit('start-workflow', handoff)
    await flushPromises()
    await router.isReady()

    expect(wrapper.findComponent(AiCreationCenter).exists()).toBe(false)
    expect((wrapper.get('#va-platform').element as HTMLInputElement).value).toBe('bilibili')
    expect((wrapper.get('#va-source-url').element as HTMLTextAreaElement).value).toBe('https://www.bilibili.com/video/BV1example')
  })

  test('点评文案 handoff 落到图片评价文案视图并带入主题', async () => {
    installFetchStub()
    const wrapper = await mountApp()
    const handoff: CreationHandoff = {
      revision: 105,
      platformId: 'dianping',
      contentFormId: 'graphic',
      source: { type: 'independent' },
      workflowId: 'review-copy',
      targetView: 'image',
      prefill: { topic: '招牌牛肉面' },
    }

    wrapper.getComponent(AiCreationCenter).vm.$emit('start-workflow', handoff)
    await flushPromises()
    await router.isReady()

    expect(wrapper.findComponent(AiCreationCenter).exists()).toBe(false)
    expect((wrapper.get('#ia-platform').element as HTMLInputElement).value).toBe('dianping')
    expect((wrapper.get('#ia-feelings').element as HTMLTextAreaElement).value).toContain('招牌牛肉面')
  })
})
