<script setup lang="ts">
interface Take {
  id: string
  takeNo: number
  status: string
  url?: string | null
  errorMessage?: string | null
  score?: number | null
  scoreLabels?: string[]
  selectable: boolean
}

interface Shot {
  id: string
  seq: number
  takes: Take[]
}

interface Task {
  selection: Record<string, string>
}

interface Props {
  shot: Shot
  task: Task
  composeSubmitting: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  'select-take': [shotId: string, takeId: string]
  'regenerate-shot': [shotId: string]
}>()

function takeStatusLabel(status: string): string {
  return { queued: '排队中', submitted: '已提交', processing: '生成中', succeeded: '已完成',
    failed: '失败', cancelled: '已取消' }[status] || status
}

/** 质检角标色阶（任务书 #66 D2）：≥80 优、60-79 提示、<60 风险——复用既有 badge token。 */
function scoreBadgeClass(score: number): string {
  if (score >= 80) return 'badge-success'
  if (score >= 60) return 'badge-warning'
  return 'badge-danger'
}

function shotLabel(shot: { takes: Array<{ status: string }> }): string {
  const active = shot.takes.filter((take) => take.status !== 'succeeded' && take.status !== 'failed'
    && take.status !== 'cancelled')
  return active.length > 0 ? '生成中' : '已完成'
}
</script>

<template>
  <div
    class="shot-card gl-tile"
    data-test="pick-shot"
  >
    <div class="shot-head">
      <span class="shot-badge">第 {{ shot.seq }} 镜</span>
      <span class="field-note">{{ shotLabel(shot) }}</span>
      <button
        type="button"
        class="btn-secondary btn-sm"
        :disabled="composeSubmitting"
        data-test="regenerate-shot"
        @click="emit('regenerate-shot', shot.id)"
      >
        重抽
      </button>
    </div>

    <div class="take-matrix">
      <div
        v-for="take in shot.takes"
        :key="take.id"
        class="take-card"
        :class="{ 'take-selected': task.selection[shot.id] === take.id }"
        data-test="take-card"
      >
        <video
          v-if="take.url"
          :src="take.url"
          class="take-video"
          controls
          muted
          preload="metadata"
        ></video>
        <div v-else class="take-placeholder">
          <span>{{ takeStatusLabel(take.status) }}</span>
          <span v-if="take.errorMessage" class="field-note">{{ take.errorMessage }}</span>
        </div>
        <div
          v-if="take.score != null"
          class="take-score-row"
          :data-test="`take-score-${take.takeNo}`"
        >
          <span class="badge" :class="scoreBadgeClass(take.score)">质检 {{ take.score }}</span>
          <span
            v-for="label in take.scoreLabels"
            :key="label"
            class="badge badge-neutral"
            :data-test="`take-score-label-${take.takeNo}`"
          >{{ label }}</span>
        </div>
        <label class="take-pick" :class="{ 'take-pick-disabled': !take.selectable }">
          <input
            type="radio"
            :name="`shot-${shot.id}-take`"
            :checked="task.selection[shot.id] === take.id"
            :disabled="!take.selectable"
            :data-test="`take-radio-${take.takeNo}`"
            @change="emit('select-take', shot.id, take.id)"
          />
          采用
        </label>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shot-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  margin-bottom: var(--space-md);
}

.shot-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.shot-head .field-note {
  margin: 0 0 0 auto;
}

.shot-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  color: var(--color-accent);
}

.field-note {
  font-size: 13px;
  color: var(--color-text-muted);
}

.take-matrix {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--space-sm);
}

.take-score-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
}

.take-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-xs);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.take-card.take-selected {
  border-color: var(--color-accent);
}

.take-video {
  width: 100%;
  aspect-ratio: 9 / 16;
  max-height: 260px;
  object-fit: contain;
  background: var(--color-surface-strong);
  border-radius: var(--radius-sm);
}

.take-placeholder {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  aspect-ratio: 9 / 16;
  max-height: 260px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.take-pick {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
}

.take-pick-disabled {
  color: var(--color-text-secondary);
  opacity: 0.6;
}

/* 父级 scoped 共享类复制（scoped 不穿透子组件，样式须随迁） */
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  text-decoration: none;
}

.btn-sm {
  font-size: var(--text-xs);
  padding: 4px 10px;
}
</style>
