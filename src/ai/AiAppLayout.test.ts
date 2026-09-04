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
vi.mock('../views/ai-center/AiCreationCenter.vue', () => ({ __esModule: true,
  default: {
    template: '<div data-testid="ai-create" />',
    props: ['authenticated', 'entry', 'mode'],
    setup(props: Record<string, unknown>) { createProps.push(props) },
  } }))
vi.mock('../views/video/VideoAnalysisView.vue', () => ({ __esModule: true,
  default: { template: '<div data-testid="tool-video" />', props: ['creationHandoff'] } }))

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOKEN = 'xat-token-0123456789abcdef0123456789abcdef0123456789'

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

    expect(wrapper.find('[data-testid="ai-create"]').exists()).toBe(true)
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

    expect(wrapper.find('[data-testid="ai-create"]').exists()).toBe(true)
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
})
