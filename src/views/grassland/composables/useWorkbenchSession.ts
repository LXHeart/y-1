import { computed, ref, watch } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { ensureAccountIdentity } from '../../../composables/useAccountBootstrap'
import { useActiveIdentity, type IdentitySide } from '../../../composables/useActiveIdentity'
import { useAccountSessionStore, type AccountTicket } from '../../../stores/account-session'
import { yuanToCents } from '../../../lib/money'
import type {
  FinanceAccount,
  OrganizationRenameRequest,
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
 *
 * 任务书 #79 C79-04：组织/门店/资金账户/钱包及更名状态按「账号 + 组织代次」双重隔离——
 * 旧账号或旧组织的迟到响应（含 catch/finally/notice/续发请求）一律静默终止。
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
  const session = useAccountSessionStore()

  /**
   * 活动身份是全局状态（PRD §11.3）：账号菜单切换、主导航标签与工作台视角共用，
   * 由 {@link useActiveIdentity} 单例持有。本 composable 只在其上叠加工作台侧效应。
   */
  const {
    activeSide: side,
    hasMerchantIdentity, hasRecommenderIdentity,
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
  /** 主体更名申请（V40：审核生效+30 天冷却）；[0] 为待审（至多一条）。 */
  const renameRequests = ref<OrganizationRenameRequest[]>([])
  const pendingRename = computed(() => renameRequests.value.find((r) => r.status === 'pending') ?? null)
  const renaming = ref(false)

  /**
   * 组织代次（任务书 #79 C79-04，D79-04）：同账号内每次组织选择变化递增——
   * 连切同组织（O1→O2→O1）也产生新代次，旧代次的迟到结果不得覆盖新选择（E15/E03）。
   */
  let organizationRevision = 0
  watch(activeOrgId, () => { organizationRevision += 1 }, { flush: 'sync' })

  /** 同账号组织上下文快照：仅内存，用于组织维度的双重匹配。 */
  function captureOrganization(): { ticket: AccountTicket; organizationId: string; revision: number } {
    return { ticket: session.capture(), organizationId: activeOrgId.value, revision: organizationRevision }
  }

  function isCurrentOrganization(captured: { ticket: AccountTicket; organizationId: string; revision: number }): boolean {
    return session.isCurrent(captured.ticket)
      && captured.organizationId === activeOrgId.value
      && captured.revision === organizationRevision
  }

  async function loadRenameRequests(): Promise<void> {
    if (!activeOrgId.value) {
      renameRequests.value = []
      return
    }
    const captured = captureOrganization()
    const list = await grassland.listOrgRenameRequests(activeOrgId.value)
    if (!isCurrentOrganization(captured)) return
    if (Array.isArray(list)) renameRequests.value = list as OrganizationRenameRequest[]
  }

  async function requestRename(name: string): Promise<void> {
    if (!activeOrgId.value || !name.trim() || renaming.value) return
    const captured = captureOrganization()
    renaming.value = true
    const ok = await grassland.requestOrgRename(activeOrgId.value, name.trim())
    // renaming 无并发拥有者（入口即闸），迟到的旧回包也要释放自己占的锁
    renaming.value = false
    if (!isCurrentOrganization(captured)) return
    if (ok === null) return  // 冷却/重复等错误走 grassland.error
    setNotice('更名申请已提交，等待平台审核')
    await loadRenameRequests()
  }

  const activeOrg = computed(() => orgs.value.find((o) => o.id === activeOrgId.value) || null)
  const managerStoreScopes = computed(() => storeScopes.value.filter((scope) => scope.role === 'manager'))
  const activeOrgHasOrganizationAccess = computed(() => organizationAccessIds.value.has(activeOrgId.value))

  /**
   * #52 决策 H（工作台路由）：入池后人人有组织身份，视图改按「组织角色 + 门店范围」分流——
   * owner/admin 与纯池内 member → 主体工作台；挂店 member（有 manager 门店范围）→ 门店工作台
   * （StoreStaffCard）。staff 范围的成员本就进不了商家侧（managerStoreScopes 才开通身份），不涉此分支。
   */
  const activeOrgStoreOnlyView = computed(() => {
    const role = activeOrganizationRole.value
    if (role === 'owner' || role === 'admin') return false
    if (!activeOrgId.value) return false
    return managerStoreScopes.value.some((scope) => scope.organizationId === activeOrgId.value)
  })
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
    const ticket = session.capture()
    const [organizationResult, scopeResult, organizationScopeResult] = await Promise.all([
      knownOrganizations ?? grassland.listOrganizations(),
      knownStoreScopes ?? grassland.listMyStoreScopes(),
      knownOrganizationScopes ?? grassland.listMyOrganizationScopes(),
    ])
    // 旧账号的迟到组织数据不得进入新会话（含本函数自身会调整 activeOrgId，故此处只验账号票）
    if (!session.isCurrent(ticket)) return
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
    // 每步之间验票（任务书 #82 C82-03）：旧账号的链不得对当前账号续发任务/账户请求。
    if (activeOrgId.value) {
      await loadActiveOrganizationStores()
      if (!session.isCurrent(ticket)) return
      await refreshAccount()
      if (!session.isCurrent(ticket)) return
      void loadRenameRequests()
      await refreshTasks()
    }
  }

  async function loadActiveOrganizationStores(): Promise<void> {
    if (!activeOrgId.value) {
      stores.value = []
      selectedStoreId.value = ''
      return
    }
    const captured = captureOrganization()
    if (!activeOrgStoreOnlyView.value) {
      // owner/admin 与纯池内 member：全量门店（listStores 门禁 MEMBER+，池内成员恰好满足）
      const list = await grassland.listStores(activeOrgId.value)
      if (!isCurrentOrganization(captured)) return
      stores.value = list ?? []
    } else {
      const scoped = managerStoreScopes.value
        .filter((scope) => scope.organizationId === activeOrgId.value)
        .map((scope) => ({
          id: scope.storeId,
          organizationId: scope.organizationId,
          name: scope.storeName,
          status: scope.storeStatus,
          createdAt: null,
        }))
      if (!isCurrentOrganization(captured)) return
      stores.value = scoped
    }
    if (activeOrgStoreOnlyView.value
        && !stores.value.some((store) => store.id === selectedStoreId.value)) {
      selectedStoreId.value = stores.value[0]?.id ?? ''
    }
  }

  /**
   * 初始化 = 全局活动身份装载（身份 + 门店范围 + 初始激活，见 useActiveIdentity）
   * + 工作台自己的组织/资金/钱包装载。
   * 任务书 #79 C79-03：身份段等待唯一 bootstrap（ensureAccountIdentity）——与布局层
   * 共用同一份 pending/快照，不再独立 loadAccountIdentity 重复请求。
   * 任务书 #79 C79-04：快照进入后再次核对 owner，组织/钱包段全部验票。
   */
  async function initForAccount(): Promise<void> {
    const ticket = session.capture()
    const boot = await ensureAccountIdentity(grassland)
    if (boot === null) return
    if (!session.isCurrent(ticket)) return

    storeScopes.value = boot.storeScopes
    const [organizations, organizationScopes] = await Promise.all([
      grassland.listOrganizations(),
      grassland.listMyOrganizationScopes(),
    ])
    if (!session.isCurrent(ticket)) return
    await loadOrganizations(
      Array.isArray(organizations) ? organizations : [],
      boot.storeScopes,
      Array.isArray(organizationScopes) ? organizationScopes : [],
    )
    // 任务书 #22：推荐官侧加载钱包余额，供任务大厅对霸王餐押金任务做报名软提示（不阻断）。
    walletBalanceCents.value = null
    if (boot.identities.some((identity) => identity.identityType === 'recommender')) {
      const walletTicket = session.capture()
      void grassland.getMyWallet().then((wallet) => {
        if (!session.isCurrent(walletTicket)) return
        walletBalanceCents.value = wallet ? wallet.balanceCents : 0
      })
    }
  }

  /**
   * 账号切换清空组织/门店/资金等会话字段；全局身份状态由布局的账号 watch 归零（工作台挂载即清曾把登录所选身份翻回默认）。
   * 任务书 #79 C79-04：清空本域**全部** refs——钱包/更名/更名中标记/建组织草稿/充值草稿（§7.2），
   * 不留下上一账号视角的任何可见状态。
   */
  function reset(): void {
    orgs.value = []
    stores.value = []
    storeScopes.value = []
    organizationAccessIds.value = new Set()
    organizationRoles.value = new Map()
    activeOrgId.value = ''
    selectedStoreId.value = ''
    account.value = null
    newOrgName.value = ''
    creditAmountYuan.value = 1000
    walletBalanceCents.value = null
    renameRequests.value = []
    renaming.value = false
  }

  async function createOrg(): Promise<void> {
    if (!newOrgName.value.trim()) return
    const captured = captureOrganization()
    const created = await grassland.createOrganization(newOrgName.value.trim())
    if (!session.isCurrent(captured.ticket)) return
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
    const captured = captureOrganization()
    // 账户可能尚未开通（404）→ 静默，由「开通账户」按钮处理
    const existing = await grassland.getAccount(activeOrgId.value)
    if (!isCurrentOrganization(captured)) return
    account.value = existing
    if (existing) grassland.clearError()
  }

  async function changeOrganization(): Promise<void> {
    // 每步之间验票（任务书 #82 C82-03）：换号/换组织后旧链终止，不向新账号续发 refresh。
    const ticket = session.capture()
    selectedStoreId.value = ''
    await loadActiveOrganizationStores()
    if (!session.isCurrent(ticket)) return
    await refreshAccount()
    if (!session.isCurrent(ticket)) return
    await refreshTasks()
  }

  async function provision(): Promise<void> {
    const captured = captureOrganization()
    const created = await grassland.provisionAccount()
    if (!isCurrentOrganization(captured)) return
    if (!created) return
    account.value = created
    setNotice('资金账户已开通')
  }

  async function credit(): Promise<void> {
    if (!activeOrgId.value) return
    const captured = captureOrganization()
    const updated = await grassland.creditAccount(activeOrgId.value, yuanToCents(creditAmountYuan.value))
    // 充值已在原会话/原事务发出：迟到回包不写新选择、不发 notice、不续发（E16）
    if (!isCurrentOrganization(captured)) return
    if (!updated) return
    account.value = updated
    setNotice(`已充值 ¥${creditAmountYuan.value}`)
  }

  /**
   * 切换视角。活动身份按 session 隔离，必须同步切后端，否则 requireMerchant/requireRecommender 会 403。
   *
   * 关键：**激活失败必须回滚 UI**。此前无论成败都切视角，账号若未开通对应身份，
   * 会出现「UI 显示推荐官、后端仍是商家、所有操作 403」且用户看不出原因（浏览器实测发现）。
   *
   * 2026-09-04 身份模型改版（任务书 #71 D9）：自助开通口子已关（商家=治理台初始化、
   * 推荐官=注册即有/裸账号兜底），「激活 409 → 自动 openIdentity 重试」回退删除——
   * 回退必败，未开通侧一律走既有回滚路径；切侧入口对未开通侧隐藏。
   *
   * 切到商家后的任务重拉不在这里做——工作台组件 watch 全局 side 统一处理，
   * 这样账号菜单发起的切换（不经本函数）同样能刷新任务列表。
   */
  async function switchSide(next: Side): Promise<void> {
    const ticket = session.capture()
    const previous = side.value
    side.value = next

    if (next === 'merchant' && !hasMerchantIdentity.value && managerStoreScopes.value.length > 0) {
      grassland.clearError()
      return
    }

    const activated = await grassland.activateIdentity(next)
    // 旧票不回写（任务书 #82 C82-03）：换号后迟到的激活结果既不保持新视角也不回滚——
    // 视角由新账号的 bootstrap 按其档案重新决定。
    if (!session.isCurrent(ticket)) return
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
    activeOrg, managerStoreScopes, activeOrgHasOrganizationAccess, activeOrgStoreOnlyView, activeOrganizationRole,
    canManageAiBudget, canPublishBounty, balanceYuan,
    loadOrganizations, loadActiveOrganizationStores, initForAccount,
    renameRequests, pendingRename, renaming, loadRenameRequests, requestRename,
    createOrg, refreshAccount, changeOrganization, provision, credit, switchSide, reset,
  }
}
