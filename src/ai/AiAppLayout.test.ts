// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import AiApp from './AiApp.vue'
import router from './router'
import { useAuth } from '../composables/useAuth'
import LoginModal from '../components/LoginModal.vue'

/**
 * AI 创作中心外壳（任务书 #76 卡 B/C）：游客试用、xat 核销、门店深链锁定、热点带入、
 * 「打开草场」反向免登。创作面本体有独立测试（AiCreationCenter.test.ts），这里 mock 掉。
 * 挂 AiApp 根（透传 router-view）——直挂 AiAppLayout 会与 '/' 路由组件形成双层嵌套。
 */
/** 捕获创作面收到的 props（KeepAlive + router-view 边界下 getComponent 解析不可靠）。 */
const createProps: Array<Record<string, unknown>> = []
// 首页已迁到独立 CreationHome；带 entry 的创作深链仍由下方 AiCreationCenter 承载。
vi.mock('./workspace/CreationHome.vue', () => ({ default: {
  template: '<div data-testid="ai-workspace-home" />',
} }))
vi.mock('../views/ai-center/AiCreationCenter.vue', () => ({ __esModule: true,
  default: {
    template: '<div data-testid="ai-create" />',
    props: ['authenticated', 'entry', 'mode'],
    setup(props: Record<string, unknown>) { createProps.push(props) },
  } }))
vi.mock('../views/video/VideoAnalysisView.vue', () => ({ __esModule: true,
  default: { template: '<div data-testid="tool-video" />', props: ['creationHandoff'] } }))
// xhs-studio 路由切换（方案 §2.2 #3/#6）：两个创作大页 mock 成轻壳，捕获壳层透传的 creationHandoff。
const articleProps: Array<Record<string, unknown>> = []
const xhsProps: Array<Record<string, unknown>> = []
vi.mock('../views/article/ArticleCreationView.vue', () => ({ __esModule: true,
  default: {
    name: 'ArticleCreationView',
    template: '<div data-testid="tool-article" />',
    props: ['creationHandoff'],
    setup(props: Record<string, unknown>) { articleProps.push(props) },
  } }))
vi.mock('../views/xhs-studio/XhsStudioView.vue', () => ({ __esModule: true,
  default: {
    name: 'XhsStudioView',
    template: '<div data-testid="xhs-studio" />',
    props: ['creationHandoff'],
    setup(props: Record<string, unknown>) { xhsProps.push(props) },
  } }))

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOKEN = 'xat-token-0123456789abcdef0123456789abcdef0123456789' // secret-scan: allow（一次性跨壳 token 测试夹具）

function stubFetch(user: unknown, log: string[] = []): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    log.push(`${init?.method ?? 'GET'} ${url}`)
    if (url === '/api/auth/me') {
      return user ? response({ success: true, data: { user } }) : response({ success: false }, 401)
    }
    if (url === '/api/auth/cross-app-tokens' && init?.method === 'POST') {
      return user ? response({ success: true, data: { token: TOKEN, expiresInSeconds: 300 } }) : response({ success: false }, 401)
    }
    if (url === '/api/auth/cross-app-tokens/exchange' && init?.method === 'POST') {
      return response({ success: true, data: { user } })
    }
    return response({ success: true, data: [] })
  }))
}

async function mountLayout(path = '/', presetQuery = ''): Promise<ReturnType<typeof mount>> {
  await router.push(path)
  await router.isReady()
  // 深链/xat 是整页加载形态：路由就绪后再把 URL 预置成带参（router.push 会重写地址栏）
  if (presetQuery) window.history.replaceState(null, '', `${path}${presetQuery}`)
  const wrapper = mount(AiApp, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  useAuth().currentUser.value = null
  useAuth().loaded.value = false
  window.history.replaceState(null, '', '/')
})

afterEach(() => {
  useAuth().currentUser.value = null
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('AI 应用外壳', () => {
  test('游客可直接进入创作面（GuestTrialPanel 由创作面承载），无身份徽标', async () => {
    stubFetch(null)
    const wrapper = await mountLayout()

    expect(wrapper.find('[data-testid="ai-workspace-home"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('荐')
    expect(wrapper.text()).not.toContain('商')
    expect(wrapper.get('.auth-trigger-primary').text()).toContain('登录')
  })

  test('URL 带 xat：核销换会话后清参，不留浏览器历史', async () => {
    const user = { id: 'u-1', email: 'creator@example.com', role: 'user', roles: [] }
    const log: string[] = []
    stubFetch(user, log)
    const wrapper = await mountLayout('/', `?xat=${TOKEN}`)

    expect(log).toContain('POST /api/auth/cross-app-tokens/exchange')
    expect(useAuth().currentUser.value?.id).toBe('u-1')
    expect(wrapper.text()).toContain('creator@example.com')
    expect(window.location.search).not.toContain('xat=')
  })

  test('核销失败（过期/已核销）不白屏：落游客态', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/cross-app-tokens/exchange') return response({ success: false, error: '登录凭证无效或已过期，请重新从应用内跳转' }, 401)
      if (url === '/api/auth/me') return response({ success: false }, 401)
      return response({ success: true, data: [] })
    }))
    const wrapper = await mountLayout('/', `?xat=${TOKEN}`)

    expect(wrapper.find('[data-testid="ai-workspace-home"]').exists()).toBe(true)
    expect(wrapper.get('.auth-trigger-primary').text()).toContain('登录')
    expect(window.location.search).not.toContain('xat=')
  })

  test('门店深链（entry=store&org=&store=）：组装 store 源 entry 传给创作面并清参；游客先落登录提示', async () => {
    stubFetch(null)
    const wrapper = await mountLayout('/', '?entry=store&org=org-9&store=store-9&xat=stale')
    // xat 核销多一拍异步；LoginModal 是异步组件，需再等一轮渲染进 body
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushPromises()

    const latest = createProps[createProps.length - 1]
    expect(latest.entry).toMatchObject({
      source: { type: 'store', organizationId: 'org-9', storeId: 'store-9' },
    })
    expect(latest.mode).toBe('personal')
    expect(window.location.search).toBe('')
    // 游客 + 门店深链：登录弹窗接住（不静默放行）。异步组件经 Teleport 渲染时序不稳，
    // 以组件 props 断言（visible + message）。
    const loginModal = wrapper.findComponent(LoginModal)
    expect(loginModal.exists()).toBe(true)
    expect(loginModal.props('visible')).toBe(true)
    expect(String(loginModal.props('message'))).toContain('门店创作需要先登录')
  })

  test('热点带入深链（entry=hot&title=）：hot-topic 源 entry 预填', async () => {
    stubFetch(null)
    await mountLayout('/', '?entry=hot&title=' + encodeURIComponent('秋日第一杯奶茶'))

    const latest = createProps[createProps.length - 1]
    expect(latest.entry).toMatchObject({
      source: { type: 'hot-topic', title: '秋日第一杯奶茶' },
      prefill: { topic: '秋日第一杯奶茶' },
    })
    expect(window.location.search).toBe('')
  })

  test('已登录点「打开草场」：签发跨应用 token 后整页跳草场（跳转目标 URL 在 useCrossAppToken.test 覆盖）', async () => {
    const user = { id: 'u-2', email: 'back@example.com', role: 'user', roles: [] }
    const log: string[] = []
    stubFetch(user, log)
    const wrapper = await mountLayout()

    await wrapper.get('.grassland-link').trigger('click')
    await flushPromises()

    expect(log).toContain('POST /api/auth/cross-app-tokens')
  })

  test('任务书 #79 C79-02：积分按 accountId 每账号拉一次；null 显示「…」不显示上一账号数值', async () => {
    const userA = { id: 'u-a', email: 'a@example.com', role: 'user', roles: [] }
    const userB = { id: 'u-b', email: 'b@example.com', role: 'user', roles: [] }
    let balanceCalls = 0
    let resolveFirst!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') return response({ success: true, data: { user: userA } })
      if (url === '/api/credits/balance') {
        balanceCalls += 1
        if (balanceCalls === 1) {
          return new Promise<Response>((resolve) => { resolveFirst = resolve })
        }
        return new Response(JSON.stringify({ success: true, data: { balance: 7, totalEarned: 7, totalSpent: 0 } }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return response({ success: true, data: [] })
    }))

    const wrapper = await mountLayout()
    // A 的余额未返回：null → 「…」，不伪造 0
    expect(balanceCalls).toBe(1)
    expect(wrapper.get('.credits-badge').text()).toContain('…')

    resolveFirst(new Response(JSON.stringify({ success: true, data: { balance: 123, totalEarned: 200, totalSpent: 77 } }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await flushPromises()
    expect(wrapper.get('.credits-badge').text()).toContain('123 次')
    expect(wrapper.get('.credits-badge').text()).not.toContain('获取失败')

    // A→B（两侧都已登录，isAuthenticated 布尔不变）：accountId watch 触发重拉
    useAuth().currentUser.value = userB
    await flushPromises()
    expect(balanceCalls).toBe(2)
    expect(wrapper.get('.credits-badge').text()).toContain('7 次')
    expect(wrapper.get('.credits-badge').text()).not.toContain('123')

    // 登出（null）：不再拉私有余额
    useAuth().currentUser.value = null
    await flushPromises()
    expect(balanceCalls).toBe(2)
    expect(wrapper.find('.credits-badge').exists()).toBe(false)
  })
})

describe('小红书专属创作台路由切换（xhs-studio 方案 §2.2 #3/#6）', () => {
  /** 工作区组件（'/' 路由渲染的 AiWorkspace）上抛的 start-workflow 是壳层唯一接入口。 */
  async function emitStartWorkflow(handoff: Record<string, unknown>): Promise<void> {
    stubFetch(null)
    const wrapper = await mountLayout('/')
    wrapper.getComponent({ name: 'AiWorkspace' }).vm.$emit('start-workflow', handoff)
    await flushPromises()
  }

  test('小红书 create 会话：push xhs-studio，handoff 原样（targetView=xhs-studio）送达视图', async () => {
    await emitStartWorkflow({
      revision: 301,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      prefill: { topic: '周末露营' },
    })

    expect(router.currentRoute.value.name).toBe('xhs-studio')
    expect(xhsProps.length).toBeGreaterThan(0)
    expect(xhsProps[xhsProps.length - 1].creationHandoff).toMatchObject({
      targetView: 'xhs-studio',
      platformId: 'xiaohongshu',
      prefill: { topic: '周末露营' },
    })
  })

  test('小红书 recipe 会话（从已有内容开始）：改写 targetView=article 留旧视图，handoff 送达旧视图', async () => {
    await emitStartWorkflow({
      revision: 302,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      recipe: { id: 'social-card-series', version: '1.0.0' },
    })

    expect(router.currentRoute.value.name).toBe('article')
    expect(articleProps.length).toBeGreaterThan(0)
    expect(articleProps[articleProps.length - 1].creationHandoff).toMatchObject({
      targetView: 'article',
      platformId: 'xiaohongshu',
      recipe: { id: 'social-card-series', version: '1.0.0' },
    })
  })

  test('小红书非 create 加工会话（adapt）：同样改写留旧视图', async () => {
    await emitStartWorkflow({
      revision: 303,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      processingMode: 'adapt',
    })

    expect(router.currentRoute.value.name).toBe('article')
  })

  test('douyin 图文 handoff 不受切流影响：仍 push article', async () => {
    await emitStartWorkflow({
      revision: 304,
      platformId: 'douyin',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'article',
      source: { type: 'independent' },
    })

    expect(router.currentRoute.value.name).toBe('article')
    expect(articleProps[articleProps.length - 1].creationHandoff).toMatchObject({
      targetView: 'article',
      platformId: 'douyin',
    })
  })

  test('旧视图直链 ?platform=xiaohongshu/?platform=zhihu 被入口守卫重定向进对应创作台（query 原样透传）', async () => {
    stubFetch(null)
    await router.push({ name: 'article', query: { platform: 'xiaohongshu', draft: 'draft-9' } })
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('xhs-studio')
    expect(router.currentRoute.value.query).toMatchObject({ platform: 'xiaohongshu', draft: 'draft-9' })

    await router.push({ name: 'article', query: { platform: 'zhihu', draft: 'draft-10' } })
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('zhihu-studio')
    expect(router.currentRoute.value.query).toMatchObject({ platform: 'zhihu', draft: 'draft-10' })
  })

  test('无平台参数的 /article 直链不受守卫影响（其余平台需要）', async () => {
    stubFetch(null)
    await router.push({ name: 'article' })
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('article')
    // 平台参数为其它值同样放行旧视图。
    await router.push({ name: 'article', query: { platform: 'wechat-official' } })
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('article')
  })

  test('创作台「返回创作中心」：独立会话清壳层 entry，落回新创作首页（不再进旧创作中心）', async () => {
    stubFetch(null)
    const wrapper = await mountLayout('/')
    wrapper.getComponent({ name: 'AiWorkspace' }).vm.$emit('start-workflow', {
      revision: 305,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
    })
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('xhs-studio')

    wrapper.getComponent({ name: 'XhsStudioView' }).vm.$emit('open-view', 'ai-center')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('create')
    expect(wrapper.find('[data-testid="ai-workspace-home"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ai-create"]').exists()).toBe(false)
  })

  test('任务会话「返回创作中心」：task 源 entry 保留，仍落任务中心视图', async () => {
    stubFetch(null)
    const wrapper = await mountLayout('/')
    wrapper.getComponent({ name: 'AiWorkspace' }).vm.$emit('start-workflow', {
      revision: 306,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'article',
      source: { type: 'task', taskId: 'task-77' },
    })
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('article')

    wrapper.getComponent({ name: 'ArticleCreationView' }).vm.$emit('open-view', 'ai-center')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('create')
    expect(wrapper.find('[data-testid="ai-create"]').exists()).toBe(true)
    const latest = createProps[createProps.length - 1]
    expect(latest.entry).toMatchObject({ source: { type: 'task', taskId: 'task-77' } })
  })
})
