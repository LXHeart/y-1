<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import type {
  AccountClosureCheck,
  AccountClosureRequest,
  PersonalDataExport,
  PiiLifecycleAudit,
} from '../types/grassland'

const grassland = useGrassland()
const { logout } = useAuth()

const exportRequest = ref<PersonalDataExport | null>(null)
const closureCheck = ref<AccountClosureCheck | null>(null)
const closureRequest = ref<AccountClosureRequest | null>(null)
const audit = ref<PiiLifecycleAudit[]>([])
const notice = ref('')
const confirmingClosure = ref(false)
let exportPoll: ReturnType<typeof setTimeout> | null = null

// 任务书 #103 §6.5：preparing 只是「正在核对并停止新任务」，不是账号已注销。
const CLOSURE_STATUS_LABELS: Record<AccountClosureRequest['status'], string> = {
  preparing: '正在核对并停止新任务，尚未注销账号',
  blocked: '注销检查未通过，账号未注销',
  retention: '已进入注销保留期',
  erasing: '正在清理个人数据',
  completed: '个人数据已清理',
  failed: '注销处理失败，系统会自动重试',
  cancelled: '注销已取消',
}

const activeBlockers = computed(() =>
  (closureRequest.value?.blockers.length ? closureRequest.value.blockers : closureCheck.value?.blockers) ?? [])

const ACTION_LABELS: Record<string, string> = {
  export_requested: '已申请数据导出',
  export_completed: '数据导出已生成',
  closure_blocked: '注销检查未通过',
  closure_requested: '已申请账号注销',
  pii_erased: '个人数据已清理',
}

async function createExport(): Promise<void> {
  notice.value = ''
  const created = await grassland.requestPersonalDataExport()
  if (!created) return
  exportRequest.value = created
  scheduleExportPoll()
  await refreshAudit()
}

function scheduleExportPoll(): void {
  if (!exportRequest.value || !['queued', 'processing'].includes(exportRequest.value.status)) return
  if (exportPoll) clearTimeout(exportPoll)
  exportPoll = setTimeout(async () => {
    const latest = await grassland.getPersonalDataExport(exportRequest.value!.id)
    if (latest) exportRequest.value = latest
    scheduleExportPoll()
  }, 2000)
}

async function runClosureCheck(): Promise<void> {
  confirmingClosure.value = false
  closureRequest.value = null
  closureCheck.value = await grassland.checkAccountClosure()
}

async function closeAccount(): Promise<void> {
  if (!closureCheck.value?.eligible) {
    await runClosureCheck()
    return
  }
  if (!confirmingClosure.value) {
    confirmingClosure.value = true
    return
  }
  const closed = await grassland.requestAccountClosure()
  if (!closed) return
  confirmingClosure.value = false
  closureRequest.value = closed
  if (closed.status === 'retention') {
    notice.value = '账号已进入注销保留期'
    await logout()
    return
  }
  // preparing（核对中，worker 续跑收敛）或 blocked（有阻塞原因）都不登出、不宣称已注销。
  notice.value = ''
}

async function refreshAudit(): Promise<void> {
  const result = await grassland.listPiiLifecycleAudit()
  audit.value = Array.isArray(result?.entries) ? result.entries : []
}

function dateLabel(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false })
}

function bytesLabel(value: number | null): string {
  if (value == null) return ''
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

onBeforeUnmount(() => {
  if (exportPoll) clearTimeout(exportPoll)
})
</script>

<template>
  <article class="compliance">
    <header class="compliance-head">
      <h3>个人数据与账号</h3>
      <button type="button" class="quiet" :disabled="grassland.loading.value" @click="refreshAudit">审计记录</button>
    </header>

    <p v-if="grassland.error.value" class="alert error" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="alert ok">{{ notice }}</p>

    <div class="action-row">
      <div>
        <strong>数据副本</strong>
        <p>账号资料与收支记录</p>
      </div>
      <button type="button" :disabled="grassland.loading.value" @click="createExport">生成 ZIP</button>
    </div>
    <div v-if="exportRequest" class="result-row">
      <span>{{ exportRequest.status }}<template v-if="exportRequest.sizeBytes != null"> · {{ bytesLabel(exportRequest.sizeBytes) }}</template></span>
      <a v-if="exportRequest.downloadUrl" :href="exportRequest.downloadUrl">下载</a>
      <span v-else-if="exportRequest.expiresAt">有效期至 {{ dateLabel(exportRequest.expiresAt) }}</span>
    </div>

    <div class="action-row danger-zone">
      <div>
        <strong>注销账号</strong>
        <p>需先结清履约、订单、余额与争议</p>
      </div>
      <button
        v-if="!closureCheck?.eligible"
        type="button" class="quiet" :disabled="grassland.loading.value" @click="runClosureCheck"
      >检查条件</button>
      <button
        v-else type="button" class="danger" :disabled="grassland.loading.value" @click="closeAccount"
      >{{ confirmingClosure ? '确认注销账号' : '申请注销' }}</button>
    </div>
    <div v-if="closureRequest" class="result-row" data-testid="closure-status">
      <span>{{ CLOSURE_STATUS_LABELS[closureRequest.status] }}</span>
      <span v-if="closureRequest.status === 'retention' && closureRequest.retentionUntil">
        保留期至 {{ dateLabel(closureRequest.retentionUntil) }}
      </span>
    </div>
    <ul v-if="activeBlockers.length" class="blockers">
      <li v-for="blocker in activeBlockers" :key="`${blocker.domain}:${blocker.code}`">
        {{ blocker.message }}<template v-if="blocker.amountCents != null">（¥{{ (blocker.amountCents / 100).toFixed(2) }}）</template>
      </li>
    </ul>
    <p v-else-if="closureRequest?.status === 'preparing'" class="eligible">请稍后重新检查注销条件</p>
    <p v-else-if="closureCheck?.eligible" class="eligible">当前满足注销条件</p>

    <ol v-if="audit.length" class="audit-list">
      <li v-for="entry in audit" :key="entry.id">
        <span>{{ ACTION_LABELS[entry.action] || entry.action }}</span>
        <time>{{ dateLabel(entry.occurredAt) }}</time>
      </li>
    </ol>
  </article>
</template>

<style scoped>
.compliance { display: flex; flex-direction: column; gap: var(--space-sm); }
.compliance-head, .action-row, .result-row, .audit-list li { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.compliance-head h3 { margin: 0; font-size: var(--type-body); }
.action-row { padding: var(--space-sm) 0; border-top: 1px solid var(--color-border); }
.action-row p { margin: var(--space-micro) 0 0; font-size: var(--type-caption); color: var(--color-text-muted); }
.result-row { padding: var(--space-xs) var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: var(--type-caption); }
.result-row a { color: var(--color-accent-2); font-weight: var(--weight-heading); }
.danger-zone { border-color: color-mix(in srgb, var(--color-danger) 35%, var(--color-border)); }
.blockers { margin: 0; padding-left: var(--space-lg); color: var(--color-danger); font-size: var(--type-caption); }
.eligible { margin: 0; color: var(--color-success); font-size: var(--type-caption); }
.audit-list { list-style: none; margin: 0; padding: var(--space-xs) 0 0; border-top: 1px solid var(--color-border); display: flex; flex-direction: column; gap: var(--space-xxs); }
.audit-list li { font-size: var(--type-caption); }
.audit-list time { opacity: 1; }
.alert { margin: 0; padding: var(--space-xs) var(--space-sm); border-radius: var(--radius-sm); font-size: var(--type-caption); }
.error { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
button { padding: var(--space-xs) var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text); cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.quiet { font-size: var(--type-caption); opacity: 1; }
.danger { color: var(--color-danger); border-color: var(--color-danger); }
@media (max-width: 560px) { .action-row { align-items: flex-start; } }
</style>
