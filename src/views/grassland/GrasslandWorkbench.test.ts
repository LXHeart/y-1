// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import GrasslandWorkbench from '../../views/grassland/GrasslandWorkbench.vue'
import MerchantTaskForm from '../../views/grassland/components/MerchantTaskForm.vue'
import { useAuth } from '../../composables/useAuth'
import { useActiveIdentity } from '../../composables/useActiveIdentity'
import type { AuthUser } from '../../types/auth'
import type { Task } from '../../types/grassland'

// 任务书 #67 卡 I（2026-09-03 补齐）：工作台的非首屏页签重卡片改为 defineAsyncComponent 分包后，
// 渲染期并发触发的动态 import 在 happy-dom 测试环境下只有第一个能 settle（其余挂起——
// 仅测试环境限制，浏览器为并行网络请求）。这里在测试模块加载时预热整组分包组件的模块缓存，
// 渲染期的动态 import 全部命中缓存、微任务内完成，断言前一次 flushPromises 即可拿到内容。
await Promise.all([
  import('../../components/MerchantKybCard.vue'),
  import('../../components/StoreStaffCard.vue'),
  import('../../components/MerchantCommerceCard.vue'),
  import('../../components/MerchantPermissionCard.vue'),
  import('../../components/MerchantMonthlyBillCard.vue'),
  import('../../components/EmailBindingCard.vue'),
  import('../../components/MySessionsCard.vue'),
  import('../../components/PersonalDataComplianceCard.vue'),
  import('../../components/MyWalletCard.vue'),
  import('../../components/AiOrgBudgetPanel.vue'),
  import('../../components/OrgTeamCard.vue'),
  import('../../components/AiOrgProviderKeysPanel.vue'),
  import('../../components/OrganizationBrandCard.vue'),
  import('../../components/PermissionReviewPanel.vue'),
  import('./components/RecommenderTaskHall.vue'),
  import('./components/BrandPublicProfilePanel.vue'),
  import('./components/StorePublicProfilePanel.vue'),
  import('./components/StoreMediaGallery.vue'),
  import('./components/MerchantTaskForm.vue'),
  import('../../components/BusinessAnalyticsPanel.vue'),
  import('../../components/OrgCreationAuditPanel.vue'),
  import('../../components/RecommenderHistoryCard.vue'),
  import('../../components/RecommenderIncomeStatsCard.vue'),
  import('../../components/RecommenderShareCard.vue'),
  import('./components/PersonalSettingsModal.vue'),
  import('../../components/ComplaintModal.vue'),
  import('./components/ComplaintsPanel.vue'),
])

/**
 * 工作台**登录态**回归测试。
 *
 * 与 `MyInvitationsCard.test.ts` 同源缺陷：工作台此前只在 `onMounted` 初始化
 * （激活活动身份 + 拉组织/余额/任务），而它在未登录时就已挂载、切标签页又不重挂载——
 * 同一页面内登录或换账号后，界面上还是上一个账号的数据（或空白），必须刷新整页。
 * 活动身份按 session 存，换账号不重新激活还会让商家操作 403。
 */

const { currentUser } = useAuth()

function asUser(id: string, email: string): AuthUser {
  return { id, email, displayName: email, role: 'user' }
}

/**
 * 工作台挂载助手：工作台用 useRoute/useRouter 做 URL 状态同步，挂载必须带 router 插件。
 * 每次挂载新建 memory router（初始路由 '/'、无 query）——URL 恢复路径整体空转，
 * 与旧裸挂载行为等价；需要验证 query 恢复时在测试内自行 push 到 /grassland 再挂载。
 */
function mountWorkbench(options?: Parameters<typeof mount>[1]) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/grassland', name: 'grassland', component: GrasslandWorkbench },
    ],
  })
  return mount(GrasslandWorkbench, {
    ...options,
    global: {
      ...options?.global,
      plugins: [...(options?.global?.plugins ?? []), router],
      // 任务表单抽屉 Teleport 到 body：不 stub 的话内容落在 wrapper 之外，find 全查不到。
      stubs: { Teleport: true, ...options?.global?.stubs },
    },
  })
}

/**
 * 打开任务表单抽屉（新建模式）。表单已不常驻页签顶部——新建路径必须先点垄眉的
 * 「发布新任务」；「编辑」按钮自带开抽屉，走编辑路径的用例不需要这一步。
 */
async function openTaskDrawer(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.findAll('button').find((b) => b.text() === '发布新任务')!.trigger('click')
  await flushPromises()
}

const ORG = {
  id: 'org-1',
  ownerAccountId: 'acct-1',
  name: '示例商家',
  status: 'active',
  permissionTier: 'finance_transaction',
  industry: 'other',
  createdAt: null,
}

function dataFor(url: string): unknown {
  if (url === '/api/me/identities') {
    return [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
  }
  if (url === '/api/organizations') return [ORG]
  if (url === '/api/me/organization-scopes') return [{
    organizationId: 'org-1', organizationName: '示例商家', organizationStatus: 'active',
    permissionTier: 'finance_transaction', role: 'owner',
  }]
  if (url === '/api/ai/organizations/org-1/budget') return {
    configured: false, version: 0,
    maxTokensPerRun: null, maxTokensDaily: null, maxTokensMonthly: null,
    maxCentsPerRun: null, maxCentsDaily: null, maxCentsMonthly: null,
    usage: { measured: false, dailyTokens: null, dailyCents: null, monthlyTokens: null, monthlyCents: null },
    overCurrentUsage: false, updatedAt: null,
  }
  if (url.includes('/stores')) return []
  if (url === '/api/me/store-scopes') return []
  if (url === '/api/me/sessions') return []
  if (url === '/api/me/active-identity') return { activeIdentityType: null }
  if (url === '/api/me/invitations') return []
  if (url.startsWith('/api/tasks/feed')) return { items: [], nextCursor: null, hasMore: false }
  if (url.startsWith('/api/tasks')) return []
  if (url.startsWith('/api/creation-generations')) return { items: [], nextBefore: null }
  if (url.startsWith('/api/finance/accounts')) return { organizationId: 'org-1', balanceCents: 100000 }
  return {}
}

function stubFetch(identities = dataFor('/api/me/identities')): { urls: string[]; calls: Array<[string, RequestInit | undefined]> } {
  const urls: string[] = []
  const calls: Array<[string, RequestInit | undefined]> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    urls.push(url)
    calls.push([url, init])
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: url === '/api/me/identities' ? identities : dataFor(url) }),
    }
  }))
  return { urls, calls }
}

// 必须自动卸载：组件监听 useAuth 的模块级 currentUser，残留实例会响应后续用例的登录事件。
enableAutoUnmount(afterEach)

beforeEach(() => {
  currentUser.value = null
  // 活动身份是全局单例（跨视图共享）：每个用例复位回「新会话默认商家」，
  // 等价于原实现「每次 mount 一个新 side ref 默认 merchant」的测试语义。
  const identity = useActiveIdentity()
  identity.reset()
  identity.activeSide.value = 'merchant'
})

afterEach(() => {
  vi.unstubAllGlobals()
  currentUser.value = null
})

describe('GrasslandWorkbench 登录态', () => {
  test('未登录时不发任何请求', async () => {
    const { urls } = stubFetch()

    mountWorkbench()
    await flushPromises()

    expect(urls).toEqual([])
  })

  test('同一页面内登录后自动激活身份并拉组织（原缺陷：需刷新整页）', async () => {
    const { urls, calls } = stubFetch()
    const wrapper = mountWorkbench()
    await flushPromises()

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 顺序关键：先查已开通身份，再激活它；避免 recommender-only 账号被默认 merchant 激活成 409。
    expect(urls[0]).toBe('/api/me/identities')
    expect(calls.some(([url, init]) => url === '/api/me/active-identity' && init?.method === 'POST')).toBe(true)
    expect(urls).toContain('/api/organizations')
    expect(wrapper.text()).toContain('示例商家')
  })

  test('AI 预算入口仅对组织 owner/admin 可见', async () => {
    const owner = stubFetch()
    const ownerWrapper = mountWorkbench()
    currentUser.value = asUser('acct-owner', 'owner@test.local')
    await flushPromises()
    expect(ownerWrapper.text()).toContain('AI 预算')
    expect(owner.urls).toContain('/api/ai/organizations/org-1/budget')
    ownerWrapper.unmount()

    currentUser.value = null
    const memberUrls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      memberUrls.push(url)
      const data = url === '/api/me/organization-scopes'
        ? [{ organizationId: 'org-1', organizationName: '示例商家', organizationStatus: 'active',
            permissionTier: 'finance_transaction', role: 'member' }]
        : dataFor(url)
      return { ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }) }
    }))
    const memberWrapper = mountWorkbench()
    currentUser.value = asUser('acct-member', 'member@test.local')
    await flushPromises()
    expect(memberWrapper.text()).not.toContain('AI 预算')
    expect(memberUrls).not.toContain('/api/ai/organizations/org-1/budget')
  })

  /**
   * 商家任务列表必须全态取（GL-P1-TASK-001 Stage 3 浏览器实测发现 + GL-P2-ADMIN-003 全审加 pending_review）。
   *
   * 原实现只取 `status=published`：刚存下的草稿在列表里不出现，「编辑 / 提交审核」无从触达；
   * 关闭报名后整条任务从列表消失，商家再也无法处理已提交的报名。
   * 全审政策下若漏 pending_review，刚提交审核的任务会从列表消失（同类 bug 的第三次）。
   */
  test('商家任务列表按 draft/pending_review/published/closed/cancelled 五态拉取', async () => {
    const { urls } = stubFetch()
    mountWorkbench()

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    const statuses = urls
      .filter((url) => url.startsWith('/api/tasks?organizationId='))
      .map((url) => new URL(url, 'http://localhost').searchParams.get('status'))
    expect(statuses).toEqual(['draft', 'pending_review', 'published', 'closed', 'cancelled'])
  })

  /**
   * 任务书 #53 S2.5：被平台驳回退回的草稿在任务列表标「已驳回·待修改」并展示驳回原因；
   * 已上架任务即使残留历史驳回字段也不显示（isRejectedDraft 只认 draft + 驳回字段非空）。
   */
  test('被驳回的草稿显示已驳回标记与驳回原因，已上架任务不显示历史驳回', async () => {
    const baseTask = {
      ownerAccountId: 'acct-1', organizationId: 'org-1', description: null,
      contentForm: null, platform: 'douyin', maxSlots: 1, bountyCents: 10000,
      freebieDepositCents: 0, minRecommenderLevel: 1,
      requirements: { mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [] },
      version: 2, applicationDeadline: null, cancelledAt: null,
      createdAt: '2026-08-27T00:00:00Z', autoAcceptMinLevel: null,
    }
    const rejectedDraft: Task = {
      ...baseTask, id: 'task-rejected', title: '被驳回的任务', status: 'draft',
      publishedAt: null,
      lastReviewAction: 'rejected', lastReviewNote: '画面含平台禁投品类',
      lastReviewedAt: '2026-08-28T10:00:00Z',
      lastRejectedNote: '画面含平台禁投品类', lastRejectedAt: '2026-08-28T10:00:00Z',
    } as Task
    const publishedTask: Task = {
      ...baseTask, id: 'task-published', title: '已上架的任务', status: 'published',
      publishedAt: '2026-08-28T00:00:00Z',
      lastReviewAction: 'approved', lastReviewNote: null, lastReviewedAt: null,
      lastRejectedNote: null, lastRejectedAt: null,
    } as Task
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data = dataFor(url)
      if (url.startsWith('/api/tasks?')) {
        data = url.includes('status=draft') ? [rejectedDraft]
          : url.includes('status=published') ? [publishedTask] : []
      }
      return { ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }) }
    }))
    const wrapper = mountWorkbench()

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    expect(wrapper.text()).toContain('已驳回·待修改')
    expect(wrapper.text().match(/驳回原因：/g)).toHaveLength(1)
    expect(wrapper.text()).toContain('驳回原因：画面含平台禁投品类')
    // 已上架任务保持原状态文案，不出现驳回标记
    expect(wrapper.text()).toContain('已发布')
    expect(wrapper.text().match(/已驳回·待修改/g)).toHaveLength(1)
  })

  test('recommender-only 账号直接激活推荐官，不尝试 merchant', async () => {
    const identities = [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }]
    const { calls } = stubFetch(identities)
    const wrapper = mountWorkbench({
      global: {
        stubs: {
          MyRecommenderProfileCard: true,
          MyWalletCard: true,
          EngagementSubmissionPanel: true,
          EngagementRatingPanel: true,
          AdjudicationPanel: true,
        },
      },
    })

    currentUser.value = asUser('acct-rec', 'recommender@test.local')
    await flushPromises()

    expect(wrapper.get('.gl-title').text()).toBe('推荐官工作台')
    // GET /me/active-identity（读会话活动身份）不算激活；激活是 POST
    const activation = calls.find(([url, init]) => url === '/api/me/active-identity' && init?.method === 'POST')
    expect(activation).toBeDefined()
    expect(JSON.parse(activation?.[1]?.body as string)).toEqual({ type: 'recommender' })
    expect(calls.filter(([url, init]) => url === '/api/me/active-identity' && init?.method === 'POST')).toHaveLength(1)
    expect(calls.some(([url, init]) => url === '/api/me/identities' && init?.method === 'POST')).toBe(false)
  })

  test('未开通身份时保留商家 onboarding，且不激活或自动开通', async () => {
    const { calls } = stubFetch([])
    const wrapper = mountWorkbench()

    currentUser.value = asUser('acct-consumer', 'consumer@test.local')
    await flushPromises()

    expect(wrapper.get('.gl-title').text()).toBe('商家工作台')
    expect(calls.filter(([url]) => url === '/api/me/active-identity')).toHaveLength(0)
    expect(calls.some(([url, init]) => url === '/api/me/identities' && init?.method === 'POST')).toBe(false)
  })

  test('商家工作台子页签：默认任务与报名，四个业务页签；个人设置弹窗含账号与合规+举报与投诉（#74）', async () => {
    stubFetch()
    const wrapper = mountWorkbench()
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    const tabs = wrapper.findAll('#gl-panel-merchant .gl-subtab')
    expect(tabs.map((t) => t.text())).toEqual(['任务与报名', '商家主体与门店', '资金与经营', 'AI 与治理'])
    expect(tabs[0].attributes('aria-selected')).toBe('true')

    // 账号级内容收进个人设置弹窗（#73）：未开弹窗时不在 DOM；打开后商家侧只有账号与合规一节
    expect(wrapper.find('.gl-zone[aria-label="账号与合规"]').exists()).toBe(false)
    await wrapper.get('button[aria-label="打开个人设置"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.gl-zone[aria-label="账号与合规"]').exists()).toBe(true)
    expect(wrapper.find('.gl-zone[aria-label="主页与分享"]').exists()).toBe(false)
  })

  test('推荐官工作台三个子页签；默认任务大厅；个人设置弹窗三节齐全', async () => {
    const identities = [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }]
    stubFetch(identities)
    // 重卡 stub：MyWalletCard/画像卡等真渲染依赖完整数据 shape，测试环境会抛渲染错（原深链用例同款处理）
    const wrapper = mountWorkbench({
      global: {
        stubs: {
          MyWalletCard: true, MyRecommenderProfileCard: true, RecommenderShareCard: true,
          EmailBindingCard: true, MySessionsCard: true, PersonalDataComplianceCard: true,
        },
      },
    })
    currentUser.value = asUser('acct-rec', 'recommender@test.local')
    await flushPromises()

    const tabs = wrapper.findAll('#gl-panel-recommender .gl-subtab')
    expect(tabs.map((t) => t.text())).toEqual(['任务大厅', '我的履约', '收益与结算'])
    // #73：推荐官默认页签从「主页与分享」改为「任务大厅」（原 home 页签已收进个人设置弹窗）
    expect(tabs[0].attributes('aria-selected')).toBe('true')

    // 个人设置弹窗：推荐官侧三节齐全（#74 起加「举报与投诉」节）
    await wrapper.get('button[aria-label="打开个人设置"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.gl-zone[aria-label="主页与分享"]').exists()).toBe(true)
    expect(wrapper.find('.gl-zone[aria-label="账号与合规"]').exists()).toBe(true)
    // 关闭途径收窄（persistent）：遮罩点击不关，× 关
    await wrapper.get('[data-testid="gl-modal-overlay"]').trigger('mousedown')
    expect(wrapper.find('.gl-zone[aria-label="主页与分享"]').exists()).toBe(true)
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(wrapper.find('.gl-zone[aria-label="主页与分享"]').exists()).toBe(false)
  })

  test('?wtab= 深链与锚点联动（推荐官侧）；旧 home/account 深链容错回落', async () => {
    const identities = [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }]
    stubFetch(identities)
    const anchorRef = ref('')
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/grassland', name: 'grassland', component: GrasslandWorkbench },
      ],
    })
    router.push('/grassland?side=recommender&wtab=hall')
    await router.isReady()
    const wrapper = mount(GrasslandWorkbench, {
      global: {
        plugins: [router],
        stubs: {
          Teleport: true, MyWalletCard: true,
          EngagementSubmissionPanel: true, EngagementRatingPanel: true, AdjudicationPanel: true,
        },
        provide: { grasslandAnchor: anchorRef },
      },
    })
    currentUser.value = asUser('acct-rec', 'recommender@test.local')
    await flushPromises()

    const tabs = wrapper.findAll('#gl-panel-recommender .gl-subtab')
    // 深链恢复：任务大厅页签激活（现为首个页签）
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    // 任务大厅锚点 id 就位（此前 scrollBlockIntoView('gl-task-hall') 一直空滚——顺手修复）
    expect(wrapper.find('#gl-task-hall').exists()).toBe(true)

    // 锚点驱动：gl-engagements（推荐官侧）属于「我的履约」（现为第二个页签）
    anchorRef.value = 'gl-engagements'
    await flushPromises()
    expect(wrapper.findAll('#gl-panel-recommender .gl-subtab')[1].attributes('aria-selected')).toBe('true')

    // #73 旧深链容错：home/account 已不是页签 id，运行中校验不命中 → 忽略该值、保持当前页签不炸
    await router.push('/grassland?side=recommender&wtab=home')
    await flushPromises()
    expect(wrapper.findAll('#gl-panel-recommender .gl-subtab')[1].attributes('aria-selected')).toBe('true')
  })

  test('任务大厅分页：下一页消费游标、上一页回退到首页起始游标（游标链，2026-09-04）', async () => {
    const feedUrls: string[] = []
    const page1 = [{ id: 't-1a', title: '一页甲', status: 'published', bountyCents: 1000 }]
    const page2 = [{ id: 't-2a', title: '二页甲', status: 'published', bountyCents: 2000 }]
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.startsWith('/api/tasks/feed')) {
        feedUrls.push(url)
        const data = url.includes('cursor=')
          ? { items: page2, nextCursor: '', hasMore: false }
          : { items: page1, nextCursor: 'c1', hasMore: true }
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
      }
      if (url === '/api/me/identities') {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data: [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }] }) }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data: dataFor(url) }) }
    }))
    const wrapper = mountWorkbench({
      global: { stubs: { MyWalletCard: true, MyRecommenderProfileCard: true } },
    })
    currentUser.value = asUser('acct-rec', 'recommender@test.local')
    await flushPromises()

    const pager = () => wrapper.get('nav[aria-label="任务大厅分页"]')
    const pagerButtons = () => pager().findAll('button')
    // 首屏：第 1 页，上一页禁用、下一页可用（hasMore）
    expect(pager().text()).toContain('第 1 页')
    expect(pagerButtons()[0].attributes('disabled')).toBeDefined()

    // 下一页：请求带 cursor=c1，页码进到 2
    await pagerButtons()[1].trigger('click')
    await flushPromises()
    expect(pager().text()).toContain('第 2 页')
    expect(feedUrls.some((u) => u.includes('cursor=c1'))).toBe(true)

    // 上一页：回退用首页起始游标（'' → URL 不带 cursor），页码回 1
    await pagerButtons()[0].trigger('click')
    await flushPromises()
    expect(pager().text()).toContain('第 1 页')
    const feedOnly = feedUrls.filter((u) => u.startsWith('/api/tasks/feed'))
    const lastFeedUrl = feedOnly[feedOnly.length - 1]
    expect(lastFeedUrl.includes('cursor=')).toBe(false)
  })

  test('?wtab= 深链恢复商家子页签；锚点滚动先切所属页签', async () => {
    stubFetch()
    const anchorRef = ref('')
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/grassland', name: 'grassland', component: GrasslandWorkbench },
      ],
    })
    router.push('/grassland?wtab=finance')
    await router.isReady()
    const wrapper = mount(GrasslandWorkbench, {
      global: {
        plugins: [router],
        provide: { grasslandAnchor: anchorRef },
      },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 深链恢复：finance 页签激活
    let tabs = wrapper.findAll('#gl-panel-merchant .gl-subtab')
    expect(tabs[2].attributes('aria-selected')).toBe('true')

    // 锚点驱动：gl-engagements 属于「任务与报名」，滚动前自动切回
    anchorRef.value = 'gl-engagements'
    await flushPromises()
    tabs = wrapper.findAll('#gl-panel-merchant .gl-subtab')
    expect(tabs[0].attributes('aria-selected')).toBe('true')
  })

  test('纯门店 MANAGER 不激活 merchant，只显示获授权门店业务', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities' || url === '/api/organizations') data = []
      else if (url === '/api/me/store-scopes') data = [{
        storeId: 'store-managed', storeName: '经理负责门店', storeStatus: 'active',
        organizationId: 'org-managed', organizationName: '授权组织', organizationStatus: 'active',
        permissionTier: 'basic_publish', role: 'manager',
      }]
      else if (url.startsWith('/api/tasks?') || url.includes('/stores')) data = []
      return { ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench()
    currentUser.value = asUser('acct-manager', 'manager@test.local')
    await flushPromises()

    expect(wrapper.text()).toContain('授权组织')
    expect(wrapper.text()).toContain('经理负责门店')
    expect(wrapper.text()).toContain('仅门店经理权限')
    expect(wrapper.text()).not.toContain('资金账户')
    expect(wrapper.text()).not.toContain('权限升级')
    expect(calls.some(([url]) => url === '/api/me/active-identity')).toBe(false)
    // 组织级门店列表（要求 org MEMBER）不可达；门店资料读取（STAFF 放行）是独立门店 KYB 的合法调用。
    expect(calls.some(([url]) => /\/stores(\?|$)/.test(url))).toBe(false)
    expect(calls.some(([url]) => url.includes('/stores/store-managed/profile'))).toBe(true)
    const taskUrls = calls.filter(([url]) => url.startsWith('/api/tasks?')).map(([url]) => url)
    expect(taskUrls).toHaveLength(5)
    expect(taskUrls.every((url) => url.includes('storeId=store-managed'))).toBe(true)
  })

  test('登出清空组织/余额/任务，不留上一个账号的数据', async () => {
    stubFetch()
    const wrapper = mountWorkbench()

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    expect(wrapper.text()).toContain('示例商家')

    currentUser.value = null
    await flushPromises()

    expect(wrapper.text()).not.toContain('示例商家')
    expect(wrapper.text()).toContain('¥—')  // 余额回到未知态
  })

  test('换账号重新激活身份并重拉（不沿用上一个账号的组织）', async () => {
    const { urls, calls } = stubFetch()
    mountWorkbench()

    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    const firstRound = urls.length
    // SPA 内换账号的归属：布局先 reset 全局身份再重装载（工作台不再自清身份）——
    // 单测只挂工作台，这里代行布局职责
    useActiveIdentity().reset()
    currentUser.value = asUser('acct-2', 'b@test.local')
    await flushPromises()

    expect(calls.slice(firstRound).some(([url, init]) => url === '/api/me/active-identity' && init?.method === 'POST')).toBe(true)
    expect(urls.slice(firstRound)).toContain('/api/organizations')
  })
})

describe('GrasslandWorkbench 商家 contest', () => {
  test('已接受履约显示理由输入与转客服按钮，并逐字发送理由', async () => {
    const application = {
      id: 'app-accepted', taskId: 'task-1', recommenderAccountId: 'acct-rec',
      status: 'accepted', note: null, reviewedByAccountId: null, decidedAt: null, createdAt: null,
    }
    const task = {
      id: 'task-1', ownerAccountId: 'acct-1', organizationId: 'org-1', title: '待核验任务',
      description: '突出门店招牌', status: 'published', contentForm: '图文', platform: '小红书', maxSlots: 1,
      bountyCents: 100, createdAt: null, version: 1, applicationDeadline: null,
      publishedAt: null, cancelledAt: null,
    }
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.includes('status=published')) {
        data = [task]
      } else if (url.startsWith('/api/tasks?') || url.startsWith('/api/tasks/feed')) {
        data = url.startsWith('/api/tasks/feed') ? { items: [], nextCursor: null, hasMore: false } : []
      } else if (url === '/api/tasks/task-1/applications') {
        data = [application]
      } else if (url === '/api/tasks/task-1/applications/app-accepted/task-context') {
        data = {
          taskId: 'task-1', taskVersion: 1, title: '待核验任务', description: '突出门店招牌',
          contentForm: '图文', platform: '小红书', storeId: null, applicationId: 'app-accepted',
          recommenderAccountId: 'acct-rec', bountyCents: 100, acceptedAt: null, requirements: {},
        }
      } else if (url.endsWith('/contest')) {
        data = { applicationId: 'app-accepted', status: 'contested', reason: '  画面与要求不符  ', disputeId: 'dispute-1' }
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      } else if (url.startsWith('/api/reputation/')) {
        data = { accountId: 'acct-rec', level: 'Lv1', levelTitle: '新锐', acceptedCount: 1, completedCount: 0, completionRate: 0, ratingCount: 0, averageScore: null, averageResponseSeconds: null }
      } else if (url.includes('/profile')) {
        data = { accountId: 'acct-rec', displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [], updatedAt: null }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    await wrapper.find('button.gl-link').trigger('click')
    await flushPromises()

    const createButton = wrapper.findAll('button').find((item) => item.text() === '围绕任务创作')!
    await createButton.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('open-creation')?.[0]?.[0]).toMatchObject({
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      source: { type: 'task', taskId: 'task-1', applicationId: 'app-accepted', taskVersion: 1 },
      prefill: { topic: '待核验任务', instructions: '突出门店招牌' },
    })

    const reason = wrapper.get('[aria-label="第 1 行拒绝理由"]')
    await reason.setValue('  画面与要求不符  ')
    const button = wrapper.findAll('button').find((item) => item.text() === '拒绝并转客服')!
    await button.trigger('click')
    await flushPromises()

    const request = calls.find(([url]) => url.endsWith('/contest'))!
    expect(JSON.parse(request[1]?.body as string)).toEqual({ reason: '画面与要求不符' })
    expect(wrapper.text()).toContain('商家异议已提交，结算已暂停并转客服裁定')
  })
})

describe('GrasslandWorkbench 接受报名预留失败（compensated）', () => {
  // UI 实测清单遗留项：浏览器实测轮账户余额始终充足，compensated 分支从未在 UI 呈现过。
  test('资金型 accept 202 后轮询到 compensated/insufficient_funds，行内显示「未接受：账户余额不足」', async () => {
    const application = {
      id: 'app-pending', taskId: 'task-1', recommenderAccountId: 'acct-rec',
      status: 'pending', note: null, reviewedByAccountId: null, decidedAt: null, createdAt: null,
    }
    const task = {
      id: 'task-1', ownerAccountId: 'acct-1', organizationId: 'org-1', title: '赏金任务',
      description: '突出门店招牌', status: 'published', contentForm: '图文', platform: '小红书', maxSlots: 1,
      bountyCents: 100, createdAt: null, version: 1, applicationDeadline: null,
      publishedAt: null, cancelledAt: null,
    }
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.includes('status=published')) {
        data = [task]
      } else if (url.startsWith('/api/tasks?') || url.startsWith('/api/tasks/feed')) {
        data = url.startsWith('/api/tasks/feed') ? { items: [], nextCursor: null, hasMore: false } : []
      } else if (url === '/api/tasks/task-1/applications') {
        data = [application]
      } else if (url === '/api/tasks/task-1/applications/app-pending/reservation') {
        // accept 返回 202 后首次轮询即到终态：预留失败、报名被补偿回 pending
        data = { applicationId: 'app-pending', status: 'compensated', reason: 'insufficient_funds' }
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 0 }
      } else if (url.startsWith('/api/reputation/')) {
        data = { accountId: 'acct-rec', level: 'Lv1', levelTitle: '新锐', acceptedCount: 0, completedCount: 0, completionRate: 0, ratingCount: 0, averageScore: null, averageResponseSeconds: null }
      } else if (url.includes('/profile')) {
        data = { accountId: 'acct-rec', displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [], updatedAt: null }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    await wrapper.find('button.gl-link').trigger('click')
    await flushPromises()

    const acceptButton = wrapper.findAll('button').find((item) => item.text() === '接受')!
    await acceptButton.trigger('click')
    await flushPromises()

    expect(calls.some(([url, init]) => url === '/api/tasks/task-1/applications/app-pending/accept' && init?.method === 'POST')).toBe(true)
    // 关键呈现：商家看到「未接受 + 原因」，而不是永久停在「处理中…」或误显「已接受」
    expect(wrapper.get('td.gl-outcome').text()).toBe('未接受：账户余额不足')
    expect(wrapper.text()).not.toContain('已接受（资金已预留）')
    expect(wrapper.text()).not.toContain('处理中…')
  })
})

describe('GrasslandWorkbench 确认履约结算暂扣（held）', () => {
  // UI 实测清单遗留项：held 分支（确认履约时存在未终局争议）此前未在 UI 呈现过。
  test('confirm 202 后轮询到 held/open_dispute，行内显示「结算暂停：存在未终局争议」', async () => {
    const application = {
      id: 'app-accepted', taskId: 'task-1', recommenderAccountId: 'acct-rec',
      status: 'accepted', note: null, reviewedByAccountId: null, decidedAt: null, createdAt: null,
    }
    const task = {
      id: 'task-1', ownerAccountId: 'acct-1', organizationId: 'org-1', title: '待确认任务',
      description: '突出门店招牌', status: 'published', contentForm: '图文', platform: '小红书', maxSlots: 1,
      bountyCents: 100, createdAt: null, version: 1, applicationDeadline: null,
      publishedAt: null, cancelledAt: null,
    }
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.includes('status=published')) {
        data = [task]
      } else if (url.startsWith('/api/tasks?') || url.startsWith('/api/tasks/feed')) {
        data = url.startsWith('/api/tasks/feed') ? { items: [], nextCursor: null, hasMore: false } : []
      } else if (url === '/api/tasks/task-1/applications') {
        data = [application]
      } else if (url === '/api/tasks/task-1/applications/app-accepted/settlement') {
        // confirm 返回 202 后首次轮询即到终态：结算被未终局争议暂扣
        data = { applicationId: 'app-accepted', status: 'held', reason: 'open_dispute' }
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      } else if (url.startsWith('/api/reputation/')) {
        data = { accountId: 'acct-rec', level: 'Lv1', levelTitle: '新锐', acceptedCount: 1, completedCount: 0, completionRate: 0, ratingCount: 0, averageScore: null, averageResponseSeconds: null }
      } else if (url.includes('/profile')) {
        data = { accountId: 'acct-rec', displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [], updatedAt: null }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    await wrapper.find('button.gl-link').trigger('click')
    await flushPromises()

    const confirmButton = wrapper.findAll('button').find((item) => item.text() === '确认履约')!
    await confirmButton.trigger('click')
    await flushPromises()

    expect(calls.some(([url, init]) => url === '/api/tasks/task-1/applications/app-accepted/confirm' && init?.method === 'POST')).toBe(true)
    expect(wrapper.get('td.gl-outcome').text()).toBe('结算暂停：存在未终局争议')
    expect(wrapper.text()).not.toContain('已结算（资金已确认扣款）')
    expect(wrapper.text()).not.toContain('结算中…')
  })
})

describe('GrasslandWorkbench deferred 争议', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function recommenderFetch(statusRequests: Array<Record<string, unknown>>) {
    const application = {
      id: 'app-accepted', taskId: 'task-1', recommenderAccountId: 'acct-rec',
      status: 'accepted', note: null, reviewedByAccountId: null, decidedAt: null, createdAt: null,
    }
    let statusIndex = 0
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(url)
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }]
      } else if (url === '/api/organizations') {
        data = []
      } else if (url.startsWith('/api/tasks/feed')) {
        data = { items: [{
          id: 'task-1', ownerAccountId: 'merchant-1', organizationId: 'org-1', title: '测试任务',
          description: null, status: 'published', contentForm: null, platform: null, maxSlots: 1,
          bountyCents: 100, createdAt: null, version: 1, applicationDeadline: null,
          publishedAt: null, cancelledAt: null,
        }], nextCursor: null, hasMore: false }
      } else if (url === '/api/tasks/task-1/applications') {
        data = [application]
      } else if (url === '/api/trust/disputes' && init?.method === 'POST') {
        data = { status: 'pending', requestId: 'request-1', engagementRef: 'app-accepted', reason: '履约存在争议', disputeId: '', workflowId: '' }
      } else if (url === '/api/trust/dispute-requests/request-1') {
        data = statusRequests[Math.min(statusIndex++, statusRequests.length - 1)]
      } else if (url.startsWith('/api/reputation/')) {
        data = { accountId: 'acct-rec', level: 'Lv1', levelTitle: '新锐', acceptedCount: 1, completedCount: 0, completionRate: 0, ratingCount: 0, averageScore: null, averageResponseSeconds: null }
      } else if (url.includes('/profile')) {
        data = { accountId: 'acct-rec', displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [], updatedAt: null }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    return calls
  }

  async function reachDeferred(wrapper: ReturnType<typeof mount>): Promise<void> {
    currentUser.value = asUser('acct-rec', 'recommender@test.local')
    await flushPromises()
    await wrapper.find('button.gl-link').trigger('click')
    await flushPromises()
    const open = wrapper.findAll('button').find((button) => button.text() === '开启争议')!
    await open.trigger('click')
    await flushPromises()
  }

  test('pending 时显示明确提示且不把 requestId 挂给 AdjudicationPanel', async () => {
    recommenderFetch([{ status: 'pending', requestId: 'request-1', engagementRef: 'app-accepted', reason: '理由', disputeId: '', workflowId: '' }])
    const wrapper = mountWorkbench({ global: { stubs: { AdjudicationPanel: true, MyWalletCard: true } } })

    await reachDeferred(wrapper)

    expect(wrapper.get('[data-testid="deferred-dispute-status"]').text()).toContain('客服案终局后自动开普通争议')
    expect(wrapper.find('adjudication-panel-stub').exists()).toBe(false)
    expect((wrapper.find('#gl-disputes input').element as HTMLInputElement).value).toBe('')
  })

  test('低频轮询 promotion 后只挂载 successor disputeId', async () => {
    const calls = recommenderFetch([
      { status: 'pending', requestId: 'request-1', engagementRef: 'app-accepted', reason: '理由', disputeId: '', workflowId: '' },
      { status: 'promoted', requestId: 'request-1', engagementRef: 'app-accepted', reason: '理由', disputeId: 'dispute-2', workflowId: 'adjudicate-dispute-2' },
    ])
    const wrapper = mountWorkbench({ global: { stubs: { AdjudicationPanel: true, MyWalletCard: true } } })
    await reachDeferred(wrapper)

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.find('adjudication-panel-stub').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()

    expect(wrapper.getComponent({ name: 'AdjudicationPanel' }).props('disputeId')).toBe('dispute-2')
    expect(calls.filter((url) => url === '/api/trust/dispute-requests/request-1')).toHaveLength(2)
    expect(wrapper.text()).toContain('普通争议已自动开启并进入七官审判流程')
  })

  test('组件卸载后清理 pending 轮询 timer', async () => {
    const calls = recommenderFetch([{ status: 'pending', requestId: 'request-1', engagementRef: 'app-accepted', reason: '理由', disputeId: '', workflowId: '' }])
    const wrapper = mountWorkbench({ global: { stubs: { AdjudicationPanel: true, MyWalletCard: true } } })
    await reachDeferred(wrapper)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(6000)

    expect(calls.filter((url) => url === '/api/trust/dispute-requests/request-1')).toHaveLength(0)
  })
})

/**
 * 任务书 #25 Stage E：商家履约确认——阶梯任务申报实际指标 + 预计结算预览。
 *
 * 锁的是「未填/非法禁用确认、合法值实时预览、确认带 JSON body、成功清理输入」，
 * 以及固定佣金任务零变化（无输入、无请求体）。
 */
describe('GrasslandWorkbench 阶梯佣金履约确认（任务书 #25）', () => {
  const ladderTask: Task = {
    id: 'task-ladder', ownerAccountId: 'acct-1', organizationId: 'org-1',
    title: '阶梯确认任务', description: null, status: 'published',
    contentForm: null, platform: 'douyin', maxSlots: 1, bountyCents: 10000,
    freebieDepositCents: 0, minRecommenderLevel: 1,
    requirements: {
      mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [],
      commissionLadder: {
        policyVersion: 'ladder-v1', metricKey: 'douyin.play_count',
        tiers: [{ threshold: 10000, payoutCents: 5000 }, { threshold: 50000, payoutCents: 10000 }],
      },
    },
    version: 1, applicationDeadline: null, publishedAt: '2026-08-01T00:00:00Z',
    cancelledAt: null, createdAt: '2026-08-01T00:00:00Z', autoAcceptMinLevel: null,
  }
  const application = {
    id: 'a-1', taskId: 'task-ladder', recommenderAccountId: 'acct-rec',
    status: 'accepted', note: null, reviewedByAccountId: null, decidedAt: null, createdAt: null,
  }

  function confirmationsFetch(task: Task): Array<[string, RequestInit | undefined]> {
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.startsWith('/api/tasks?') && url.includes('status=published')) {
        data = [task]
      } else if (url.startsWith('/api/tasks?') || url.startsWith('/api/tasks/feed')) {
        data = url.startsWith('/api/tasks/feed') ? { items: [], nextCursor: null, hasMore: false } : []
      } else if (url === '/api/tasks/task-ladder/applications') {
        data = [application]
      } else if (url.endsWith('/confirm')) {
        data = { applicationId: 'a-1', status: 'confirmed' }
      } else if (url.endsWith('/settlement')) {
        data = { status: 'settled' }
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      } else if (url.startsWith('/api/reputation/')) {
        data = { accountId: 'acct-rec', level: 'Lv1', levelTitle: '新锐', acceptedCount: 1, completedCount: 0, completionRate: 0, ratingCount: 0, averageScore: null, averageResponseSeconds: null }
      } else if (url.includes('/profile')) {
        data = { accountId: 'acct-rec', displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [], updatedAt: null }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    return calls
  }

  async function selectFirstTask(wrapper: ReturnType<typeof mount>): Promise<void> {
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    await wrapper.find('button.gl-link').trigger('click')
    await flushPromises()
  }

  function confirmButton(wrapper: ReturnType<typeof mount>) {
    return wrapper.findAll('button').find((item) => item.text() === '确认履约')!
  }

  test('阶梯任务渲染指标输入与预计结算，确认发送申报值并在成功后清空', async () => {
    const calls = confirmationsFetch(ladderTask)
    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    await selectFirstTask(wrapper)

    // 未填：有输入、预览 ¥0.00、明确提示、确认禁用
    const metric = wrapper.get('[aria-label="第 1 行实际指标（douyin.play_count）"]')
    expect(wrapper.text()).toContain('预计结算 ¥0.00')
    expect(wrapper.text()).toContain('请输入实际指标')
    expect((confirmButton(wrapper).element as HTMLButtonElement).disabled).toBe(true)

    // 填 50000：达 50000 档（¥100.00），确认可点并发送 JSON body
    await metric.setValue('50000')
    expect(wrapper.text()).toContain('预计结算 ¥100.00')
    expect((confirmButton(wrapper).element as HTMLButtonElement).disabled).toBe(false)
    await confirmButton(wrapper).trigger('click')
    await flushPromises()

    const confirmCall = calls.find(([url]) => url.endsWith('/a-1/confirm'))!
    expect(confirmCall[1]?.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(String(confirmCall[1]?.body))).toEqual({ confirmedMetricValue: 50000 })
    // 成功后清理该 application 的临时输入 → 回到未填态、确认再次禁用
    expect((metric.element as HTMLInputElement).value).toBe('')
    expect((confirmButton(wrapper).element as HTMLButtonElement).disabled).toBe(true)
  })

  test('清空或非法输入时禁用确认且不发请求', async () => {
    const calls = confirmationsFetch(ladderTask)
    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    await selectFirstTask(wrapper)

    const metric = wrapper.get('[aria-label="第 1 行实际指标（douyin.play_count）"]')
    await metric.setValue('-5')
    expect(wrapper.text()).toContain('实际指标必须是非负安全整数')
    expect((confirmButton(wrapper).element as HTMLButtonElement).disabled).toBe(true)

    await metric.setValue('')  // 清空回到未填态
    expect(wrapper.text()).toContain('请输入实际指标')
    expect((confirmButton(wrapper).element as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(([url]) => url.endsWith('/a-1/confirm'))).toBe(false)
  })

  test('固定佣金任务无指标输入，确认仍是无请求体', async () => {
    // 复用 ladderTask 的 id（fetch stub 按固定 id 路由报名列表），但 requirements 无 commissionLadder
    const fixedTask = {
      ...ladderTask,
      title: '固定确认任务',
      requirements: { ...ladderTask.requirements, commissionLadder: undefined },
    }
    const calls = confirmationsFetch(fixedTask)
    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    await selectFirstTask(wrapper)

    expect(wrapper.find('[aria-label^="实际指标"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('预计结算')
    expect((confirmButton(wrapper).element as HTMLButtonElement).disabled).toBe(false)

    await confirmButton(wrapper).trigger('click')
    await flushPromises()

    const confirmCall = calls.find(([url]) => url.endsWith('/a-1/confirm'))!
    expect(confirmCall).toBeDefined()
    expect(confirmCall[1]?.body).toBeUndefined()
  })
})

/**
 * 通知落点（Slice 12 Stage 4）。本应用无 vue-router，故通知的 `linkPath` 落成
 * 「切到草场视图 + 滚到卡片锚点」。这里锁：锚点 id 真实存在（改卡片时别把 id 删掉）、
 * 消费后置空（同一锚点能被再次点击触发）、以及**不替用户切换商家/推荐官视角**。
 */
describe('GrasslandWorkbench 通知锚点', () => {
  // 任务书 #49：gl-invitations 已随邀请流下线（存量通知链接在 NOTIFICATION_LINK_TARGETS 兜底到 gl-organizations）
  const ANCHORS = ['gl-organizations', 'gl-engagements', 'gl-disputes', 'gl-wallet']

  test('商家视角下四个锚点都在 DOM 里', async () => {
    stubFetch()
    const wrapper = mountWorkbench({ attachTo: document.body })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    for (const anchor of ANCHORS) {
      expect(wrapper.find(`#${anchor}`).exists()).toBe(true)
    }
    // 同名 id 不能同时出现（两侧是 v-if/v-else）
    expect(document.querySelectorAll('#gl-wallet')).toHaveLength(1)
    expect(document.querySelectorAll('#gl-engagements')).toHaveLength(1)
  })

  test('provide 的锚点触发滚动，消费后置空且不切换视角', async () => {
    stubFetch()
    const anchor = ref('')
    const scrolled: string[] = []
    // happy-dom 没实现 scrollIntoView
    Element.prototype.scrollIntoView = function scrollIntoViewStub(this: Element) {
      scrolled.push(this.id)
    } as unknown as typeof Element.prototype.scrollIntoView

    const wrapper = mountWorkbench({
      attachTo: document.body,
      global: { provide: { grasslandAnchor: anchor } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    anchor.value = 'gl-disputes'
    await flushPromises()

    expect(scrolled).toEqual(['gl-disputes'])
    expect(anchor.value).toBe('')  // 置空后同一锚点可再次触发
    expect(wrapper.get('.gl-title').text()).toBe('商家工作台')  // 未替用户切身份
  })

  /**
   * 回归：真浏览器 e2e 抓到的首次挂载竞态。
   *
   * 点通知时 `App.vue` **同一 tick 内**既切 `currentView='grassland'` 又置锚点 → 工作台此刻才挂载，
   * 锚点 ref 在 `watch` 注册之前就已是目标值。非 `immediate` 的 watch 只响应注册**之后**的变化，
   * 于是首次从别的视图点通知进来不滚动（`scrollY` 不动）；已在草场视图时再点才滚。
   * 修法：watch 带 `immediate`，挂载时若锚点非空就补滚一次。
   */
  test('挂载前就已设好的锚点（首次从其它视图点通知进来）也要滚动', async () => {
    stubFetch()
    // 关键：挂载前锚点已是目标值（模拟 currentView 切换与置锚点同一 tick）
    const anchor = ref('gl-wallet')
    const scrolled: string[] = []
    Element.prototype.scrollIntoView = function scrollIntoViewStub(this: Element) {
      scrolled.push(this.id)
    } as unknown as typeof Element.prototype.scrollIntoView

    mountWorkbench({
      attachTo: document.body,
      global: { provide: { grasslandAnchor: anchor } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    expect(scrolled).toEqual(['gl-wallet'])
    expect(anchor.value).toBe('')
  })
})

describe('GrasslandWorkbench 已发布任务编辑出新版本', () => {
  /**
   * 锁的是「修订送全字段，含赏金/平台」：accept/结算已读 app 的 bounty 快照（snapshot-pinning），
   * 故修订 task 赏金只影响新报名。请求体应带 platform/bountyCents（载入的原值，未改也回传）。
   */
  test('修订已发布任务送全字段（含赏金/平台，载入原值）', async () => {
    const published = {
      id: 'task-pub', ownerAccountId: 'acct-1', organizationId: 'org-1',
      title: '原标题', description: '原描述', status: 'published',
      contentForm: null, platform: 'douyin', maxSlots: 3, bountyCents: 500,
      minRecommenderLevel: 4,
      requirements: {
        productServiceInfo: '原套餐', mustInclude: ['门店名'], forbiddenContent: ['绝对化功效'],
        publishStartAt: '2026-08-20T10:00:00Z', publishEndAt: '2026-08-25T10:00:00Z',
        metricRequirements: ['播放量截图'], evidenceRequirements: ['发布链接'],
      },
      version: 1, applicationDeadline: null, publishedAt: '2026-08-01T00:00:00Z',
      cancelledAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.startsWith('/api/tasks/feed')) {
        data = { items: [], nextCursor: null, hasMore: false }
      } else if (url.startsWith('/api/tasks?') && url.includes('status=published')) {
        data = [published]
      } else if (url.startsWith('/api/tasks/task-pub/revise')) {
        data = { ...published, title: '修订标题', version: 2 }
      } else if (url.startsWith('/api/tasks')) {
        data = []
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench()
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 点已发布任务的「编辑」进入修订模式（全字段可改，输入不再禁用）
    const editBtn = wrapper.findAll('button').find((b) => b.text() === '编辑')!
    await editBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('正在修订已发布任务')
    expect(wrapper.find('select[name="task-platform"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('input[placeholder="任务标题"]').setValue('修订标题')
    await wrapper.get('[aria-label="产品服务信息"]').setValue(' 双人招牌套餐 ')
    await wrapper.get('[aria-label="必须包含"]').setValue('门店名\n招牌菜\n门店名')
    await wrapper.get('[aria-label="禁止内容"]').setValue('绝对化功效')
    await wrapper.get('[aria-label="指标要求"]').setValue('发布后 24 小时播放量')
    await wrapper.get('[aria-label="凭证要求"]').setValue('发布链接\n后台截图')
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === '保存修订')!
    await saveBtn.trigger('click')
    await flushPromises()

    const revise = calls.find(([u]) => u.endsWith('/api/tasks/task-pub/revise'))!
    const body = JSON.parse(revise[1]!.body as string)
    expect(body.title).toBe('修订标题')
    expect(body.expectedVersion).toBe(1)
    // 全字段：载入的平台/赏金原值随修订回传（未改也送）
    expect(body.platform).toBe('douyin')
    expect(body.bountyCents).toBe(500)
    expect(body.minRecommenderLevel).toBe(4)
    expect(body.requirements).toMatchObject({
      productServiceInfo: '双人招牌套餐',
      mustInclude: ['门店名', '招牌菜'],
      forbiddenContent: ['绝对化功效'],
      metricRequirements: ['发布后 24 小时播放量'],
      evidenceRequirements: ['发布链接', '后台截图'],
    })
    expect(body.requirements.publishStartAt).toBe('2026-08-20T10:00:00.000Z')
    expect(body.requirements.publishEndAt).toBe('2026-08-25T10:00:00.000Z')
  })
})

/**
 * 任务书 #25 Stage C：工作台持久化与回填。
 *
 * 锁的是「validate-then-build」闭环——提交审核 / 存草稿 / 保存修订共用同一校验入口，
 * payload 由 buildCommissionLadderPayload 构造（启用才带 commissionLadder，档位按阈值升序），
 * 编辑草稿 / 修订已发布任务从任务快照回填表单且不篡改后端返回的 policyVersion。
 */
describe('GrasslandWorkbench 阶梯佣金（任务书 #25）', () => {
  /** 登录商家身份并等初始化（调用方须先 stub fetch）。 */
  async function loginMerchant(_wrapper: ReturnType<typeof mount>): Promise<void> {
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
  }

  /** 赏金输入在「赏金 ¥」label 内（无独立 aria-label，用 label 文本定位）。 */
  function bountyInput(wrapper: ReturnType<typeof mount>) {
    const form = wrapper.getComponent(MerchantTaskForm)
    return form.findAll('label').find((l) => l.text().startsWith('赏金'))!.find('input')
  }

  /**
   * 表单内找按钮——MerchantKybCard 也有「提交审核」（KYB 资料送审），
   * 全局按文本找会点错卡片，必须限定在任务表单内。
   */
  function formButton(wrapper: ReturnType<typeof mount>, text: string) {
    return wrapper.getComponent(MerchantTaskForm).findAll('button').find((b) => b.text() === text)!
  }

  test('发布任务 payload 含按阈值升序的 commissionLadder', async () => {
    const { calls } = stubFetch()
    const wrapper = mountWorkbench()
    await loginMerchant(wrapper)
    await openTaskDrawer(wrapper)

    await wrapper.find('input[placeholder="任务标题"]').setValue('阶梯佣金任务')
    await bountyInput(wrapper).setValue('100')
    await wrapper.get('[aria-label="启用阶梯佣金"]').setValue(true)
    await wrapper.get('[aria-label="阶梯佣金指标标识"]').setValue('douyin.play_count')
    // 故意逆序填两档：payload 必须按阈值升序重排（高阈值高佣金，校验应通过）
    await wrapper.get('[aria-label="第 1 档阈值"]').setValue('20000')
    await wrapper.get('[aria-label="第 1 档佣金"]').setValue('60')
    await wrapper.get('[aria-label="添加档位"]').trigger('click')
    await wrapper.get('[aria-label="第 2 档阈值"]').setValue('10000')
    await wrapper.get('[aria-label="第 2 档佣金"]').setValue('50')

    const publish = formButton(wrapper, '提交审核')
    await publish.trigger('click')
    await flushPromises()

    const createCall = calls.find(([url, init]) => url === '/api/tasks' && init?.method === 'POST')
    expect(createCall).toBeDefined()
    expect(JSON.parse(String(createCall?.[1]?.body))).toEqual(expect.objectContaining({
      bountyCents: 10_000,
      requirements: expect.objectContaining({
        commissionLadder: {
          policyVersion: 'ladder-v1',
          metricKey: 'douyin.play_count',
          tiers: [
            { threshold: 10_000, payoutCents: 5_000 },
            { threshold: 20_000, payoutCents: 6_000 },
          ],
        },
      }),
    }))
  })

  test('最高档佣金超过赏金时不发 POST 并提示「最高档佣金不能超过任务赏金」', async () => {
    const { calls } = stubFetch()
    const wrapper = mountWorkbench()
    await loginMerchant(wrapper)
    await openTaskDrawer(wrapper)

    await wrapper.find('input[placeholder="任务标题"]').setValue('超额阶梯任务')
    await bountyInput(wrapper).setValue('50')  // 赏金 5000 分
    await wrapper.get('[aria-label="启用阶梯佣金"]').setValue(true)
    await wrapper.get('[aria-label="阶梯佣金指标标识"]').setValue('douyin.play_count')
    await wrapper.get('[aria-label="第 1 档阈值"]').setValue('10000')
    await wrapper.get('[aria-label="第 1 档佣金"]').setValue('60')  // ¥60 = 6000 分 > 赏金

    const publish = formButton(wrapper, '提交审核')
    await publish.trigger('click')
    await flushPromises()

    expect(calls.some(([url, init]) => url === '/api/tasks' && init?.method === 'POST')).toBe(false)
    expect(wrapper.text()).toContain('最高档佣金不能超过任务赏金')
  })

  test('修订 legacy-v3 阶梯任务原样保留 policyVersion，且版本不进 UI', async () => {
    const published = {
      id: 'task-ladder', ownerAccountId: 'acct-1', organizationId: 'org-1',
      title: '旧版阶梯任务', description: null, status: 'published',
      contentForm: null, platform: 'douyin', maxSlots: 3, bountyCents: 1000,
      minRecommenderLevel: 1,
      requirements: {
        mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [],
        commissionLadder: {
          policyVersion: 'legacy-v3', metricKey: 'douyin.play_count',
          tiers: [{ threshold: 100, payoutCents: 500 }],
        },
      },
      version: 4, applicationDeadline: null, publishedAt: '2026-08-01T00:00:00Z',
      cancelledAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: null,
    }
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.startsWith('/api/tasks/feed')) {
        data = { items: [], nextCursor: null, hasMore: false }
      } else if (url.startsWith('/api/tasks?') && url.includes('status=published')) {
        data = [published]
      } else if (url.startsWith('/api/tasks/task-ladder/revise')) {
        data = { ...published, version: 5 }
      } else if (url.startsWith('/api/tasks')) {
        data = []
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench()
    await loginMerchant(wrapper)

    const editBtn = wrapper.findAll('button').find((b) => b.text() === '编辑')!
    await editBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('正在修订已发布任务')
    // 快照回填：编辑器可见、指标已载入；内部策略版本不出现在界面上
    const metricInput = wrapper.get('[aria-label="阶梯佣金指标标识"]').element as HTMLInputElement
    expect(metricInput.value).toBe('douyin.play_count')
    expect(wrapper.text()).not.toContain('legacy-v3')

    const saveBtn = formButton(wrapper, '保存修订')
    await saveBtn.trigger('click')
    await flushPromises()

    const revise = calls.find(([u]) => u.endsWith('/api/tasks/task-ladder/revise'))!
    const body = JSON.parse(revise[1]!.body as string)
    expect(body.requirements.commissionLadder).toEqual({
      policyVersion: 'legacy-v3',
      metricKey: 'douyin.play_count',
      tiers: [{ threshold: 100, payoutCents: 500 }],
    })
    expect(wrapper.text()).not.toContain('legacy-v3')
  })

  /** 任务书 #25 Stage D：商家任务列表对阶梯任务渲染 compact 摘要（状态/赏金标签旁），普通任务不渲染。 */
  test('商家任务列表渲染阶梯摘要（标签/指标/范围/明细），无 ladder 的任务不受影响', async () => {
    const ladderTask = {
      id: 'task-ladder-display', ownerAccountId: 'acct-1', organizationId: 'org-1',
      title: '阶梯展示任务', description: null, status: 'published',
      contentForm: null, platform: 'douyin', maxSlots: 3, bountyCents: 10000,
      minRecommenderLevel: 1,
      requirements: {
        mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [],
        commissionLadder: {
          policyVersion: 'ladder-v1', metricKey: 'douyin.play_count',
          tiers: [{ threshold: 50000, payoutCents: 10000 }, { threshold: 10000, payoutCents: 5000 }],
        },
      },
      version: 1, applicationDeadline: null, publishedAt: '2026-08-01T00:00:00Z',
      cancelledAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: null,
    }
    const fixedTask = {
      id: 'task-fixed', ownerAccountId: 'acct-1', organizationId: 'org-1',
      title: '固定佣金任务', description: null, status: 'published',
      contentForm: null, platform: null, maxSlots: 1, bountyCents: 8800,
      minRecommenderLevel: 1,
      requirements: { mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [] },
      version: 1, applicationDeadline: null, publishedAt: '2026-08-01T00:00:00Z',
      cancelledAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: null,
    }
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.startsWith('/api/tasks/feed')) {
        data = { items: [], nextCursor: null, hasMore: false }
      } else if (url.startsWith('/api/tasks?') && url.includes('status=published')) {
        data = [ladderTask, fixedTask]
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))

    const wrapper = mountWorkbench()
    await loginMerchant(wrapper)

    const list = wrapper.get('#gl-engagements ul.gl-list')
    expect(list.text()).toContain('阶梯佣金')
    expect(list.text()).toContain('douyin.play_count')
    expect(list.text()).toContain('¥50.00–¥100.00')
    expect(list.text()).toContain('10,000 → ¥50.00')
    expect(list.text()).toContain('固定佣金，不累加')
    // 赏金标签（最高档预留）与普通任务的固定赏金文案保持不变
    expect(list.text()).toContain('¥100.00')
    expect(list.text()).toContain('¥88.00')
    // 摘要是 compact 实例；普通/无 ladder 任务不渲染摘要组件
    const summaries = wrapper.findAllComponents({ name: 'CommissionLadderSummary' })
    expect(summaries).toHaveLength(1)
    expect(summaries[0].props('compact')).toBe(true)
  })
})

// ---------- 问题 2 / 问题 3 共用的造数与挂载 helper（模块级，两个 describe 共享） ----------

function takenTask(): Task {
  return {
    id: 'task-taken', ownerAccountId: 'acct-1', organizationId: 'org-1',
    title: '有人报名的任务', description: null, status: 'published',
    contentForm: null, platform: 'douyin', maxSlots: 5, bountyCents: 10000,
    freebieDepositCents: 0, minRecommenderLevel: 1,
    requirements: { mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [] },
    version: 2, applicationDeadline: null, cancelledAt: null,
    createdAt: '2026-08-29T00:00:00Z', autoAcceptMinLevel: null, publishedAt: '2026-08-29T00:00:00Z',
    progress: {
      totalApplications: 3, pendingApplications: 1, reservingApplications: 1, acceptedApplications: 1,
      rejectedApplications: 0, withdrawnApplications: 0, refundedApplications: 0,
      acceptedApplicationCount: 2, occupiedSlots: 2, maxSlots: 5, remainingSlots: 3,
      submittedDeliverables: 0, confirmedDeliverables: 0, settledEngagements: 0,
      reservedBountyCents: 0, settledBountyCents: 0,
    },
  } as unknown as Task
}

function openTask(): Task {
  return {
    id: 'task-open', ownerAccountId: 'acct-1', organizationId: 'org-1',
    title: '无人报名的任务', description: null, status: 'published',
    contentForm: null, platform: 'douyin', maxSlots: 1, bountyCents: 10000,
    freebieDepositCents: 0, minRecommenderLevel: 1,
    requirements: { mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [] },
    version: 2, applicationDeadline: null, cancelledAt: null,
    createdAt: '2026-08-29T00:00:00Z', autoAcceptMinLevel: null, publishedAt: '2026-08-29T00:00:00Z',
  } as unknown as Task
}

function stubTasks(tasks: Task[]): void {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    let data: unknown = []
    if (url === '/api/me/identities') {
      data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
    } else if (url === '/api/organizations') {
      data = [ORG]
    } else if (url.startsWith('/api/tasks?') && url.includes('status=published')) {
      data = tasks
    } else if (url.startsWith('/api/tasks/feed')) {
      data = { items: [], nextCursor: null, hasMore: false }
    } else if (url.startsWith('/api/finance/accounts')) {
      data = { organizationId: 'org-1', balanceCents: 100000 }
    }
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
  }))
}

async function loginAndMount(tasks: Task[]): Promise<ReturnType<typeof mount>> {
  stubTasks(tasks)
  const wrapper = mountWorkbench()
  currentUser.value = asUser('acct-1', 'merchant@test.local')
  await flushPromises()
  return wrapper
}

/**
 * 问题 2（PRD §2.3，2026-08-29 拍板）：有人报名成功（accepted+reserving）时已发布任务
 * 「编辑」禁用并给行内原因——后端 revise 409 守卫的前端前置 + API 直调兜底。
 */
describe('GrasslandWorkbench 修订入口冻结（问题 2）', () => {
  test('有人报名成功：「编辑」禁用并显示行内原因；无人报名的任务不受影响', async () => {
    const wrapper = await loginAndMount([takenTask(), openTask()])

    const editButtons = wrapper.findAll('button').filter((b) => b.text() === '编辑')
    expect(editButtons).toHaveLength(2)
    expect((editButtons[0].element as HTMLButtonElement).disabled).toBe(true)
    expect((editButtons[1].element as HTMLButtonElement).disabled).toBe(false)
    expect(wrapper.text()).toContain('已有 2 名推荐官报名成功，任务不可再修改')
  })
})

/**
 * 问题 3：任务标题再点一次收起展开块；展开块头部有「收起」出口——
 * selectTask 此前只设不清，块一旦展开永远开着。
 */
describe('GrasslandWorkbench 任务展开块收起（问题 3）', () => {
  test('任务标题再点一次收起展开块；「收起」按钮清掉选中态', async () => {
    const wrapper = await loginAndMount([openTask()])
    const title = wrapper.findAll('button').find((b) => b.text() === '无人报名的任务')!

    await title.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('报名列表')
    expect(wrapper.find('.gl-apps-collapse').exists()).toBe(true)

    // 再点同一标题 = 收起（toggle）
    await title.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('报名列表')

    // 重新展开后走「收起」按钮
    await title.trigger('click')
    await flushPromises()
    await wrapper.find('.gl-apps-collapse').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('报名列表')
  })
})

/**
 * 任务书 #62 卡7：目标问题的提交载荷。只有「platform=zhihu 且问题非空」才带
 * questionText/questionRef——非知乎携带后端 422，空值白占一次校验，故整键省略。
 */
describe('GrasslandWorkbench 目标问题载荷（任务书 #62 卡7）', () => {
  async function loginMerchant(): Promise<void> {
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
  }

  function formButton(wrapper: ReturnType<typeof mount>, text: string) {
    return wrapper.getComponent(MerchantTaskForm).findAll('button').find((b) => b.text() === text)!
  }

  function createBody(calls: Array<[string, RequestInit | undefined]>): Record<string, unknown> {
    const call = calls.find(([url, init]) => url === '/api/tasks' && init?.method === 'POST')
    expect(call).toBeDefined()
    return JSON.parse(String(call?.[1]?.body))
  }

  async function fillZhihuTask(wrapper: ReturnType<typeof mount>, question: string): Promise<void> {
    await wrapper.find('input[placeholder="任务标题"]').setValue('知乎回答任务')
    await wrapper.get('select[name="task-platform"]').setValue('zhihu')
    await flushPromises()
    if (question) await wrapper.get('[data-testid="task-question-text"]').setValue(question)
  }

  test('知乎任务带问题链接：payload 含 questionText 与本地提取的 questionRef', async () => {
    const { calls } = stubFetch()
    const wrapper = mountWorkbench()
    await loginMerchant()
    await openTaskDrawer(wrapper)
    await fillZhihuTask(wrapper, 'https://www.zhihu.com/question/1999041081275355787')

    await formButton(wrapper, '提交审核').trigger('click')
    await flushPromises()

    expect(createBody(calls)).toEqual(expect.objectContaining({
      platform: 'zhihu',
      questionText: 'https://www.zhihu.com/question/1999041081275355787',
      questionRef: '1999041081275355787',
    }))
    // 零外呼红线（#62 §3.7）：全程不得对知乎发任何请求
    expect(calls.some(([url]) => url.includes('zhihu.com'))).toBe(false)
  })

  test('手输纯文本问题：带 questionText，不带 questionRef 键', async () => {
    const { calls } = stubFetch()
    const wrapper = mountWorkbench()
    await loginMerchant()
    await openTaskDrawer(wrapper)
    await fillZhihuTask(wrapper, '为什么大厂都在弃用 Kubernetes？')

    await formButton(wrapper, '提交审核').trigger('click')
    await flushPromises()

    const body = createBody(calls)
    expect(body.questionText).toBe('为什么大厂都在弃用 Kubernetes？')
    expect(body).not.toHaveProperty('questionRef')
  })

  test('知乎任务不填问题：两个键都不带（零回归——文章形态任务载荷不变）', async () => {
    const { calls } = stubFetch()
    const wrapper = mountWorkbench()
    await loginMerchant()
    await openTaskDrawer(wrapper)
    await fillZhihuTask(wrapper, '')

    await formButton(wrapper, '提交审核').trigger('click')
    await flushPromises()

    const body = createBody(calls)
    expect(body).not.toHaveProperty('questionText')
    expect(body).not.toHaveProperty('questionRef')
  })
})

describe('GrasslandWorkbench 场景化举报（任务书 #74）', () => {
  /** 弹窗打开后：标题、对象摘要行、按对象过滤的原因选项。 */
  function expectModalFor(
    wrapper: Awaited<ReturnType<typeof mountWorkbench>>,
    title: string,
    summary: string,
    optionLabels: string[],
  ): void {
    expect(wrapper.get('.modal-title').text()).toBe(title)
    expect(wrapper.get('.complaint-target-summary').text()).toBe(summary)
    const options = wrapper.get('.complaint-modal select').findAll('option')
    expect(options.map((o) => o.text())).toEqual(optionLabels)
  }

  test('入口①：推荐官任务大厅任务行「举报」→ task（摘要=任务标题，原因四项）', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }]
      } else if (url.startsWith('/api/tasks/feed')) {
        data = {
          items: [{ id: 'task-feed-1', title: '大厅举报对象任务', status: 'published', bountyCents: 1000 }],
          nextCursor: null, hasMore: false,
        }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    const wrapper = mountWorkbench({
      global: { stubs: { MyWalletCard: true, MyRecommenderProfileCard: true, RecommenderHistoryCard: true, RecommenderIncomeStatsCard: true } },
    })
    currentUser.value = asUser('acct-rec', 'recommender@test.local')
    await flushPromises()

    // 任务行的举报按钮与报名并排（全行可见）
    const reportButtons = wrapper.findAll('button').filter((b) => b.text() === '举报')
    expect(reportButtons).toHaveLength(1)
    await reportButtons[0].trigger('click')
    await flushPromises()

    expectModalFor(wrapper, '举报任务', '大厅举报对象任务', ['垃圾信息', '涉嫌欺诈', '违规内容', '其他'])
  })

  /** 商家侧两个入口共用的挂载：已发布任务 + 一条 accepted 报名。 */
  async function mountMerchantWithAccepted(): Promise<ReturnType<typeof mountWorkbench>> {
    const application = {
      id: 'app-accepted', taskId: 'task-1', recommenderAccountId: 'acct-rec',
      status: 'accepted', note: null, reviewedByAccountId: null, decidedAt: null, createdAt: null,
    }
    const task = {
      id: 'task-1', ownerAccountId: 'acct-1', organizationId: 'org-1', title: '待核验任务',
      description: '突出门店招牌', status: 'published', contentForm: '图文', platform: '小红书', maxSlots: 1,
      bountyCents: 100, createdAt: null, version: 1, applicationDeadline: null,
      publishedAt: null, cancelledAt: null,
    }
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = []
      if (url === '/api/me/identities') {
        data = [{ id: 'identity-merchant', identityType: 'merchant', organizationId: 'org-1', status: 'active' }]
      } else if (url === '/api/organizations') {
        data = [ORG]
      } else if (url.includes('status=published')) {
        data = [task]
      } else if (url.startsWith('/api/tasks?') || url.startsWith('/api/tasks/feed')) {
        data = url.startsWith('/api/tasks/feed') ? { items: [], nextCursor: null, hasMore: false } : []
      } else if (url === '/api/tasks/task-1/applications') {
        data = [application]
      } else if (url.startsWith('/api/finance/accounts')) {
        data = { organizationId: 'org-1', balanceCents: 100000 }
      } else if (url.startsWith('/api/reputation/')) {
        data = { accountId: 'acct-rec', level: 'Lv1', levelTitle: '新锐', acceptedCount: 1, completedCount: 0, completionRate: 0, ratingCount: 0, averageScore: null, averageResponseSeconds: null }
      } else if (url.includes('/profile')) {
        data = { accountId: 'acct-rec', displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [], updatedAt: null }
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    const wrapper = mountWorkbench({
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    await wrapper.find('button.gl-link').trigger('click')
    await flushPromises()
    return wrapper
  }

  test('入口②：商家报名行「举报」→ user（摘要=推荐官账号前 8 位，原因四项）', async () => {
    const wrapper = await mountMerchantWithAccepted()

    await wrapper.get('button[aria-label="举报推荐官 acct-rec"]').trigger('click')
    await flushPromises()

    expectModalFor(wrapper, '举报用户', '推荐官 acct-rec…', ['涉嫌欺诈', '违规内容', '垃圾信息', '其他'])
  })

  test('入口③：商家履约交付物块「举报」→ submission（摘要=任务标题+账号前 8 位，原因五项）', async () => {
    const wrapper = await mountMerchantWithAccepted()

    await wrapper.get('button[aria-label="举报履约交付物 app-acce"]').trigger('click')
    await flushPromises()

    expectModalFor(
      wrapper,
      '举报履约交付物',
      '任务「待核验任务」的履约交付物（acct-rec…）',
      ['侵权', '违规内容', '涉嫌欺诈', '垃圾信息', '其他'],
    )
  })

  test('三处入口共用一份弹窗：关闭后再从另一入口打开，对象随新入口切换', async () => {
    const wrapper = await mountMerchantWithAccepted()

    await wrapper.get('button[aria-label="举报推荐官 acct-rec"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.modal-title').text()).toBe('举报用户')
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(wrapper.find('.modal-title').exists()).toBe(false)

    await wrapper.get('button[aria-label="举报履约交付物 app-acce"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.modal-title').text()).toBe('举报履约交付物')
  })
})

describe('GrasslandWorkbench settings 深链（任务书 #74 D3）', () => {
  test('?settings=1 自动打开个人设置弹窗；关闭后参数从 URL 清除', async () => {
    stubFetch()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/grassland', name: 'grassland', component: GrasslandWorkbench },
      ],
    })
    router.push('/grassland?settings=1')
    await router.isReady()
    const wrapper = mount(GrasslandWorkbench, {
      global: { plugins: [router], stubs: { Teleport: true } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 旧 /complaints 深链与首页「平台治理」卡的落点：个人设置弹窗自动打开
    expect(wrapper.find('.gl-zone[aria-label="举报与投诉"]').exists()).toBe(true)
    expect(router.currentRoute.value.query.settings).toBe('1')

    // 关闭弹窗 → 既有 query 写出机制移除 settings 参数
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.gl-zone[aria-label="举报与投诉"]').exists()).toBe(false)
    expect(router.currentRoute.value.query.settings).toBeUndefined()
  })
})
