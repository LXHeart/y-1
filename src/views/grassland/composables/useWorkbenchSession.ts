import { computed, ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { yuanToCents } from '../../../lib/money'
import type {
  FinanceAccount,
  MembershipRole,
  Organization,
  OrganizationAccessScope,
  Store,
  StoreAccessScope,
} from '../../../types/grassland'

export type Side = 'merchant' | 'recommender'

/**
 * 工作台会话域：账号初始化、组织/门店装载、身份激活与视角切换、商家资金账户。
 *
 * 从 GrasslandWorkbench.vue 原样迁出（行为不变）。两个刻意设计随迁：
 * - 按**账号**初始化（组件层 watch currentUser 触发 {@link initForAccount}），而非 onMounted——
 *   工作台未登录时就已挂载且切标签页不重挂载，换账号必须重拉并重新激活活动身份。
 * - 换组织/切回商家视角后要重拉任务列表，但任务列表属于履约域（useWorkbenchEngagements），
 *   故以 `refreshTasks` 回调注入：状态自上而下、命令以回调回流，避免两 composable 相互持有。
 */
export function useWorkbenchSession(
  grassland: ReturnType<typeof useGrassland>,
  deps: {
    setNotice: (message: string) => void
    /** 换组织 / 切回商家视角后的任务列表重拉（履约域注入）。 */
    refreshTasks: () => Promise<void>
  },
) {
  const { setNotice, refreshTasks } = deps

  const side = ref<Side>('merchant')
  const orgs = ref<Organization[]>([])
  const stores = ref<Store[]>([])
  const storeScopes = ref<StoreAccessScope[]>([])
  const organizationAccessIds = ref<Set<string>>(new Set())
  const organizationRoles = ref<Map<string, MembershipRole>>(new Map())
  const hasMerchantIdentity = ref(false)
  const activeOrgId = ref('')
  /** Empty means legacy organization-level task scope; otherwise the selected store. */
  const selectedStoreId = ref('')
  const account = ref<FinanceAccount | null>(null)
  const newOrgName = ref('')
  const creditAmountYuan = ref(1000)
  /** 推荐官钱包余额（分，任务书 #22：霸王餐押金任务的报名软提示）；null = 未加载。 */
  const walletBalanceCents = ref<number | null>(null)

  const activeOrg = computed(() => orgs.value.find((o) => o.id === activeOrgId.value) || null)
  const managerStoreScopes = computed(() => storeScopes.value.filter((scope) => scope.role === 'manager'))
  const activeOrgHasOrganizationAccess = computed(() => organizationAccessIds.value.has(activeOrgId.value))
  const activeOrganizationRole = computed(() => organizationRoles.value.get(activeOrgId.value) ?? null)
  const canManageAiBudget = computed(() =>
    activeOrganizationRole.value === 'owner' || activeOrganizationRole.value === 'admin',
  )
  const canPublishBounty = computed(() => activeOrg.value?.permissionTier === 'finance_transaction')
  const balanceYuan = computed(() =>
    account.value ? (account.value.balanceCents / 100).toFixed(2) : '—')

  async function loadOrganizations(
    knownOrganizations?: Organization[], knownStoreScopes?: StoreAccessScope[],
    knownOrganizationScopes?: OrganizationAccessScope[],
  ): Promise<void> {
    const [organizationResult, scopeResult, organizationScopeResult] = await Promise.all([
      knownOrganizations ?? grassland.listOrganizations(),
      knownStoreScopes ?? grassland.listMyStoreScopes(),
      knownOrganizationScopes ?? grassland.listMyOrganizationScopes(),
    ])
    if (organizationResult === null && scopeResult === null && organizationScopeResult === null) return

    const organizationList = Array.isArray(organizationResult) ? organizationResult : []
    storeScopes.value = Array.isArray(scopeResult) ? scopeResult : []
    organizationAccessIds.value = new Set(organizationList.map((organization) => organization.id))
    const organizationScopes = Array.isArray(organizationScopeResult) ? organizationScopeResult : []
    organizationRoles.value = new Map(
      organizationScopes.map((scope) => [scope.organizationId, scope.role]),
    )

    const merged = [...organizationList]
    for (const scope of storeScopes.value.filter((item) => item.role === 'manager')) {
      if (merged.some((organization) => organization.id === scope.organizationId)) continue
      merged.push({
        id: scope.organizationId,
        name: scope.organizationName,
        ownerAccountId: '',
        permissionTier: scope.permissionTier,
        industry: null,
        createdAt: null,
      })
    }
    orgs.value = merged
    if (!merged.some((organization) => organization.id === activeOrgId.value)) {
      activeOrgId.value = merged[0]?.id ?? ''
    }
    // 无条件刷新：此前只在「首次选中组织」时拉数据，导致重新进入草场标签页时
    // 列表仍是旧的（App.vue 用 <component :is> 复用组件，onMounted 不必然重跑，
    // 且期间可能有新任务）。浏览器实测发现：后端 3 个任务、UI 只显示 2 个。
    if (activeOrgId.value) {
      await loadActiveOrganizationStores()
      await refreshAccount()
      await refreshTasks()
    }
  }

  async function loadActiveOrganizationStores(): Promise<void> {
    if (!activeOrgId.value) {
      stores.value = []
      selectedStoreId.value = ''
      return
    }
    if (activeOrgHasOrganizationAccess.value) {
      stores.value = (await grassland.listStores(activeOrgId.value)) ?? []
    } else {
      stores.value = managerStoreScopes.value
        .filter((scope) => scope.organizationId === activeOrgId.value)
        .map((scope) => ({
          id: scope.storeId,
          organizationId: scope.organizationId,
          name: scope.storeName,
          status: scope.storeStatus,
          createdAt: null,
        }))
    }
    if (!activeOrgHasOrganizationAccess.value
        && !stores.value.some((store) => store.id === selectedStoreId.value)) {
      selectedStoreId.value = stores.value[0]?.id ?? ''
    }
  }

  /**
   * 初始化先读取已开通身份，再激活与之对应的当前 session 身份。
   *
   * 推荐官-only 账号若沿用默认 merchant，会产生一个可预期却会出现在浏览器 console 的 409；
   * 无身份账号保留 merchant onboarding 界面，但不因打开工作台暗中开通/激活 merchant。
   */
  async function initForAccount(): Promise<void> {
    // 只激活已开通的身份：推荐官-only 账号不应因默认 merchant 视图收到可预期的 409。
    // merchant 优先保留双身份账号的既有工作台入口；无身份则留在 merchant onboarding，但不暗中开户/激活。
    const [identities, organizations, scopes, organizationScopes] = await Promise.all([
      grassland.listIdentities(),
      grassland.listOrganizations(),
      grassland.listMyStoreScopes(),
      grassland.listMyOrganizationScopes(),
    ])
    if (identities === null) return

    hasMerchantIdentity.value = identities.some((identity) => identity.identityType === 'merchant')
    storeScopes.value = Array.isArray(scopes) ? scopes : []
    const hasManagerScope = storeScopes.value.some((scope) => scope.role === 'manager')
    const initialIdentity = hasMerchantIdentity.value
      ? 'merchant'
      : hasManagerScope
        ? null
        : identities.some((identity) => identity.identityType === 'recommender')
          ? 'recommender'
          : null
    if (hasManagerScope && !hasMerchantIdentity.value) side.value = 'merchant'
    if (initialIdentity) {
      side.value = initialIdentity
      await grassland.activateIdentity(initialIdentity)
      grassland.clearError()  // 已知身份的激活失败由后续具体操作给出更明确的错误
    }
    await loadOrganizations(
      Array.isArray(organizations) ? organizations : [],
      Array.isArray(scopes) ? scopes : [],
      Array.isArray(organizationScopes) ? organizationScopes : [],
    )
    // 任务书 #22：推荐官侧加载钱包余额，供任务大厅对霸王餐押金任务做报名软提示（不阻断）。
    walletBalanceCents.value = null
    if (identities.some((identity) => identity.identityType === 'recommender')) {
      void grassland.getMyWallet().then((wallet) => {
        walletBalanceCents.value = wallet ? wallet.balanceCents : 0
      })
    }
  }

  /** 账号切换清空（原 resetAccountState 的会话字段；side/wallet 刻意不清，与原实现一致）。 */
  function reset(): void {
    orgs.value = []
    stores.value = []
    storeScopes.value = []
    organizationAccessIds.value = new Set()
    organizationRoles.value = new Map()
    hasMerchantIdentity.value = false
    activeOrgId.value = ''
    selectedStoreId.value = ''
    account.value = null
  }

  async function createOrg(): Promise<void> {
    if (!newOrgName.value.trim()) return
    const created = await grassland.createOrganization(newOrgName.value.trim())
    if (!created) return
    newOrgName.value = ''
    setNotice(`组织「${created.name}」已创建（等级 ${created.permissionTier}）`)
    await loadOrganizations()
  }

  async function refreshAccount(): Promise<void> {
    if (!activeOrgId.value || !activeOrgHasOrganizationAccess.value) {
      account.value = null
      return
    }
    // 账户可能尚未开通（404）→ 静默，由「开通账户」按钮处理
    const existing = await grassland.getAccount(activeOrgId.value)
    account.value = existing
    if (existing) grassland.clearError()
  }

  async function changeOrganization(): Promise<void> {
    selectedStoreId.value = ''
    await loadActiveOrganizationStores()
    await refreshAccount()
    await refreshTasks()
  }

  async function provision(): Promise<void> {
    const created = await grassland.provisionAccount()
    if (!created) return
    account.value = created
    setNotice('资金账户已开通')
  }

  async function credit(): Promise<void> {
    if (!activeOrgId.value) return
    const updated = await grassland.creditAccount(activeOrgId.value, yuanToCents(creditAmountYuan.value))
    if (!updated) return
    account.value = updated
    setNotice(`已充值 ¥${creditAmountYuan.value}`)
  }

  /**
   * 切换视角。活动身份按 session 隔离，必须同步切后端，否则 requireMerchant/requireRecommender 会 403。
   *
   * 关键：**激活失败必须回滚 UI**。此前无论成败都切视角，账号若未开通对应身份，
   * 会出现「UI 显示推荐官、后端仍是商家、所有操作 403」且用户看不出原因（浏览器实测发现）。
   * 未开通时后端返回 409，这里自动尝试开通一次（推荐官无需 org，可直接开通）。
   */
  async function switchSide(next: Side): Promise<void> {
    const previous = side.value
    side.value = next

    if (next === 'merchant' && !hasMerchantIdentity.value && managerStoreScopes.value.length > 0) {
      grassland.clearError()
      await refreshTasks()
      return
    }

    let activated = await grassland.activateIdentity(next)
    if (activated === null) {
      // 多半是「未开通该身份」——推荐官不需要 org，可就地开通后重试
      const opened = await grassland.openIdentity(
        next, next === 'merchant' ? activeOrgId.value || undefined : undefined)
      if (opened !== null) {
        activated = await grassland.activateIdentity(next)
      }
    }

    if (activated === null) {
      side.value = previous  // 回滚，避免 UI 与后端身份不一致
      setNotice('')
      return
    }

    grassland.clearError()
    if (next === 'merchant') {
      await refreshTasks()
    }
  }

  return {
    side, orgs, stores, storeScopes, organizationAccessIds, organizationRoles,
    hasMerchantIdentity, activeOrgId, selectedStoreId, account,
    newOrgName, creditAmountYuan, walletBalanceCents,
    activeOrg, managerStoreScopes, activeOrgHasOrganizationAccess, activeOrganizationRole,
    canManageAiBudget, canPublishBounty, balanceYuan,
    loadOrganizations, loadActiveOrganizationStores, initForAccount,
    createOrg, refreshAccount, changeOrganization, provision, credit, switchSide, reset,
  }
}
