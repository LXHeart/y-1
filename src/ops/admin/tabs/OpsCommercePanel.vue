<template>
  <section class="ops-commerce">
    <header>
      <div>
        <h3>经营看板</h3>
        <p>套餐推广经营度量（任务书 #98 C98-05）：每个指标标注数据来源与统计窗口；归因销售额不宣称增量收益。</p>
      </div>
      <div class="filters">
        <select v-model="windowDays" data-testid="dashboard-window" @change="loadDashboard">
          <option :value="7">近 7 天</option>
          <option :value="14">近 14 天</option>
          <option :value="30">近 30 天</option>
          <option :value="90">近 90 天</option>
        </select>
        <button type="button" class="ops-button ops-button-primary" :disabled="commerce.loading.value" @click="loadDashboard">刷新</button>
      </div>
    </header>
    <p v-if="error || commerce.error.value" class="error-msg">{{ error || commerce.error.value }}</p>

    <div v-if="dashboard" class="metric-grid" data-testid="ops-metrics">
      <div v-for="metric in dashboard.metrics" :key="metric.key" class="metric-card">
        <div class="metric-label">{{ metric.label }}</div>
        <div class="metric-value">{{ money(metric.valueCents) }}</div>
        <div class="metric-source">来源：{{ metric.source }} · {{ metric.window }}</div>
        <div v-if="metric.note" class="metric-note">{{ metric.note }}</div>
      </div>
    </div>

    <div class="section-head">
      <div><h4>异常订单暂扣队列</h4><p>自动标记（flagged）不碰钱；人工确认后挂起结算并计时处理期限，解除恢复结算。</p></div>
      <div class="filters">
        <select v-model="holdStatus" @change="loadHolds">
          <option value="flagged">待确认（flagged）</option>
          <option value="held">暂扣中（held）</option>
        </select>
        <span>共 {{ holds.length }} 条</span>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>规则 / 原因</th><th>订单</th><th>状态</th><th>标记时间</th><th>处理期限</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="hold in holds" :key="hold.id" :data-testid="`hold-row-${hold.status}`">
            <td class="reason"><strong>{{ ruleLabel(hold.rule) }}</strong><small>{{ hold.reason }}</small></td>
            <td><code>{{ short(hold.orderId) }}</code></td>
            <td><span :class="['status', hold.status]">{{ statusLabel(hold.status) }}</span></td>
            <td>{{ format(hold.flaggedAt) }}</td>
            <td>
              <template v-if="hold.status === 'held'">
                <span :class="{ overdue: isOverdue(hold.holdDeadlineAt) }">{{ countdown(hold.holdDeadlineAt) }}</span>
                <small>{{ format(hold.holdDeadlineAt) }}</small>
              </template>
              <small v-else-if="hold.status === 'flagged'">确认后 72 小时</small>
              <small v-else>—</small>
            </td>
            <td>
              <template v-if="hold.status === 'flagged'">
                <button type="button" class="ops-button ops-button-primary" :disabled="commerce.loading.value" @click="confirm(hold)">确认暂扣</button>
                <button type="button" class="ops-button secondary" :disabled="commerce.loading.value"
                  @click="dismiss(hold)">驳回标记</button>
              </template>
              <template v-else-if="hold.status === 'held'">
                <input v-model="releaseDrafts[hold.id]!.note" placeholder="解除说明（必填）" />
                <button type="button" class="ops-button ops-button-primary" :disabled="commerce.loading.value" @click="release(hold)">解除并恢复结算</button>
              </template>
            </td>
          </tr>
          <tr v-if="holds.length === 0"><td colspan="6" class="empty">暂无{{ holdStatus === 'held' ? '暂扣中' : '待确认' }}记录</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { formatYuan } from '../../../lib/money'
import { useCommerce } from '../../../composables/useCommerce'
import type { OpsDashboard, OpsOrderHold } from '../../../types/commerce'

/** 任务书 #98 C98-05：经营看板页签（指标卡+数据来源脚注）与异常订单人工确认暂扣队列。 */
const commerce = useCommerce()
const dashboard = ref<OpsDashboard | null>(null)
const windowDays = ref(30)
const holds = ref<OpsOrderHold[]>([])
const holdStatus = ref<'flagged' | 'held'>('flagged')
const error = ref('')
const now = ref(Date.now())
const releaseDrafts = reactive<Record<string, { note: string }>>({})
let clock: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  void loadAll()
  clock = setInterval(() => { now.value = Date.now() }, 30_000)
})
onUnmounted(() => { if (clock) clearInterval(clock) })

async function loadAll(): Promise<void> {
  await Promise.all([loadDashboard(), loadHolds()])
}

async function loadDashboard(): Promise<void> {
  error.value = ''
  const result = await commerce.adminOpsDashboard(windowDays.value)
  if (result) dashboard.value = result
}

async function loadHolds(): Promise<void> {
  error.value = ''
  const result = await commerce.listAdminOrderHolds(holdStatus.value)
  if (result) {
    holds.value = result
    for (const hold of result) releaseDrafts[hold.id] ||= { note: '' }
  }
}

/** 人工确认暂扣：flagged → held（结算挂起 + 处理期限），带确认弹窗（不可逆操作前先看原因）。 */
async function confirm(hold: OpsOrderHold): Promise<void> {
  if (!window.confirm(`确认暂扣该订单结算？\n规则：${ruleLabel(hold.rule)}\n原因：${hold.reason}`)) return
  const updated = await commerce.confirmOrderHold(hold.id)
  if (updated) await loadHolds()
}

async function release(hold: OpsOrderHold): Promise<void> {
  const note = (releaseDrafts[hold.id]?.note || '').trim()
  if (!note) {
    error.value = '解除说明必填（审计留痕）'
    return
  }
  error.value = ''
  const updated = await commerce.releaseOrderHold(hold.id, note)
  if (updated) await loadHolds()
}

async function dismiss(hold: OpsOrderHold): Promise<void> {
  const updated = await commerce.dismissOrderHold(hold.id)
  if (updated) await loadHolds()
}

function ruleLabel(rule: string): string {
  return ({ referral_refund_rate: '推荐官退款率', appeal_burst: '申诉集中度', rlid_order_burst: '链接订单激增' })[rule] || rule
}

function statusLabel(status: string): string {
  return ({ flagged: '待确认', held: '暂扣中', released: '已解除', dismissed: '已驳回' })[status] || status
}

function short(value: string): string { return value.length > 12 ? `${value.slice(0, 8)}…` : value }
function money(cents: number): string { return formatYuan(cents) }
function format(value?: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
function isOverdue(value?: string | null): boolean {
  return Boolean(value && new Date(value).getTime() <= now.value)
}
function countdown(value?: string | null): string {
  if (!value) return '无期限'
  const remaining = new Date(value).getTime() - now.value
  if (remaining <= 0) return '已超时'
  const hours = Math.floor(remaining / 3_600_000)
  const minutes = Math.floor((remaining % 3_600_000) / 60_000)
  return `剩余 ${hours}小时${minutes}分`
}
</script>

<style scoped>
.ops-commerce { display: grid; gap: var(--space-md); }
.ops-commerce > header, .filters, .section-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.ops-commerce h3, .ops-commerce h4, .ops-commerce p { margin: 0; }
.ops-commerce header p, .section-head p { font-size: var(--text-xs); opacity: .7; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--space-sm); }
.metric-card { display: grid; gap: var(--space-xs); padding: var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); }
.metric-label { font-size: var(--text-xs); color: var(--color-text-secondary); }
.metric-value { font-size: var(--text-xl); font-weight: 600; }
.metric-source { font-size: var(--text-xs); color: var(--color-text-secondary); }
.metric-note { font-size: var(--text-xs); color: var(--color-text-secondary); opacity: .8; }
.table-wrap { overflow: auto; max-height: min(520px, 64vh); border: 1px solid var(--color-border); border-radius: var(--radius-lg); }
table { width: 100%; border-collapse: collapse; font-size: var(--text-xs); }
th, td { padding: var(--space-xs) var(--space-sm); border-bottom: 1px solid var(--color-border); text-align: left; vertical-align: top; }
th { position: sticky; top: 0; z-index: 1; background: var(--color-surface); }
td code, td small { display: block; margin-top: var(--space-xxs); opacity: .68; }
td.reason small { max-width: 320px; white-space: normal; }
.status { display: inline-flex; padding: var(--space-xxs) var(--space-xs); border-radius: var(--radius-pill); background: color-mix(in srgb, var(--color-accent) 12%, transparent); }
.status.flagged { color: var(--color-warning); }
.status.held { color: var(--color-danger); }
.status.released, .status.dismissed { color: var(--color-text-secondary); }
.error-msg { color: var(--color-danger); margin: 0; }
.empty { text-align: center; opacity: .65; }
select { min-height: var(--ops-control-height); padding: var(--space-xs) var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text); font-size: var(--text-sm); }
button.secondary { opacity: .8; }
td input { width: 100%; min-height: var(--ops-control-height); margin-bottom: var(--space-xs); padding: var(--space-xxs) var(--space-xs); border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font-size: var(--text-xs); }
.overdue { color: var(--color-danger); font-weight: 600; }
</style>
