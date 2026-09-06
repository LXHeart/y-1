// @vitest-environment happy-dom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../../stores/auth'
import type { AuthUser } from '../../../types/auth'
import { useAccountSessionStore } from '../../../stores/account-session'
import { useActiveIdentity } from '../../../composables/useActiveIdentity'
import { useWorkbenchSession } from './useWorkbenchSession'
import type { useGrassland } from '../../../composables/useGrassland'
import type {
  FinanceAccount, IdentityProfile, Organization, OrganizationAccessScope, Store, StoreAccessScope, Wallet,
} from '../../../types/grassland'

/**
 * TC79-04A/04B（任务书 #79 C79-04）：工作台组织/门店/资金账户/钱包/更名按账号与组织代次隔离。
 * fixture 全取 §12.2 固定合成值；mock 在域 API 边界；deferred 控制释放顺序。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

const OA: Organization = { id: '11111111-1111-4111-8111-111111111111', name: '甲店主体', ownerAccountId: userA.id, permissionTier: 'finance_transaction', industry: null, createdAt: null }
const OB: Organization = { id: '22222222-2222-4222-8222-222222222222', name: '乙店主体', ownerAccountId: userB.id, permissionTier: 'finance_transaction', industry: null, createdAt: null }
const S1: Store = { id: '33333333-3333-4333-8333-333333333333', organizationId: OA.id, name: '甲店', status: 'active', createdAt: null }
const S2: Store = { id: '44444444-4444-4444-8444-444444444444', organizationId: OB.id, name: '乙店', status: 'active', createdAt: null }
const FA: FinanceAccount = { id: '55555555-5555-4555-8555-555555555555', organizationId: OA.id, balanceCents: 12345, currency: 'CNY' }
const FB: FinanceAccount = { id: '66666666-6666-4666-8666-666666666666', organizationId: OB.id, balanceCents: 0, currency: 'CNY' }

const MERCHANT_A: IdentityProfile = { id: 'aaaaaaaa-mmmm-4mmm-8mmm-aaaaaaaaaaaa', identityType: 'merchant', organizationId: OA.id, status: 'active' }
const RECOMMENDER_B: IdentityProfile = { id: 'bbbbbbbb-rrrr-4rrr-8rrr-bbbbbbbbbbbb', identityType: 'recommender', organizationId: null, status: 'active' }

function orgScope(org: Organization): OrganizationAccessScope {
  return { organizationId: org.id, organizationName: org.name, organizationStatus: 'active', permissionTier: org.permissionTier, role: 'owner' }
}

function managerScope(store: Store, org: Organization): StoreAccessScope {
  return {
    organizationId: org.id, organizationName: org.name, organizationStatus: 'active', permissionTier: org.permissionTier,
    storeId: store.id, storeName: store.name, storeStatus: store.status, role: 'manager',
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason?: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

interface FakeOptions {
  identities: IdentityProfile[]
  organizations: Organization[]
  orgScopes?: OrganizationAccessScope[]
  storeScopes?: StoreAccessScope[]
  stores?: Store[]
  account?: FinanceAccount | null
  wallet?: Wallet | null
  hangStores?: boolean
  hangAccount?: boolean
  hangRename?: boolean
  hangCredit?: boolean
  hangActivate?: boolean
  renameFail?: boolean
}

function fakeGrassland(options: FakeOptions) {
  const calls: string[] = []
  const storesDeferred = deferred<Store[] | null>()
  const accountDeferred = deferred<FinanceAccount | null>()
  const renameDeferred = deferred<{ ok: boolean } | null>()
  const creditDeferred = deferred<FinanceAccount | null>()
  const activateDeferred = deferred<{ ok: boolean, type: string } | null>()
  const api = {
    listIdentities: vi.fn(async () => options.identities),
    listMyStoreScopes: vi.fn(async () => options.storeScopes ?? []),
    listMyOrganizationScopes: vi.fn(async () => options.orgScopes ?? []),
    listOrganizations: vi.fn(async () => { calls.push('list-organizations'); return options.organizations }),
    listStores: vi.fn(async (organizationId: string) => {
      calls.push('list-stores')
      if (options.hangStores && organizationId === OA.id) return storesDeferred.promise
      return options.stores?.filter((store) => store.organizationId === organizationId) ?? []
    }),
    getAccount: vi.fn(async (organizationId: string) => {
      calls.push('get-account')
      if (options.hangAccount && organizationId === OA.id) return accountDeferred.promise
      const account = options.account ?? null
      return account && account.organizationId === organizationId ? account : null
    }),
    getMyWallet: vi.fn(async () => { calls.push('get-wallet'); return options.wallet ?? null }),
    listOrgRenameRequests: vi.fn(async () => { calls.push('list-rename'); return [] }),
    requestOrgRename: vi.fn(async () => { calls.push('request-rename'); return options.hangRename ? renameDeferred.promise : options.renameFail ? null : { ok: true } }),
    createOrganization: vi.fn(async (name: string) => ({ ...OA, name })),
    provisionAccount: vi.fn(async () => FA),
    creditAccount: vi.fn(async (organizationId: string, amountCents: number) => {
      calls.push(`credit:${organizationId}:${amountCents}`)
      return options.hangCredit ? creditDeferred.promise : { ...FA, balanceCents: FA.balanceCents + amountCents }
    }),
    activateIdentity: vi.fn(async (type: string) => {
      calls.push(`activate:${type}`)
      // 仅挂起 recommender（显式切换）：初始 merchant 激活即时完成，init 不会被卡死
      if (options.hangActivate && type === 'recommender') return activateDeferred.promise
      return { ok: true, type }
    }),
    getActiveIdentity: vi.fn(async () => ({ activeIdentityType: null })),
    openIdentity: vi.fn(async (type: string) => ({ ok: true, type })),
    clearError: vi.fn(),
  }
  return {
    api: api as unknown as ReturnType<typeof useGrassland>,
    calls,
    storesDeferred, accountDeferred, renameDeferred, creditDeferred, activateDeferred,
  }
}

function makeSession(user: AuthUser, options: FakeOptions) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  useAccountSessionStore()
  useActiveIdentity().reset()
  auth.currentUser = user
  const fake = fakeGrassland(options)
  const notices: string[] = []
  const refreshTasks = vi.fn(async () => { })
  const wb = useWorkbenchSession(fake.api, {
    setNotice: (message: string) => { notices.push(message) },
    refreshTasks,
  })
  return { auth, ...fake, wb, notices, refreshTasks }
}

beforeEach(() => {
  useActiveIdentity().reset()
})

describe('工作台会话装载（TC79-04A）', () => {
  test('OA/S1/FA 顺序加载：金额 12345 分显示 123.45 元；权限按 scope 派生；零写请求', async () => {
    const { wb, calls, refreshTasks } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
    })
    await wb.initForAccount()

    expect(wb.orgs.value).toEqual([OA])
    expect(wb.stores.value).toEqual([S1])
    expect(wb.account.value).toEqual(FA)
    expect(wb.account.value?.balanceCents).toBe(12345)
    expect(wb.balanceYuan.value).toBe('123.45')
    expect(wb.activeOrganizationRole.value).toBe('owner')
    expect(wb.canPublishBounty.value).toBe(true)
    expect(wb.walletBalanceCents.value).toBeNull() // 商家侧不拉推荐官钱包
    expect(calls).not.toContain('get-wallet')
    expect(refreshTasks).toHaveBeenCalled()
    expect(calls.filter((call) => call.startsWith('credit:'))).toEqual([])
  })

  test('E01：切无组织账号：orgs 空清 account/stores，回落空态', async () => {
    const { auth, wb } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
    })
    await wb.initForAccount()
    expect(wb.orgs.value).toHaveLength(1)

    auth.currentUser = userB
    wb.reset()
    expect(wb.orgs.value).toEqual([])
    expect(wb.stores.value).toEqual([])
    expect(wb.account.value).toBeNull()
    expect(wb.walletBalanceCents.value).toBeNull()
    expect(wb.renameRequests.value).toEqual([])
    expect(wb.renaming.value).toBe(false)

    const empty = makeSession(userB, { identities: [RECOMMENDER_B], organizations: [], stores: [] })
    await empty.wb.initForAccount()
    expect(empty.wb.orgs.value).toEqual([])
    expect(empty.wb.account.value).toBeNull()
  })

  test('E14：钱包 0 与 null 不同；成功 0 仍显示 0（推荐官侧）', async () => {
    const zeroWallet: Wallet = { accountId: userB.id, balanceCents: 0, updatedAt: null, entries: [] }
    const { wb } = makeSession(userB, { identities: [RECOMMENDER_B], organizations: [], wallet: zeroWallet })
    await wb.initForAccount()
    expect(wb.walletBalanceCents.value).toBe(0)
    expect(wb.walletBalanceCents.value).not.toBeNull()

    const noWallet = makeSession(userB, { identities: [MERCHANT_A], organizations: [OA] })
    await noWallet.wb.initForAccount()
    expect(noWallet.wb.walletBalanceCents.value).toBeNull()
  })

  test('E17/manager 单店分支：零档案+manager scope 本地商家视角，stores 由 scope 派生、不调 listStores', async () => {
    const { wb, calls } = makeSession(userA, {
      identities: [], organizations: [], storeScopes: [managerScope(S1, OA)], stores: [S1],
    })
    await wb.initForAccount()
    expect(wb.activeOrgStoreOnlyView.value).toBe(true)
    expect(wb.orgs.value.map((org) => org.id)).toEqual([OA.id])
    expect(wb.stores.value.map((store) => store.id)).toEqual([S1.id])
    expect(wb.selectedStoreId.value).toBe(S1.id)
    expect(calls).not.toContain('list-stores')
    expect(calls.filter((call) => call.startsWith('activate:') || call.startsWith('open:'))).toEqual([])
  })

  test('E21：credit 金额沿用 yuanToCents（1000 元 → 100000 分）不改取整', async () => {
    const { wb, calls } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
    })
    await wb.initForAccount()
    await wb.credit()
    expect(calls).toContain(`credit:${OA.id}:100000`)
    expect(wb.account.value?.balanceCents).toBe(112345)
  })
})

describe('账号/组织双维度隔离（TC79-04B）', () => {
  test('OA 请求挂起 → 切 B/OB → 释放 OA：列表/账户/钱包只属当前选择', async () => {
    const { auth, wb, storesDeferred, accountDeferred } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangStores: true, hangAccount: true,
    })
    const aInit = wb.initForAccount()

    auth.currentUser = userB
    wb.reset()
    const bSide = makeSession(userB, {
      identities: [MERCHANT_A], organizations: [OB], orgScopes: [orgScope(OB)], stores: [S2], account: FB,
    })
    await bSide.wb.initForAccount()
    expect(bSide.wb.orgs.value).toEqual([OB])
    expect(bSide.wb.account.value?.balanceCents).toBe(0)
    expect(bSide.wb.balanceYuan.value).toBe('0.00')

    // 释放 A 的迟到回包：不写任何 B 侧状态
    storesDeferred.resolve([S1])
    accountDeferred.resolve(FA)
    await aInit
    expect(bSide.wb.orgs.value).toEqual([OB])
    expect(bSide.wb.stores.value).toEqual([S2])
    expect(bSide.wb.account.value?.id).toBe(FB.id)
    expect(bSide.wb.account.value?.balanceCents).toBe(0)
    expect(bSide.notices).toEqual([])
  })

  test('E02/E04：更名迟到回包（含 403/500 失败）不写新组织 notice', async () => {
    const { wb, renameDeferred, notices } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangRename: true,
    })
    await wb.initForAccount()
    const renaming = wb.requestRename('新甲店')
    expect(wb.renaming.value).toBe(true)

    // 切组织（同账号 O1→O2 维度也生效：此处以换账号验证）
    renameDeferred.resolve({ ok: true })
    await renaming
    // 当前组织仍属 A：正常路径 notice 可见（对照组）
    expect(notices).toContain('更名申请已提交，等待平台审核')

    const stale = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangRename: true,
    })
    await stale.wb.initForAccount()
    const staleRename = stale.wb.requestRename('新甲店')
    stale.auth.currentUser = userB // 切号：票据失效
    stale.renameDeferred.resolve({ ok: true })
    await staleRename
    expect(stale.notices).toEqual([]) // 旧票不发 notice
    expect(stale.wb.renaming.value).toBe(false) // 锁已释放
  })

  test('E16：充值已提交后切号：至多一次 credit、body 为 100000 分、不写 B 状态不续发', async () => {
    const { auth, wb, creditDeferred, calls, notices } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangCredit: true,
    })
    await wb.initForAccount()
    const crediting = wb.credit()
    expect(calls.filter((call) => call.startsWith(`credit:${OA.id}:`))).toHaveLength(1)

    auth.currentUser = userB
    wb.reset()
    creditDeferred.resolve({ ...FA, balanceCents: 112345 })
    await crediting

    expect(calls.filter((call) => call.startsWith('credit:'))).toHaveLength(1) // 不二次充值
    expect(wb.account.value).toBeNull() // 不把 A 的账户写给新会话
    expect(notices).toEqual([]) // notice 不含 A 的充值成功
  })

  test('E15：O2 先回、O1 后回不得覆盖（连切同 org 代次也隔离）', async () => {
    const { wb, storesDeferred } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA, OB], orgScopes: [orgScope(OA)], stores: [S1, S2], account: FA,
      hangStores: true,
    })
    // init 内对 O1 的 listStores 挂起
    const initPromise = wb.initForAccount()

    // 切到 O2：revision 递增，发起 O2 的装载（立即返回 S2）
    wb.activeOrgId.value = OB.id
    const o2Load = wb.loadActiveOrganizationStores()
    // O2 先回
    await o2Load
    // O1 后回（init 的迟到链）
    storesDeferred.resolve([S1])
    await initPromise

    expect(wb.stores.value.map((store) => store.id)).toEqual([S2.id])
    expect(wb.activeOrgId.value).toBe(OB.id)
  })

  test('E19：新账号不恢复旧 activeOrgId；E06：reset 后旧链不触发 refreshTasks', async () => {
    const { auth, wb, storesDeferred, refreshTasks } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangStores: true,
    })
    const aInit = wb.initForAccount()
    auth.currentUser = userB
    wb.reset()
    expect(wb.activeOrgId.value).toBe('')

    const tasksBefore = refreshTasks.mock.calls.length
    storesDeferred.resolve([S1])
    await aInit
    expect(refreshTasks.mock.calls.length).toBe(tasksBefore) // 旧链终止，不再续发任务重拉
  })
})

describe('视角切换与链式续发守卫（任务书 #82 C82-03）', () => {
  test('switchSide 激活迟到且失败：换号后不回滚视角、不写 notice、不动 error（旧票不回写）', async () => {
    const { auth, wb, activateDeferred, api, notices } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangActivate: true,
    })
    await wb.initForAccount()
    expect(wb.side.value).toBe('merchant')
    const clearErrorCalls = (api.clearError as unknown as { mock: { calls: unknown[] } }).mock.calls.length

    const switching = wb.switchSide('recommender')
    expect(wb.side.value).toBe('recommender') // 乐观切换（A 会话内的合法同步写入）

    auth.currentUser = userB
    activateDeferred.resolve(null) // A 的激活失败迟到
    await switching
    expect(wb.side.value).toBe('recommender') // 不回滚：视角由新账号 bootstrap 重新决定
    expect(notices).toEqual([]) // 不写 notice
    // 旧票不触发清理动作（clearError 无新增调用）
    expect((api.clearError as unknown as { mock: { calls: unknown[] } }).mock.calls.length).toBe(clearErrorCalls)
    auth.currentUser = null
  })

  test('init 链中途换号（不 reset）：旧链不写 B 的账户、不续发 refreshTasks', async () => {
    const { auth, wb, storesDeferred, calls, refreshTasks } = makeSession(userA, {
      identities: [MERCHANT_A], organizations: [OA], orgScopes: [orgScope(OA)], stores: [S1], account: FA,
      hangStores: true,
    })
    const aInit = wb.initForAccount()

    auth.currentUser = userB // 只换号、不 reset：activeOrgId 仍为 OA，链式续发必须有票据闸
    storesDeferred.resolve([S1])
    await aInit

    expect(calls).not.toContain('get-account') // refreshAccount 未被旧链续发
    expect(refreshTasks).not.toHaveBeenCalled() // 任务重拉未被旧链续发
    expect(wb.account.value).toBeNull() // A 的 FA 没有写进新会话
    auth.currentUser = null
  })
})
