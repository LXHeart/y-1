// @vitest-environment happy-dom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useActiveIdentity } from './useActiveIdentity'
import type { useGrassland } from './useGrassland'
import type { IdentityProfile, StoreAccessScope } from '../types/grassland'

/**
 * 全局活动身份单例的特征测试。
 *
 * 锁定 PRD §11.3 的三条规则：
 * 1. 初始视角按已开通身份计算（商家优先；仅推荐官则激活推荐官；无身份不暗中开户）；
 * 2. 账号菜单切换只在已开通身份之间进行，激活成功才落本地镜像；
 * 3. reset 清身份表但不动 activeSide（换账号不触发无意义翻转）。
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
  /** 服务器会话已激活的活动身份（模拟登录时已选定/刷新页面场景）。 */
  serverActive?: 'merchant' | 'recommender' | null
}) {
  const calls: string[] = []
  // 与 useGrassland().run 同语义：失败吞异常返回 null（调用方按 null 判定），error 走 clearError 通道。
  const api = {
    listIdentities: vi.fn(async () => {
      calls.push('list-identities')
      return options.identities === null ? null : options.identities ?? []
    }),
    listMyStoreScopes: vi.fn(async () => { calls.push('list-store-scopes'); return options.scopes ?? [] }),
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

    const snapshot = await state.loadAccountIdentity(api)

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

    await state.loadAccountIdentity(api)

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

    await state.loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(state.merchantViewViaManagerScope.value).toBe(false)
    expect(calls).toContain('activate:recommender')
  })

  test('初始激活每个账号只做一次：二次装载不重激活（防覆盖登录所选身份）', async () => {
    const { api, calls } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()

    await state.loadAccountIdentity(api)
    await state.activateIdentitySide('recommender', api)
    // 账号 watch 的并发装载晚到：不得再用默认商家覆盖已显式激活的推荐官
    await state.loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(calls.filter((call) => call === 'activate:merchant')).toHaveLength(1)
  })

  test('会话已激活推荐官：装载尊重服务器值，双身份也不翻回商家（登录后进工作台/刷新的回归）', async () => {
    const { api, calls } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      serverActive: 'recommender',
    })
    const state = useActiveIdentity()

    await state.loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('recommender')
    expect(calls.filter((call) => call.startsWith('activate:'))).toEqual([]) // 不重激活
  })

  test('会话无记录时维持既有默认（双身份商家优先激活）', async () => {
    const { api, calls } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      serverActive: null,
    })
    const state = useActiveIdentity()

    await state.loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('merchant')
    expect(calls).toContain('activate:merchant')
  })

  test('无身份但有门店管理范围：商家视角本地生效，不激活', async () => {
    const { api, calls } = fakeGrassland({ identities: [], scopes: [managerScope()] })
    const state = useActiveIdentity()

    await state.loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('merchant')
    expect(state.merchantViewViaManagerScope.value).toBe(true)
    expect(calls.filter((call) => call.startsWith('activate:'))).toEqual([])
  })

  test('无身份无范围：保持商家视角进入入驻引导，不开户', async () => {
    const { api, calls } = fakeGrassland({ identities: [] })
    const state = useActiveIdentity()

    await state.loadAccountIdentity(api)

    expect(state.activeSide.value).toBe('merchant')
    expect(state.identitiesLoaded.value).toBe(true)
    expect(calls.filter((call) => call.startsWith('activate:'))).toEqual([])
  })

  test('身份列表拉取失败：返回 null 且不置 loaded', async () => {
    const failing = fakeGrassland({ identities: null })
    const state = useActiveIdentity()

    const snapshot = await state.loadAccountIdentity(failing.api)

    expect(snapshot).toBeNull()
    expect(state.identitiesLoaded.value).toBe(false)
  })
})

describe('activateIdentitySide 账号菜单切换', () => {
  test('未开通的身份：not-opened 且不落本地镜像', async () => {
    const { api } = fakeGrassland({ identities: [identity('merchant')] })
    const state = useActiveIdentity()
    await state.loadAccountIdentity(api)

    const result = await state.activateIdentitySide('recommender', api)

    expect(result).toBe('not-opened')
    expect(state.activeSide.value).toBe('merchant')
  })

  test('已开通身份：激活成功后切换本地镜像', async () => {
    const { api } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()
    await state.loadAccountIdentity(api)

    const result = await state.activateIdentitySide('recommender', api)

    expect(result).toBe('ok')
    expect(state.activeSide.value).toBe('recommender')
  })

  /**
   * 冷会话选「商家」登录的回归（2026-08-28 治理台 #53 实测发现）：
   * activeSide 默认就是 merchant，旧快路径 `next === activeSide` 据此跳过激活 POST，
   * 服务端会话停留在消费者（isMerchant()=false，草稿/商家资料全部不可见）。
   * 快路径必须以「服务端已激活侧」镜像为准——未经服务端确认的同侧切换仍要 POST。
   */
  test('服务端未确认前，同侧切换不得短路激活（冷会话登录选商家）', async () => {
    const { api, calls } = fakeGrassland({ identities: [identity('merchant')] })
    const state = useActiveIdentity()
    // 登录路径：claim → 装载（跳过初始激活块）→ activateIdentitySide('merchant')
    state.claimActivation()
    await state.loadAccountIdentity(api)

    const result = await state.activateIdentitySide('merchant', api)

    expect(result).toBe('ok')
    expect(calls).toContain('activate:merchant')

    // 服务端确认后，重复切同侧才允许短路（账号菜单点击当前身份不重发）
    const callsBefore = calls.length
    expect(await state.activateIdentitySide('merchant', api)).toBe('ok')
    expect(calls.length).toBe(callsBefore)

    // claim 是模块级独占标记（reset 刻意不清），用完必须收敛，否则污染同文件后续用例
    state.initialActivationFinalize()
  })

  test('后端激活失败：failed 且保持原视角（不得只信客户端）', async () => {
    const { api } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      activateFail: true,
    })
    const state = useActiveIdentity()
    await state.loadAccountIdentity(api)

    const result = await state.activateIdentitySide('recommender', api)

    expect(result).toBe('failed')
    expect(state.activeSide.value).toBe('merchant')
  })

  test('切换到当前活动身份：幂等 ok', async () => {
    const { api } = fakeGrassland({ identities: [identity('merchant'), identity('recommender')] })
    const state = useActiveIdentity()
    await state.loadAccountIdentity(api)

    expect(await state.activateIdentitySide('merchant', api)).toBe('ok')
    expect(state.activeSide.value).toBe('merchant')
  })
})

describe('reset 语义', () => {
  test('清空身份表与标记，activeSide 刻意保留', async () => {
    const { api } = fakeGrassland({ identities: [identity('recommender')] })
    const state = useActiveIdentity()
    await state.loadAccountIdentity(api)
    expect(state.activeSide.value).toBe('recommender')

    state.reset()

    expect(state.identities.value).toEqual([])
    expect(state.identitiesLoaded.value).toBe(false)
    // 与原工作台 resetAccountState 的「side 刻意不清」一致
    expect(state.activeSide.value).toBe('recommender')
  })
})
