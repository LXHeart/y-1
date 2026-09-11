<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasProduction } from '../composables/useCanvasProduction'
import { formatYuan } from '../../../lib/money'

/**
 * 任务书 #100 C100-07：画布生成栏——费用/进度/提交主行动。
 *
 * 阶段与进度只认服务端 task.phase/progress（202 受理≠成功）；取消与完成的竞态以
 * 服务端结果为准，「已取消=预留费用全额退还」仅在服务端确认 cancelled 后展示；
 * 存在未确认选片时禁用合成（§4.3）；改配文属交付面板（不触发生成）。
 */
const props = defineProps<{
  production: CanvasProduction
  /** 当前分镜 id（发起制作的幂等键基数）；空=主行动禁用。 */
  storyboardId: string
}>()

const canBegin = computed(() => props.storyboardId.length > 0)

const session = computed(() => props.production.session)
const task = computed(() => props.production.task.value)
const phase = computed(() => task.value?.phase ?? '')
const running = computed(() =>
  ['queued', 'generating', 'voicing', 'composing'].includes(phase.value))
/** 合成入口：选齐 + 无未确认 + 非 composing/终态。 */
const canCompose = computed(() =>
  !!task.value
  && props.production.selectionComplete.value
  && !props.production.composeBlocked.value
  && !['composing', 'succeeded', 'failed', 'cancelled'].includes(phase.value))
/** 选片未齐或未确认时的合成禁用说明。 */
const composeGateNote = computed(() => {
  if (!task.value || canCompose.value || ['composing', 'succeeded', 'failed', 'cancelled'].includes(phase.value)) return ''
  if (props.production.composeBlocked.value) return '有选片仍在保存——确认后才能合成'
  if (!props.production.selectionComplete.value) return '每镜选择一个候选后才能合成'
  return ''
})
/** C100-13：全自有/混合说明（§6.5）——费用为整片一口价，own 镜头不再逐镜生成。 */
const ownMediaNote = computed(() => {
  const shots = task.value?.shots ?? []
  if (!shots.length) return ''
  const own = shots.filter(shot => shot.source?.kind === 'own-media').length
  if (own === 0) return ''
  return own === shots.length
    ? '全部镜头使用自有素材——按整片一口价计费，无逐镜生成费用'
    : `混合制作：${own} 镜使用自有素材，其余镜头生成费用已含在整片一口价`
})

const cancelNote = computed(() =>
  phase.value === 'cancelled' ? '已取消，预留费用已全额退还' : '')

function onBegin(): void {
  void props.production.beginProduction(props.storyboardId)
}
</script>

<template>
  <div class="canvas-runbar gl-zone" data-test="canvas-runbar">
    <template v-if="task">
      <div class="runbar-head">
        <span class="badge badge-accent" data-test="canvas-run-phase">{{ production.phaseLabel.value }}</span>
        <span class="field-note gl-num" data-test="canvas-run-price">
          预估 {{ formatYuan(task.estimatedCostCents) }}
          <template v-if="task.actualCostCents != null">· 实结 {{ formatYuan(task.actualCostCents) }}</template>
        </span>
      </div>

      <div v-if="running" class="runbar-progress">
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: task.progress + '%' }"></div>
        </div>
        <span class="field-note gl-num" data-test="canvas-run-progress">{{ task.progress }}%</span>
        <button
          type="button"
          class="runbar-cancel"
          data-test="canvas-run-cancel"
          @click="session.cancelTask()"
        >取消任务</button>
      </div>

      <div class="runbar-actions">
        <button
          v-if="canCompose"
          type="button"
          class="gl-btn-primary"
          :disabled="production.composeSubmitting.value"
          data-test="canvas-run-compose"
          @click="session.composeTask()"
        >{{ production.composeSubmitting.value ? '合成请求中…' : '合成成片' }}</button>
        <p v-if="composeGateNote" class="field-note" data-test="canvas-run-compose-gate">{{ composeGateNote }}</p>
        <p v-if="ownMediaNote" class="field-note" data-test="canvas-run-own-note">{{ ownMediaNote }}</p>
        <p v-if="cancelNote" class="field-note" data-test="canvas-run-cancel-note">{{ cancelNote }}</p>
        <p v-if="task.errorMessage" class="field-note runbar-error" data-test="canvas-run-task-error">{{ task.errorMessage }}</p>
        <p v-if="session.taskError.value" class="field-note runbar-error" role="alert" data-test="canvas-run-error">
          {{ session.taskError.value }}
        </p>
        <p v-if="task.phase === 'succeeded'" class="field-note" data-test="canvas-run-done">
          成片完成——交付材料与导出见下方交付面板
        </p>
      </div>
    </template>

    <template v-else>
      <button
        type="button"
        class="gl-btn-primary"
        :disabled="!canBegin || production.creating.value"
        data-test="canvas-run-begin"
        @click="onBegin"
      >{{ production.creating.value ? '任务创建中…' : '发起制作' }}</button>
      <span class="field-note">按分镜生成候选；同一分镜重复发起沿用同一任务，不重复计费</span>
      <p v-if="production.createError.value" class="field-note runbar-error" role="alert" data-test="canvas-run-create-error">
        {{ production.createError.value }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.canvas-runbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
}

.runbar-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.runbar-progress {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex: 1;
  min-width: 240px;
}

.progress-bar-track {
  flex: 1;
  height: 6px;
  border-radius: var(--radius-xs);
  background: var(--color-border-hover);
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  border-radius: var(--radius-xs);
  background: var(--color-accent);
  transition: width 0.3s ease;
}

.runbar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
  flex: 1;
}

.runbar-cancel,
.runbar-error {
  color: var(--color-danger);
}

.runbar-cancel {
  min-height: 38px;
  padding: 0 var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: var(--text-sm);
  cursor: pointer;
}

.runbar-cancel:hover {
  border-color: var(--color-border-hover);
  background: var(--surface-hover);
}

@media (max-width: 767px) {
  .runbar-cancel {
    min-height: 44px;
  }
}
</style>
