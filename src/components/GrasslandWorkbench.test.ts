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
   * 锁的是「修订只送非资金字段」：bountyCents/platform 发布后冻结，改了会动已报名履约的条款
   * （task_version 快照尚未被 accept/结算消费）。请求体里不该出现这些键——这是后端冻结之外的前端第二道闸。
   */
  test('修订已发布任务只送 title/description/maxSlots/deadline，不送赏金/平台', async () => {
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

    // 点已发布任务的「编辑」进入修订模式
    const editBtn = wrapper.findAll('button').find((b) => b.text() === '编辑')!
    await editBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('正在修订已发布任务')
    // 修订模式冻结资金/物料字段：平台输入禁用
    expect(wrapper.find('input[placeholder="平台（可选）"]').attributes('disabled')).toBeDefined()

    await wrapper.find('input[placeholder="任务标题"]').setValue('修订标题')
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === '保存修订')!
    await saveBtn.trigger('click')
    await flushPromises()

    const revise = calls.find(([u]) => u.endsWith('/api/tasks/task-pub/revise'))!
    const body = JSON.parse(revise[1]!.body as string)
    expect(body.title).toBe('修订标题')
    expect(body.expectedVersion).toBe(1)
    // 资金/物料字段不在修订请求里（冻结）
    expect(body).not.toHaveProperty('bountyCents')
    expect(body).not.toHaveProperty('platform')
    expect(body).not.toHaveProperty('contentForm')
  })
})
