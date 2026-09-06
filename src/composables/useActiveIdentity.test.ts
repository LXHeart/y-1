// @vitest-environment happy-dom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { loadAccountIdentity, useActiveIdentity } from './useActiveIdentity'
import { useAuthStore } from '../stores/auth'
import { useAccountSessionStore } from '../stores/account-session'
import type { useGrassland } from './useGrassland'
import type { IdentityProfile, StoreAccessScope } from '../types/grassland'

/**
 * 全局活动身份单例的特征测试。
 *
 * 锁定 PRD §11.3 的三条规则：
 * 1. 初始视角按已开通身份计算（商家优先；仅推荐官则激活推荐官）；
 * 2. 账号菜单切换只在已开通身份之间进行，激活成功才落本地镜像；
 * 3. reset 清身份表但不动 activeSide（换账号不触发无意义翻转）。
 *
 * 2026-09-04 身份模型改版（任务书 #71 D6/D8）：claim 竞态机制删除（默认激活是唯一
 * 写入者）；装载链新增裸账号兜底——零档案+零门店范围+零组织归属时自动补开推荐官。
 */

function identity(type: 'merchant' | 'recommender'): IdentityProfile {
  return { id: `identity-${type}`, identityType: type, organizationId: type === 'merchant' ? 'org-1' : null, status: 'active' }
}

function managerScope(): StoreAccessScope {
  return {
    organizationId: 'org-1', organizationName: '组织', organizationStatus: 'active',
    storeId: 'store-1', storeName: '门店',
    storeStatus: 'active', role: 'manager', permissionTier: 'basic_publish',
  }
}

function fakeGrassland(options: {
  identities?: IdentityProfile[] | null
  scopes?: StoreAccessScope[]
  activateFail?: boolean
  /** 裸账号兜底要读的组织列表（默认空 = 无组织归属）。null = 请求失败。 */
  organizations?: Array<Record<string, unknown>> | null
  /** 开户（POST /me/identities）失败开关。 */
  openFail?: boolean
  /** 服务器会话已激活的活动身份（模拟登录时已选定/刷新页面场景）。 */
  serverActive?: 'merchant' | 'recommender' | null
}) {
  const calls: string[] = []
  // 开户成功后身份表随之可见（模拟裸账号兜底里 openIdentity → listIdentities 重列）
  let currentIdentities: IdentityProfile[] | null =
    options.identities === null ? null : options.identities ?? []
  // 与 useGrassland().run 同语义：失败吞异常返回 null（调用方按 null 判定），error 走 clearError 通道。
  const api = {
    listIdentities: vi.fn(async () => {
      calls.push('list-identities')
      return currentIdentities
    }),
    listMyStoreScopes: vi.fn(async () => { calls.push('list-store-scopes'); return options.scopes ?? [] }),
    listOrganizations: vi.fn(async () => {
      calls.push('list-organizations')
      return options.organizations === null ? null : options.organizations ?? []
    }),
    openIdentity: vi.fn(async (type: string) => {
      calls.push(`open:${type}`)
      if (options.openFail) return null
      if (type === 'recommender') currentIdentities = [identity('recommender')]
      return { ok: true, type }
    }),
    activateIdentity: vi.fn(async (type: string) => {
      calls.push(`activate:${type}`)
      return options.activateFail ? null : { ok: true, type }
    }),
    getActiveIdentity: vi.fn(async () => {
      calls.push('get-active')
      return { activeIdentityType: options.serverActive ?? null }
    }),
    clearError: vi.fn(),
  }
  return { api: api as unknown as ReturnType<typeof useGrassland>, calls }
}

beforeEach(() => {
  useActiveIdentity().reset()
})

describe('loadAccountIdentity 初始视角规则', () => {
  test('双身份账号：商家优先并激活商家', async () => {
    const { api, calls } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()

    const snapshot = await loadAccountIdentity(api)

    expect(snapshot?.identities).toHaveLength(2)
    expect(state.activeSide.value).toBe('merchant')
    expect(state.hasMerchantIdentity.value).toBe(true)
    expect(state.hasRecommenderIdentity.value).toBe(true)
    expect(calls).toContain('activate:merchant')
    expect(calls).not.toContain('activate:recommender')
  })

  test('仅推荐官：激活推荐官，避免沿用默认商家的可预期 409', async () => {
    const { api, calls } = fakeGrassland({ identities: [identity('recommender')] })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(calls).toContain('activate:recommender')
  })

  test('推荐官身份 + 门店管理范围：激活推荐官，管理范围不压身份档案', async () => {
    // judge1 实况：历史种子给了门店经理授权，旧排序会把工作台压进商家面板
    const { api, calls } = fakeGrassland({
      identities: [identity('recommender')],
      scopes: [managerScope()],
    })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(state.merchantViewViaManagerScope.value).toBe(false)
    expect(calls).toContain('activate:recommender')
  })

  test('初始激活每个账号只做一次：二次装载不重激活（防覆盖登录所选身份）', async () => {
    const { api, calls } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)
    await state.activateIdentitySide('recommender', api)
    // 账号 watch 的并发装载晚到：不得再用默认商家覆盖已显式激活的推荐官
    await loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(calls.filter((call) => call === 'activate:merchant')).toHaveLength(1)
  })

  test('会话已激活推荐官：装载尊重服务器值，双身份也不翻回商家（登录后进工作台/刷新的回归）', async () => {
    const { api, calls } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      serverActive: 'recommender',
    })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(calls.filter((call) => call.startsWith('activate:'))).toEqual([]) // 不重激活
  })

  test('会话无记录时维持既有默认（双身份商家优先激活）', async () => {
    const { api, calls } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      serverActive: null,
    })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('merchant')
    expect(calls).toContain('activate:merchant')
  })

  test('无身份但有门店管理范围：商家视角本地生效，不激活', async () => {
    const { api, calls } = fakeGrassland({ identities: [], scopes: [managerScope()] })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('merchant')
    expect(state.merchantViewViaManagerScope.value).toBe(true)
    expect(calls.filter((call) => call.startsWith('activate:'))).toEqual([])
  })

  test('零档案零范围零组织（存量裸账号）：兜底补开推荐官并激活推荐官（D6）', async () => {
    const { api, calls } = fakeGrassland({ identities: [], organizations: [] })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(calls).toContain('open:recommender')
    expect(state.hasRecommenderIdentity.value).toBe(true)
    expect(state.activeSide.value).toBe('recommender')
    expect(calls).toContain('activate:recommender')
  })

  test('零档案但有门店范围：不兜底开户，保持商家视角（店长/店员视角）', async () => {
    const { api, calls } = fakeGrassland({ identities: [], scopes: [managerScope()], organizations: [] })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(calls).not.toContain('open:recommender')
    expect(calls).not.toContain('list-organizations')
    expect(state.activeSide.value).toBe('merchant')
  })

  test('零档案零范围但有组织归属：不兜底开户（主体子账号/池成员保持商家视角）', async () => {
    const { api, calls } = fakeGrassland({
      identities: [],
      organizations: [{ id: 'org-1', name: '主体' }],
    })
    const state = useActiveIdentity()

    await loadAccountIdentity(api)

    expect(calls).toContain('list-organizations')
    expect(calls).not.toContain('open:recommender')
    expect(state.activeSide.value).toBe('merchant')
  })

  test('身份列表拉取失败：返回 null 且不置 loaded', async () => {
    const failing = fakeGrassland({ identities: null })
    const state = useActiveIdentity()

    const snapshot = await loadAccountIdentity(failing.api)

    expect(snapshot).toBeNull()
    expect(state.identitiesLoaded.value).toBe(false)
  })
})

describe('activateIdentitySide 账号菜单切换', () => {
  test('未开通的身份：not-opened 且不落本地镜像', async () => {
    const { api } = fakeGrassland({ identities: [identity('merchant')] })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)

    const result = await state.activateIdentitySide('recommender', api)

    expect(result).toBe('not-opened')
    expect(state.activeSide.value).toBe('merchant')
  })

  test('已开通身份：激活成功后切换本地镜像', async () => {
    const { api } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)

    const result = await state.activateIdentitySide('recommender', api)

    expect(result).toBe('ok')
    expect(state.activeSide.value).toBe('recommender')
  })

  /**
   * 冷会话的快路径语义（2026-08-28 治理台 #53 实测发现）：activeSide 默认就是 merchant，
   * 快路径必须以「服务端已激活侧」镜像为准——未经服务端确认的同侧切换不得短路。
   * 登录编排（claim）退役后，唯一写入者是装载链的默认激活：装载完成 = 服务端已确认，
   * 同侧切换允许短路；此处锁定「装载后同侧不重发」。
   */
  test('装载激活完成后，同侧切换短路不重发（服务端已确认）', async () => {
    const { api, calls } = fakeGrassland({ identities: [identity('merchant')] })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)

    const callsBefore = calls.length
    expect(await state.activateIdentitySide('merchant', api)).toBe('ok')
    expect(calls.length).toBe(callsBefore)
  })

  test('后端激活失败：failed 且保持原视角（不得只信客户端）', async () => {
    const { api } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      activateFail: true,
    })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)

    const result = await state.activateIdentitySide('recommender', api)

    expect(result).toBe('failed')
    expect(state.activeSide.value).toBe('merchant')
  })

  test('切换到当前活动身份：幂等 ok', async () => {
    const { api } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)

    expect(await state.activateIdentitySide('merchant', api)).toBe('ok')
    expect(state.activeSide.value).toBe('merchant')
  })
})

describe('reset 语义', () => {
  test('清空身份表与标记，activeSide 刻意保留', async () => {
    const { api } = fakeGrassland({ identities: [identity('recommender')] })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)
    expect(state.activeSide.value).toBe('recommender')

    state.reset()

    expect(state.identities.value).toEqual([])
    expect(state.identitiesLoaded.value).toBe(false)
    // 与原工作台 resetAccountState 的「side 刻意不清」一致
    expect(state.activeSide.value).toBe('recommender')
  })
})

/** 任务书 #79 C79-03：装载/激活提交边界挂账号票据 + 激活串行队列。 */
describe('账号票据与激活串行化（任务书 #79 C79-03）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', role: 'user' }

  function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
    let resolve!: (value: T) => void
    const promise = new Promise<T>((res) => { resolve = res })
    return { promise, resolve }
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    useAccountSessionStore()
    useAuthStore().currentUser = null
  })

  test('装载期间换账号：迟到的身份列表不写身份表，返回 null', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    const hang = deferred<IdentityProfile[] | null>()
    const calls: string[] = []
    const api = {
      listIdentities: vi.fn(async () => { calls.push('list-identities'); return hang.promise }),
      listMyStoreScopes: vi.fn(async () => []),
      listOrganizations: vi.fn(async () => []),
      openIdentity: vi.fn(async (type: string) => ({ ok: true, type })),
      activateIdentity: vi.fn(async (type: string) => ({ ok: true, type })),
      getActiveIdentity: vi.fn(async () => ({ activeIdentityType: null })),
      clearError: vi.fn(),
    } as unknown as ReturnType<typeof useGrassland>

    const state = useActiveIdentity()
    const loading = loadAccountIdentity(api)
    auth.currentUser = userB // 切号：票据失效
    hang.resolve([identity('merchant')])
    await expect(loading).resolves.toBeNull()
    expect(state.identities.value).toEqual([])
    expect(state.identitiesLoaded.value).toBe(false)
    auth.currentUser = null
  })

  test('E15：显式切换排在 bootstrap 默认激活之后，完成后不被默认覆盖', async () => {
    setActivePinia(createPinia())
    useAccountSessionStore()
    const auth = useAuthStore()
    auth.currentUser = userA
    const hangActive = deferred<{ activeIdentityType: string | null }>()
    const calls: string[] = []
    const api = {
      listIdentities: vi.fn(async () => [identity('merchant'), identity('recommender')]),
      listMyStoreScopes: vi.fn(async () => []),
      listOrganizations: vi.fn(async () => []),
      openIdentity: vi.fn(async (type: string) => ({ ok: true, type })),
      activateIdentity: vi.fn(async (type: string) => { calls.push(`activate:${type}`); return { ok: true, type } }),
      getActiveIdentity: vi.fn(async () => hangActive.promise),
      clearError: vi.fn(),
    } as unknown as ReturnType<typeof useGrassland>

    const state = useActiveIdentity()
    const bootstrapping = loadAccountIdentity(api)
    // 身份表先落地（真实 UI 只在此时才可点切换）；默认激活挂在 getActiveIdentity 上未结束
    await flushPromises()
    expect(state.identitiesLoaded.value).toBe(true)
    // 显式切推荐官：排队等待 bootstrap 默认激活完成
    const explicit = state.activateIdentitySide('recommender', api)
    hangActive.resolve({ activeIdentityType: null })
    await bootstrapping
    await expect(explicit).resolves.toBe('ok')

    expect(state.activeSide.value).toBe('recommender')
    // 默认（merchant）先执行、显式（recommender）后执行——顺序可证
    expect(calls.indexOf('activate:merchant')).toBeGreaterThanOrEqual(0)
    expect(calls.indexOf('activate:recommender')).toBeGreaterThan(calls.indexOf('activate:merchant'))
    auth.currentUser = null
  })

  test('E09/E16：切号后排队到达的显式切换不对新账号发激活', async () => {
    setActivePinia(createPinia())
    useAccountSessionStore()
    const auth = useAuthStore()
    auth.currentUser = userA
    const recommenderActivate = deferred<{ ok: boolean, type: string } | null>()
    const calls: string[] = []
    const api = {
      listIdentities: vi.fn(async () => [identity('merchant'), identity('recommender')]),
      listMyStoreScopes: vi.fn(async () => []),
      listOrganizations: vi.fn(async () => []),
      openIdentity: vi.fn(async (type: string) => ({ ok: true, type })),
      activateIdentity: vi.fn(async (type: string) => {
        calls.push(`activate:${type}`)
        // 只有显式的 recommender 切换挂起（默认 merchant 激活即时完成）
        return type === 'recommender' ? recommenderActivate.promise : { ok: true, type }
      }),
      getActiveIdentity: vi.fn(async () => ({ activeIdentityType: null })),
      clearError: vi.fn(),
    } as unknown as ReturnType<typeof useGrassland>

    const state = useActiveIdentity()
    await loadAccountIdentity(api) // 默认激活 merchant 完成

    const switching = state.activateIdentitySide('recommender', api)
    // 让队列任务先跑到挂起的 POST（已按 A 会话发出），再换号
    await flushPromises()
    expect(calls).toContain('activate:recommender')
    auth.currentUser = userB // POST 已在 A 会话发出；等待期间换号
    recommenderActivate.resolve({ ok: true, type: 'recommender' })
    await expect(switching).resolves.toBe('failed') // 旧票据不落本地镜像
    expect(state.activeSide.value).toBe('merchant')
    expect(calls.filter((call) => call === 'activate:recommender')).toHaveLength(1) // 不向 B 重发
    auth.currentUser = null
  })
})

describe('owner 绑定与自动换号（任务书 #82 C82-03）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', role: 'user' }

  beforeEach(() => {
    setActivePinia(createPinia())
    useAccountSessionStore()
    useAuthStore().currentUser = null
  })

  test('换号自动清身份表——无人调 reset 也隔离；ownerAccountId 公开可观察', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    const { api } = fakeGrassland({ identities: [identity('merchant')] })
    const state = useActiveIdentity()
    const snapshot = await loadAccountIdentity(api)
    expect(snapshot).not.toBeNull()
    expect(state.ownerAccountId.value).toBe(userA.id)
    expect(state.identitiesLoaded.value).toBe(true)
    expect(state.identities.value).toHaveLength(1)

    auth.currentUser = userB // 没有任何消费方调 reset
    expect(state.ownerAccountId.value).toBe(userB.id)
    expect(state.identities.value).toEqual([])
    expect(state.identitiesLoaded.value).toBe(false)
    // activeSide 刻意保留（reset 既有语义不变）
    auth.currentUser = null
    expect(state.ownerAccountId.value).toBeNull()
  })

  test('同 owner 重复 resetForAccount 幂等：不清当前账号数据（多消费方 watcher 并存）', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    const { api } = fakeGrassland({ identities: [identity('merchant')] })
    const state = useActiveIdentity()
    await loadAccountIdentity(api)

    state.resetForAccount(userA.id)
    expect(state.identitiesLoaded.value).toBe(true) // 未被误清
    expect(state.identities.value).toHaveLength(1)
    auth.currentUser = null
  })
})

describe('API 封口（任务书 #83 C83-02）', () => {
  test('useActiveIdentity() 公开返回值不暴露 loadAccountIdentity——装载只能经 bootstrap 总入口', () => {
    expect(Object.keys(useActiveIdentity())).not.toContain('loadAccountIdentity')
  })
})
