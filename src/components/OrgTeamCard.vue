<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  Membership,
  MembershipRole,
  OrgTeamSummary,
  Store,
  StoreMembership,
  StoreRole,
} from '../types/grassland'

/**
 * 组织成员（池）+ 门店 + 门店分配管理（Slice 2F / 2G / 2J 前端；任务书 #52 池模型）。
 *
 * 模型：建号一律入主体池（organization member），门店身份是池上的分配层（store_membership，
 * 至多挂一店）。分配/移除/调度均为主体 ADMIN+ 人事权；一店一店长（冲突 409）。
 *
 * 授权分档（后端口径，UI 只做提示，真正门禁在服务端）：
 * - 建号/分配/移除/调度/停用恢复/删号：org **ADMIN+**（店长仅可停用/恢复本店员工）
 * - 门店成员列表：门店 STAFF+；守卫：owner 保护 / 不可自操作（「最后店长」守卫已随 #52 决策 E 废除）
 */

const emit = defineEmits<{ 'stores-changed': []; summary: [OrgTeamSummary] }>()
const props = defineProps<{ orgId: string }>()

const grassland = useGrassland()

const members = ref<Membership[]>([])
const stores = ref<Store[]>([])
const selectedStoreId = ref('')
const storeMembers = ref<StoreMembership[]>([])
const notice = ref('')

const newStoreName = ref('')

/** 单店模式（任务书 #50 D1 推导制）：≤1 家门店即单店——门店管理 UI 收敛、建号免选门店。 */
const singleStore = computed(() => stores.value.length <= 1)
/** 建第二店成功即切换多店（推导自动生效，这里只补提示）。 */
const wasSingleStore = ref(false)

const ROLE_LABEL: Record<MembershipRole, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
}

const STORE_ROLE_LABEL: Record<StoreRole, string> = {
  manager: '店长',
  staff: '店员',
}

/**
 * 门店员工行的视图模型：`implicit` 标记「主体账号隐式管理本店」的合成行。
 *
 * 后端 `StoreAuthorization` 早已把 org OWNER/ADMIN 隐式视为门店 MANAGER，但
 * `store_membership` 里没有那一行——列表接口只查表，于是单店商家看到「暂无成员」+
 * 添加入口，UI 在逼用户把自己加进自己的店。这里把既有授权真相显式呈现，
 * 不落库、不改契约（合成行 id 带 `implicit:` 前缀，不与真实行冲突）。
 */
type StoreStaffRow = StoreMembership & { implicit?: boolean }

async function refresh(): Promise<void> {
  if (!props.orgId) return
  selectedStoreId.value = ''
  storeMembers.value = []
  const m = await grassland.listMemberships(props.orgId)
  const s = await grassland.listStores(props.orgId)
  if (m) members.value = m
  if (s) stores.value = s
  // 单店模式：唯一门店隐式选中（门店成员区直接呈现，无选择动作）
  if (stores.value.length === 1) {
    await selectStore(stores.value[0]!.id)
  }
  await loadAccountPrefix()
}

watch(() => props.orgId, refresh, { immediate: true })

/**
 * 向父组件冒泡摘要（概览节用）。
 *
 * 挂在**派生值**上而不是逐个 mutation 点：members/invitations/stores 有十来条改写路径
 * （增删成员、发/撤邀请、建门店、停用恢复、审核…），逐个加 emit 必然漏。
 * deep 不需要——这些 ref 都是整体替换而非原地改元素。
 */
watch(
  (): OrgTeamSummary => {
    // Array.isArray 守卫：load 只判 truthy，上游给非数组时摘要不能连带崩掉整卡
    // （卡身用 v-for 能容忍，`.length`/`.filter` 不能）。
    const memberList = Array.isArray(members.value) ? members.value : []
    return {
      memberCount: memberList.length,
      storeCount: Array.isArray(stores.value) ? stores.value.length : 0,
      pendingReviewCount: memberList.filter((item) => item.accountStatus === 'pending_review').length,
    }
  },
  (summary) => emit('summary', summary),
  { immediate: true },
)

async function reloadMembers(): Promise<void> {
  const list = await grassland.listMemberships(props.orgId)
  if (list) members.value = list
}

// 任务书 #49：挂靠入口（填 accountId 直接添加 / 「移除」仅解除关系）已随挂靠通路下线。
// 成员移除走「删除」动作（S5：解除关系 + 账号永久作废 + 输入账号名强确认）。

async function addStore(): Promise<void> {
  const name = newStoreName.value.trim()
  if (!name) return
  notice.value = ''
  wasSingleStore.value = singleStore.value
  const created = await grassland.createStore(props.orgId, name)
  if (!created) return
  newStoreName.value = ''
  const list = await grassland.listStores(props.orgId)
  if (list) stores.value = list
  // 推导制切换提示（任务书 #50 D1）：单店 → 多店由门店数自动生效
  notice.value = wasSingleStore.value && stores.value.length >= 2
    ? `门店「${created.name}」已创建——已切换到多店管理`
    : `门店「${created.name}」已创建`
  emit('stores-changed')
}

// ---------- 门店停用/恢复/删除（2026-08-27：门店此前只能新增） ----------

/** 待删除门店的确认态：显示警示弹窗，点确认才调端点（守卫冲突 409 由 error 条呈现）。 */
const deleteStoreTarget = ref<Store | null>(null)

async function setStoreActive(store: Store, active: boolean): Promise<void> {
  notice.value = ''
  const done = active
    ? await grassland.restoreStore(props.orgId, store.id)
    : await grassland.suspendStore(props.orgId, store.id)
  if (done === null) return
  notice.value = active
    ? `门店「${store.name}」已恢复`
    : `门店「${store.name}」已停用（对外隐藏，可随时恢复）`
  emit('stores-changed')
  const list = await grassland.listStores(props.orgId)
  if (list) stores.value = list
}

async function confirmDeleteStore(): Promise<void> {
  const target = deleteStoreTarget.value
  if (!target) return
  notice.value = ''
  const done = await grassland.deleteStore(props.orgId, target.id)
  if (done === null) {
    deleteStoreTarget.value = null
    return
  }
  deleteStoreTarget.value = null
  notice.value = `门店「${target.name}」已删除（不可恢复）`
  emit('stores-changed')
  const list = await grassland.listStores(props.orgId)
  if (list) stores.value = list
  // 删除后门店数变化可能触发模式推导变化（多店→单店），重选唯一门店
  await refresh()
}

async function selectStore(storeId: string): Promise<void> {
  selectedStoreId.value = storeId
  storeMembers.value = []
  const list = await grassland.listStoreMemberships(props.orgId, storeId)
  if (list) storeMembers.value = list
}

// 任务书 #49：门店挂靠函数（addStoreMember/removeStoreMember）已随挂靠通路下线。

// ---------- 任务书 #52：建号入池 / 门店分配 ----------

/** 主体成员账号前缀（#49 D5）：只读——建号预览与展示用；改名归运营（#51）。 */
const accountPrefix = ref('')
const orgCreateLoginName = ref('')
const orgCreateName = ref('')
/** 建号时直接分配门店（#52 第 3 条）：'' = 暂不分配（纯池内成员）。 */
const orgCreateStoreId = ref('')
/** 建号分配的门店角色（选了门店才有意义；店长过一店一店长闸，冲突 409 由错误条呈现）。 */
const orgCreateStoreRole = ref<'manager' | 'staff'>('staff')
/** 从池中分配到当前选中门店（#52 第 2 条）。 */
const assignAccountId = ref('')
const assignRole = ref<'manager' | 'staff'>('staff')
/** 行内调度表单（#52 第 4 条）：transferFor = 目标成员 accountId；null = 收起。 */
const transferFor = ref<string | null>(null)
const transferStoreId = ref('')
const transferRole = ref<'manager' | 'staff'>('staff')

/** 登录名规则（#49 D4）：仅小写字母数字、3-24 位；输入即时小写化。 */
const LOGIN_NAME_RE = /^[a-z0-9]{3,24}$/
const loginNameValid = (v: string) => LOGIN_NAME_RE.test(v.trim().toLowerCase())

/** 门店级建号锁定店员（D1：店长仅能建本店 staff；任命店长走上方主体入口）。 */

const ACCOUNT_STATUS_LABEL: Record<string, string> = {
  active: '正常',
  suspended: '已停用',
  pending_review: '待审核',
  rejected: '已驳回',
}

/** 组织级建号：登录名与显示名合法即可提交（门店分配为可选）。 */
const orgCreateDisabled = computed(() =>
  !loginNameValid(orgCreateLoginName.value) || !orgCreateName.value.trim())

/**
 * 池内可分配人选（#52）：未挂店的组织成员。2026-08-28 拍板纳入 owner/admin——
 * 管理层亲自运营某店时领店长名分（权限本就全覆盖，这是身份的如实呈现）。
 */
const poolMembers = computed(() => {
  const memberList = Array.isArray(members.value) ? members.value : []
  return memberList.filter((m) => !m.storeId)
})

/** accountId → 组织角色（门店行据此区分管理层：owner 行不给停用/删除，同主体成员表口径）。 */
const orgRoleByAccount = computed(() => {
  const map = new Map<string, MembershipRole>()
  for (const m of Array.isArray(members.value) ? members.value : []) map.set(m.accountId, m.role)
  return map
})
const isOrgOwner = (accountId: string) => orgRoleByAccount.value.get(accountId) === 'owner'

/** 建号预览：前缀-登录名（后端拼同样规则，前端只做提示）。 */
const orgUsernamePreview = computed(() =>
  accountPrefix.value && orgCreateLoginName.value.trim()
    ? `${accountPrefix.value}-${orgCreateLoginName.value.trim().toLowerCase()}`
    : '')

/**
 * 建号/重置刚返回的一次性初始密码——响应之后任何接口都取不到，展示区只存在到
 * 用户点「我已保存」为止。这是「商家直建、线下交付」模型的安全底线（PRD §2.1）。
 */
const oneTimePassword = ref<{ username: string; password: string } | null>(null)

async function loadAccountPrefix(): Promise<void> {
  if (!props.orgId) return
  const prefix = await grassland.getAccountPrefix(props.orgId)
  if (prefix) accountPrefix.value = prefix.prefix
}

// 任务书 #51：商家侧改前缀（savePrefix + prefixInput）已删除——前缀自动生成、商家只读，
// 后端 PATCH /api/organizations/{id}/account-prefix 也已下线。改名是运营动作（会连带重写
// 该主体下全部成员的登录名），入口在运营台「账号前缀」页签。
// 任务书 #52 决策 A：审核开关（member-review-required）与店长代建/审批端点同批退役。

/**
 * 建号入池（#52 唯一建号路径）：member = 入池不挂店；选了门店则同时分配为该店店长/店员。
 * 一店一店长闸在后端（建号/分配/调度三处同闸），冲突 409 由错误条呈现。
 */
async function createOrgAccount(): Promise<void> {
  const loginName = orgCreateLoginName.value.trim().toLowerCase()
  const displayName = orgCreateName.value.trim()
  if (!loginNameValid(loginName) || !displayName) return
  const storeId = orgCreateStoreId.value || undefined
  const role = storeId ? orgCreateStoreRole.value : 'member'
  notice.value = ''
  const created = await grassland.createSubAccount(props.orgId, {
    role, loginName, displayName, ...(storeId ? { storeId } : {}),
  })
  if (!created) return
  orgCreateLoginName.value = ''
  orgCreateName.value = ''
  orgCreateStoreId.value = ''
  orgCreateStoreRole.value = 'staff'
  oneTimePassword.value = { username: created.account.username, password: created.initialPassword ?? '' }
  notice.value = storeId
    ? `${STORE_ROLE_LABEL[role as StoreRole]}账号已创建并分配到门店，凭据请线下转交`
    : '组织成员账号已创建（暂未分配门店），凭据请线下转交'
  await reloadMembers()
  if (selectedStoreId.value) await selectStore(selectedStoreId.value)
}

/** 从池中分配到当前选中门店（#52 第 2 条）：assign-or-move——成员已在别店则先解除再挂本店。 */
async function assignPoolMember(): Promise<void> {
  const storeId = selectedStoreId.value
  if (!storeId || !assignAccountId.value) return
  notice.value = ''
  const done = await grassland.assignStoreMember(props.orgId, storeId, assignAccountId.value, assignRole.value)
  if (done === null) return
  notice.value = `已分配为本店${STORE_ROLE_LABEL[assignRole.value]}`
  assignAccountId.value = ''
  assignRole.value = 'staff'
  await Promise.all([reloadMembers(), selectStore(storeId)])
}

/** 调度（#52 第 4 条）：把本店成员移到其他门店并设定其角色（同端点 assign-or-move）。 */
function startTransfer(accountId: string): void {
  transferFor.value = accountId
  transferStoreId.value = ''
  transferRole.value = 'staff'
}

async function confirmTransfer(): Promise<void> {
  const accountId = transferFor.value
  const currentStoreId = selectedStoreId.value
  if (!accountId || !currentStoreId || !transferStoreId.value) return
  notice.value = ''
  const targetName = stores.value.find((s) => s.id === transferStoreId.value)?.name ?? '目标门店'
  const done = await grassland.assignStoreMember(props.orgId, transferStoreId.value, accountId, transferRole.value)
  if (done === null) return
  notice.value = `已调度至「${targetName}」担任${STORE_ROLE_LABEL[transferRole.value]}`
  transferFor.value = null
  await Promise.all([reloadMembers(), selectStore(currentStoreId)])
}

/** 移除出本店（#52）：只解除挂靠，账号回池（组织关系保留），可再分配。删号在主体区。 */
async function removeFromStore(accountId: string): Promise<void> {
  const storeId = selectedStoreId.value
  if (!storeId) return
  notice.value = ''
  const done = await grassland.removeStoreMember(props.orgId, storeId, accountId)
  if (done === null) return
  notice.value = '已移除出本店（账号保留在主体成员池，可再分配）'
  await Promise.all([reloadMembers(), selectStore(storeId)])
}

/**
 * 门店人员 = 真实 store_membership 行；仅单店额外派生主体账号（owner/admin）隐式店长行。
 *
 * 单店（#51 第 4 条前半）：本店员工默认主体账号为店长，隐式行排最前、标「主体账号」、
 * 不给停用/恢复/删除（主体级动作在「主体成员」表做）。
 * 多店（2026-08-28 二轮收敛）：只呈现真实任命/创建的成员——主体管理员对全部门店保有
 * 管理权（后端 orgSuperUserAsManager），但那是权限不是身份，不再画成每个店的「店长」行；
 * 无店长的店由主体账号代管，见模板中的代管提示与内联任命入口。
 */
const storeStaffRows = computed<StoreStaffRow[]>(() => {
  const explicit = Array.isArray(storeMembers.value) ? storeMembers.value : []
  if (!singleStore.value) return explicit
  const explicitAccountIds = new Set(explicit.map((m) => m.accountId))
  const memberList = Array.isArray(members.value) ? members.value : []
  const implicitRows: StoreStaffRow[] = memberList
    .filter((m) => (m.role === 'owner' || m.role === 'admin') && !explicitAccountIds.has(m.accountId))
    .map((m) => ({
      id: `implicit:${m.accountId}`,
      storeId: selectedStoreId.value,
      accountId: m.accountId,
      role: 'manager' as StoreRole,
      createdAt: null,
      accountStatus: m.accountStatus,
      username: m.username,
      implicit: true,
    }))
  return [...implicitRows, ...explicit]
})

/** 当前门店是否已有真实店长（多店无店长时提示「主体账号代管」并引导内联任命）。 */
const hasStoreManager = computed(() => {
  const explicit = Array.isArray(storeMembers.value) ? storeMembers.value : []
  return explicit.some((m) => m.role === 'manager')
})

/**
 * 主体成员表是否呈现操作列（任务书 #51 第 5 条，2026-08-28 拍板严格字面）：单店一律不呈现。
 *
 * 单店只有主体账号一个用户，owner 的停用/删除服务端一律 403——整列都是死按钮。代价由
 * 运营台兜底：多店期建的 `role=member` 成员不挂门店，删分店回落单店后商家侧不再有
 * 停用/删除入口，只能由平台运营在治理台处置。
 */
const showMemberActions = computed(() => !singleStore.value)

/** 停用 / 恢复即时生效；守卫冲突（最后一个店长、owner 保护等）由 error 条原样呈现。 */
async function setAccountActive(accountId: string, active: boolean): Promise<void> {
  notice.value = ''
  const done = active
    ? await grassland.restoreSubAccount(props.orgId, accountId)
    : await grassland.suspendSubAccount(props.orgId, accountId)
  if (done === null) return
  notice.value = active ? '账号已恢复可用' : '账号已停用（立即生效，系统将站内知会主体）'
  await Promise.all([reloadMembers(), selectedStoreId.value ? selectStore(selectedStoreId.value) : null])
}

// ---------- 删除成员（任务书 #49 D8：永久作废 + 输入账号名强确认）----------

/** 待删除目标：username 供强确认比对（列表行由后端 LEFT JOIN account_username 带出）。 */
const deleteTarget = ref<{ accountId: string; username: string } | null>(null)
const deleteConfirmInput = ref('')
/** 输入与目标账号名完全一致才允许确认——防误点的一票否决。 */
const deleteConfirmable = computed(() =>
  deleteTarget.value !== null && deleteConfirmInput.value.trim() === deleteTarget.value.username)

function askDelete(accountId: string, username: string | null): void {
  // 无登录名（存量挂靠过渡态/账号行缺失）时以 accountId 作确认物——强确认语义不降级
  deleteTarget.value = { accountId, username: username || accountId }
  deleteConfirmInput.value = ''
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value || !deleteConfirmable.value) return
  notice.value = ''
  const target = deleteTarget.value
  const done = await grassland.deleteSubAccount(props.orgId, target.accountId)
  if (done === null) {
    deleteTarget.value = null
    return
  }
  deleteTarget.value = null
  deleteConfirmInput.value = ''
  notice.value = '成员已删除：账号已永久作废，不可恢复'
  await Promise.all([reloadMembers(), selectedStoreId.value ? selectStore(selectedStoreId.value) : null])
}

</script>

<template>
  <article class="team">
    <header class="team-head">
      <h3>成员与门店</h3>
      <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="team-alert team-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="team-alert team-ok">{{ notice }}</p>

    <!-- 一次性初始密码（任务书 #48）：仅建号/重置的响应里存在一次，此后接口不可再取 -->
    <div v-if="oneTimePassword" class="team-alert team-pw" data-testid="one-time-password">
      <p class="team-pw-line">
        账号 <strong>{{ oneTimePassword.username }}</strong> 的初始密码：
        <code class="team-pw-code">{{ oneTimePassword.password }}</code>
      </p>
      <p class="team-pw-hint">请立即线下转交本人；对方用该账号名登录，首次登录须改密。关闭后无法再次查看。</p>
      <button type="button" class="team-quiet" @click="oneTimePassword = null">我已妥善保存</button>
    </div>

    <!-- 成员账号前缀（任务书 #51）：自动生成、商家只读。改前缀会连带改掉全部成员的登录名
         （旧登录名立即失效），是平台处置动作，入口在运营台 -->
    <div class="team-row">
      <span class="team-tag">账号前缀 {{ accountPrefix || '…' }}</span>
      <span class="team-hint">成员账号名 = 前缀-登录名；前缀由系统生成，如需修改请联系平台运营</span>
    </div>

    <!-- 组织成员（#52 池模型：全员入池，所属门店是分配层） -->
    <section class="team-sec">
      <h4>主体成员</h4>
      <p v-if="members.length === 0" class="team-hint">暂无成员记录。</p>
      <table v-else class="team-table">
        <thead>
          <tr>
            <th>账号</th><th>角色</th><th>所属门店</th><th>状态</th>
            <!-- 任务书 #51 第 5 条：单店（只有 owner）不呈现操作列——owner 的停用/删除服务端一律 403 -->
            <th v-if="showMemberActions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in members" :key="m.id">
            <td><code>{{ m.username || m.accountId.slice(0, 8) + '…' }}</code></td>
            <td>{{ ROLE_LABEL[m.role] || m.role }}</td>
            <!-- #52 池模型：挂靠门店（至多一店）；未分配 = 池内待分配 -->
            <td>
              <span v-if="m.storeName" class="team-tag">{{ m.storeName }}</span>
              <span v-else class="team-hint">未分配</span>
            </td>
            <td><span v-if="m.accountStatus" class="team-tag">{{ ACCOUNT_STATUS_LABEL[m.accountStatus] || m.accountStatus }}</span></td>
            <td v-if="showMemberActions">
              <!-- owner 行不给任何操作（服务端守卫 403：不可停用、不可删除、不可自操作） -->
              <span v-if="m.role === 'owner'" class="team-hint">主体所有者</span>
              <template v-else>
                <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
                <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
                <button
                  type="button" class="team-quiet team-danger"
                  :disabled="grassland.loading.value"
                  @click="askDelete(m.accountId, m.username ?? null)"
                >删除</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 任务书 #49：邀请表单与「已知账号 ID 直接添加」挂靠入口均已下线（成员经下方主体直建产生） -->

      <!-- 任务书 #51 第 3 条：单店只有主体账号一个用户，不呈现建号入口。
           需要员工先开分店（下方「门店」区），进入多店管理后建号入口自动出现 -->
      <p v-if="singleStore" class="team-hint">
        单店模式下本主体只有你这一个账号。需要为员工开账号请先在下方「门店」区开分店，
        进入多店管理后即可创建店长/店员账号。
      </p>

      <!-- 建号入池（#52 唯一建号路径）：可选在建号时就分配门店与角色（第 3 条） -->
      <details v-else class="team-adv">
        <summary>添加成员：主体直接创建账号</summary>
        <div class="team-row">
          <input
            v-model="orgCreateLoginName"
            placeholder="登录名（3-24 位字母数字）"
            @keyup.enter="createOrgAccount"
          />
          <span v-if="orgUsernamePreview" class="team-tag">{{ orgUsernamePreview }}</span>
          <input v-model="orgCreateName" placeholder="显示名" @keyup.enter="createOrgAccount" />
          <select v-model="orgCreateStoreId">
            <option value="">暂不分配门店</option>
            <option v-for="s in stores" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <select v-if="orgCreateStoreId" v-model="orgCreateStoreRole">
            <option value="staff">店员</option>
            <option value="manager">店长</option>
          </select>
          <button
            type="button"
            :disabled="grassland.loading.value || orgCreateDisabled"
            @click="createOrgAccount"
          >创建账号</button>
        </div>
        <p class="team-hint">
          创建即生效；账号名 = 前缀-登录名（如 {{ accountPrefix ? accountPrefix + '-zhangsan' : '前缀-zhangsan' }}），
          系统生成一次性初始密码供你线下转交，对方首次登录须改密，登录后可自行绑定邮箱。
          不选门店 = 纯主体成员（后续在「门店成员」区分配）；选门店即同时定岗为该店店长/店员
          ——一个门店只能有一个店长，冲突会被拒绝。分配与调度也可随时在下方「门店成员」区调整。
        </p>
      </details>
    </section>

    <!-- 门店：单店模式（任务书 #50 D1/D4）收敛为「我的门店」+ 开分店入口；多店保持列表管理 -->
    <section v-if="singleStore" class="team-sec">
      <h4>门店</h4>
      <div v-if="stores.length === 1" class="team-row">
        <span class="team-tag">我的门店</span>
        <strong>{{ stores[0]!.name }}</strong>
        <span v-if="stores[0]!.status === 'suspended'" class="team-tag">已停用（对外隐藏）</span>
        <button
          v-if="stores[0]!.status === 'suspended'"
          type="button" class="team-quiet"
          :disabled="grassland.loading.value"
          @click="setStoreActive(stores[0]!, true)"
        >恢复</button>
        <button
          v-else
          type="button" class="team-quiet"
          :disabled="grassland.loading.value"
          @click="setStoreActive(stores[0]!, false)"
        >停用</button>
        <span class="team-hint">唯一门店不可删除，不经营可停用；资料与认证在「认证」分节</span>
      </div>
      <p v-else class="team-hint">暂无门店。</p>
      <details class="team-adv">
        <summary>开分店</summary>
        <div class="team-row">
          <input v-model="newStoreName" placeholder="分店名称" @keyup.enter="addStore" />
          <button type="button" :disabled="grassland.loading.value || !newStoreName.trim()" @click="addStore">
            创建分店
          </button>
        </div>
        <p class="team-hint">开设第二家门店后自动进入多店管理（门店列表、按店管理成员）。</p>
      </details>
    </section>
    <section v-else class="team-sec">
      <h4>门店</h4>
      <ul class="team-list">
        <li v-for="s in stores" :key="s.id" class="team-store-row">
          <button
            type="button" class="team-link"
            :class="{ active: selectedStoreId === s.id }"
            @click="selectStore(s.id)"
          >{{ s.name }}</button>
          <span class="team-tag">{{ s.status === 'suspended' ? '已停用' : s.status }}</span>
          <button
            v-if="s.status === 'suspended'"
            type="button" class="team-quiet"
            :disabled="grassland.loading.value"
            @click="setStoreActive(s, true)"
          >恢复</button>
          <button
            v-else
            type="button" class="team-quiet"
            :disabled="grassland.loading.value"
            @click="setStoreActive(s, false)"
          >停用</button>
          <button
            type="button" class="team-quiet team-danger"
            :disabled="grassland.loading.value"
            @click="deleteStoreTarget = s"
          >删除</button>
        </li>
      </ul>

      <div class="team-row">
        <input v-model="newStoreName" placeholder="新门店名称" @keyup.enter="addStore" />
        <button type="button" :disabled="grassland.loading.value || !newStoreName.trim()" @click="addStore">
          新建门店
        </button>
      </div>
      <p class="team-hint">建门店需主体管理员及以上。点门店名可管理其成员。</p>
    </section>

    <!-- 门店成员：单店时唯一门店自动选中，直呼本店员工 -->
    <section v-if="selectedStoreId" class="team-sec">
      <h4>{{ singleStore ? '本店员工' : '门店成员' }}</h4>
      <p v-if="storeStaffRows.length === 0" class="team-hint">该门店暂无成员。</p>
      <table v-else class="team-table">
        <thead><tr><th>账号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in storeStaffRows" :key="m.id">
            <td>
              <code>{{ m.username || m.accountId.slice(0, 8) + '…' }}</code>
              <!-- 主体账号（隐式合成行或真实挂靠的管理层） -->
              <span v-if="m.implicit || (orgRoleByAccount.get(m.accountId) ?? 'member') !== 'member'" class="team-tag">主体账号</span>
            </td>
            <td>{{ STORE_ROLE_LABEL[m.role] || m.role }}</td>
            <td><span v-if="m.accountStatus" class="team-tag">{{ ACCOUNT_STATUS_LABEL[m.accountStatus] || m.accountStatus }}</span></td>
            <td>
              <span v-if="m.implicit" class="team-hint">默认管理本店（在「主体成员」处管理）</span>
              <template v-else>
                <!-- owner 行的停用/恢复/删除服务端一律 403（账号级守卫），不画死按钮；
                     分配层的调度/移除与其无关，照常可用 -->
                <template v-if="!isOrgOwner(m.accountId)">
                  <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
                  <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
                </template>
                <span v-else class="team-hint">主体所有者（账号管理在「主体成员」处）</span>
                <!-- 调度/移除（#52 第 4 条）：分配层人事操作，仅多店且 ADMIN+（后端门禁） -->
                <template v-if="!singleStore">
                  <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="startTransfer(m.accountId)">调度</button>
                  <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="removeFromStore(m.accountId)">移除</button>
                </template>
              </template>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 任务书 #49：门店邀请表单与「已知账号 ID 直接添加」挂靠入口均已下线（本店员工经下方店长直建产生） -->

      <!-- 单店：主体账号本身就是店长，无需添加；建号入口整体不在单店呈现（#51 第 3 条），
           故这里也不指向任何入口，只说明「先开分店」这条唯一路径 -->
      <p v-if="singleStore" class="team-hint">
        你的主体账号默认就是本店店长，无需添加。需要为员工开账号请先开分店（上方「门店」区）。
      </p>

      <template v-else>
        <!-- 多店无店长：新分店默认无人挂店长位，主体账号代管（orgSuperUserAsManager 是权限
             不是身份，不画店长行）；从池中分配一位店长即可 -->
        <p v-if="!hasStoreManager" class="team-hint">
          该门店尚未任命店长，当前由主体账号代管。可从下方分配池内成员为本店店长。
        </p>

        <!-- 从池中分配（#52 第 2 条）：门店区不再建号，只做分配；成员经上方主体区创建 -->
        <details class="team-adv">
          <summary>从成员池分配到本店</summary>
          <div class="team-row">
            <select v-model="assignAccountId">
              <option value="" disabled>选择未分配成员</option>
              <option v-for="p in poolMembers" :key="p.id" :value="p.accountId">
                {{ (p.username || p.accountId.slice(0, 8) + '…')
                  + (p.role !== 'member' ? `（${ROLE_LABEL[p.role]}）` : '') }}
              </option>
            </select>
            <select v-model="assignRole">
              <option value="staff">店员</option>
              <option value="manager">店长</option>
            </select>
            <button
              type="button"
              :disabled="grassland.loading.value || !assignAccountId"
              @click="assignPoolMember"
            >分配到本店</button>
          </div>
          <p v-if="poolMembers.length === 0" class="team-hint">
            暂无可分配人选——先在上方「主体成员」区创建（可不选门店），再回到这里分配。
          </p>
          <p class="team-hint">
            可分配未分配成员或主体管理层（所有者/管理员亲自运营时领店长名分）；分配即定岗
            （店长/店员），一个门店只能有一个店长，冲突会被拒绝。已挂他店时分配到本店即完成调度。
          </p>
        </details>

        <!-- 行内调度表单（#52 第 4 条）：点行内「调度」展开 -->
        <div v-if="transferFor" class="team-row team-transfer">
          <span class="team-tag">调度</span>
          <select v-model="transferStoreId">
            <option value="" disabled>目标门店</option>
            <option v-for="s in stores.filter((x) => x.id !== selectedStoreId)" :key="s.id" :value="s.id">
              {{ s.name }}
            </option>
          </select>
          <select v-model="transferRole">
            <option value="staff">店员</option>
            <option value="manager">店长</option>
          </select>
          <button
            type="button"
            :disabled="grassland.loading.value || !transferStoreId"
            @click="confirmTransfer"
          >确认调度</button>
          <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="transferFor = null">
            取消
          </button>
        </div>
      </template>
    </section>
    <!-- 删除强确认（任务书 #49 D9）：输入完整账号名且完全一致才可确认；红色警示 -->
    <div v-if="deleteTarget" class="team-del-mask" role="dialog" aria-modal="true" aria-label="删除成员确认">
      <div class="team-del-dialog" data-testid="delete-confirm">
        <h4 class="team-del-title">删除成员账号</h4>
        <p class="team-del-warn">
          将永久作废 <code>{{ deleteTarget.username }}</code>：解除全部成员关系，账号此后无法登录，
          <strong>不可恢复</strong>（停用才是可逆动作）。此操作仅在库里留痕，不物理删除。
        </p>
        <label class="team-del-label" :for="'del-confirm-' + deleteTarget.accountId">
          输入完整账号名 <code>{{ deleteTarget.username }}</code> 以确认
        </label>
        <input
          :id="'del-confirm-' + deleteTarget.accountId"
          v-model.trim="deleteConfirmInput"
          class="team-del-input"
          autocomplete="off"
          spellcheck="false"
          @keyup.enter="confirmDelete"
        />
        <div class="team-del-actions">
          <button type="button" class="team-quiet" @click="deleteTarget = null">取消</button>
          <button
            type="button" class="team-del-confirm"
            :disabled="grassland.loading.value || !deleteConfirmable"
            @click="confirmDelete"
          >永久删除</button>
        </div>
      </div>
    </div>
    <!-- 门店删除确认（不可逆；守卫冲突如店内有任务/成员由后端 409 呈现） -->
    <div v-if="deleteStoreTarget" class="team-del-mask" role="dialog" aria-modal="true" aria-label="删除门店确认">
      <div class="team-del-dialog" data-testid="store-delete-confirm">
        <h4 class="team-del-title">删除门店</h4>
        <p class="team-del-warn">
          将删除门店 <code>{{ deleteStoreTarget.name }}</code>：从主体与公开页面中移除，
          <strong>不可恢复</strong>（不经营建议改用停用）。店内须无成员、无任务记录，否则会被拒绝。
        </p>
        <div class="team-del-actions">
          <button type="button" class="team-quiet" @click="deleteStoreTarget = null">取消</button>
          <button
            type="button" class="team-del-confirm"
            :disabled="grassland.loading.value"
            @click="confirmDeleteStore"
          >确认删除</button>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.team { border: 1px solid var(--color-border); border-radius: var(--radius-lg); padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.team-head { display: flex; justify-content: space-between; align-items: center; }
.team-head h3 { margin: 0; font-size: 15px; }
.team-alert { margin: 0; padding: 7px 11px; border-radius: var(--radius-sm); font-size: 13px; }
.team-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.team-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
/* 一次性初始密码展示区：强调「现在不看就永远看不到」的紧迫感 */
.team-pw { background: color-mix(in srgb, var(--color-warning) 16%, transparent); display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.team-pw-line { margin: 0; font-size: 13px; }
.team-pw-code { font-size: 15px; letter-spacing: 1px; padding: 2px 8px; border-radius: var(--radius-xs); background: var(--color-surface-strong); user-select: all; }
.team-pw-hint { margin: 0; font-size: 12px; opacity: 0.72; }
.team-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
.team-toggle input { min-width: auto; }
.team-sec { display: flex; flex-direction: column; gap: 8px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.team-sec h4 { margin: 0; font-size: 13px; }
.team-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.team-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.team-table th, .team-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.team-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.team-list li { display: flex; align-items: center; gap: 8px; }
.team-link { background: transparent; border: none; padding: 2px 0; color: var(--color-text); cursor: pointer; text-decoration: underline; font-size: 13px; }
.team-link.active { color: var(--color-accent); font-weight: 500; }
.team-tag { font-size: 11px; padding: 1px 6px; border-radius: var(--radius-xs); background: var(--color-surface-strong); }
.team-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.team-adv { font-size: 12px; }
.team-adv summary { cursor: pointer; opacity: 0.7; padding: 2px 0; }
.team-adv > .team-row { margin-top: 6px; }
.team-adv-nested { margin-top: 6px; padding-left: 12px; border-left: 2px solid var(--color-border); }
input, select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; }
input { min-width: 200px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.team-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
.team-prefix-input { min-width: 150px; }
.team-store-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.team-danger { color: var(--color-danger); }
/* 删除强确认弹窗：遮罩 + 居中卡片，警示色走 token */
.team-del-mask { position: fixed; inset: 0; background: color-mix(in srgb, var(--color-bg, #000) 55%, transparent); display: flex; align-items: center; justify-content: center; z-index: 60; }
.team-del-dialog { width: min(420px, calc(100vw - 32px)); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-lg); padding: 18px; display: flex; flex-direction: column; gap: 12px; }
.team-del-title { margin: 0; font-size: 15px; color: var(--color-danger); }
.team-del-warn { margin: 0; font-size: 13px; }
.team-del-label { font-size: 12px; opacity: 0.8; }
.team-del-input { width: 100%; }
.team-del-actions { display: flex; justify-content: flex-end; gap: 8px; }
.team-del-confirm { padding: 6px 16px; border-radius: var(--radius-sm); border: 1px solid var(--color-danger); background: var(--color-danger); color: var(--color-accent-contrast, #fff); cursor: pointer; font-size: 13px; }
.team-del-confirm:disabled { opacity: 0.45; cursor: not-allowed; }
</style>
