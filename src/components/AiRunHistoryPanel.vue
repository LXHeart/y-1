<template>
  <section class="ai-control-panel" aria-labelledby="ai-run-history-title">
    <header class="panel-heading">
      <div>
        <h3 id="ai-run-history-title">运行记录</h3>
        <p>最近 50 次通过 AI 控制面执行的任务<template v-if="draftId"> · 关联项目 {{ shortDraftId }}</template></p>
      </div>
      <button type="button" class="icon-command" aria-label="刷新运行记录" :disabled="loading" @click="loadRuns">
        ↻
      </button>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state" aria-live="polite">正在加载运行记录...</p>
    <p v-else-if="!error && runs.length === 0" class="empty-state">暂无运行记录</p>

    <!-- 任务书 #92 C-06：成本摘要（§6.6/AC-502）——token/积分缺字段显示「暂无数据」，不伪造 0 -->
    <p v-else-if="!loading && !error" class="cost-summary gl-num" aria-live="polite">
      <span>{{ visibleRuns.length }} 条记录</span>
      <span>消耗合计 {{ costTotalCents }} 分</span>
      <span data-testid="token-summary">{{ tokenSummaryText }}</span>
    </p>

    <div v-if="!loading && !error && visibleRuns.length" class="data-table-wrap">
      <table class="data-table">
        <thead>
          <tr><th>状态</th><th>能力</th><th>模型</th><th>来源</th><th>费用</th><th>版本</th><th>开始时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="run in visibleRuns" :key="run.runId">
            <td><span class="status-tag" :class="`status-${run.status}`">{{ statusLabel(run.status) }}</span></td>
            <td>{{ capabilityLabel(run.capability) }}</td>
            <td><strong>{{ run.model || '-' }}</strong><small>{{ run.provider }}</small></td>
            <td>{{ run.taskContext.resolutionType === 'BYOK' ? '个人密钥' : '平台模型' }}</td>
            <td>{{ run.actualCents == null ? '-' : `${run.actualCents} 分` }}</td>
            <td>{{ run.taskContext.priceTableVersion }}<small v-if="run.taskContext.platformModelVersion != null">模型 v{{ run.taskContext.platformModelVersion }}</small></td>
            <td>{{ formatDateTime(run.startedAt) }}</td>
            <td>
              <!-- 失败出口（REQ-006）：重试带 runId 沿用既有幂等键；继续编辑回表单不丢已保存输入 -->
              <template v-if="run.status === 'failed'">
                <button type="button" class="row-command" data-testid="retry-run"
                  @click="emit('retry-run', run)">重试</button>
                <button type="button" class="row-command" data-testid="continue-edit"
                  @click="emit('continue-edit', run)">继续编辑</button>
              </template>
              <span v-else>-</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAiControlPlane } from '../composables/useAiControlPlane'
import type { AiRun } from '../types/ai-control-plane'

/**
 * 运行记录面板（任务书 #92 C-06）：可选 draftId/associatedRunIds 关联当前项目（runs API 无
 * draft 维度，按草稿 runIds 客户端过滤）；成本摘要缺字段显示「暂无数据」；失败行提供
 * 重试（载荷带 runId 供工作流沿用既有幂等键，不重复扣费）/继续编辑出口。
 * 无 draft 关联时保持既有全量形态（面板可独立使用）。
 */
const props = defineProps<{
  draftId?: string
  associatedRunIds?: string[]
}>()
const emit = defineEmits<{ 'retry-run': [run: AiRun]; 'continue-edit': [run: AiRun] }>()

const api = useAiControlPlane()
const runs = ref<AiRun[]>([])
const loading = ref(false)
const error = ref('')
let loadEpoch = 0

const visibleRuns = computed(() => props.associatedRunIds?.length
  ? runs.value.filter((run) => props.associatedRunIds?.includes(run.runId))
  : runs.value)
const shortDraftId = computed(() => props.draftId && props.draftId.length > 8
  ? `${props.draftId.slice(0, 8)}…` : props.draftId || '')
const costTotalCents = computed(() =>
  visibleRuns.value.reduce((sum, run) => sum + (run.actualCents ?? 0), 0))
const tokenSummaryText = computed(() => {
  const hasTokens = visibleRuns.value.some((run) => run.inputTokens != null || run.outputTokens != null)
  if (!hasTokens) return 'token 消耗：暂无数据'
  const inputTotal = visibleRuns.value.reduce((sum, run) => sum + (run.inputTokens ?? 0), 0)
  const outputTotal = visibleRuns.value.reduce((sum, run) => sum + (run.outputTokens ?? 0), 0)
  return `token 消耗：${inputTotal} 入 / ${outputTotal} 出`
})

onMounted(() => { void loadRuns() })

async function loadRuns(): Promise<void> {
  const epoch = ++loadEpoch
  loading.value = true
  error.value = ''
  try {
    const result = await api.listRuns()
    if (epoch !== loadEpoch) return   // 快速切换：乱序响应丢弃，以最后一次为准
    runs.value = [...result]
  } catch (caught: unknown) {
    if (epoch !== loadEpoch) return
    runs.value = []
    // 权限撤销/会话失效：只显示错误文案，不渲染任何运行详情
    error.value = caught instanceof Error ? caught.message : '运行记录加载失败'
  } finally {
    if (epoch === loadEpoch) loading.value = false
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
.icon-command { width: 36px; height: 36px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); cursor: pointer; font-size: 1.1rem; }
.icon-command:disabled { cursor: wait; opacity: 0.5; }
.empty-state, .error-state { margin: 0; padding: 24px 0; text-align: center; color: var(--color-text-muted); }
.error-state { color: var(--color-danger); }
.data-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.data-table { width: 100%; min-width: 780px; border-collapse: collapse; font-size: 0.82rem; }
.data-table th, .data-table td { padding: 11px 12px; text-align: left; border-bottom: 1px solid var(--color-border); vertical-align: top; }
.data-table tr:last-child td { border-bottom: 0; }
.data-table th { color: var(--color-text-muted); background: var(--surface-muted); font-weight: 600; }
.data-table td { color: var(--color-text-secondary); }
.data-table strong, .data-table small { display: block; }
.data-table strong { color: var(--color-text); }
.data-table small { margin-top: 2px; color: var(--color-text-muted); }
.status-tag { display: inline-block; padding: 3px 7px; border-radius: var(--radius-sm); background: var(--surface-muted); }
.status-completed { color: var(--color-success); }
.status-failed { color: var(--color-danger); }
.status-running { color: var(--color-accent); }
.cost-summary { display: flex; gap: 16px; flex-wrap: wrap; margin: 0; color: var(--color-text-muted); font-size: 0.8rem; }
.row-command { padding: 3px 8px; margin-right: 4px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text-secondary); font-size: 0.76rem; cursor: pointer; }
.row-command:hover { border-color: var(--color-border-hover); color: var(--color-text); }
</style>
