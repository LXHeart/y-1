// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import AiWorkspaceNavigation from './AiWorkspaceNavigation.vue'
import appRouter from '../router'
import { CREATION_CAPABILITIES } from '../../lib/creation-workspace'
import DigitalHumanWorkbench from '../../views/digital-human/DigitalHumanWorkbench.vue'
import { useAuth } from '../../composables/useAuth'

/**
 * C105E-01 导航与路由（TC105E-01-01/03/04 的自动可证部分）：
 * 真实路由表 + 真实组件；仅 stub 最外层 HTTP（目录/会话信封），不 mock 被测事务本身。
 * 1440/390 明暗截图属本卡手工门禁（记录于 test-artifacts/task-105/E/C105E-01/）。
 */

const USER = { id: 'u-dh-1', email: 'dh@example.com', role: 'user', roles: [] }

const PERSONAL_CATALOG = {
  authenticated: true,
  version: 1,
  enabled: true,
  newSessionsAllowed: true,
  recordingEnabled: false,
  customAvatarEnabled: false,
  avatars: [],
  voices: [],
  // E-02 起工作台按「有可用后端」判定是否进入角色配置区；无 approved 后端是另一种空态。
  backends: [{
    id: 'backend-mock-1',
    model: 'fake-render',
    platformConfigId: 'cfg-1',
    platformModelVersion: null,
    transport: 'mock',
    state: 'approved',
    supportsCustomAvatar: false,
    supportsRecording: false,
    width: 1280,
    height: 720,
    fps: 25,
    measuredCapacity: 1,
    evidenceRef: null,
  }],
  limits: {
    maxSessionsPerAccount: 1,
    maxSessionsGlobal: 1,
    maxQueuedGlobal: 10,
    sessionDurationMs: 600000,
    idleTimeoutMs: 120000,
    resumeWindowMs: 30000,
    contextPairs: 10,
    maxRecordingMs: 300000,
  },
  billingNoticeVersion: 'dh-billing-v1',
}

const PUBLIC_CATALOG = { authenticated: false, enabled: true, description: '数字人创作助手' }
const SESSION_ID = '44444444-4444-4444-8444-444444444444'

function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function errorEnvelope(status: number, code: string): Response {
  return envelope({ success: false, error: '合成错误', code }, status)
}

/** Storage 逐 key 快照（happy-dom Storage 的属性在原型上，Object.entries 拿不到）。 */
function storageEntries(storage: Storage): Array<[string, string]> {
  const entries: Array<[string, string]> = []
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index)
    if (key != null) entries.push([key, storage.getItem(key) ?? ''])
  }
  return entries
}

interface FetchPlan {
  catalog?: () => Response
  session?: () => Response
}

function stubFetch(plan: FetchPlan): string[] {
  const log: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    log.push(`${init?.method ?? 'GET'} ${url}`)
    if (url === '/api/digital-human/catalog') return plan.catalog?.() ?? envelope({ success: true, data: PERSONAL_CATALOG })
    if (url === '/api/digital-human/profiles') return envelope({ success: true, data: { items: [], nextCursor: null } })
    if (url.startsWith(`/api/digital-human/sessions/${SESSION_ID}`)) {
      return plan.session?.() ?? errorEnvelope(404, 'dh_not_found')
    }
    return envelope({ success: true, data: {} })
  }))
  return log
}

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{
      path: '/',
      component: { template: '<div><router-view /></div>' },
      children: [
        { path: '', name: 'create', component: { template: '<div data-testid="create-view" />' } },
        { path: 'digital-human', name: 'digital-human', component: DigitalHumanWorkbench },
      ],
    }],
  })
}

async function mountWorkbench(path = '/digital-human'): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(DigitalHumanWorkbench, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  useAuth().currentUser.value = null
  window.history.replaceState(null, '', '/')
})

afterEach(() => {
  useAuth().currentUser.value = null
})

// VTU 不自动卸载：残留挂载的组件会在下一用例改 auth 状态时再次 bootstrap，
// 把额外请求打进新用例的 fetch stub（实测曾因此多出第二条目录 GET）。
enableAutoUnmount(afterEach)

describe('TC105E-01-01 路由与深链', () => {
  test('应用路由表含 digital-human 深链（lazy route），不扩创作能力枚举', () => {
    const resolved = appRouter.resolve('/digital-human')
    expect(resolved.name).toBe('digital-human')
    expect(resolved.matched.length).toBeGreaterThan(0)
    // K11：不把数字人塞进 CreationCapability 四能力枚举（跨路由导航，不是创作能力卡）。
    expect([...CREATION_CAPABILITIES]).not.toContain('digital-human')
  })

  test('匿名 + 开放：显示登录引导；登录走既有入口（request-login）；provider 调用 0', async () => {
    const log = stubFetch({ catalog: () => envelope({ success: true, data: PUBLIC_CATALOG }) })
    const { wrapper } = await mountWorkbench()

    expect(wrapper.text()).toContain('登录后开始')
    // 唯一网络调用 = 公开目录；无 AI provider、无会话创建。
    expect(log).toEqual(['GET /api/digital-human/catalog'])

    // 登录按钮 → emit request-login（壳层既有 LoginModal 接住），不新建登录 token 存储。
    const loginButton = wrapper.findAll('button').find((button) => button.text().includes('登录'))
    expect(loginButton).toBeTruthy()
    await loginButton!.trigger('click')
    expect(wrapper.emitted('request-login')).toBeTruthy()
    // DH 自身零 storage 写入（账号私有缓存是平台既有机制，不属本卡；只断言无 DH/敏感键）。
    const stored = [...storageEntries(window.sessionStorage), ...storageEntries(window.localStorage)]
      .filter(([key, value]) => key.startsWith('dh') || value.includes('secret'))
    expect(stored).toEqual([])
  })

  test('已登录 + 开放：进入工作台（角色配置区），不自动 create session', async () => {
    useAuth().currentUser.value = USER
    const log = stubFetch({})
    const { wrapper } = await mountWorkbench()

    expect(wrapper.text()).toContain('从配置你的数字人角色开始')
    // 仅目录 + 角色列表两个读；无 AI provider、无会话创建（无 POST /sessions、无预检）。
    expect(log).toEqual(['GET /api/digital-human/catalog', 'GET /api/digital-human/profiles'])
    expect(log.filter((entry) => entry.startsWith('POST'))).toEqual([])
  })

  test.each([
    { label: '登录目录关闭', catalog: () => envelope({ success: true, data: { ...PERSONAL_CATALOG, enabled: false } }) },
    { label: '游客目录关闭', catalog: () => envelope({ success: true, data: { ...PUBLIC_CATALOG, enabled: false } }) },
  ])('开关关闭（$label）：显示服务未开放，不给开始入口', async ({ catalog }) => {
    useAuth().currentUser.value = USER
    stubFetch({ catalog })
    const { wrapper } = await mountWorkbench()

    expect(wrapper.text()).toContain('数字人服务暂未开放')
    expect(wrapper.text()).not.toContain('从配置你的数字人角色开始')
  })

  test('newSessionsAllowed=false：页面仍可用并明确提示暂不开放新会话（区别于整体关闭）', async () => {
    useAuth().currentUser.value = USER
    stubFetch({ catalog: () => envelope({ success: true, data: { ...PERSONAL_CATALOG, newSessionsAllowed: false } }) })
    const { wrapper } = await mountWorkbench()

    expect(wrapper.text()).toContain('当前暂不开放新会话')
    expect(wrapper.text()).toContain('从配置你的数字人角色开始')
    expect(wrapper.text()).not.toContain('数字人服务暂未开放')
  })

  test('session 深链刷新：先本人 GET；不存在/无权同状态并可返回工作台（清 query）', async () => {
    useAuth().currentUser.value = USER
    const log = stubFetch({ session: () => errorEnvelope(404, 'dh_not_found') })
    const { wrapper, router } = await mountWorkbench(`/digital-human?session=${SESSION_ID}`)

    expect(log).toContain(`GET /api/digital-human/sessions/${SESSION_ID}`)
    expect(wrapper.text()).toContain('会话不存在或无权访问')

    const back = wrapper.findAll('button').find((button) => button.text().includes('返回工作台'))
    expect(back).toBeTruthy()
    await back!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({})
  })

  test('目录请求失败：加载失败态 + 重试，不把失败当空态或未开放', async () => {
    useAuth().currentUser.value = USER
    let calls = 0
    stubFetch({
      catalog: () => {
        calls += 1
        return calls === 1 ? errorEnvelope(503, 'dh_runtime_unavailable') : envelope({ success: true, data: PERSONAL_CATALOG })
      },
    })
    const { wrapper } = await mountWorkbench()

    expect(wrapper.text()).toContain('数字人服务暂时不可用')
    expect(wrapper.text()).not.toContain('数字人服务暂未开放')

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('从配置你的数字人角色开始')
    expect(calls).toBe(2)
  })
})

describe('TC105E-01-03 导航语义（键盘/前后退/aria-current）', () => {
  async function mountNav(): Promise<{ wrapper: VueWrapper; router: Router }> {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AiWorkspaceNavigation, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    return { wrapper, router }
  }

  test('两条真实 RouterLink（创作中心/数字人），nav 语义而非 tablist', async () => {
    const { wrapper } = await mountNav()

    const nav = wrapper.get('nav')
    expect(nav.attributes('aria-label')).toBe('工作区')
    // K11：跨路由链接不得伪装成 tablist/tab。
    expect(wrapper.find('[role="tablist"]').exists()).toBe(false)
    expect(wrapper.find('[role="tab"]').exists()).toBe(false)

    const links = wrapper.findAll('a')
    expect(links).toHaveLength(2)
    expect(links[0].attributes('href')).toBe('/')
    expect(links[0].text()).toBe('创作中心')
    expect(links[1].attributes('href')).toBe('/digital-human')
    expect(links[1].text()).toBe('数字人')
  })

  test('aria-current 跟随当前路由；浏览器返回/前进同步移动', async () => {
    const { wrapper, router } = await mountNav()
    const createLink = wrapper.get('[data-testid="nav-create"]')
    const dhLink = wrapper.get('[data-testid="nav-digital-human"]')

    expect(createLink.attributes('aria-current')).toBe('page')
    expect(dhLink.attributes('aria-current')).toBeUndefined()

    await router.push('/digital-human')
    await flushPromises()
    expect(dhLink.attributes('aria-current')).toBe('page')
    expect(createLink.attributes('aria-current')).toBeUndefined()

    // 浏览器返回：地址栏回 /，aria-current 回落创作中心（非激活链接无 aria-current）。
    await router.back()
    await flushPromises()
    expect(createLink.attributes('aria-current')).toBe('page')
    expect(dhLink.attributes('aria-current')).toBeUndefined()

    await router.forward()
    await flushPromises()
    expect(dhLink.attributes('aria-current')).toBe('page')
  })

  test('键盘可达：链接为原生锚点可聚焦，Tab 顺序遵循 DOM 顺序', async () => {
    const { wrapper } = await mountNav()
    const links = wrapper.findAll('a')

    links[0].element.focus()
    expect(document.activeElement).toBe(links[0].element)
    links[1].element.focus()
    expect(document.activeElement).toBe(links[1].element)
    // 无 aria-hidden/负 tabindex 把链接移出键盘序列。
    for (const link of links) {
      expect(link.attributes('aria-hidden')).toBeUndefined()
      expect(link.attributes('tabindex')).toBeUndefined()
    }
  })
})

describe('TC105E-01-04 布局基础（结构断言；1440/390 明暗截图另行走本卡手工门禁）', () => {
  test('工作台无营销 hero；页面标题锚定 main 区；标题与区域 aria 关联', async () => {
    useAuth().currentUser.value = USER
    stubFetch({})
    const { wrapper } = await mountWorkbench()

    expect(wrapper.find('[class*="hero"]').exists()).toBe(false)
    expect(wrapper.find('[class*="banner-marketing"]').exists()).toBe(false)
    const section = wrapper.get('section.dh-page')
    expect(section.attributes('aria-labelledby')).toBe('dh-title')
    const title = wrapper.get('#dh-title')
    expect(title.element.tagName).toBe('H2')
    expect(title.text()).toBe('数字人工作台')
  })
})
