<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  InvitationStatus,
  Membership,
  MembershipRole,
  OrgInvitation,
  Store,
  StoreMembership,
  StoreRole,
} from '../types/grassland'

/**
 * 组织成员 + 门店 + 门店成员管理（Slice 2F / 2G / 2J 的前端）。
 *
 * 后端三级权限早已建好且全测通，但此前零 UI——多门店、多成员的商家没法自助管理。
 *
 * 加成员的主路径是**按邮箱邀请**（identity 没有、也刻意不做「按邮箱查人」——那等于账号枚举探针）：
 * 邀请只记邮箱，由对方登录后在「我的邀请」里自行接受。填 UUID 直接添加保留为次要入口。
 *
 * 授权分档（后端口径，UI 只做提示，真正门禁在服务端）：
 * - 组织成员增删 / 发邀请 / 撤销邀请：org **OWNER**（且不能经此端点授予 owner）
 * - 建门店：org **ADMIN+**
 * - 门店列表：org MEMBER+；门店成员列表：门店 STAFF+
 * - 任命门店 manager：org **ADMIN+**；加 staff：**门店 MANAGER+**（店长可自管本店员工）
 * - 守卫：移除最后一个 owner / 唯一 manager 均 409
 */

const emit = defineEmits<{ 'stores-changed': [] }>()
const props = defineProps<{ orgId: string }>()

const grassland = useGrassland()

const members = ref<Membership[]>([])
const invitations = ref<OrgInvitation[]>([])
const stores = ref<Store[]>([])
const selectedStoreId = ref('')
const storeMembers = ref<StoreMembership[]>([])
const notice = ref('')

const newMemberEmail = ref('')
const newMemberAccount = ref('')
const newMemberRole = ref<'admin' | 'member'>('member')
const newStoreName = ref('')
const newStoreMemberAccount = ref('')
const newStoreMemberRole = ref<StoreRole>('staff')
const newStoreMemberEmail = ref('')
/** 邀请记录里的门店名（storeId → name），列表渲染用。 */
const storeNameById = computed(() => new Map(stores.value.map((store) => [store.id, store.name])))

const ROLE_LABEL: Record<MembershipRole, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
}

const STORE_ROLE_LABEL: Record<StoreRole, string> = {
  manager: '店长',
  staff: '店员',
}

/** 邀请角色标签：组织级 admin/member 与门店级 staff/manager 并存，逐档回退。 */
function invitationRoleLabel(role: OrgInvitation['role']): string {
  return ROLE_LABEL[role as MembershipRole] || STORE_ROLE_LABEL[role as StoreRole] || role
}

const INVITATION_STATUS_LABEL: Record<InvitationStatus, string> = {
  pending: '待接受',
  accepted: '已接受',
  revoked: '已撤销',
  declined: '已谢绝',
}

async function refresh(): Promise<void> {
  if (!props.orgId) return
  selectedStoreId.value = ''
  storeMembers.value = []
  const m = await grassland.listMemberships(props.orgId)
  const i = await grassland.listInvitations(props.orgId)
  const s = await grassland.listStores(props.orgId)
  if (m) members.value = m
  if (i) invitations.value = i
  if (s) stores.value = s
  await loadReviewToggle()
}

watch(() => props.orgId, refresh, { immediate: true })

async function reloadMembers(): Promise<void> {
  const list = await grassland.listMemberships(props.orgId)
  if (list) members.value = list
}

async function addMember(): Promise<void> {
  const accountId = newMemberAccount.value.trim()
  if (!accountId) return
  notice.value = ''
  const added = await grassland.addMembership(props.orgId, accountId, newMemberRole.value)
  if (!added) return
  newMemberAccount.value = ''
  notice.value = `已添加${ROLE_LABEL[newMemberRole.value]}`
  await reloadMembers()
}

async function reloadInvitations(): Promise<void> {
  const list = await grassland.listInvitations(props.orgId)
  if (list) invitations.value = list
}

async function sendInvite(): Promise<void> {
  const email = newMemberEmail.value.trim()
  if (!email) return
  notice.value = ''
  const created = await grassland.inviteMember(props.orgId, email, newMemberRole.value)
  if (!created) return
  newMemberEmail.value = ''
  // emailSent 如实反映后端是否真的发出邮件：本地未配 SMTP 时为 false，此时必须由邀请人自己通知对方
  notice.value = created.emailSent
    ? `已向 ${created.email} 发出邀请邮件`
    : `已记录对 ${created.email} 的邀请（未配置邮件服务，请自行通知对方登录后接受）`
  await reloadInvitations()
}

/** 复制「邀请直达」链接：对方打开后落到草场工作台的「我的邀请」（链接不含邀请 id，见 DefaultLayout 注释）。 */
async function copyInviteLink(): Promise<void> {
  const url = new URL(window.location.origin + window.location.pathname)
  url.searchParams.set('invite', '1')
  try {
    await navigator.clipboard.writeText(url.toString())
    notice.value = '邀请链接已复制——对方打开后会直达「我的邀请」'
  } catch {
    notice.value = `邀请链接：${url.toString()}`
  }
}

async function revokeInvite(invitation: OrgInvitation): Promise<void> {
  notice.value = ''
  const revoked = await grassland.revokeInvitation(props.orgId, invitation.id)
  if (revoked === null) return  // 已被接受/谢绝等 409 由 error 条呈现
  notice.value = '邀请已撤销'
  await reloadInvitations()
}

async function removeMember(m: Membership): Promise<void> {
  notice.value = ''
  const removed = await grassland.removeMembership(props.orgId, m.accountId)
  if (removed === null) return  // 失败（如末位 owner 守卫 409）已由 error 条呈现
  notice.value = '成员已移除'
  await reloadMembers()
}

async function addStore(): Promise<void> {
  const name = newStoreName.value.trim()
  if (!name) return
  notice.value = ''
  const created = await grassland.createStore(props.orgId, name)
  if (!created) return
  newStoreName.value = ''
  notice.value = `门店「${created.name}」已创建`
  emit('stores-changed')
  const list = await grassland.listStores(props.orgId)
  if (list) stores.value = list
}

async function selectStore(storeId: string): Promise<void> {
  selectedStoreId.value = storeId
  storeMembers.value = []
  const list = await grassland.listStoreMemberships(props.orgId, storeId)
  if (list) storeMembers.value = list
}

async function sendStoreInvite(): Promise<void> {
  const email = newStoreMemberEmail.value.trim()
  if (!email || !selectedStoreId.value) return
  notice.value = ''
  const created = await grassland.inviteMember(props.orgId, email, newStoreMemberRole.value, selectedStoreId.value)
  if (!created) return
  newStoreMemberEmail.value = ''
  notice.value = created.emailSent
    ? `已向 ${created.email} 发出${STORE_ROLE_LABEL[newStoreMemberRole.value]}邀请邮件`
    : `已记录对 ${created.email} 的${STORE_ROLE_LABEL[newStoreMemberRole.value]}邀请（未配置邮件服务，请自行通知对方登录后接受）`
  await reloadInvitations()
}

async function addStoreMember(): Promise<void> {
  const accountId = newStoreMemberAccount.value.trim()
  if (!accountId || !selectedStoreId.value) return
  notice.value = ''
  const added = await grassland.addStoreMembership(
    props.orgId, selectedStoreId.value, accountId, newStoreMemberRole.value)
  if (!added) return
  newStoreMemberAccount.value = ''
  notice.value = `已添加${STORE_ROLE_LABEL[newStoreMemberRole.value]}`
  await selectStore(selectedStoreId.value)
}

async function removeStoreMember(m: StoreMembership): Promise<void> {
  notice.value = ''
  const removed = await grassland.removeStoreMembership(props.orgId, selectedStoreId.value, m.accountId)
  if (removed === null) return  // 末位 manager 守卫 409 等由 error 条呈现
  notice.value = '门店成员已移除'
  await selectStore(selectedStoreId.value)
}

// ---------- 任务书 #48：子账号直建 / 停用恢复 / 审核开关 ----------

const memberReviewRequired = ref(false)
const orgCreateEmail = ref('')
const orgCreateName = ref('')
const storeCreateEmail = ref('')
const storeCreateName = ref('')

/**
 * 建号/重置刚返回的一次性初始密码——响应之后任何接口都取不到，展示区只存在到
 * 用户点「我已保存」为止。这是「商家直建、线下交付」模型的安全底线（PRD §2.1）。
 */
const oneTimePassword = ref<{ email: string; password: string } | null>(null)

async function loadReviewToggle(): Promise<void> {
  if (!props.orgId) return
  const state = await grassland.getMemberReviewRequired(props.orgId)
  if (state) memberReviewRequired.value = state.required
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
  const email = orgCreateEmail.value.trim()
  const displayName = orgCreateName.value.trim()
  if (!email || !displayName) return
  notice.value = ''
  const created = await grassland.createSubAccount(props.orgId, { role: 'member', email, displayName })
  if (!created) return
  orgCreateEmail.value = ''
  orgCreateName.value = ''
  if (created.initialPassword) {
    oneTimePassword.value = { email: created.account.email, password: created.initialPassword }
    notice.value = '账号已创建，凭据请线下转交'
  } else {
    // 无初始密码 = 邮箱已被注册且被确认关联为成员，原账号凭据不受影响
    notice.value = `已将既有平台账号 ${created.account.email} 关联为本主体成员`
  }
  await reloadMembers()
}

async function createStoreAccount(): Promise<void> {
  const email = storeCreateEmail.value.trim()
  const displayName = storeCreateName.value.trim()
  if (!email || !displayName || !selectedStoreId.value) return
  notice.value = ''
  const created = await grassland.createStaffSubAccount(props.orgId, selectedStoreId.value, {
    email, displayName,
  })
  if (!created) return
  storeCreateEmail.value = ''
  storeCreateName.value = ''
  if (created.initialPassword) {
    oneTimePassword.value = { email: created.account.email, password: created.initialPassword }
    notice.value = created.account.status === 'pending_review'
      ? '员工账号已登记，待主体审核通过后才能登录使用'
      : '账号已创建，凭据请线下转交'
  } else {
    notice.value = `已将既有平台账号 ${created.account.email} 关联为本店${STORE_ROLE_LABEL[newStoreMemberRole.value]}`
  }
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
        <strong>{{ oneTimePassword.email }}</strong> 的初始密码：
        <code class="team-pw-code">{{ oneTimePassword.password }}</code>
      </p>
      <p class="team-pw-hint">请立即线下转交本人；对方首次登录须改密。关闭后无法再次查看。</p>
      <button type="button" class="team-quiet" @click="oneTimePassword = null">我已妥善保存</button>
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
        <thead><tr><th>账号</th><th>角色</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in members" :key="m.id">
            <td><code>{{ m.accountId.slice(0, 8) }}…</code></td>
            <td>{{ ROLE_LABEL[m.role] || m.role }}</td>
            <td>
              <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
              <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
              <button
                type="button" class="team-quiet"
                :disabled="grassland.loading.value"
                @click="removeMember(m)"
              >移除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="team-row">
        <input v-model="newMemberEmail" type="email" placeholder="对方邮箱" @keyup.enter="sendInvite" />
        <select v-model="newMemberRole">
          <option value="member">成员</option>
          <option value="admin">管理员</option>
        </select>
        <button type="button" :disabled="grassland.loading.value || !newMemberEmail.trim()" @click="sendInvite">
          发出邀请
        </button>
      </div>
      <p class="team-hint">
        需主体所有者权限；所有者角色只能在创建主体时产生，不能在此授予。
        <strong>系统不会告诉你该邮箱是否已注册</strong>——无论如何都记下邀请，由对方登录后自行接受（防账号枚举）。
      </p>

      <details class="team-adv">
        <summary>已知账号 ID？直接添加</summary>
        <div class="team-row">
          <input v-model="newMemberAccount" placeholder="账号 ID（UUID）" />
          <button
            type="button"
            :disabled="grassland.loading.value || !newMemberAccount.trim()"
            @click="addMember"
          >直接添加</button>
        </div>
        <p class="team-hint">跳过邀请直接入组，仅在你确知对方账号 ID 时可用；角色取上方下拉框的选择。</p>
      </details>

      <!-- 主体直建子账号（任务书 #48）：对方没有平台账号也能入组 -->
      <details class="team-adv">
        <summary>对方没有平台账号？主体直接创建</summary>
        <div class="team-row">
          <input v-model="orgCreateEmail" type="email" placeholder="新账号邮箱" />
          <input v-model="orgCreateName" placeholder="显示名" @keyup.enter="createOrgAccount" />
          <button
            type="button"
            :disabled="grassland.loading.value || !orgCreateEmail.trim() || !orgCreateName.trim()"
            @click="createOrgAccount"
          >创建成员账号</button>
        </div>
        <p class="team-hint">
          创建即生效并挂为本主体成员；系统生成一次性初始密码供你线下转交，对方首次登录须改密。
          若邮箱已是平台账号，会提示你确认后改为「关联为成员」，原密码不受影响。
        </p>
      </details>
    </section>

    <!-- 邀请 -->
    <section class="team-sec">
      <h4>邀请记录</h4>
      <p v-if="invitations.length === 0" class="team-hint">暂无邀请。</p>
      <table v-else class="team-table">
        <thead><tr><th>邮箱</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="i in invitations" :key="i.id">
            <td>{{ i.email }}</td>
            <td>
              <span v-if="i.storeId" class="team-tag">{{ storeNameById.get(i.storeId) || '门店' }}</span>
              {{ invitationRoleLabel(i.role) }}
            </td>
            <td>
              {{ INVITATION_STATUS_LABEL[i.status] || i.status }}
              <span v-if="i.expired" class="team-tag">已过期</span>
            </td>
            <td>
              <template v-if="i.status === 'pending'">
                <button
                  type="button" class="team-quiet"
                  :disabled="grassland.loading.value"
                  @click="copyInviteLink"
                >复制链接</button>
                <button
                  type="button" class="team-quiet"
                  :disabled="grassland.loading.value"
                  @click="revokeInvite(i)"
                >撤销</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 门店 -->
    <section class="team-sec">
      <h4>门店</h4>
      <p v-if="stores.length === 0" class="team-hint">暂无门店。</p>
      <ul v-else class="team-list">
        <li v-for="s in stores" :key="s.id">
          <button
            type="button" class="team-link"
            :class="{ active: selectedStoreId === s.id }"
            @click="selectStore(s.id)"
          >{{ s.name }}</button>
          <span class="team-tag">{{ s.status }}</span>
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

    <!-- 门店成员 -->
    <section v-if="selectedStoreId" class="team-sec">
      <h4>门店成员</h4>
      <p v-if="storeMembers.length === 0" class="team-hint">该门店暂无成员。</p>
      <table v-else class="team-table">
        <thead><tr><th>账号</th><th>角色</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in storeMembers" :key="m.id">
            <td><code>{{ m.accountId.slice(0, 8) }}…</code></td>
            <td>{{ STORE_ROLE_LABEL[m.role] || m.role }}</td>
            <td>
              <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
              <button type="button" class="team-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
              <button
                type="button" class="team-quiet"
                :disabled="grassland.loading.value"
                @click="removeStoreMember(m)"
              >移除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="team-row">
        <input v-model="newStoreMemberEmail" type="email" placeholder="对方邮箱" @keyup.enter="sendStoreInvite" />
        <select v-model="newStoreMemberRole">
          <option value="staff">店员</option>
          <option value="manager">店长</option>
        </select>
        <button
          type="button"
          :disabled="grassland.loading.value || !newStoreMemberEmail.trim()"
          @click="sendStoreInvite"
        >发出邀请</button>
      </div>
      <p class="team-hint">
        邀请店员需本店店长及以上；邀请店长需主体管理员及以上。对方登录后在「我的邀请」接受，
        直接成为本店成员（不占主体成员席位）。系统不会透露该邮箱是否已注册。
      </p>

      <details class="team-adv">
        <summary>已知账号 ID？直接添加</summary>
        <div class="team-row">
          <input v-model="newStoreMemberAccount" placeholder="账号 ID（UUID）" />
          <button
            type="button"
            :disabled="grassland.loading.value || !newStoreMemberAccount.trim()"
            @click="addStoreMember"
          >直接添加</button>
        </div>
        <p class="team-hint">跳过邀请直接入店；角色取上方下拉框的选择。</p>
      </details>

      <!-- 店长代建员工（任务书 #48）：审核开关开启时登记为待启用，须主体过审后登录 -->
      <details class="team-adv">
        <summary>对方没有平台账号？主体直接创建</summary>
        <div class="team-row">
          <input v-model="storeCreateEmail" type="email" placeholder="新账号邮箱" />
          <input v-model="storeCreateName" placeholder="显示名" @keyup.enter="createStoreAccount" />
          <select v-model="newStoreMemberRole">
            <option value="staff">店员</option>
            <option value="manager">店长</option>
          </select>
          <button
            type="button"
            :disabled="grassland.loading.value || !storeCreateEmail.trim() || !storeCreateName.trim()"
            @click="createStoreAccount"
          >创建账号并挂到本店</button>
        </div>
        <p class="team-hint">
          系统生成一次性初始密码供你线下转交，对方首次登录须改密。任命店长需主体管理员权限；
          开启审核后新建店员为待启用状态。
        </p>
      </details>
    </section>
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
input, select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; }
input { min-width: 200px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.team-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
