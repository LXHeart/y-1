<template>
  <section class="analytics-console">
    <header class="panel-head">
      <div><h3>经营分析</h3><p>{{ admin ? '查看组织经营结果与推荐官归因贡献' : '跟踪任务转化、履约和实际经营结果' }}</p></div>
      <div class="panel-actions">
        <button type="button" :disabled="loading || !effectiveOrganizationId" @click="load">刷新</button>
        <button type="button" :disabled="loading || !effectiveOrganizationId" @click="exportCsv">导出 CSV</button>
      </div>
    </header>

    <div class="filters">
      <label v-if="admin">组织 ID<input v-model.trim="organizationFilter" placeholder="输入组织 ID" @keyup.enter="load" /></label>
      <label v-if="admin">门店 ID<input v-model.trim="storeFilter" placeholder="留空为组织级" @keyup.enter="load" /></label>
      <label>开始时间<input v-model="from" type="datetime-local" /></label>
      <label>结束时间<input v-model="to" type="datetime-local" /></label>
      <button type="button" :disabled="loading || !effectiveOrganizationId" @click="load">查询</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="!effectiveOrganizationId" class="empty">{{ admin ? '输入组织 ID 后查询' : '选择组织后查看经营数据' }}</p>
    <p v-else-if="loading && !merchantReport && !adminReport" class="empty">正在汇总经营数据...</p>

    <template v-if="merchantReport">
      <div class="metric-grid">
        <div><span>任务</span><strong>{{ merchantReport.taskCount }}</strong><small>{{ merchantReport.publishedTaskCount }} 个进行中</small></div>
        <div><span>报名</span><strong>{{ merchantReport.totalApplications }}</strong><small>接受率 {{ percent(merchantReport.applicationAcceptanceRate) }}</small></div>
        <div><span>确认履约</span><strong>{{ merchantReport.confirmedDeliverables }}</strong><small>{{ merchantReport.settledEngagements }} 笔已结算</small></div>
        <div><span>平均评分</span><strong>{{ merchantReport.averageRating == null ? '-' : merchantReport.averageRating.toFixed(1) }}</strong><small>满分 5.0</small></div>
        <div><span>净 GMV</span><strong>{{ money(merchantReport.businessMetrics.netGmvCents) }}</strong><small>退款 {{ money(merchantReport.businessMetrics.refundedGmvCents) }}</small></div>
        <div><span>营销 ROI</span><strong>{{ roi(merchantReport.marketingMetrics.roi) }}</strong><small>{{ dataStatus(merchantReport.marketingMetrics.status) }}</small></div>
      </div>
      <section class="funnel">
        <h4>营销转化</h4>
        <div class="funnel-row"><span>曝光</span><meter :value="merchantReport.marketingMetrics.exposures" :max="funnelMax" /><strong>{{ merchantReport.marketingMetrics.exposures }}</strong></div>
        <div class="funnel-row"><span>互动</span><meter :value="merchantReport.marketingMetrics.interactions" :max="funnelMax" /><strong>{{ merchantReport.marketingMetrics.interactions }}</strong></div>
        <div class="funnel-row"><span>转化</span><meter :value="merchantReport.marketingMetrics.conversions" :max="funnelMax" /><strong>{{ merchantReport.marketingMetrics.conversions }}</strong></div>
        <p>归因收入 {{ money(merchantReport.marketingMetrics.attributedRevenueCents) }} · 已结算赏金 {{ money(merchantReport.settledBountyCents) }}</p>
      </section>
      <section v-if="merchantReport.advice?.length || merchantReport.alerts?.length" class="guidance">
        <h4>经营建议与告警</h4>
        <p v-for="item in merchantReport.advice" :key="item.code" :class="`severity-${item.severity}`">
          {{ item.message }}：{{ item.action }}
        </p>
        <p v-for="item in merchantReport.alerts" :key="item.ruleCode" :class="`severity-${item.severity}`">
          {{ item.message }}
        </p>
      </section>
    </template>

    <template v-if="adminReport">
      <div class="metric-grid">
        <div><span>订单</span><strong>{{ adminReport.orders }}</strong><small>{{ adminReport.paidOrders }} 笔已支付</small></div>
        <div><span>已核销</span><strong>{{ adminReport.redeemedOrders }}</strong><small>{{ adminReport.refundedOrders }} 笔退款</small></div>
        <div><span>净 GMV</span><strong>{{ money(adminReport.netGmvCents) }}</strong><small>总额 {{ money(adminReport.grossGmvCents) }}</small></div>
        <div><span>商家收入</span><strong>{{ money(adminReport.merchantRevenueCents) }}</strong><small>平台费 {{ money(adminReport.platformFeeCents) }}</small></div>
        <div><span>归因转化</span><strong>{{ adminReport.attribution.conversions }}</strong><small>{{ adminReport.attribution.interactions }} 次互动</small></div>
        <div><span>营销 ROI</span><strong>{{ roi(adminReport.attribution.roi) }}</strong><small>{{ dataStatus(adminReport.attribution.status) }}</small></div>
      </div>
      <section class="ranking">
        <div class="ranking-head"><h4>推荐官贡献</h4><span>按归因收入排序</span></div>
        <div class="table-wrap"><table><thead><tr><th>推荐官</th><th>转化</th><th>归因收入</th><th>推荐官收入</th></tr></thead><tbody>
          <tr v-for="item in recommenders" :key="item.recommenderAccountId"><td :title="item.recommenderAccountId">{{ compact(item.recommenderAccountId) }}</td><td>{{ item.conversions }}</td><td>{{ money(item.attributedRevenueCents) }}</td><td>{{ money(item.recommenderRevenueCents) }}</td></tr>
          <tr v-if="recommenders.length === 0"><td colspan="4" class="empty">暂无推荐官归因数据</td></tr>
        </tbody></table></div>
      </section>
      <section v-if="adminReport.advice?.length || adminReport.alerts?.length" class="guidance">
        <h4>经营建议与告警</h4>
        <p v-for="item in adminReport.advice" :key="item.code" :class="`severity-${item.severity}`">
          {{ item.message }}：{{ item.action }}
        </p>
        <p v-for="item in adminReport.alerts" :key="item.id" :class="`severity-${item.severity}`">
          {{ item.message }}
        </p>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { BusinessAnalyticsReport, MerchantAnalyticsDashboard, RecommenderAnalyticsReport } from '../types/grassland'

const props = withDefaults(defineProps<{ organizationId?: string; storeId?: string; admin?: boolean }>(), { organizationId: '', storeId: '', admin: false })
const grassland = useGrassland()
const organizationFilter = ref(props.organizationId)
const storeFilter = ref(props.storeId)
const from = ref('')
const to = ref('')
const loading = ref(false)
const error = ref('')
const merchantReport = ref<MerchantAnalyticsDashboard | null>(null)
const adminReport = ref<BusinessAnalyticsReport | null>(null)
const recommenders = ref<RecommenderAnalyticsReport[]>([])
const effectiveOrganizationId = computed(() => props.admin ? organizationFilter.value : props.organizationId)
const effectiveStoreId = computed(() => props.admin ? storeFilter.value : props.storeId)
const funnelMax = computed(() => Math.max(1, merchantReport.value?.marketingMetrics.exposures || 0, merchantReport.value?.marketingMetrics.interactions || 0, merchantReport.value?.marketingMetrics.conversions || 0))

watch(() => [props.organizationId, props.storeId], ([organizationId, storeId]) => {
  if (props.admin) return
  organizationFilter.value = organizationId || ''
  storeFilter.value = storeId || ''
  merchantReport.value = null
  if (organizationId) void load()
}, { immediate: true })

async function load(): Promise<void> {
  if (!effectiveOrganizationId.value || loading.value) return
  if (from.value && to.value && new Date(from.value) >= new Date(to.value)) {
    error.value = '结束时间必须晚于开始时间'
    return
  }
  loading.value = true
  error.value = ''
  merchantReport.value = null
  adminReport.value = null
  recommenders.value = []
  const query = {
    organizationId: effectiveOrganizationId.value,
    storeId: effectiveStoreId.value || undefined,
    from: toIso(from.value),
    to: toIso(to.value),
  }
  if (props.admin) {
    const [report, ranking] = await Promise.all([
      grassland.getAdminBusinessAnalytics(query),
      grassland.getAdminRecommenderAnalytics(query),
    ])
    if (isBusinessReport(report)) adminReport.value = report
    if (Array.isArray(ranking)) recommenders.value = [...ranking].sort((a, b) => b.attributedRevenueCents - a.attributedRevenueCents)
    if (!isBusinessReport(report) || !Array.isArray(ranking)) error.value = grassland.error.value || '经营分析数据格式无效'
  } else {
    const report = await grassland.getMerchantAnalytics(query)
    if (isMerchantDashboard(report)) merchantReport.value = report
    else error.value = grassland.error.value || '经营分析数据格式无效'
  }
  loading.value = false
}

function toIso(value: string): string | undefined { return value ? new Date(value).toISOString() : undefined }
function exportCsv(): void {
  if (!effectiveOrganizationId.value) return
  const query = new URLSearchParams({ organizationId: effectiveOrganizationId.value })
  if (effectiveStoreId.value) query.set('storeId', effectiveStoreId.value)
  if (from.value) query.set('from', toIso(from.value) || '')
  if (to.value) query.set('to', toIso(to.value) || '')
  const endpoint = props.admin ? '/api/admin/analytics/business/export.csv' : '/api/analytics/export.csv'
  window.location.assign(`${endpoint}?${query.toString()}`)
}
function money(cents: number): string { return `¥${(cents / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
function percent(value: number): string { return `${(value * 100).toFixed(1)}%` }
function roi(value: number | 'unavailable' | null): string { return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '-' }
function dataStatus(value: string): string { return ({ not_collected: '尚未采集', unavailable: '数据不足', available: '数据可用', sandbox: 'Sandbox 数据' } as Record<string, string>)[value] || value }
function compact(value: string): string { return value.length > 22 ? `${value.slice(0, 10)}...${value.slice(-6)}` : value }
function isMerchantDashboard(value: unknown): value is MerchantAnalyticsDashboard {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<MerchantAnalyticsDashboard>
  return typeof candidate.taskCount === 'number'
    && Boolean(candidate.businessMetrics && candidate.marketingMetrics)
}
function isBusinessReport(value: unknown): value is BusinessAnalyticsReport {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<BusinessAnalyticsReport>
  return typeof candidate.orders === 'number' && Boolean(candidate.attribution)
}
</script>

<style scoped>
.analytics-console { display: grid; gap: 16px; }
.panel-head, .ranking-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; }.panel-actions { display: flex; gap: 8px; }
.panel-head h3, .panel-head p, .ranking h4 { margin: 0; }.panel-head h3 { font-size: 1rem; }.panel-head p { margin-top: 4px; color: var(--color-text-muted); font-size: .82rem; }
button, input { font: inherit; letter-spacing: 0; } button { min-height: 34px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); cursor: pointer; } button:disabled { opacity: .5; cursor: not-allowed; }
.filters { display: flex; align-items: end; gap: 10px; flex-wrap: wrap; }.filters label { min-width: 170px; display: grid; gap: 5px; color: var(--color-text-muted); font-size: .75rem; }.filters input { box-sizing: border-box; width: 100%; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); }
.metric-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); border-block: 1px solid var(--color-border); }.metric-grid > div { min-width: 0; display: grid; gap: 4px; padding: 16px 14px; border-right: 1px solid var(--color-border); }.metric-grid > div:last-child { border-right: 0; }.metric-grid span, .metric-grid small { color: var(--color-text-muted); font-size: .72rem; }.metric-grid strong { font-size: 1.16rem; overflow-wrap: anywhere; }
.funnel, .ranking { display: grid; gap: 12px; }.funnel h4 { margin: 0; font-size: .88rem; }.funnel-row { display: grid; grid-template-columns: 64px 1fr 70px; align-items: center; gap: 12px; }.funnel-row meter { width: 100%; height: 12px; }.funnel-row strong { text-align: right; }.funnel > p { margin: 0; color: var(--color-text-muted); font-size: .78rem; }
.ranking-head span { color: var(--color-text-muted); font-size: .75rem; }.table-wrap { overflow-x: auto; }table { width: 100%; border-collapse: collapse; font-size: .82rem; }th, td { padding: 9px 10px; border-bottom: 1px solid var(--color-border); text-align: left; }th { color: var(--color-text-muted); font-weight: 600; }
.guidance { display: grid; gap: 8px; border-block: 1px solid var(--color-border); padding-block: 12px; }.guidance h4, .guidance p { margin: 0; }.guidance p { font-size: .82rem; }.severity-critical { color: var(--color-danger); }.severity-warning { color: var(--color-warning, #9a6700); }.severity-info { color: var(--color-text-muted); }
.error { margin: 0; padding: 9px 12px; border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); color: var(--color-danger); }.empty { margin: 0; padding: 24px; text-align: center; color: var(--color-text-muted); }
@media (max-width: 980px) { .metric-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }.metric-grid > div:nth-child(3) { border-right: 0; } }
@media (max-width: 600px) { .filters, .panel-head { align-items: stretch; flex-direction: column; }.filters label { width: 100%; }.metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.metric-grid > div:nth-child(3) { border-right: 1px solid var(--color-border); }.metric-grid > div:nth-child(2n) { border-right: 0; } }
</style>
