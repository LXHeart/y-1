<script setup lang="ts">
import { computed, useId } from 'vue'
import type { CanvasPlanResult } from '../../../types/video-canvas'
import type { CanvasShot } from '../useVideoCanvas'
import CanvasPlanPreview from './CanvasPlanPreview.vue'

/**
 * 任务书 #100 C100-18：画布 AI 助手面板——对话/计划/澄清/错误与范围。
 *
 * 显示将发送的节点名称与数量；无选择禁用提交并给具体引导（clarify 由服务端固定引导
 * 呈现，不默认改全项目）；准备生成只填充动作区并指向现有提交按钮——「计划已生成」
 * 绝不显示成「视频已生成」。
 */
const props = withDefaults(defineProps<{
  selectedNodeLabels: string[]
  instruction: string
  plan: CanvasPlanResult | null
  submitting: boolean
  applying: boolean
  error: string
  blockedReason?: string
  baselineShots?: CanvasShot[]
  planNodeLabels?: string[]
  canRetryPending?: boolean
  querying?: boolean
  errorCode?: string
  applyUnknown?: boolean
  readonly?: boolean
}>(), { canRetryPending: undefined })

const emit = defineEmits<{
  (e: 'submit'): void
  (e: 'apply'): void
  (e: 'retry-pending'): void
  (e: 'refresh'): void
  (e: 'recover-apply'): void
  (e: 'update:instruction', value: string): void
}>()

const hasSelection = computed(() => props.selectedNodeLabels.length > 0)
const helpId = useId()
const instructionLength = computed(() => [...props.instruction.trim()].length)
const canSubmit = computed(() => !props.readonly && hasSelection.value && instructionLength.value > 0 && instructionLength.value <= 2000 && !props.submitting)
function onInstructionKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault()
    if (canSubmit.value) emit('submit')
  }
}
const statusLabel = computed(() => {
  const map: Record<string, string> = {
    preparing: '计划生成中…（这是文本计划，不是视频）',
    ready: '修改计划已生成（待你确认应用）',
    clarify: '需要补充信息',
    failed: '计划生成失败',
    applied: '计划已应用',
    expired: '计划已过期',
  }
  return props.plan ? (map[props.plan.status] ?? props.plan.status) : ''
})
const isApplyable = computed(() => props.plan?.status === 'ready' && !!props.plan.action
  && ['edit', 'variant', 'prepare-generation'].includes(props.plan.action.kind))
</script>

<template>
  <section class="assistant-panel gl-zone" data-test="canvas-assistant-panel" aria-label="画布 AI 助手">
    <header class="assistant-head">
      <h3 class="panel-title">AI 助手</h3>
      <span class="field-note" data-test="canvas-assistant-scope">
        将发送 {{ selectedNodeLabels.length }} 个选中节点
      </span>
    </header>

    <ul v-if="hasSelection" class="assistant-scope" data-test="canvas-assistant-nodes">
      <li v-for="label in selectedNodeLabels" :key="label" class="field-note">{{ label }}</li>
    </ul>
    <p v-else class="field-note" role="note" data-test="canvas-assistant-empty-selection">
      先在画布选中要修改的具体镜头——空选择不会修改任何内容（也不会请求付费计划）。
    </p>

    <label class="gl-form-field">
      <span class="field-label">修改要求</span>
      <textarea class="gl-input" rows="3" :value="instruction" data-test="canvas-assistant-instruction"
      :disabled="readonly || submitting || applying" :aria-describedby="helpId"
        :placeholder="hasSelection ? '例如：把选中的两镜旁白改得更口语化' : '先选中镜头…'"
        @input="$emit('update:instruction', ($event.target as HTMLTextAreaElement).value)"
        @keydown="onInstructionKeydown"></textarea>
    </label>
    <p :id="helpId" class="field-note">{{ instructionLength }}/2000 字 · Ctrl/Cmd+Enter 提交，Enter 换行</p>

    <p v-if="readonly" class="field-note" role="status">当前项目仅可查看，不能生成或应用修改计划。</p>
    <button type="button" :class="isApplyable ? 'gl-btn-ghost' : 'gl-btn-primary'" :disabled="!canSubmit || applying"
      data-test="canvas-assistant-submit" @click="emit('submit')">
      {{ submitting ? '请求中…' : '生成修改计划' }}
    </button>

    <p v-if="error" class="field-note runbar-error" role="alert" data-test="canvas-assistant-error" :data-error-code="errorCode">
      {{ error }}
      <button v-if="canRetryPending ?? plan?.status === 'preparing'" type="button" class="gl-btn-ghost" :disabled="submitting"
        data-test="canvas-assistant-retry" @click="emit('retry-pending')">重试上次请求</button>
      <button v-if="plan" type="button" class="gl-btn-ghost" :disabled="querying"
        data-test="canvas-assistant-refresh" @click="emit('refresh')">{{ querying ? '查询中…' : '查询计划状态' }}</button>
    </p>

    <button v-if="applyUnknown" type="button" class="gl-btn-ghost" :disabled="applying" data-test="canvas-assistant-recover-apply"
      @click="emit('recover-apply')">恢复上次应用结果</button>

    <template v-if="plan">
      <p class="field-note" role="status" :data-test="`canvas-assistant-status-${plan.status}`">{{ statusLabel }}</p>
      <p v-if="planNodeLabels?.length" class="field-note" data-test="canvas-plan-scope">本计划范围：{{ planNodeLabels.join('；') }}</p>
      <p class="field-note">草稿 v{{ plan.baseDraftVersion }} · 分镜 v{{ plan.baseEditVersion }} · 画布 v{{ plan.baseCanvasRevision }}</p>
      <p v-if="plan.clarification" class="field-note" data-test="canvas-assistant-clarify">
        {{ plan.clarification }}
      </p>
      <CanvasPlanPreview v-if="plan.action" :action="plan.action" :plan-status="plan.status" :baseline-shots="baselineShots" />
      <p v-if="blockedReason && plan.status === 'ready'" class="field-note" role="status" data-test="canvas-plan-stale">{{ blockedReason }}</p>

      <button v-if="isApplyable" type="button" class="gl-btn-primary" :disabled="readonly || applying || !!blockedReason"
        data-test="canvas-assistant-apply" @click="emit('apply')">
        {{ applying ? '应用中…' : '应用此计划' }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.assistant-panel { display: flex; flex-direction: column; gap: var(--space-sm); min-width: 0; overflow-wrap: anywhere; }
.assistant-panel p, .assistant-panel h3 { margin: 0; }
.assistant-panel textarea { width: 100%; font-family: var(--font-body); font-size: var(--type-body); }
.assistant-scope { margin: 0; padding-left: var(--space-lg); }
.assistant-head { display: flex; flex-direction: column; gap: var(--space-xs); }
.panel-title { font-family: var(--font-display); font-size: var(--type-section-title); }
</style>
