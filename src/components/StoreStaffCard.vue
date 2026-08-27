<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { Store, StoreMembership } from '../types/grassland'

/**
 * 「本店员工」——店长（门店工作台视图）的员工管理卡。
 *
 * 任务书 #52 池模型（决策 A）：建号、分配、调度、移除全部收归主体管理员（OrgTeamCard），
 * 店长不再自建店员，审核开关与待审流随之退役。本卡收敛为纯管理面：列本店成员、
 * 停用/恢复；人员由主体分配与调度进出本店。删除/建号是主体级动作，不进店长视图。
 */
const props = defineProps<{ orgId: string; stores: Store[] }>()

const grassland = useGrassland()

const selectedStoreId = ref('')
const storeMembers = ref<StoreMembership[]>([])
const notice = ref('')

const ACCOUNT_STATUS_LABEL: Record<string, string> = {
  active: '正常',
  suspended: '已停用',
  pending_review: '待审核',
  rejected: '已驳回',
}

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

</script>

<template>
  <article class="staff-card" aria-label="本店员工">
    <header class="staff-head">
      <h4>本店员工</h4>
      <span v-if="selectedStore" class="staff-tag">{{ selectedStore.name }}</span>
    </header>

    <p v-if="grassland.error.value" class="staff-alert staff-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="staff-alert staff-ok">{{ notice }}</p>

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
            <button type="button" class="staff-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, false)">停用账号</button>
            <button type="button" class="staff-quiet" :disabled="grassland.loading.value" @click="setAccountActive(m.accountId, true)">恢复</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="staff-hint">该门店暂无员工记录。</p>

    <p class="staff-hint">
      成员账号由主体管理员统一创建与分配（含调度进出本店）；如需增减人手或调整岗位，
      请联系主体管理员。你可以停用/恢复本店成员账号。
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
