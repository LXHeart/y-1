<template>
  <section class="ai-control-panel" aria-labelledby="ai-run-history-title">
    <header class="panel-heading">
      <div>
        <h3 id="ai-run-history-title">运行记录</h3>
        <p>最近 50 次通过 AI 控制面执行的任务</p>
      </div>
      <button type="button" class="icon-command" aria-label="刷新运行记录" :disabled="loading" @click="loadRuns">
        ↻
      </button>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state" aria-live="polite">正在加载运行记录...</p>
    <p v-else-if="!error && runs.length === 0" class="empty-state">暂无运行记录</p>

    <div v-else-if="!loading && !error" class="data-table-wrap">
      <table class="data-table">
        <thead>
          <tr><th>状态</th><th>能力</th><th>模型</th><th>来源</th><th>费用</th><th>版本</th><th>开始时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.runId">
            <td><span class="status-tag" :class="`status-${run.status}`">{{ statusLabel(run.status) }}</span></td>
            <td>{{ capabilityLabel(run.capability) }}</td>
            <td><strong>{{ run.model || '-' }}</strong><small>{{ run.provider }}</small></td>
            <td>{{ run.taskContext.resolutionType === 'BYOK' ? '个人密钥' : '平台模型' }}</td>
            <td>{{ run.actualCents == null ? '-' : `${run.actualCents} 分` }}</td>
            <td>{{ run.taskContext.priceTableVersion }}<small v-if="run.taskContext.platformModelVersion != null">模型 v{{ run.taskContext.platformModelVersion }}</small></td>
            <td>{{ formatDateTime(run.startedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAiControlPlane } from '../composables/useAiControlPlane'
import type { AiRun } from '../types/ai-control-plane'

const api = useAiControlPlane()
const runs = ref<AiRun[]>([])
const loading = ref(false)
const error = ref('')

onMounted(() => { void loadRuns() })

async function loadRuns(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await api.listRuns()
    runs.value = [...result]
  } catch (caught: unknown) {
    runs.value = []
    error.value = caught instanceof Error ? caught.message : '运行记录加载失败'
  } finally {
    loading.value = false
  }
}

function statusLabel(status: AiRun['status']): string {
  return { running: '执行中', completed: '已完成', failed: '失败', cancelled: '已取消' }[status]
}

function capabilityLabel(capability: string): string {
  return { text: '文本', image: '图片理解', image_generation: '图片生成', video_generation: '视频生成' }[capability] || capability
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(date)
}
</script>

<style scoped>
.ai-control-panel { display: grid; gap: 16px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.panel-heading h3 { margin: 0; color: var(--color-text); font-size: 1.05rem; letter-spacing: 0; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 0.82rem; }
.icon-command { width: 36px; height: 36px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); cursor: pointer; font-size: 1.1rem; }
.icon-command:disabled { cursor: wait; opacity: 0.5; }
.empty-state, .error-state { margin: 0; padding: 24px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }
.data-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: 8px; }
.data-table { width: 100%; min-width: 780px; border-collapse: collapse; font-size: 0.82rem; }
.data-table th, .data-table td { padding: 11px 12px; text-align: left; border-bottom: 1px solid var(--color-border); vertical-align: top; }
.data-table tr:last-child td { border-bottom: 0; }
.data-table th { color: var(--color-text-muted); background: var(--color-surface-muted); font-weight: 600; }
.data-table td { color: var(--color-text-secondary); }
.data-table strong, .data-table small { display: block; }
.data-table strong { color: var(--color-text); }
.data-table small { margin-top: 2px; color: var(--color-text-muted); }
.status-tag { display: inline-block; padding: 3px 7px; border-radius: 5px; background: var(--color-surface-muted); }
.status-completed { color: #15803d; }
.status-failed { color: var(--color-danger); }
.status-running { color: var(--color-accent); }
</style>
