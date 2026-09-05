// @vitest-environment happy-dom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import type { AuthUser } from '../types/auth'
import { useAccountSessionStore } from '../stores/account-session'
import { ensureAccountIdentity } from './useAccountBootstrap'
import { useActiveIdentity, type AccountIdentitySnapshot } from './useActiveIdentity'
import type { useGrassland } from './useGrassland'
import type { IdentityProfile, StoreAccessScope } from '../types/grassland'

/**
 * TC79-03A/03B（任务书 #79 C79-03）：唯一身份 bootstrap 协调器。
 * mock 在域 API 边界（fakeGrassland，与 useActiveIdentity.test 同款），不 mock 被测协调器。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function identity(type: 'merchant' | 'recommender', owner = 'a'): IdentityProfile {
  return { id: `identity-${owner}-${type}`, identityType: type, organizationId: type === 'merchant' ? `org-${owner}` : null, status: 'active' }
}

function managerScope(owner = 'a'): StoreAccessScope {
  return {
    organizationId: `org-${owner}`, organizationName: `${owner}主体`, organizationStatus: 'active',
    storeId: `store-${owner}`, storeName: `${owner}店`,
    storeStatus: 'active', role: 'manager', permissionTier: 'finance_transaction',
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => { resolve = res })
  return { promise, resolve }
}

function fakeGrassland(options: {
  identities?: IdentityProfile[] | null
  scopes?: StoreAccessScope[]
  organizations?: Array<Record<string, unknown>> | null
  serverActive?: 'merchant' | 'recommender' | null
  hangIdentities?: boolean
}) {
  const calls: string[] = []
  const identitiesDeferred = deferred<IdentityProfile[] | null>()
  let currentIdentities: IdentityProfile[] | null =
    options.identities === null ? null : options.identities ?? []
  const api = {
    listIdentities: vi.fn(async () => {
      calls.push('list-identities')
      if (options.hangIdentities) return identitiesDeferred.promise
      return currentIdentities
    }),
    listMyStoreScopes: vi.fn(async () => { calls.push('list-store-scopes'); return options.scopes ?? [] }),
    listOrganizations: vi.fn(async () => {
      calls.push('list-organizations')
      return options.organizations === null ? null : options.organizations ?? []
    }),
    openIdentity: vi.fn(async (type: string) => {
      calls.push(`open:${type}`)
      if (type === 'recommender') currentIdentities = [identity('recommender')]
      return { ok: true, type }
    }),
    activateIdentity: vi.fn(async (type: string) => {
      calls.push(`activate:${type}`)
      return { ok: true, type }
    }),
    getActiveIdentity: vi.fn(async () => {
      calls.push('get-active')
      return { activeIdentityType: options.serverActive ?? null }
    }),
    clearError: vi.fn(),
  }
  return { api: api as unknown as ReturnType<typeof useGrassland>, calls, identitiesDeferred }
}

/** 每用例独立 Pinia + 指定账号就位（会话票据先行建立）。 */
function setupUser(user: AuthUser | null) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const session = useAccountSessionStore()
  if (user) auth.currentUser = user
  return { auth, session }
}

beforeEach(() => {
  useActiveIdentity().reset()
})

describe('ensureAccountIdentity 并发与计数（TC79-03A）', () => {
  test('E01：匿名不 bootstrap，零身份 I/O', async () => {
    setupUser(null)
    const { api, calls } = fakeGrassland({ identities: [] })
    await expect(ensureAccountIdentity(api)).resolves.toBeNull()
    expect(calls).toEqual([])
  })

  test('E03：双消费方并发共用同一 pending；完成后第三个消费方直接拿快照', async () => {
    setupUser(userA)
    const { api, calls } = fakeGrassland({ identities: [identity('merchant')] })
    const first = ensureAccountIdentity(api)
    const second = ensureAccountIdentity(api)
    const [snapshotOne, snapshotTwo] = await Promise.all([first, second])
    expect(snapshotOne).not.toBeNull()
    expect(snapshotTwo).toBe(snapshotOne)
    expect(calls.filter((call) => call === 'list-identities')).toHaveLength(1)

    const third = await ensureAccountIdentity(api)
    expect(third).toBe(snapshotOne)
    expect(calls.filter((call) => call === 'list-identities')).toHaveLength(1)
  })

  test('E14/§6.6：裸账号兜底在双消费方下也只 open 一次、身份重拉至多一次', async () => {
    setupUser(userA)
    const { api, calls } = fakeGrassland({ identities: [], organizations: [] })
    await Promise.all([ensureAccountIdentity(api), ensureAccountIdentity(api)])
    expect(calls.filter((call) => call === 'open:recommender')).toHaveLength(1)
    expect(calls.filter((call) => call === 'get-active')).toHaveLength(1)
    expect(calls.filter((call) => call === 'activate:recommender')).toHaveLength(1)
    expect(useActiveIdentity().activeSide.value).toBe('recommender')
  })

  test('E10：服务器活动身份优先，双身份不重激活（§6.6：服务器活动身份读取一次）', async () => {
    setupUser(userA)
    const { api, calls } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      serverActive: 'recommender',
    })
    const snapshot = await ensureAccountIdentity(api)
    expect(snapshot?.identities).toHaveLength(2)
    expect(useActiveIdentity().activeSide.value).toBe('recommender')
    expect(calls.filter((call) => call.startsWith('activate:'))).toEqual([])
    expect(calls.filter((call) => call === 'get-active')).toHaveLength(1)
  })

  test('E17：零档案+manager scope → 商家视角本地生效，不 POST 激活、不开户', async () => {
    setupUser(userA)
    const { api, calls } = fakeGrassland({ identities: [], scopes: [managerScope()] })
    await ensureAccountIdentity(api)
    expect(useActiveIdentity().merchantViewViaManagerScope.value).toBe(true)
    expect(calls.filter((call) => call.startsWith('activate:') || call.startsWith('open:'))).toEqual([])
  })

  test('零档案+组织归属（池成员）：不兜底开户，保持商家视角', async () => {
    setupUser(userA)
    const { api, calls } = fakeGrassland({ identities: [], organizations: [{ id: 'org-a', name: '甲主体' }] })
    await ensureAccountIdentity(api)
    expect(calls).not.toContain('open:recommender')
    expect(useActiveIdentity().activeSide.value).toBe('merchant')
  })
})

describe('ensureAccountIdentity 失效与重试（TC79-03B）', () => {
  test('E04：装载失败返回 null 且可显式重试（第二次重新发起）', async () => {
    setupUser(userA)
    const failing = fakeGrassland({ identities: null })
    await expect(ensureAccountIdentity(failing.api)).resolves.toBeNull()
    expect(useActiveIdentity().identitiesLoaded.value).toBe(false)

    // 服务恢复后重试成功
    const recovered = fakeGrassland({ identities: [identity('recommender')] })
    const snapshot = await ensureAccountIdentity(recovered.api)
    expect(snapshot?.identities).toHaveLength(1)
  })

  test('E11：A 挂起 → 切 B → 释放 A：A 的快照与身份表写入全部丢弃，B 只见自己的结果', async () => {
    const { auth } = setupUser(userA)
    const { api, calls, identitiesDeferred } = fakeGrassland({ identities: [identity('merchant', 'a')], hangIdentities: true })
    const aBootstrap = ensureAccountIdentity(api)

    auth.currentUser = userB
    const bApi = fakeGrassland({ identities: [identity('recommender', 'b')] })
    const bSnapshot = await ensureAccountIdentity(bApi.api)
    expect(bSnapshot?.identities[0]?.identityType).toBe('recommender')
    expect(useActiveIdentity().identities.value.map((item) => item.identityType)).toEqual(['recommender'])

    // 释放 A 的迟到响应：不写身份表、不再发 A 的 open/activate
    identitiesDeferred.resolve([identity('merchant', 'a')])
    await expect(aBootstrap).resolves.toBeNull()
    expect(useActiveIdentity().identities.value.map((item) => item.identityType)).toEqual(['recommender'])
    expect(calls.filter((call) => call.startsWith('activate:') || call.startsWith('open:'))).toEqual([])
  })

  test('E11（A→B→A）：回到 A 是新代次，不复用旧快照、重新装载', async () => {
    const { auth } = setupUser(userA)
    const first = fakeGrassland({ identities: [identity('merchant', 'a')] })
    const firstSnapshot = await ensureAccountIdentity(first.api)
    expect(firstSnapshot).not.toBeNull()

    auth.currentUser = userB
    auth.currentUser = userA
    const second = fakeGrassland({ identities: [identity('merchant', 'a'), identity('recommender', 'a')] })
    const secondSnapshot = await ensureAccountIdentity(second.api)
    expect(secondSnapshot).not.toBe(firstSnapshot)
    expect(secondSnapshot?.identities).toHaveLength(2)
    expect(second.calls.filter((call) => call === 'list-identities')).toHaveLength(1)
  })

  test('E12：一个消费方「卸载」不影响其他消费方等待中的同账号 bootstrap', async () => {
    setupUser(userA)
    const { api, identitiesDeferred } = fakeGrassland({ identities: [identity('merchant')], hangIdentities: true })
    const first = ensureAccountIdentity(api)
    const second = ensureAccountIdentity(api)
    // 模拟第一个消费方卸载（scope stop）：pending 归 Pinia，不因消费方消失而取消
    identitiesDeferred.resolve([identity('merchant')])
    const [firstResult, secondResult] = await Promise.all([first, second])
    expect(firstResult).not.toBeNull()
    expect(secondResult).toBe(firstResult)
  })

  test('E13：服务器返回未知 side 不视为合法激活侧，按档案默认走激活', async () => {
    setupUser(userA)
    const { api, calls } = fakeGrassland({
      identities: [identity('merchant'), identity('recommender')],
      serverActive: 'unknown' as unknown as 'merchant' | 'recommender' | null,
    })
    await ensureAccountIdentity(api)
    expect(useActiveIdentity().activeSide.value).toBe('merchant')
    expect(calls).toContain('activate:merchant')
  })
})
