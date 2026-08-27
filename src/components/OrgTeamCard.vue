<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
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
 * 组织成员 + 门店 + 门店成员管理（Slice 2F / 2G / 2J 的前端）。
 *
 * 任务书 #49：邀请流（按邮箱邀请 + 「我的邀请」接受侧）整条下线——成员只能经主体直建子账号产生。
 *
 * 授权分档（后端口径，UI 只做提示，真正门禁在服务端）：
 * - 门店列表：org MEMBER+；门店成员列表：门店 STAFF+
 * - 任命门店 manager：org **ADMIN+**；店长建 staff：**门店 MANAGER+**（店长可自管本店员工）
 * - 守卫：移除最后一个 owner / 唯一 manager 均 409
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

async function refresh(): Promise<void> {
  if (!props.orgId) return
  selectedStoreId.value = ''
  storeMembers.value = []
  const m = await grassland.listMemberships(props.orgId)
  const s = await grassland.listStores(props.orgId)
  if (m) members.value = m
  if (s) stores.value = s
  // 单店模式：唯一门店隐式选中（门店成员区直接呈现，无选择动作）；建号默认店员（拍板②）
  if (stores.value.length === 1) {
    await selectStore(stores.value[0]!.id)
    if (orgCreateRole.value === 'member') orgCreateRole.value = 'staff'
  }
  await loadReviewToggle()
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

// ---------- 任务书 #48：子账号直建 / 停用恢复 / 审核开关 ----------

const memberReviewRequired = ref(false)
/** 主体成员账号前缀（#49 D5）：建号预览与设置用；组织成员可读。 */
const accountPrefix = ref('')
const prefixInput = ref('')
const orgCreateLoginName = ref('')
const orgCreateName = ref('')
/** 组织级建号目标角色：member=主体成员；manager/staff 须再选门店（D1：任命店长仅 ADMIN+，本入口本身就是 ADMIN+ 门禁）。 */
const orgCreateRole = ref<'member' | 'manager' | 'staff'>('member')
const orgCreateStoreId = ref('')
const storeCreateLoginName = ref('')
const storeCreateName = ref('')

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

/** 组织级建号：登录名合法且（多店时）选了门店角色但未选门店时禁用提交。单店隐式用唯一门店。 */
const orgCreateDisabled = computed(() => {
  if (!loginNameValid(orgCreateLoginName.value) || !orgCreateName.value.trim()) return true
  if (singleStore.value) return false
  return orgCreateRole.value !== 'member' && !orgCreateStoreId.value
})

/** 门店角色的目标门店：单店隐式取唯一门店（UI 不渲染选择），多店显式选择。 */
const effectiveStoreId = computed(() =>
  orgCreateRole.value === 'member' ? undefined
    : singleStore.value ? (stores.value[0]?.id ?? '') : orgCreateStoreId.value)

/** 建号预览：前缀-登录名（后端拼同样规则，前端只做提示）。 */
const orgUsernamePreview = computed(() =>
  accountPrefix.value && orgCreateLoginName.value.trim()
    ? `${accountPrefix.value}-${orgCreateLoginName.value.trim().toLowerCase()}`
    : '')
const storeUsernamePreview = computed(() =>
  accountPrefix.value && storeCreateLoginName.value.trim()
    ? `${accountPrefix.value}-${storeCreateLoginName.value.trim().toLowerCase()}`
    : '')

/**
 * 建号/重置刚返回的一次性初始密码——响应之后任何接口都取不到，展示区只存在到
 * 用户点「我已保存」为止。这是「商家直建、线下交付」模型的安全底线（PRD §2.1）。
 */
const oneTimePassword = ref<{ username: string; password: string } | null>(null)

async function loadReviewToggle(): Promise<void> {
  if (!props.orgId) return
  const state = await grassland.getMemberReviewRequired(props.orgId)
  if (state) memberReviewRequired.value = state.required
  const prefix = await grassland.getAccountPrefix(props.orgId)
  if (prefix) accountPrefix.value = prefix.prefix
}

/** 改前缀（#49 D5）：ADMIN+；成功后只影响之后新建的账号。 */
async function savePrefix(): Promise<void> {
  const next = prefixInput.value.trim().toLowerCase()
  if (!LOGIN_NAME_RE.test(next)) return
  notice.value = ''
  const updated = await grassland.setAccountPrefix(props.orgId, next)
  if (!updated) return
  accountPrefix.value = next
  prefixInput.value = ''
  notice.value = `前缀已改为 ${next}（只影响之后新建的账号）`
}

async function toggleReview(event: Event): Promise<void> {
  const box = event.target as HTMLInputElement
  const required = box.checked
  notice.value = ''
  const updated = await grassland.setMemberReviewRequired(props.orgId, required)
  if (!updated) {
    // 切换失败（如非管理员 403）回滚 UI；原生 checked 不受 Vue 状态绑定管理，须显式写回
    memberReviewRequired.value = !required
    await nextTick()
    box.checked = !required
    return
  }
  notice.value = required ? '已开启：店长添加的员工须经主体审核后启用' : '已关闭：店长添加员工即时生效'
}

async function createOrgAccount(): Promise<void> {
  const loginName = orgCreateLoginName.value.trim().toLowerCase()
  const displayName = orgCreateName.value.trim()
  if (!loginNameValid(loginName) || !displayName) return
  const targetStoreId = effectiveStoreId.value
  if (orgCreateRole.value !== 'member' && !targetStoreId) return
  notice.value = ''
  const created = await grassland.createSubAccount(props.orgId, {
    role: orgCreateRole.value,
    loginName,
    displayName,
    ...(orgCreateRole.value !== 'member' ? { storeId: targetStoreId } : {}),
  })
  if (!created) return
  orgCreateLoginName.value = ''
  orgCreateName.value = ''
  const roleLabel = orgCreateRole.value === 'member' ? ROLE_LABEL.member
    : STORE_ROLE_LABEL[orgCreateRole.value]
  oneTimePassword.value = { username: created.account.username, password: created.initialPassword ?? '' }
  notice.value = created.account.status === 'pending_review'
    ? `${roleLabel}账号已登记，待审核通过后才能登录使用`
    : `${roleLabel}账号已创建，凭据请线下转交`
  await reloadMembers()
  if (orgCreateRole.value !== 'member') await selectStore(targetStoreId as string)
}

async function createStoreAccount(): Promise<void> {
  const loginName = storeCreateLoginName.value.trim().toLowerCase()
  const displayName = storeCreateName.value.trim()
  if (!loginNameValid(loginName) || !displayName || !selectedStoreId.value) return
  notice.value = ''
  const created = await grassland.createStaffSubAccount(props.orgId, selectedStoreId.value, {
    loginName, displayName,
  })
  if (!created) return
  storeCreateLoginName.value = ''
  storeCreateName.value = ''
  oneTimePassword.value = { username: created.account.username, password: created.initialPassword ?? '' }
  notice.value = created.account.status === 'pending_review'
    ? '员工账号已登记，待主体审核通过后才能登录使用'
    : '账号已创建，凭据请线下转交'
  await selectStore(selectedStoreId.value)
}

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

/** 审核店长代建的员工（D6）：approve 即启用；reject 是终态，账号不可再用。 */
async function reviewCreation(accountId: string, decision: 'approve' | 'reject'): Promise<void> {
  notice.value = ''
  const done = await grassland.reviewSubAccountCreation(props.orgId, accountId, decision)
  if (done === null) return
  notice.value = decision === 'approve' ? '已通过审核，该员工现在可以登录使用' : '已驳回，该账号将不可用'
  if (selectedStoreId.value) await selectStore(selectedStoreId.value)
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

    <!-- 成员账号前缀（任务书 #49 D5）：改前缀只影响之后新建的账号 -->
    <div class="team-row">
      <span class="team-tag">账号前缀 {{ accountPrefix || '…' }}</span>
      <input v-model="prefixInput" class="team-prefix-input" placeholder="新前缀（字母数字）" />
      <button
        type="button" class="team-quiet"
        :disabled="grassland.loading.value || !loginNameValid(prefixInput)"
        @click="savePrefix"
      >改前缀</button>
      <span class="team-hint">成员账号名 = 前缀-登录名；需管理员</span>
    </div>

    <!-- 审核开关：仅影响店长代建路径，owner/admin 直建永不 pending（任务书 #48 D6） -->
    <div class="team-row">
      <label class="team-toggle">
        <input
          type="checkbox"
          :checked="memberReviewRequired"
          :disabled="grassland.loading.value"
          @change="toggleReview"
        />
        店长添加员工需主体审核
      </label>
      <span class="team-tag">切换需管理员</span>
    </div>

    <!-- 组织成员 -->
    <section class="team-sec">
      <h4>主体成员</h4>
      <p v-if="members.length === 0" class="team-hint">暂无成员记录。</p>
      <table v-else class="team-table">
        <thead><tr><th>账号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in members" :key="m.id">
            <td><code>{{ m.username || m.accountId.slice(0, 8) + '…' }}</code></td>
            <td>{{ ROLE_LABEL[m.role] || m.role }}</td>
            <td><span v-if="m.accountStatus" class="team-tag">{{ ACCOUNT_STATUS_LABEL[m.accountStatus] || m.accountStatus }}</span></td>
            <td>
              <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
              <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
              <!-- owner 永不可删（服务端守卫 403），不渲染入口 -->
              <button
                v-if="m.role !== 'owner'"
                type="button" class="team-quiet team-danger"
                :disabled="grassland.loading.value"
                @click="askDelete(m.accountId, m.username ?? null)"
              >删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 任务书 #49：邀请表单与「已知账号 ID 直接添加」挂靠入口均已下线（成员经下方主体直建产生） -->

      <!-- 主体直建子账号（任务书 #48/#49/#50）：登录名 = 前缀-登录名，邮箱由成员登录后自行绑定。
           单店模式（拍板②）：角色默认店员、免选门店；店长藏进高级选项 -->
      <details class="team-adv">
        <summary>添加成员：主体直接创建账号</summary>
        <div class="team-row">
          <input
            v-model="orgCreateLoginName"
            placeholder="登录名（3-24 位字母数字）"
            @keyup.enter="createOrgAccount"
          />
          <span v-if="orgUsernamePreview" class="team-tag">{{ orgUsernamePreview }}</span>
          <input v-model="orgCreateName" placeholder="显示名" @keyup.enter="createOrgAccount" />
          <template v-if="singleStore">
            <select v-model="orgCreateRole">
              <option value="staff">店员</option>
              <option value="member">组织成员</option>
            </select>
          </template>
          <select v-else v-model="orgCreateRole">
            <option value="member">组织成员</option>
            <option value="manager">店长</option>
            <option value="staff">店员</option>
          </select>
          <select v-if="!singleStore && orgCreateRole !== 'member'" v-model="orgCreateStoreId">
            <option value="" disabled>选择门店</option>
            <option v-for="s in stores" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <button
            type="button"
            :disabled="grassland.loading.value || orgCreateDisabled"
            @click="createOrgAccount"
          >创建账号</button>
        </div>
        <!-- 单店高级选项（拍板②）：任命店长的能力保留、入口降级 -->
        <details v-if="singleStore" class="team-adv team-adv-nested">
          <summary>高级选项：任命店长</summary>
          <div class="team-row">
            <select v-model="orgCreateRole">
              <option value="staff">店员</option>
              <option value="manager">店长（管理本店与员工）</option>
            </select>
          </div>
          <p class="team-hint">店长可管理门店资料与本店员工；单店通常由你本人管理，需要时再任命。</p>
        </details>
        <p class="team-hint">
          创建即生效（管理员直建不受审核开关影响）；账号名 = 前缀-登录名（如 {{ accountPrefix ? accountPrefix + '-zhangsan' : '前缀-zhangsan' }}），
          系统生成一次性初始密码供你线下转交，对方首次登录须改密，登录后可自行绑定邮箱。
          {{ singleStore ? '账号默认挂到你的门店。' : '选店长/店员时须指定门店。' }}
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
      <p v-if="storeMembers.length === 0" class="team-hint">该门店暂无成员。</p>
      <table v-else class="team-table">
        <thead><tr><th>账号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in storeMembers" :key="m.id">
            <td><code>{{ m.username || m.accountId.slice(0, 8) + '…' }}</code></td>
            <td>{{ STORE_ROLE_LABEL[m.role] || m.role }}</td>
            <td><span v-if="m.accountStatus" class="team-tag">{{ ACCOUNT_STATUS_LABEL[m.accountStatus] || m.accountStatus }}</span></td>
            <td>
              <template v-if="m.accountStatus === 'pending_review'">
                <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="reviewCreation(m.accountId, 'approve')">通过</button>
                <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="reviewCreation(m.accountId, 'reject')">驳回</button>
              </template>
              <template v-else>
                <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
                <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
              </template>
              <button
                type="button" class="team-quiet team-danger"
                :disabled="grassland.loading.value"
                @click="askDelete(m.accountId, m.username ?? null)"
              >删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 任务书 #49：门店邀请表单与「已知账号 ID 直接添加」挂靠入口均已下线（本店员工经下方店长直建产生） -->

      <!-- 店长代建员工（任务书 #48/#49）：固定建店员（任命店长走上方主体入口） -->
      <details class="team-adv">
        <summary>添加店员：直接创建账号</summary>
        <div class="team-row">
          <input
            v-model="storeCreateLoginName"
            placeholder="登录名（3-24 位字母数字）"
            @keyup.enter="createStoreAccount"
          />
          <span v-if="storeUsernamePreview" class="team-tag">{{ storeUsernamePreview }}</span>
          <input v-model="storeCreateName" placeholder="员工姓名" @keyup.enter="createStoreAccount" />
          <button
            type="button"
            :disabled="grassland.loading.value || !loginNameValid(storeCreateLoginName) || !storeCreateName.trim()"
            @click="createStoreAccount"
          >创建店员账号</button>
        </div>
        <p class="team-hint">
          账号名 = 前缀-登录名，系统生成一次性初始密码供你线下转交，对方首次登录须改密，登录后可自行绑定邮箱。
          此处固定创建店员；任命店长需主体管理员在上方主体入口创建。开启审核后新建为「待审核」，主体通过后才能登录。
        </p>
      </details>
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
