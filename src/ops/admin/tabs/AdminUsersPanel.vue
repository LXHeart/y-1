<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AdjustCreditsDialog from '../components/AdjustCreditsDialog.vue'
import MerchantAccountInitDialog from '../components/MerchantAccountInitDialog.vue'
import AdminUserDetailDrawer from '../components/AdminUserDetailDrawer.vue'
import AdminUserSuspendDialog from '../components/AdminUserSuspendDialog.vue'
import AdminUserResetPasswordDialog from '../components/AdminUserResetPasswordDialog.vue'
import OpsPagination from '../components/OpsPagination.vue'
import { DEFAULT_TAB_ROLES, TAB_ROLES } from '../adminTabs'
import { useAuth } from '../../../composables/useAuth'
import { request, GrasslandHttpError } from '../../../composables/grassland-http'
import type { PagedResult } from '../../../types/grassland'
import { formatDate } from '../admin-format'

defineOptions({ name: 'AdminUsersPanel' })

/**
 * 用户管理面板（任务书 #91 A2 自 AdminView.vue 内联分支整段迁出，纯搬运；fetch=onMounted
 * 一次，D-04——切回不重拉，KeepAlive 常驻等价原父级 refs 常驻）。四个管控弹窗 + 详情抽屉随迁。
 */
interface UserItem {
  id: string
  email: string
  displayName: string | null
  role: string
  status: string
  createdAt: string
  balance: number
  totalEarned: number
  totalSpent: number
  /** backend_role dbValue 列表（卡A 起后端已返回）。 */
  roles?: string[]
  /** 身份/组织归属聚合（任务书 #72 卡A）：ownedOrgNames=null 表示未建/非 owner；
   *  ownedOrgs 结构化清单（卡D 连坐，additive）供详情抽屉组织管控分区定位 orgId。 */
  identities?: {
    recommender: boolean
    merchant: boolean
    member: boolean
    ownedOrgNames: string | null
    ownedOrgs?: Array<{ id: string; name: string; status: string }>
  }
}

const { currentUser, hasBackendRole } = useAuth()
/** 管控按钮（调整积分/停用/恢复/重置密码）platform_admin 专属——与页签可见性同源（卡C）。 */
const adminControls = computed(() => !currentUser.value || hasBackendRole('platform_admin'))

/** 状态列徽标：active→success / suspended→danger / 其余（pending_review 等）→warning。 */
const USER_STATUS_META: Record<string, { label: string; badge: string }> = {
  active: { label: '正常', badge: 'badge-success' },
  suspended: { label: '已停用', badge: 'badge-danger' },
  pending_review: { label: '待复核', badge: 'badge-warning' },
  deleted: { label: '已删除', badge: 'badge-warning' },
}
function userStatusMeta(status: string): { label: string; badge: string } {
  return USER_STATUS_META[status] ?? { label: status, badge: 'badge-warning' }
}
/** 五个内置列表每页条数真源（默认 10，OpsPagination 触发 10/20/50/100 切换并归零 offset）。 */
const usersLimit = ref(10)
const users = ref<UserItem[]>([])
const userSearch = ref('')
/** 状态/身份筛选（任务书 #72 卡C）：空串=全部=不传参。 */
const userStatusFilter = ref('')
const userIdentityFilter = ref('')
const usersOffset = ref(0)
const usersTotal = ref(0)
const loading = ref(false)
const loadError = ref('')
let listRequestVersion = 0

const adjustTarget = ref<UserItem | null>(null)
/** 任务书 #94：数量改受控字符串（空串/1.5/NaN 等原样可见，提交时校验），一次意图一个幂等键。 */
const adjustAmount = ref('')
const adjustNote = ref('')
const adjusting = ref(false)
const adjustError = ref('')
const adjustOperationId = ref('')

const ADJUST_AMOUNT_ERROR_ZERO = '数量不能为 0'
const ADJUST_AMOUNT_ERROR_INTEGER = '数量必须为非零整数'
const ADJUST_AMOUNT_ERROR_RANGE = '数量绝对值不能超过 1,000,000'
const ADJUST_AMOUNT_ERRORS = new Set([ADJUST_AMOUNT_ERROR_ZERO, ADJUST_AMOUNT_ERROR_INTEGER, ADJUST_AMOUNT_ERROR_RANGE])

// —— 任务书 #72 卡 D：详情抽屉 + 停用/恢复 + 重置密码弹窗 ——
const detailUser = ref<UserItem | null>(null)
const suspendDialogUser = ref<UserItem | null>(null)
const suspendDialogMode = ref<'suspend' | 'restore'>('suspend')
const resetDialogUser = ref<UserItem | null>(null)
const userActionMessage = ref('')

async function loadUsers(): Promise<void> {
  const version = ++listRequestVersion
  loading.value = true
  loadError.value = ''
  try {
    const query = userSearch.value.trim()
    const params = new URLSearchParams({
      limit: String(usersLimit.value),
      offset: String(usersOffset.value),
    })
    if (query) params.set('q', query)
    if (userStatusFilter.value) params.set('status', userStatusFilter.value)
    if (userIdentityFilter.value) params.set('identityType', userIdentityFilter.value)
    const data = await request<PagedResult<UserItem>>(
      `/api/admin/users?${params.toString()}`,
      {},
      { fallbackError: '加载失败' },
    )
    if (version !== listRequestVersion) return
    users.value = data.items
    usersTotal.value = data.total
  } catch (e: unknown) {
    if (version !== listRequestVersion) return
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    if (version === listRequestVersion) loading.value = false
  }
}

/** 搜索提交：offset 归零后重载。 */
function searchUsers(): void {
  usersOffset.value = 0
  void loadUsers()
}

/** 状态/身份筛选变更：offset 归零重载（「全部」不传参，保持旧调用形态）。 */
function applyUserFilters(): void {
  usersOffset.value = 0
  void loadUsers()
}

function changeUsersPage(offset: number): void {
  usersOffset.value = offset
  void loadUsers()
}

function changeUsersLimit(limit: number): void {
  usersLimit.value = limit
  usersOffset.value = 0
  void loadUsers()
}

/** 打开弹窗即生成幂等键（D94-07）：一次意图一键；提交失败重试复用，成功或关闭即丢弃。 */
function openAdjust(user: UserItem): void {
  adjustTarget.value = user
  adjustAmount.value = ''
  adjustNote.value = ''
  adjustError.value = ''
  adjustOperationId.value = `admin_adjust:${crypto.randomUUID()}`
}

/** 关闭弹窗：丢弃幂等键（下次打开是新的调整意图）。 */
function closeAdjust(): void {
  adjustTarget.value = null
  adjustOperationId.value = ''
}

/** 数量校验（D94-07：非零整数、1 ≤ |amount| ≤ 1_000_000）；不满足返回 null 并写 adjustError。 */
function parseAdjustAmount(): number | null {
  const raw = adjustAmount.value.trim()
  const amount = Number(raw)
  if (raw === '' || amount === 0) {
    adjustError.value = ADJUST_AMOUNT_ERROR_ZERO
    return null
  }
  if (!Number.isFinite(amount) || !Number.isInteger(amount)) {
    adjustError.value = ADJUST_AMOUNT_ERROR_INTEGER
    return null
  }
  if (Math.abs(amount) > 1_000_000) {
    adjustError.value = ADJUST_AMOUNT_ERROR_RANGE
    return null
  }
  return amount
}

/** blur 校验：仅清/写数量类错误，不吞备注与后端错误文案。 */
function onAdjustAmountBlur(): void {
  const amount = parseAdjustAmount()
  if (amount !== null && ADJUST_AMOUNT_ERRORS.has(adjustError.value)) {
    adjustError.value = ''
  }
}

function openUserDetail(user: UserItem): void {
  detailUser.value = user
}

function openUserSuspend(user: UserItem): void {
  suspendDialogMode.value = 'suspend'
  suspendDialogUser.value = user
}

function openUserRestore(user: UserItem): void {
  suspendDialogMode.value = 'restore'
  suspendDialogUser.value = user
}

function openUserResetPassword(user: UserItem): void {
  resetDialogUser.value = user
}

function closeUserDetail(): void {
  detailUser.value = null
}

/** 管控成功统一收口：提示 + 刷新当前页（抽屉若开着则同步行数据，组织状态/角色随之更新）。 */
async function refreshUsersAfterAction(message?: string): Promise<void> {
  if (message) userActionMessage.value = message
  await loadUsers()
  if (detailUser.value) {
    detailUser.value = users.value.find((row) => row.id === detailUser.value?.id) ?? detailUser.value
  }
}

function handleSuspendDone(): void {
  suspendDialogUser.value = null
  void refreshUsersAfterAction(suspendDialogMode.value === 'suspend' ? '账号已停用' : '账号已恢复')
}

function handleResetDone(): void {
  resetDialogUser.value = null
  void refreshUsersAfterAction('密码已重置，请线下交付一次性初始密码')
}

async function handleAdjust(): Promise<void> {
  if (!adjustTarget.value) return
  const amount = parseAdjustAmount()
  if (amount === null) return
  if (!adjustNote.value.trim()) {
    adjustError.value = '请输入备注'
    return
  }

  adjusting.value = true
  adjustError.value = ''

  try {
    await request('/api/admin/adjust-credits', {
      method: 'POST',
      body: JSON.stringify({
        userId: adjustTarget.value.id,
        amount,
        note: adjustNote.value.trim(),
        operationId: adjustOperationId.value,
      }),
    }, { fallbackError: '调整失败' })
    // 成功：关弹窗并丢弃幂等键
    adjustTarget.value = null
    adjustOperationId.value = ''
    await loadUsers()
  } catch (e: unknown) {
    // 失败不关弹窗、键保留：同一次意图重试复用同键（D94-07）；文案按状态码落位
    if (e instanceof GrasslandHttpError && e.status === 402) {
      adjustError.value = '积分余额不足，无法扣减'
    } else if (e instanceof GrasslandHttpError && e.status === 409) {
      adjustError.value = '该次提交已在处理，请刷新后查看余额'
    } else if (e instanceof GrasslandHttpError && e.status === 502) {
      adjustError.value = '积分服务暂不可用，请稍后重试'
    } else {
      adjustError.value = e instanceof Error ? e.message : '调整失败'
    }
  } finally {
    adjusting.value = false
  }
}

/** 任务书 #71：初始化商家账号（商家身份唯一来源=平台初始化，D2/D4/D5）。 */
interface MerchantAccountInitResult {
  userId: string
  email: string
  displayName: string
  initialPassword: string
}
const initDialogOpen = ref(false)
const initEmail = ref('')
const initDisplayName = ref('')
const initEmailError = ref('')
const initError = ref('')
const initSubmitting = ref(false)
const initResult = ref<MerchantAccountInitResult | null>(null)

function openInitDialog(): void {
  initDialogOpen.value = true
  initEmail.value = ''
  initDisplayName.value = ''
  initEmailError.value = ''
  initError.value = ''
  initResult.value = null
}

async function handleInitMerchantAccount(): Promise<void> {
  initEmailError.value = ''
  initError.value = ''
  const email = initEmail.value.trim()
  const displayName = initDisplayName.value.trim()
  if (!email || !email.includes('@')) {
    initEmailError.value = '请填写有效邮箱'
    return
  }
  if (!displayName) {
    initError.value = '请填写姓名'
    return
  }

  initSubmitting.value = true
  try {
    initResult.value = await request<MerchantAccountInitResult>('/api/admin/merchant-accounts', {
      method: 'POST',
      body: JSON.stringify({ email, displayName }),
    }, { fallbackError: '初始化失败' })
  } catch (e: unknown) {
    if (e instanceof GrasslandHttpError && e.status === 409) {
      // 邮箱已注册：字段级错误（仅支持全新邮箱，D5）
      initEmailError.value = e.message || '该邮箱已注册；商家账号仅支持全新邮箱初始化'
    } else {
      initError.value = e instanceof Error ? e.message : '初始化失败'
    }
  } finally {
    initSubmitting.value = false
  }
}

/** 关闭初始化弹窗；已建号（拿到过一次性密码）时刷新用户列表。 */
function closeInitDialog(): void {
  if (initSubmitting.value) return
  const hadResult = initResult.value !== null
  initDialogOpen.value = false
  initResult.value = null
  if (hadResult) void loadUsers()
}

onMounted(() => {
  void loadUsers()
})

/**
 * 冷会话直登治理台（原 AdminView watch(currentUser.id) 补拉语义随迁）：本面板在登录前
 * 挂载会 401，身份从无到有时补拉一次。users 可见性门槛与原实现同源。
 */
function canSeeUsersTab(): boolean {
  if (!currentUser.value) return true
  const roles = TAB_ROLES.users ?? DEFAULT_TAB_ROLES
  return roles.some((role) => hasBackendRole(role))
}
watch(() => currentUser.value?.id, (id, prev) => {
  if (!id || id === prev || !canSeeUsersTab()) return
  void loadUsers()
})
</script>

<template>
  <form class="panel-toolbar search-toolbar" @submit.prevent="searchUsers">
    <!-- 任务书 #72 卡C：状态/身份筛选（全部=不传参，变更即 offset 归零重载）。 -->
    <div class="user-filters">
      <label>状态
        <select v-model="userStatusFilter" class="user-filter-select" data-testid="user-status-filter" @change="applyUserFilters">
          <option value="">全部</option>
          <option value="active">正常</option>
          <option value="suspended">已停用</option>
          <option value="pending_review">待复核</option>
        </select>
      </label>
      <label>身份
        <select v-model="userIdentityFilter" class="user-filter-select" data-testid="user-identity-filter" @change="applyUserFilters">
          <option value="">全部</option>
          <option value="recommender">推荐官</option>
          <option value="merchant">商家</option>
          <option value="member">成员</option>
        </select>
      </label>
    </div>
    <div class="user-search-group">
      <input v-model="userSearch" type="search" maxlength="100" placeholder="搜索邮箱、昵称或账号 ID">
      <button class="refresh-btn" type="submit" :disabled="loading">搜索</button>
      <!-- 任务书 #71：商家身份唯一来源=平台初始化（D2/D4）。主按钮视觉同全局
           .btn-confirm，但刻意用独立类名——弹窗确认按钮依赖 .btn-confirm 查找语义。 -->
      <button v-if="adminControls" class="init-merchant-btn" type="button" data-testid="open-merchant-init" @click="openInitDialog">
        初始化商家账号
      </button>
    </div>
  </form>
  <p v-if="loadError" class="error-msg" role="alert">{{ loadError }}</p>
  <p v-if="userActionMessage" class="action-success-msg" role="status" data-testid="user-action-message">
    {{ userActionMessage }}</p>
  <div v-if="loading" class="loading-state">加载中...</div>
  <template v-else>
  <div class="table-card">
    <div class="table-scroll">
    <table class="user-table">
      <thead><tr><th>账号</th><th>状态</th><th>身份</th><th>后台角色</th>
        <th>积分余额</th><th>累计获得</th><th>累计使用</th><th>注册时间</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="user in users" :key="user.id" :class="{ 'row-suspended': user.status === 'suspended' }">
          <td class="user-account-cell">
            <div class="user-account-label"><strong>{{ user.displayName || user.email }}</strong>
              <span v-if="user.role !== 'user'" class="role-tag" :class="'role-' + user.role">{{ user.role }}</span>
            </div>
            <span v-if="user.displayName" class="td-muted">{{ user.email }}</span>
          </td>
          <td><span class="badge" :class="userStatusMeta(user.status).badge">{{ userStatusMeta(user.status).label }}</span></td>
          <td class="td-identity">
            <span v-if="user.identities && (user.identities.recommender || user.identities.merchant || user.identities.member)"
              class="identity-chip-group">
              <span v-if="user.identities.recommender" class="type-tag">推荐官</span>
              <span v-if="user.identities.merchant" class="type-tag"
                :title="user.identities.ownedOrgNames || '未建主体'">商家</span>
              <span v-if="user.identities.member" class="type-tag"
                :title="user.identities.ownedOrgNames || '未建主体'">成员</span>
            </span>
            <span v-else class="td-muted">—</span>
          </td>
          <td>{{ user.roles && user.roles.length ? user.roles.join('、') : '—' }}</td>
          <td class="td-balance">{{ user.balance }}</td><td>{{ user.totalEarned }}</td>
          <td>{{ user.totalSpent }}</td><td class="td-time">{{ formatDate(user.createdAt) }}</td>
          <td class="user-row-btns">
            <div class="user-row-actions">
            <button class="detail-btn" type="button" @click="openUserDetail(user)">详情</button>
            <button v-if="adminControls" class="adjust-btn" type="button" @click="openAdjust(user)">调整积分</button>
            <button v-if="adminControls && user.status !== 'suspended'" class="suspend-btn" type="button"
              @click="openUserSuspend(user)">停用</button>
            <button v-if="adminControls && user.status === 'suspended'" class="restore-btn" type="button"
              @click="openUserRestore(user)">恢复</button>
            <button v-if="adminControls" class="reset-btn" type="button"
              @click="openUserResetPassword(user)">重置密码</button>
            </div>
          </td>
        </tr>
        <tr v-if="users.length === 0"><td colspan="9" class="td-empty">暂无用户</td></tr>
      </tbody>
    </table>
    </div>
  </div>
  <OpsPagination :total="usersTotal" :limit="usersLimit" :offset="usersOffset"
    @change="changeUsersPage" @change-limit="changeUsersLimit" />
  </template>

  <Teleport to="body">
    <AdjustCreditsDialog
      :target="adjustTarget"
      :amount="adjustAmount"
      :note="adjustNote"
      :error="adjustError"
      :adjusting="adjusting"
      @close="closeAdjust"
      @update:amount="adjustAmount = $event"
      @update:note="adjustNote = $event"
      @blur-amount="onAdjustAmountBlur"
      @confirm="handleAdjust"
    />
    <MerchantAccountInitDialog
      :open="initDialogOpen"
      :email="initEmail"
      :display-name="initDisplayName"
      :email-error="initEmailError"
      :error="initError"
      :submitting="initSubmitting"
      :result="initResult"
      @close="closeInitDialog"
      @update:email="initEmail = $event"
      @update:display-name="initDisplayName = $event"
      @submit="handleInitMerchantAccount"
    />
    <!-- 任务书 #72 卡 D：账号详情抽屉（只读身份档案 + admin 管控分区）与两个管控弹窗 -->
    <AdminUserDetailDrawer
      :user="detailUser"
      :admin="adminControls"
      @close="closeUserDetail"
      @refresh="void refreshUsersAfterAction()"
      @adjust="openAdjust"
    />
    <AdminUserSuspendDialog
      :open="Boolean(suspendDialogUser)"
      :user="suspendDialogUser"
      :mode="suspendDialogMode"
      @close="suspendDialogUser = null"
      @done="handleSuspendDone"
    />
    <AdminUserResetPasswordDialog
      :open="Boolean(resetDialogUser)"
      :user="resetDialogUser"
      @close="resetDialogUser = null"
      @done="handleResetDone"
    />
  </Teleport>
</template>

<style scoped src="../admin-shared.css"></style>

<style scoped>
/* 任务书 #72 卡C：状态/身份筛选（左）+ 搜索/初始化（右），窄屏自然换行 */
.user-filters { display: flex; align-items: center; gap: var(--space-sm); margin-right: auto; flex-wrap: wrap; }
.user-filters label { display: flex; align-items: center; gap: var(--space-xs); font-size: 0.84rem; color: var(--color-text-secondary); }
.user-filter-select { min-height: 34px; padding: 0 var(--space-xs); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer; }
.user-filter-select:focus-visible { outline: none; border-color: var(--color-accent); }
.user-search-group { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; }

.role-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 0.78rem;
  font-weight: 600;
  text-transform: uppercase;
}

.role-admin {
  background: color-mix(in srgb, var(--color-warning) 15%, transparent);
  color: var(--color-warning);
}

.role-user {
  background: var(--surface-muted);
  color: var(--color-text-muted);
}

.adjust-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent);
  font-size: 0.78rem;
  cursor: pointer;
  transition: all 0.15s ease-out;
}

.adjust-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
}

/* 任务书 #72 卡C：行操作四钮（详情/调整积分/停用|恢复/重置密码）。基础形与 .adjust-btn 同格，
   独立类名——既有测试以 .adjust-btn 定位调整积分，不可共享。 */
.user-row-btns { display: flex; gap: 6px; flex-wrap: wrap; }

.detail-btn,
.suspend-btn,
.restore-btn,
.reset-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: 0.78rem;
  cursor: pointer;
  transition: all 0.15s ease-out;
}

.detail-btn {
  color: var(--color-accent);
}

.suspend-btn {
  border-color: color-mix(in srgb, var(--color-danger) 30%, transparent);
  color: var(--color-danger);
}

.restore-btn {
  color: var(--color-success);
  border-color: color-mix(in srgb, var(--color-success) 35%, transparent);
}

.reset-btn {
  color: var(--color-text-secondary);
}

/* 已停用行整行弱化（同文件 .refresh-btn:disabled 的 opacity 先例） */
.row-suspended {
  opacity: 0.55;
}

/* 管控成功提示（任务书 #72 卡D）：与 .error-msg 同构、success 语义色 */
.action-success-msg {
  margin: 0;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-success) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-success) 20%, transparent);
  color: var(--color-success);
  font-size: 0.8rem;
}

.td-identity .identity-chip-group {
  display: inline-flex;
  gap: 4px;
  flex-wrap: wrap;
}

/* 初始化商家账号（任务书 #71）：工具区主按钮——视觉同全局 .btn-confirm（primary
   渐变），独立类名避免抢占弹窗确认按钮的 .btn-confirm 查找语义。 */
.init-merchant-btn {
  margin-left: var(--space-sm);
  padding: 8px 20px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--gradient-accent);
  color: var(--color-on-accent);
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
}

.init-merchant-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
