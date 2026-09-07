<script setup lang="ts">
import { onActivated, ref } from 'vue'
import OpsPagination from '../components/OpsPagination.vue'
import { useGrassland } from '../../../composables/useGrassland'
import { formatDateTime } from '../admin-format'

defineOptions({ name: 'AdminFinancePanel' })

/**
 * 财务对账面板（任务书 #91 A3 自 AdminView.vue 内联分支整段迁出，纯搬运）。
 * fetch=onActivated（D-04）：首次挂载即拉（B-2），之后每次激活重拉（等价原 onActivate）。
 */
const grassland = useGrassland()

interface JournalEntry {
  id: string
  type: string
  operationId: string | null
  currency: string
  organizationId: string | null
  engagementRef: string | null
  memo: string | null
  createdAt: string | null
}
const journals = ref<JournalEntry[]>([])
const journalLoading = ref(false)
const journalError = ref('')
const journalOrgFilter = ref('')
const journalLimit = ref(10)
const journalOffset = ref(0)
const journalTotal = ref(0)
let listRequestVersion = 0

const JOURNAL_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: '充值', RESERVE: '预留', RELEASE: '释放',
  CAPTURE: '结算', REVERSE: '冲正', WITHDRAW: '提现', OPENING: '期初',
  CONSUMER_PAYMENT: '消费支付', CONSUMER_REFUND: '消费退款', CONSUMER_SPLIT: '核销分账',
  FREEBIE_RESERVE: '霸王餐押金预付', FREEBIE_REFUND: '霸王餐押金返还',
  FREEBIE_COMPENSATE: '霸王餐押金补偿',
}

async function loadJournals(): Promise<void> {
  const version = ++listRequestVersion
  journalLoading.value = true
  journalError.value = ''
  const result = await grassland.listFinanceJournals({
    organizationId: journalOrgFilter.value.trim() || undefined,
    limit: journalLimit.value,
    offset: journalOffset.value,
  })
  if (version !== listRequestVersion) return
  if (result) {
    journals.value = result.items as unknown as JournalEntry[]
    journalTotal.value = result.total
  } else {
    journalError.value = grassland.error.value || '账本流水加载失败'
  }
  journalLoading.value = false
}

/** 应用组织筛选：offset 归零后重载。 */
function applyJournalFilter(): void {
  journalOffset.value = 0
  void loadJournals()
}

function changeJournalPage(offset: number): void {
  journalOffset.value = offset
  void loadJournals()
}

function changeJournalLimit(limit: number): void {
  journalLimit.value = limit
  journalOffset.value = 0
  void loadJournals()
}

onActivated(() => {
  void loadJournals()
})
</script>

<template>
  <div class="panel-toolbar">
    <div><h3>账本流水</h3></div>
    <button class="refresh-btn" type="button" :disabled="journalLoading" @click="loadJournals">刷新</button>
  </div>
  <div class="ops-filters">
    <label>组织 ID
      <input v-model="journalOrgFilter" type="text" placeholder="留空 = 全量" @keyup.enter="applyJournalFilter" />
    </label>
    <button type="button" class="refresh-btn" :disabled="journalLoading" @click="applyJournalFilter">查询</button>
  </div>
  <p v-if="journalError" class="error-msg" role="alert">{{ journalError }}</p>
  <div v-if="journalLoading" class="loading-state">加载中...</div>
  <template v-else>
  <div class="table-card">
    <div class="table-scroll">
    <table class="user-table kyb-table">
      <thead><tr><th>类型</th><th>组织</th><th>关联</th><th>备注</th><th>幂等键</th><th>时间</th></tr></thead>
      <tbody>
        <tr v-for="j in journals" :key="j.id">
          <td><span class="type-tag">{{ JOURNAL_TYPE_LABELS[j.type] || j.type }}</span></td>
          <td class="id-cell" :title="j.organizationId || ''">{{ j.organizationId || '—' }}</td>
          <td class="id-cell" :title="j.engagementRef || ''">{{ j.engagementRef || '—' }}</td>
          <td>{{ j.memo || '—' }}</td>
          <td class="id-cell" :title="j.operationId || ''">{{ j.operationId ? j.operationId.slice(0, 16) + '…' : '—' }}</td>
          <td class="td-time">{{ formatDateTime(j.createdAt) }}</td>
        </tr>
        <tr v-if="journals.length === 0"><td colspan="6" class="td-empty">暂无流水</td></tr>
      </tbody>
    </table>
    </div>
  </div>
  <OpsPagination :total="journalTotal" :limit="journalLimit" :offset="journalOffset"
    @change="changeJournalPage" @change-limit="changeJournalLimit" />
  </template>
</template>

<style scoped src="../admin-shared.css"></style>

<style scoped>
/* 财务对账的组织筛选行（类名与运营处置台同名，但 scoped 不跨组件，须本地定义） */
.ops-filters { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.ops-filters label { display: flex; align-items: center; gap: var(--space-xs); font-size: 0.84rem; color: var(--color-text-secondary); }
.ops-filters input { min-height: 32px; padding: 6px var(--space-sm); border:  1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
</style>
