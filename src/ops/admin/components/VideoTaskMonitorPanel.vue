<script setup lang="ts">
import { handleTabKeydown } from '../../../lib/tab-navigation'
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
        <div class="window-switch" role="tablist" aria-label="统计窗口" @keydown="handleTabKeydown">
          <button
            v-for="option in ['7d', '30d'] as const"
            :key="option"
            type="button"
            role="tab"
            :aria-selected="statsWindow === option"
            :class="{ active: statsWindow === option }"
            :data-test="`window-${option}`"
            @click="switchWindow(option)"
           :tabindex="(statsWindow === option) ? 0 : -1">
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
.video-monitor-panel { display: grid; gap: var(--space-sm); }
.panel-head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.panel-head h3 { margin: 0; font-size: var(--type-body); }
.head-actions { display: flex; gap: var(--space-xs); align-items: center; }
.head-actions > button { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; cursor: pointer; font-size: var(--type-caption); }
.window-switch { display: inline-flex; padding: var(--space-xxs); border-radius: var(--radius-pill); background: var(--surface-muted, var(--color-border)); gap: var(--space-micro); }
.window-switch button { border: none; background: transparent; padding: var(--space-xxs) var(--space-sm); border-radius: var(--radius-pill); font-size: var(--type-caption); cursor: pointer; color: var(--color-text); }
.window-switch button.active { background: var(--color-surface-highlight); font-weight: var(--weight-heading); }

.metric-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: var(--space-sm); }
.metric-card { display: grid; gap: var(--space-xxs); padding: var(--space-md) var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface, transparent); }
.metric-label { font-size: var(--type-caption); color: var(--color-text-muted, var(--color-text)); opacity: 1; }
.metric-value { font-size: var(--type-page-title); font-weight: var(--weight-heading); }
.metric-sub { font-size: var(--type-caption); opacity: 1; }

.provider-scroll { overflow: auto; max-height: min(420px, 52vh); }
.provider-table { width: 100%; border-collapse: collapse; font-size: var(--type-caption); }
.provider-table th { text-align: left; padding: var(--space-xs) var(--space-sm); border-bottom: 1px solid var(--color-border); font-weight: var(--weight-heading); position: sticky; top: 0; background: var(--color-surface, inherit); }
.provider-table td { padding: var(--space-xs) var(--space-sm); border-bottom: 1px solid var(--color-border); }
.provider-table .num { text-align: right; font-variant-numeric: tabular-nums; }
.note { font-size: var(--type-caption); opacity: 1; }
</style>
