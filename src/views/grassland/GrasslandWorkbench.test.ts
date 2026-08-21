// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import GrasslandWorkbench from '../../views/grassland/GrasslandWorkbench.vue'
import MerchantTaskForm from '../../views/grassland/components/MerchantTaskForm.vue'
import { useAuth } from '../../composables/useAuth'
import type { AuthUser } from '../../types/auth'
import type { Task } from '../../types/grassland'

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
})

afterEach(() => {
  vi.unstubAllGlobals()
  currentUser.value = null
})

describe('GrasslandWorkbench 登录态', () => {
  test('未登录时不发任何请求', async () => {
    const { urls } = stubFetch()

    mount(GrasslandWorkbench)
    await flushPromises()

    expect(urls).toEqual([])
  })

  test('同一页面内登录后自动激活身份并拉组织（原缺陷：需刷新整页）', async () => {
    const { urls } = stubFetch()
    const wrapper = mount(GrasslandWorkbench)
    await flushPromises()

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 顺序关键：先查已开通身份，再激活它；避免 recommender-only 账号被默认 merchant 激活成 409。
    expect(urls[0]).toBe('/api/me/identities')
    expect(urls).toContain('/api/me/active-identity')
    expect(urls).toContain('/api/organizations')
    expect(wrapper.text()).toContain('示例商家')
  })

  test('AI 预算入口仅对组织 owner/admin 可见', async () => {
    const owner = stubFetch()
    const ownerWrapper = mount(GrasslandWorkbench)
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
    const memberWrapper = mount(GrasslandWorkbench)
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
    mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    const statuses = urls
      .filter((url) => url.startsWith('/api/tasks?organizationId='))
      .map((url) => new URL(url, 'http://localhost').searchParams.get('status'))
    expect(statuses).toEqual(['draft', 'pending_review', 'published', 'closed', 'cancelled'])
  })

  test('recommender-only 账号直接激活推荐官，不尝试 merchant', async () => {
    const identities = [{ id: 'identity-rec', identityType: 'recommender', organizationId: null, status: 'active' }]
    const { calls } = stubFetch(identities)
    const wrapper = mount(GrasslandWorkbench, {
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

    expect(wrapper.find('[aria-selected="true"]').text()).toBe('推荐官视角')
    const activation = calls.find(([url]) => url === '/api/me/active-identity')
    expect(activation).toBeDefined()
    expect(JSON.parse(activation?.[1]?.body as string)).toEqual({ type: 'recommender' })
    expect(calls.filter(([url]) => url === '/api/me/active-identity')).toHaveLength(1)
    expect(calls.some(([url, init]) => url === '/api/me/identities' && init?.method === 'POST')).toBe(false)
  })

  test('未开通身份时保留商家 onboarding，且不激活或自动开通', async () => {
    const { calls } = stubFetch([])
    const wrapper = mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-consumer', 'consumer@test.local')
    await flushPromises()

    expect(wrapper.find('[aria-selected="true"]').text()).toBe('商家视角')
    expect(calls.filter(([url]) => url === '/api/me/active-identity')).toHaveLength(0)
    expect(calls.some(([url, init]) => url === '/api/me/identities' && init?.method === 'POST')).toBe(false)
    expect(wrapper.find('[aria-selected="true"]').text()).toBe('商家视角')
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

    const wrapper = mount(GrasslandWorkbench)
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
    const wrapper = mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    expect(wrapper.text()).toContain('示例商家')

    currentUser.value = null
    await flushPromises()

    expect(wrapper.text()).not.toContain('示例商家')
    expect(wrapper.text()).toContain('¥—')  // 余额回到未知态
  })

  test('换账号重新激活身份并重拉（不沿用上一个账号的组织）', async () => {
    const { urls } = stubFetch()
    mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    const firstRound = urls.length
    currentUser.value = asUser('acct-2', 'b@test.local')
    await flushPromises()

    expect(urls.slice(firstRound)).toContain('/api/me/active-identity')
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

    const wrapper = mount(GrasslandWorkbench, {
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

    const reason = wrapper.get('[aria-label="拒绝理由 app-accepted"]')
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

    const wrapper = mount(GrasslandWorkbench, {
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

    const wrapper = mount(GrasslandWorkbench, {
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
    const wrapper = mount(GrasslandWorkbench, { global: { stubs: { AdjudicationPanel: true, MyWalletCard: true } } })

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
    const wrapper = mount(GrasslandWorkbench, { global: { stubs: { AdjudicationPanel: true, MyWalletCard: true } } })
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
    const wrapper = mount(GrasslandWorkbench, { global: { stubs: { AdjudicationPanel: true, MyWalletCard: true } } })
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
    const wrapper = mount(GrasslandWorkbench, {
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    await selectFirstTask(wrapper)

    // 未填：有输入、预览 ¥0.00、明确提示、确认禁用
    const metric = wrapper.get('[aria-label="实际指标 douyin.play_count a-1"]')
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
    const wrapper = mount(GrasslandWorkbench, {
      global: { stubs: { EngagementSubmissionPanel: true, EngagementRatingPanel: true } },
    })
    await selectFirstTask(wrapper)

    const metric = wrapper.get('[aria-label="实际指标 douyin.play_count a-1"]')
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
    const wrapper = mount(GrasslandWorkbench, {
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
  const ANCHORS = ['gl-invitations', 'gl-organizations', 'gl-engagements', 'gl-disputes', 'gl-wallet']

  test('商家视角下五个锚点都在 DOM 里', async () => {
    stubFetch()
    const wrapper = mount(GrasslandWorkbench, { attachTo: document.body })
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

    const wrapper = mount(GrasslandWorkbench, {
      attachTo: document.body,
      global: { provide: { grasslandAnchor: anchor } },
    })
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    anchor.value = 'gl-disputes'
    await flushPromises()

    expect(scrolled).toEqual(['gl-disputes'])
    expect(anchor.value).toBe('')  // 置空后同一锚点可再次触发
    expect(wrapper.find('[aria-selected="true"]').text()).toBe('商家视角')  // 未替用户切视角
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

    mount(GrasslandWorkbench, {
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

    const wrapper = mount(GrasslandWorkbench)
    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 点已发布任务的「编辑」进入修订模式（全字段可改，输入不再禁用）
    const editBtn = wrapper.findAll('button').find((b) => b.text() === '编辑')!
    await editBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('正在修订已发布任务')
    expect(wrapper.find('input[placeholder="平台（可选）"]').attributes('disabled')).toBeUndefined()

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
    const wrapper = mount(GrasslandWorkbench)
    await loginMerchant(wrapper)

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
    const wrapper = mount(GrasslandWorkbench)
    await loginMerchant(wrapper)

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

    const wrapper = mount(GrasslandWorkbench)
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

    const wrapper = mount(GrasslandWorkbench)
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
