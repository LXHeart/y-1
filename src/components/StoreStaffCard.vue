<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { Store, StoreMembership } from '../types/grassland'

/**
 * 「本店员工」——纯门店经理（store-only 视图，任务书 #50 阶段 2）的员工管理卡。
 *
 * 服务对象是 #49 直建的店长子账号（只有门店成员关系、无组织身份）：OrgTeamCard
 * 整卡要求组织身份不可复用，本卡自含门店粒度的最小管理面——列员工、建店员
 * （一次性密码线下转交）、停用/恢复、开审模式下的过审。删除（账号永久作废）
 * 是主体级管理动作，不进店长视图。
 *
 * 全部走 #48/#49 既有端点：/stores/{storeId}/accounts、/accounts/{id}/suspend|
 * restore|review——零后端改动。
 */
const props = defineProps<{ orgId: string; stores: Store[] }>()

const grassland = useGrassland()

const selectedStoreId = ref('')
const storeMembers = ref<StoreMembership[]>([])
const notice = ref('')

const createLoginName = ref('')
const createName = ref('')

const ACCOUNT_STATUS_LABEL: Record<string, string> = {
  active: '正常',
  suspended: '已停用',
  pending_review: '待审核',
  rejected: '已驳回',
}

/** 建号/重置刚返回的一次性初始密码——仅本次响应存在，点「我已保存」即销毁。 */
const oneTimePassword = ref<{ username: string; password: string } | null>(null)

const LOGIN_NAME_RE = /^[a-z0-9]{3,24}$/
const loginNameValid = (v: string) => LOGIN_NAME_RE.test(v.trim().toLowerCase())

const selectedStore = computed(() => props.stores.find((store) => store.id === selectedStoreId.value) ?? null)
const multiStore = computed(() => props.stores.length > 1)

async function selectStore(storeId: string): Promise<void> {
  selectedStoreId.value = storeId
  storeMembers.value = []
  if (!storeId) return
  const list = await grassland.listStoreMemberships(props.orgId, storeId)
  if (list) storeMembers.value = list
}

watch(
  () => props.stores,
  (list) => {
    // 默认选中第一家；已是当前选择则仅刷新成员
    const first = list[0]?.id ?? ''
    if (first && first !== selectedStoreId.value) void selectStore(first)
  },
  { immediate: true },
)

async function createStoreAccount(): Promise<void> {
  const loginName = createLoginName.value.trim().toLowerCase()
  const displayName = createName.value.trim()
  if (!loginNameValid(loginName) || !displayName || !selectedStoreId.value) return
  notice.value = ''
  const created = await grassland.createStaffSubAccount(props.orgId, selectedStoreId.value, {
    loginName, displayName,
  })
  if (!created) return
  createLoginName.value = ''
  createName.value = ''
  oneTimePassword.value = { username: created.account.username, password: created.initialPassword ?? '' }
  notice.value = created.account.status === 'pending_review'
    ? '员工账号已登记，待主体审核通过后才能登录使用'
    : '账号已创建，凭据请线下转交'
  await selectStore(selectedStoreId.value)
}

/** 停用/恢复即时生效；守卫冲突由 error 条原样呈现。 */
async function setAccountActive(accountId: string, active: boolean): Promise<void> {
  notice.value = ''
  const done = active
    ? await grassland.restoreSubAccount(props.orgId, accountId)
    : await grassland.suspendSubAccount(props.orgId, accountId)
  if (done === null) return
  notice.value = active ? '账号已恢复可用' : '账号已停用（立即生效）'
  await selectStore(selectedStoreId.value)
}

/** 开审模式：店长代建的员工行内过审。 */
async function reviewCreation(accountId: string, decision: 'approve' | 'reject'): Promise<void> {
  notice.value = ''
  const done = await grassland.reviewSubAccountCreation(props.orgId, accountId, decision)
  if (done === null) return
  notice.value = decision === 'approve' ? '已通过审核，该员工现在可以登录使用' : '已驳回，该账号将不可用'
  await selectStore(selectedStoreId.value)
}
</script>

<template>
  <article class="staff-card" aria-label="本店员工">
    <header class="staff-head">
      <h4>本店员工</h4>
      <span v-if="selectedStore" class="staff-tag">{{ selectedStore.name }}</span>
    </header>

    <p v-if="grassland.error.value" class="staff-alert staff-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="staff-alert staff-ok">{{ notice }}</p>

    <!-- 一次性初始密码：仅建号响应这一次可见 -->
    <div v-if="oneTimePassword" class="staff-alert staff-pw" data-testid="one-time-password">
      <p class="staff-pw-line">
        账号 <strong>{{ oneTimePassword.username }}</strong> 的初始密码：
        <code class="staff-pw-code">{{ oneTimePassword.password }}</code>
      </p>
      <p class="staff-hint">请立即线下转交本人；对方用该账号名登录，首次登录须改密。关闭后无法再次查看。</p>
      <button type="button" class="staff-quiet" @click="oneTimePassword = null">我已妥善保存</button>
    </div>

    <!-- 多店经理才需要选店；单店隐式 -->
    <ul v-if="multiStore" class="staff-store-list">
      <li v-for="s in stores" :key="s.id">
        <button
          type="button" class="staff-link"
          :class="{ active: selectedStoreId === s.id }"
          @click="selectStore(s.id)"
        >{{ s.name }}</button>
      </li>
    </ul>

    <table v-if="storeMembers.length > 0" class="staff-table">
      <thead><tr><th>账号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="m in storeMembers" :key="m.id">
          <td><code>{{ m.username || m.accountId.slice(0, 8) + '…' }}</code></td>
          <td>{{ m.role === 'manager' ? '店长' : '店员' }}</td>
          <td><span v-if="m.accountStatus" class="staff-tag">{{ ACCOUNT_STATUS_LABEL[m.accountStatus] || m.accountStatus }}</span></td>
          <td>
            <template v-if="m.accountStatus === 'pending_review'">
              <button type="button" class="staff-quiet" :disabled="grassland.loading.value" @click="reviewCreation(m.accountId, 'approve')">通过</button>
              <button type="button" class="staff-quiet" :disabled="grassland.loading.value" @click="reviewCreation(m.accountId, 'reject')">驳回</button>
            </template>
            <template v-else>
              <button type="button" class="staff-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
              <button type="button" class="staff-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
            </template>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="staff-hint">该门店暂无员工记录。</p>

    <div class="staff-row">
      <input
        v-model="createLoginName"
        placeholder="登录名（3-24 位字母数字）"
        @keyup.enter="createStoreAccount"
      />
      <input v-model="createName" placeholder="员工姓名" @keyup.enter="createStoreAccount" />
      <button
        type="button"
        :disabled="grassland.loading.value || !loginNameValid(createLoginName) || !createName.trim()"
        @click="createStoreAccount"
      >创建店员账号</button>
    </div>
    <p class="staff-hint">
      系统生成一次性初始密码供你线下转交，对方首次登录须改密，登录后可自行绑定邮箱。
      若主体开启了「员工添加需审核」，新建员工须主体审核通过后才能登录。
    </p>
  </article>
</template>

<style scoped>
.staff-card { border: 1px solid var(--color-border); border-radius: var(--radius-lg); padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.staff-head { display: flex; align-items: center; gap: 8px; }
.staff-head h4 { margin: 0; font-size: 14px; }
.staff-alert { margin: 0; padding: 7px 11px; border-radius: var(--radius-sm); font-size: 13px; }
.staff-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.staff-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.staff-pw { background: color-mix(in srgb, var(--color-warning) 16%, transparent); display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.staff-pw-line { margin: 0; font-size: 13px; }
.staff-pw-code { font-size: 15px; letter-spacing: 1px; padding: 2px 8px; border-radius: var(--radius-xs); background: var(--color-surface-strong); user-select: all; }
.staff-store-list { list-style: none; margin: 0; padding: 0; display: flex; gap: 8px; flex-wrap: wrap; }
.staff-link { background: transparent; border: none; padding: 2px 0; color: var(--color-text); cursor: pointer; text-decoration: underline; font-size: 13px; }
.staff-link.active { color: var(--color-accent); font-weight: 500; }
.staff-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.staff-table th, .staff-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.staff-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.staff-tag { font-size: 11px; padding: 1px 6px; border-radius: var(--radius-xs); background: var(--color-surface-strong); }
.staff-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.staff-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
input { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; min-width: 180px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
