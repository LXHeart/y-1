// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import GrasslandWorkbench from './GrasslandWorkbench.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

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
  if (url.startsWith('/api/tasks/feed')) return { items: [], nextCursor: null, hasMore: false }
  if (url.startsWith('/api/tasks')) return []
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

  /**
   * 商家任务列表必须四态全取（GL-P1-TASK-001 Stage 3 浏览器实测发现）。
   *
   * 原实现只取 `status=published`：刚存下的草稿在列表里不出现，「编辑 / 发布」无从触达；
   * 关闭报名后整条任务从列表消失，商家再也无法处理已提交的报名。
   */
  test('商家任务列表按 draft/published/closed/cancelled 四态拉取', async () => {
    const { urls } = stubFetch()
    mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    const statuses = urls
      .filter((url) => url.startsWith('/api/tasks?organizationId='))
      .map((url) => new URL(url, 'http://localhost').searchParams.get('status'))
    expect(statuses).toEqual(['draft', 'published', 'closed', 'cancelled'])
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
      let data: unknown = {}
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
      let data: unknown = {}
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
      version: 1, applicationDeadline: null, publishedAt: '2026-08-01T00:00:00Z',
      cancelledAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
    }
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = {}
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
  })
})
