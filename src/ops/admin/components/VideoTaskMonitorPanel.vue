<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import { formatYuan } from '../../../lib/money'

/**
 * 视频任务监控面板（任务书 #65 卡7）：窗口（7d/30d）汇总卡片 + 供应商表。
 * **只读**——干预走既有「AI 模型」/「价目」面板（范围外明示不做）。
 */

interface ProviderRow {
  provider: string
  taskCount: number
  avgSeconds: number
  failureRate: number
}

interface VideoTaskMetrics {
  window: '7d' | '30d'
  taskCount: number
  successRate: number
  cancelRate: number
  avgPipelineSeconds: number
  providers: ProviderRow[]
  costVsRevenue: { costCents: number; revenueCents: number }
  degraded: { slideshowRatio: number; noVoiceRatio: number }
  retryRatio: number
  rerollRatio: number
}

const metrics = ref<VideoTaskMetrics | null>(null)
const statsWindow = ref<'7d' | '30d'>('7d')
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const body = await request<VideoTaskMetrics>(
      `/api/admin/video-production/metrics?window=${statsWindow.value}`, {},
      { fallbackError: '视频任务指标读取失败' })
    metrics.value = body ?? null
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '视频任务指标读取失败'
  } finally {
    loading.value = false
  }
}

function switchWindow(next: '7d' | '30d'): void {
  if (statsWindow.value === next || loading.value) return
  statsWindow.value = next
  void load()
}

function percent(ratio: number): string {
  return `${(ratio * 100).toFixed(1)}%`
}

onMounted(() => { void load() })
</script>

<template>
  <article class="video-monitor-panel" data-test="video-monitor-panel">
    <header class="panel-head">
      <h3>视频任务监控</h3>
      <div class="head-actions">
        <div class="window-switch" role="tablist" aria-label="统计窗口">
          <button
            v-for="option in ['7d', '30d'] as const"
            :key="option"
            type="button"
            role="tab"
            :aria-selected="statsWindow === option"
            :class="{ active: statsWindow === option }"
            :data-test="`window-${option}`"
            @click="switchWindow(option)"
          >
            {{ option === '7d' ? '近 7 天' : '近 30 天' }}
          </button>
        </div>
        <button type="button" :disabled="loading" data-test="refresh-metrics" @click="load">刷新</button>
      </div>
    </header>

    <p v-if="error" class="gl-alert gl-alert-error" role="alert">{{ error }}</p>
    <p v-else-if="loading && !metrics" class="gl-empty">正在读取指标…</p>
    <p v-else-if="metrics && metrics.taskCount === 0" class="gl-empty">窗口内还没有视频任务。</p>

    <template v-if="metrics && metrics.taskCount > 0">
      <div class="metric-grid">
        <div class="metric-card" data-test="metric-card">
          <span class="metric-label">任务总数</span>
          <strong class="metric-value">{{ metrics.taskCount }}</strong>
          <span class="metric-sub">成功率 {{ percent(metrics.successRate) }} · 取消率 {{ percent(metrics.cancelRate) }}</span>
        </div>
        <div class="metric-card" data-test="metric-card">
          <span class="metric-label">平均管线时长</span>
          <strong class="metric-value">{{ metrics.avgPipelineSeconds }}s</strong>
          <span class="metric-sub">成片成功任务 completed − created 均值</span>
        </div>
        <div class="metric-card" data-test="metric-card">
          <span class="metric-label">成本 / 收入</span>
          <strong class="metric-value">{{ formatYuan(metrics.costVsRevenue.costCents) }}</strong>
          <span class="metric-sub">收入 {{ formatYuan(metrics.costVsRevenue.revenueCents) }}（一口价按实际秒）</span>
        </div>
        <div class="metric-card" data-test="metric-card">
          <span class="metric-label">降级占比</span>
          <strong class="metric-value">{{ percent(metrics.degraded.slideshowRatio) }}</strong>
          <span class="metric-sub">图文成片 · 无配音 {{ percent(metrics.degraded.noVoiceRatio) }}</span>
        </div>
        <div class="metric-card" data-test="metric-card">
          <span class="metric-label">重试 / 重抽率</span>
          <strong class="metric-value">{{ percent(metrics.retryRatio) }}</strong>
          <span class="metric-sub">成片后重抽 {{ percent(metrics.rerollRatio) }}</span>
        </div>
      </div>

      <div class="provider-scroll">
        <table class="provider-table" data-test="provider-table">
          <thead>
            <tr>
              <th>供应商</th>
              <th class="num">任务数</th>
              <th class="num">平均秒数</th>
              <th class="num">失败率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in metrics.providers" :key="row.provider" data-test="provider-row">
              <td>{{ row.provider }}</td>
              <td class="num">{{ row.taskCount }}</td>
              <td class="num">{{ row.avgSeconds }}s</td>
              <td class="num">{{ percent(row.failureRate) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="gl-empty note">只读视图：渠道配置与价目干预请前往「AI 模型」面板。</p>
    </template>
  </article>
</template>

<style scoped>
.video-monitor-panel { display: grid; gap: 12px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.panel-head h3 { margin: 0; font-size: 15px; }
.head-actions { display: flex; gap: 8px; align-items: center; }
.head-actions > button { min-height: 34px; padding: 0 14px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; cursor: pointer; font-size: 13px; }
.window-switch { display: inline-flex; padding: 3px; border-radius: var(--radius-pill); background: var(--surface-muted, var(--color-border)); gap: 2px; }
.window-switch button { border: none; background: transparent; padding: 4px 12px; border-radius: var(--radius-pill); font-size: 13px; cursor: pointer; color: var(--color-text); }
.window-switch button.active { background: var(--color-surface, var(--color-canvas, inherit)); font-weight: 600; }

.metric-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; }
.metric-card { display: grid; gap: 4px; padding: 14px 16px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface, transparent); }
.metric-label { font-size: 12px; color: var(--color-text-muted, var(--color-text)); opacity: 0.7; }
.metric-value { font-size: 20px; font-weight: 600; }
.metric-sub { font-size: 12px; opacity: 0.6; }

.provider-scroll { overflow: auto; max-height: min(420px, 52vh); }
.provider-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.provider-table th { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--color-border); font-weight: 600; position: sticky; top: 0; background: var(--color-surface, inherit); }
.provider-table td { padding: 8px 12px; border-bottom: 1px solid var(--color-border); }
.provider-table .num { text-align: right; font-variant-numeric: tabular-nums; }
.note { font-size: 12px; opacity: 0.6; }
</style>
