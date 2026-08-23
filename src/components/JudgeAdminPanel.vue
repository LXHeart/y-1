<template>
  <section class="judge-admin" aria-label="审判官运营准入">
    <div class="panel-toolbar">
      <div>
        <h3>审判官准入</h3>
        <p>当前显示 {{ judges.length }} 名候选人</p>
      </div>
      <button type="button" class="secondary-btn" :disabled="loading" @click="loadJudges(false)">刷新</button>
    </div>
    <div class="judge-search">
      <label>账号 ID
        <input v-model.trim="searchAccountId" type="text" placeholder="精确 UUID" @keyup.enter="loadJudges(false)" />
      </label>
      <button type="button" class="secondary-btn" :disabled="loading" @click="loadJudges(false)">搜索</button>
      <button v-if="searchAccountId" type="button" class="secondary-btn" :disabled="loading" @click="clearSearch">清除</button>
    </div>
    <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-msg" role="status">{{ notice }}</p>
    <div v-if="loading" class="loading-state">加载中...</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr><th>账号</th><th>等级</th><th>入池</th><th>运营准入</th><th>原因</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="judge in judges" :key="judge.id">
            <td class="account-cell" :title="judge.accountId">{{ judge.accountId }}</td>
            <td>Lv{{ judge.eligibilityTier }}</td>
            <td><span :class="judge.active ? 'state-good' : 'state-muted'">{{ judge.active ? '活跃' : '已退池' }}</span></td>
            <td><span :class="judge.opsAdmitted ? 'state-good' : 'state-warn'">{{ judge.opsAdmitted ? '已准入' : '待准入' }}</span></td>
            <td>
              <input
                :value="reasons[judge.id] || ''"
                data-testid="judge-reason"
                type="text"
                maxlength="500"
                placeholder="必填，1-500 字"
                @input="setReason(judge.id, $event)"
              />
            </td>
            <td class="row-actions">
              <button
                type="button"
                :class="judge.opsAdmitted ? 'danger-btn' : 'primary-btn'"
                data-testid="judge-admission-toggle"
                :disabled="savingAccountId === judge.accountId"
                @click="changeAdmission(judge)"
              >{{ judge.opsAdmitted ? '撤销' : '准入' }}</button>
              <button type="button" class="secondary-btn" @click="loadDetail(judge.accountId)">记录</button>
            </td>
          </tr>
          <tr v-if="judges.length === 0"><td colspan="6" class="empty-cell">暂无审判官候选人</td></tr>
        </tbody>
      </table>
    </div>
    <div v-if="nextCursor && !searchAccountId" class="pagination-actions">
      <button type="button" class="secondary-btn" :disabled="loading" @click="loadJudges(true)">加载更多</button>
    </div>

    <section v-if="selected" class="audit-section" aria-labelledby="judge-audit-title">
      <div class="audit-heading">
        <div>
          <h4 id="judge-audit-title">准入记录</h4>
          <p>{{ selected.accountId }}</p>
        </div>
        <button type="button" class="icon-btn" title="关闭记录" aria-label="关闭记录" @click="selected = null">×</button>
      </div>
      <div v-if="detailLoading" class="loading-state">加载中...</div>
      <ol v-else class="audit-list">
        <li v-for="item in selected.audit || []" :key="item.id">
          <span :class="item.action === 'granted' ? 'state-good' : 'state-warn'">
            {{ item.action === 'granted' ? '授予' : '撤销' }}
          </span>
          <strong>{{ item.reason }}</strong>
          <small>{{ item.actorAccountId }} · v{{ item.previousVersion }} → v{{ item.newVersion }} · {{ formatDateTime(item.createdAt) }}</small>
        </li>
        <li v-if="!selected.audit?.length" class="empty-cell">暂无准入变更记录</li>
      </ol>
    </section>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { AdminJudge } from '../types/grassland'

const grassland = useGrassland()
const judges = ref<AdminJudge[]>([])
const reasons = ref<Record<string, string>>({})
const loading = ref(false)
const detailLoading = ref(false)
const savingAccountId = ref('')
const selected = ref<AdminJudge | null>(null)
const error = ref('')
const notice = ref('')
const searchAccountId = ref('')
const nextCursor = ref<string | null>(null)
let listRequestSequence = 0

async function loadJudges(append = false): Promise<void> {
  const requestSequence = ++listRequestSequence
  loading.value = true
  error.value = ''
  notice.value = ''
  const result = await grassland.listAdminJudges({
    limit: 50,
    cursor: append ? nextCursor.value || undefined : undefined,
    accountId: searchAccountId.value || undefined,
  })
  if (requestSequence !== listRequestSequence) return
  if (result) {
    const items = result.items.map(cloneJudge)
    judges.value = append ? [...judges.value, ...items] : items
    nextCursor.value = result.nextCursor
  }
  else error.value = grassland.error.value || '审判官列表加载失败'
  loading.value = false
}

function clearSearch(): void {
  searchAccountId.value = ''
  void loadJudges(false)
}

function cloneJudge(judge: AdminJudge): AdminJudge {
  return { ...judge, audit: judge.audit?.map((item) => ({ ...item })) }
}

function setReason(judgeId: string, event: Event): void {
  reasons.value = {
    ...reasons.value,
    [judgeId]: (event.currentTarget as HTMLInputElement).value,
  }
}

async function changeAdmission(judge: AdminJudge): Promise<void> {
  const reason = (reasons.value[judge.id] || '').trim()
  if (!reason) {
    error.value = '请填写准入原因'
    return
  }
  savingAccountId.value = judge.accountId
  error.value = ''
  notice.value = ''
  const updated = await grassland.updateJudgeAdmission(judge.accountId, {
    admitted: !judge.opsAdmitted,
    expectedVersion: judge.version,
    reason,
  })
  if (updated) {
    judges.value = judges.value.map((item) => item.id === updated.id ? cloneJudge(updated) : item)
    reasons.value = { ...reasons.value, [judge.id]: '' }
    notice.value = updated.opsAdmitted ? '审判官已准入' : '审判官准入已撤销'
    if (selected.value?.id === judge.id) await loadDetail(judge.accountId)
  } else {
    error.value = grassland.error.value || '审判官准入更新失败'
  }
  savingAccountId.value = ''
}

async function loadDetail(accountId: string): Promise<void> {
  detailLoading.value = true
  error.value = ''
  const result = await grassland.getAdminJudge(accountId)
  if (result) selected.value = cloneJudge(result)
  else error.value = grassland.error.value || '准入记录加载失败'
  detailLoading.value = false
}

function formatDateTime(value: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

onMounted(() => void loadJudges(false))
</script>

<style scoped>
.judge-admin { display: grid; gap: 14px; }
.panel-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.panel-toolbar h3, .audit-heading h4 { margin: 0; font-size: 17px; }
.panel-toolbar p, .audit-heading p { margin: 5px 0 0; color: var(--color-text-muted); font-size: 13px; }
.primary-btn, .secondary-btn, .danger-btn, .icon-btn {
  min-height: 34px; padding: 0 11px; border-radius: 6px; border: 1px solid transparent;
  font: inherit; font-weight: 600; cursor: pointer;
}
.primary-btn { background: var(--color-accent); color: var(--color-on-accent); }
.secondary-btn { background: var(--color-surface); border-color: var(--color-border); color: var(--color-text); }
.danger-btn { background: var(--color-danger); color: var(--color-on-accent); }
.icon-btn { width: 34px; padding: 0; background: transparent; color: var(--color-text-muted); font-size: 20px; }
button:disabled { opacity: .55; cursor: not-allowed; }
.error-msg, .success-msg { margin: 0; padding: 9px 11px; border-radius: 6px; font-size: 13px; }
.error-msg { background: color-mix(in srgb, var(--color-danger) 10%, transparent); color: var(--color-danger); border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); }
.success-msg { background: color-mix(in srgb, var(--color-success) 10%, transparent); color: var(--color-success); border: 1px solid color-mix(in srgb, var(--color-success) 30%, transparent); }
.loading-state { padding: 24px; text-align: center; color: var(--color-text-muted); }
.judge-search { display: grid; grid-template-columns: minmax(260px, 520px) auto auto; align-items: end; gap: 8px; }
.judge-search label { display: grid; gap: 5px; color: var(--color-text-muted); font-size: 12px; }
.judge-search input { height: 34px; box-sizing: border-box; padding: 0 8px; border: 1px solid var(--color-border); border-radius: 5px; background: var(--color-surface); color: var(--color-text); }
.pagination-actions { display: flex; justify-content: center; }
.table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: 6px; }
table { width: 100%; border-collapse: collapse; min-width: 900px; }
th, td { padding: 10px 11px; border-bottom: 1px solid var(--color-border); text-align: left; font-size: 13px; }
th { color: var(--color-text-muted); background: var(--color-surface-hover); font-weight: 600; }
tbody tr:last-child td { border-bottom: 0; }
.account-cell { max-width: 230px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, monospace; }
td input { width: 100%; min-width: 170px; height: 34px; box-sizing: border-box; padding: 0 8px; border: 1px solid var(--color-border); border-radius: 5px; background: var(--color-surface); color: var(--color-text); }
.row-actions { display: flex; gap: 6px; white-space: nowrap; }
.state-good { color: var(--color-success); font-weight: 700; }
.state-warn { color: var(--color-warning); font-weight: 700; }
.state-muted { color: var(--color-text-muted); }
.empty-cell { padding: 24px; text-align: center; color: var(--color-text-muted); }
.audit-section { border-top: 1px solid var(--color-border); padding-top: 16px; }
.audit-heading { display: flex; align-items: flex-start; justify-content: space-between; }
.audit-list { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 1px; background: var(--color-border); border: 1px solid var(--color-border); border-radius: 6px; overflow: hidden; }
.audit-list li { display: grid; grid-template-columns: 56px minmax(160px, 1fr) minmax(260px, 1.4fr); align-items: center; gap: 10px; padding: 11px; background: var(--color-surface); font-size: 13px; }
.audit-list small { color: var(--color-text-muted); }
@media (max-width: 720px) {
  .judge-search { grid-template-columns: 1fr; }
  .audit-list li { grid-template-columns: 1fr; }
}
</style>
