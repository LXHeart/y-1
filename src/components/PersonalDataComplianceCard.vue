<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import type {
  AccountClosureCheck,
  PersonalDataExport,
  PiiLifecycleAudit,
} from '../types/grassland'

const grassland = useGrassland()
const { logout } = useAuth()

const exportRequest = ref<PersonalDataExport | null>(null)
const closureCheck = ref<AccountClosureCheck | null>(null)
const audit = ref<PiiLifecycleAudit[]>([])
const notice = ref('')
const confirmingClosure = ref(false)
let exportPoll: ReturnType<typeof setTimeout> | null = null

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
  notice.value = '账号已进入注销保留期'
  await logout()
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
    <ul v-if="closureCheck?.blockers.length" class="blockers">
      <li v-for="blocker in closureCheck.blockers" :key="`${blocker.domain}:${blocker.code}`">
        {{ blocker.message }}<template v-if="blocker.amountCents != null">（¥{{ (blocker.amountCents / 100).toFixed(2) }}）</template>
      </li>
    </ul>
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
.compliance { display: flex; flex-direction: column; gap: 10px; }
.compliance-head, .action-row, .result-row, .audit-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.compliance-head h3 { margin: 0; font-size: 15px; }
.action-row { padding: 10px 0; border-top: 1px solid var(--color-border); }
.action-row p { margin: 2px 0 0; font-size: 12px; opacity: 0.62; }
.result-row { padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 12px; }
.result-row a { color: var(--color-accent); font-weight: 600; }
.danger-zone { border-color: color-mix(in srgb, var(--color-danger) 35%, var(--color-border)); }
.blockers { margin: 0; padding-left: 20px; color: var(--color-danger); font-size: 12px; }
.eligible { margin: 0; color: var(--color-success); font-size: 12px; }
.audit-list { list-style: none; margin: 0; padding: 8px 0 0; border-top: 1px solid var(--color-border); display: flex; flex-direction: column; gap: 5px; }
.audit-list li { font-size: 12px; }
.audit-list time { opacity: 0.58; }
.alert { margin: 0; padding: 7px 11px; border-radius: var(--radius-sm); font-size: 13px; }
.error { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
button { padding: 6px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text); cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.quiet { font-size: 12px; opacity: 0.75; }
.danger { color: var(--color-danger); border-color: var(--color-danger); }
@media (max-width: 560px) { .action-row { align-items: flex-start; } }
</style>
