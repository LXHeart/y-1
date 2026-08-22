import { computed, ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useActiveIdentity, type IdentitySide } from '../../../composables/useActiveIdentity'
import { yuanToCents } from '../../../lib/money'
import type {
  FinanceAccount,
  MembershipRole,
  Organization,
  OrganizationAccessScope,
  Store,
  StoreAccessScope,
} from '../../../types/grassland'

export type Side = IdentitySide

/**
 * 工作台会话域：账号初始化、组织/门店装载、身份视角切换、商家资金账户。
 *
 * 从 GrasslandWorkbench.vue 迁出；活动身份（side）改为全局单例（useActiveIdentity），
 * 账号菜单切换与主导航标签共享同一状态。两个刻意设计随迁：
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

  /**
   * 活动身份是全局状态（PRD §11.3）：账号菜单切换、主导航标签与工作台视角共用，
   * 由 {@link useActiveIdentity} 单例持有。本 composable 只在其上叠加工作台侧效应。
   */
  const {
    activeSide: side,
    hasMerchantIdentity, hasRecommenderIdentity,
    loadAccountIdentity,
  } = useActiveIdentity()
  const orgs = ref<Organization[]>([])
  const stores = ref<Store[]>([])
  const storeScopes = ref<StoreAccessScope[]>([])
  const organizationAccessIds = ref<Set<string>>(new Set())
  const organizationRoles = ref<Map<string, MembershipRole>>(new Map())
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
   * 初始化 = 全局活动身份装载（身份 + 门店范围 + 初始激活，见 useActiveIdentity）
   * + 工作台自己的组织/资金/钱包装载。身份段与布局层共享同一实现与结果。
   */
  async function initForAccount(): Promise<void> {
    const boot = await loadAccountIdentity(grassland)
    if (boot === null) return

    storeScopes.value = boot.storeScopes
    const [organizations, organizationScopes] = await Promise.all([
      grassland.listOrganizations(),
      grassland.listMyOrganizationScopes(),
    ])
    await loadOrganizations(
      Array.isArray(organizations) ? organizations : [],
      boot.storeScopes,
      Array.isArray(organizationScopes) ? organizationScopes : [],
    )
    // 任务书 #22：推荐官侧加载钱包余额，供任务大厅对霸王餐押金任务做报名软提示（不阻断）。
    walletBalanceCents.value = null
    if (boot.identities.some((identity) => identity.identityType === 'recommender')) {
      void grassland.getMyWallet().then((wallet) => {
        walletBalanceCents.value = wallet ? wallet.balanceCents : 0
      })
    }
  }

/** 账号切换清空组织/门店/资金等会话字段；全局身份状态由布局的账号 watch 归零（工作台挂载即清曾把登录所选身份翻回默认）。 */
  function reset(): void {
    orgs.value = []
    stores.value = []
    storeScopes.value = []
    organizationAccessIds.value = new Set()
    organizationRoles.value = new Map()
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
   *
   * 切到商家后的任务重拉不在这里做——工作台组件 watch 全局 side 统一处理，
   * 这样账号菜单发起的切换（不经本函数）同样能刷新任务列表。
   */
  async function switchSide(next: Side): Promise<void> {
    const previous = side.value
    side.value = next

    if (next === 'merchant' && !hasMerchantIdentity.value && managerStoreScopes.value.length > 0) {
      grassland.clearError()
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
  }

  return {
    side, orgs, stores, storeScopes, organizationAccessIds, organizationRoles,
    hasMerchantIdentity, hasRecommenderIdentity, activeOrgId, selectedStoreId, account,
    newOrgName, creditAmountYuan, walletBalanceCents,
    activeOrg, managerStoreScopes, activeOrgHasOrganizationAccess, activeOrganizationRole,
    canManageAiBudget, canPublishBounty, balanceYuan,
    loadOrganizations, loadActiveOrganizationStores, initForAccount,
    createOrg, refreshAccount, changeOrganization, provision, credit, switchSide, reset,
  }
}
