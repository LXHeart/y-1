<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { VisualJobItem } from '../../../types/creation-studio'
import GlModal from '../../../components/GlModal.vue'
import VisualCandidateCard from './VisualCandidateCard.vue'
import type { useVisualJob } from '../composables/useVisualJob'
import type { useVisualPlan } from '../composables/useVisualPlan'

const props = defineProps<{
  plan: ReturnType<typeof useVisualPlan>; job: ReturnType<typeof useVisualJob>
  disabled?: boolean; adopting?: boolean; adoptedMediaIds?: string[]; adoptError?: string
}>()
const emit = defineEmits<{
  'candidate-selected': [selection: { itemId: string; artifactId: string }]
  zoom: [url: string]
  'adopt-requested': [selection: { itemId: string; artifactId: string }]
}>()
const { current, quote, quoting, creating, cancelling, error, polling, pollTimedOut } = props.job
const scope = ref<'cover' | 'all' | 'remaining'>('cover')
const consistencyMode = ref<'prompt-only' | 'reference-image'>('prompt-only')
const anchorArtifactId = ref('')
const unknownAck = ref<{ itemIds: string[]; attemptIds: string[] } | null>(null)
const costConfirm = ref<{ itemIds: string[]; quoteId: string; consistencyMode: 'prompt-only' | 'reference-image'
  anchorArtifactId?: string; acknowledgedUnknownAttemptIds: string[] } | null>(null)
const items = computed(() => props.job.itemStates?.value ?? current.value?.items ?? [])
const candidates = computed(() => props.job.candidates?.value ?? items.value.filter(item => item.state === 'succeeded'))
const planItems = computed(() => props.plan.document.value?.items ?? [])
const allIds = computed(() => planItems.value.map(item => item.itemId))
const coverId = computed(() => planItems.value.find(item => item.role === 'cover')?.itemId ?? '')
const finishedIds = computed(() => new Set(candidates.value.map(item => item.itemId)))
const remainingIds = computed(() => allIds.value.filter(id => !finishedIds.value.has(id)))
const selectedIds = computed(() => scope.value === 'cover' ? coverId.value ? [coverId.value] : []
  : scope.value === 'remaining' ? remainingIds.value : allIds.value)
const coverCandidates = computed(() => candidates.value.filter(item => item.itemId === coverId.value))
const referenceAvailable = computed(() => props.job.capabilities?.value?.available
  && props.job.capabilities.value.referenceKinds.includes('image'))
const activeJob = computed(() => current.value != null
  && !['succeeded', 'partial', 'failed', 'cancelled', 'unknown'].includes(current.value.state))
const ready = computed(() => !props.disabled && !props.plan.dirty.value && !props.plan.saving.value
  && !props.plan.current.value?.stale && props.plan.current.value?.status === 'ready'
  && props.plan.current.value.confirmedRevision === props.plan.current.value.revision)
const adoptedSet = computed(() => new Set(props.adoptedMediaIds ?? []))
const isAdopted = (item: VisualJobItem) => adoptedSet.value.has(item.artifact?.deliveryMediaRef.id ?? '')
const selectionAdopted = computed(() => candidates.value.some(item =>
  item.artifact?.id === props.job.selectedCandidate.value?.artifactId && isAdopted(item)))
const stateLabels: Record<string, string> = {
  waiting_anchor: '待封面', queued: '排队中', prepared: '准备中', dispatching: '生成中',
  generated_unsettled: '结算确认中', succeeded: '完成', failed: '失败', cancelled: '已取消', unknown: '待核实',
}
const jobLabels: Record<string, string> = {
  queued: '排队中', running: '生成中', succeeded: '已成功', partial: '部分成功',
  failed: '失败', cancelled: '已取消', unknown: '结果待核实',
}
watch(() => props.plan.current.value?.id, () => { costConfirm.value = null; unknownAck.value = null; anchorArtifactId.value = '' })
watch(() => current.value?.state, state => {
  if (state === 'succeeded' && scope.value === 'cover' && remainingIds.value.length) scope.value = 'remaining'
})

function unknownAttempts(ids: string[]): string[] {
  const jobs = [current.value, ...(props.job.history?.value ?? [])]
  return [...new Set(jobs.flatMap(job => job?.items ?? [])
    .filter(item => item.state === 'unknown' && ids.includes(item.itemId)).map(item => item.attemptId))]
}
async function quoteThenConfirm(ids: string[], acknowledgedUnknownAttemptIds: string[] = []): Promise<void> {
  if (!ids.length || quoting.value || creating.value || !ready.value) return
  const unknown = unknownAttempts(ids).filter(id => !acknowledgedUnknownAttemptIds.includes(id))
  if (unknown.length) { unknownAck.value = { itemIds: ids, attemptIds: unknown }; return }
  const anchor = consistencyMode.value === 'reference-image' && !ids.includes(coverId.value)
    ? anchorArtifactId.value || undefined : undefined
  const fresh = await props.job.estimate(ids, consistencyMode.value, anchor)
  if (fresh) costConfirm.value = { itemIds: ids, quoteId: fresh.id, consistencyMode: consistencyMode.value,
    anchorArtifactId: anchor, acknowledgedUnknownAttemptIds }
}
async function onConfirmCost(): Promise<void> {
  if (!costConfirm.value || creating.value) return
  const intent = costConfirm.value
  const job = await props.job.create({ ...intent, selectedItemIds: intent.itemIds })
  if (job) costConfirm.value = null
}
async function onConfirmUnknownRedo(): Promise<void> {
  const target = unknownAck.value
  unknownAck.value = null
  if (target) await quoteThenConfirm(target.itemIds, target.attemptIds)
}
function onSelect(selection: { itemId: string; artifactId: string }): void {
  props.job.selectCandidate(selection.itemId, selection.artifactId)
  if (selection.itemId === coverId.value) anchorArtifactId.value = selection.artifactId
  emit('candidate-selected', selection)
}
function onAdopt(): void {
  const selection = props.job.selectedCandidate.value
  if (selection && ready.value && !props.adopting) emit('adopt-requested', selection)
}
const costText = computed(() => {
  const value = quote.value
  if (!value) return ''
  return value.billingSource === 'platform'
    ? '预计使用平台预算 ¥' + (value.platformBudgetCents / 100).toFixed(2) + '（' + value.imageCalls + ' 次图片生成）；用户积分 ' + value.userCredits
    : '将由自有模型密钥计费（' + value.imageCalls + ' 次图片生成，外部费用以供应商账单为准）'
})
</script>

<template>
  <section class="studio-panel visual-production-panel" data-test="visual-production-panel" aria-label="图片制作">
    <div class="panel-head">
      <h4>图片制作</h4>
      <span v-if="current" class="badge badge-info" data-test="visual-job-state" aria-live="polite">
        {{ jobLabels[current.state] ?? current.state }} · {{ items.filter(item => item.state === 'succeeded').length }}/{{ planItems.length }} 完成
      </span>
    </div>
    <fieldset class="form-field" :disabled="disabled || activeJob || creating || quoting">
      <legend>生成范围</legend>
      <div class="studio-actions">
        <label><input v-model="scope" type="radio" value="cover"> 先生成封面</label>
        <label><input v-model="scope" type="radio" value="all"> 生成整套（{{ allIds.length }} 张）</label>
        <label v-if="current"><input v-model="scope" type="radio" value="remaining"> 生成剩余（{{ remainingIds.length }} 张）</label>
      </div>
      <label>一致性方式
        <select v-model="consistencyMode" data-test="visual-consistency">
          <option value="prompt-only">文字风格约束</option>
          <option value="reference-image" :disabled="!referenceAvailable">使用封面图片作为参考</option>
        </select>
      </label>
      <p v-if="!referenceAvailable" class="studio-hint">
        {{ props.job.capabilities?.value?.unavailableReason || '当前协议不支持通用图片参考；人物参考不能替代整体风格参考。' }}
      </p>
      <label v-if="consistencyMode === 'reference-image' && coverCandidates.length">参考封面
        <select v-model="anchorArtifactId" data-test="visual-anchor">
          <option value="">请选择已完成的封面</option>
          <option v-for="(item, index) in coverCandidates" :key="item.artifact!.id" :value="item.artifact!.id">封面候选 {{ index + 1 }}</option>
        </select>
      </label>
      <button type="button" class="gl-btn-primary" data-test="visual-quote-start"
        :disabled="!ready || !selectedIds.length || activeJob || creating || quoting"
        @click="quoteThenConfirm(selectedIds)">{{ quoting ? '估算中…' : '查看费用并生成' }}</button>
    </fieldset>
    <p v-if="!ready" class="studio-hint">保存并确认当前计划后可制作；正文变化时需要重新策划。</p>
    <p v-if="polling" class="studio-hint">任务在后台继续，可离开页面后返回查看。</p>
    <p v-if="pollTimedOut" class="studio-hint" data-test="visual-poll-timeout">任务耗时较长，已暂停自动刷新。</p>
    <div v-if="current" class="studio-actions">
      <button type="button" class="secondary" data-test="visual-refresh" @click="props.job.refresh()">刷新状态</button>
      <button v-if="activeJob" type="button" class="secondary" data-test="visual-cancel"
        :disabled="cancelling" @click="props.job.cancel()">{{ cancelling ? '取消中…' : '取消剩余生成' }}</button>
    </div>
    <ul class="progress-list">
      <li v-for="item in items" :key="item.attemptId" class="progress-row" :data-test="'visual-item-' + item.position">
        <span class="badge" :class="item.state === 'succeeded' ? 'badge-success' : item.state === 'failed' ? 'badge-danger' : 'badge-info'">{{ stateLabels[item.state] }}</span>
        <span>第 {{ item.position }} 张</span>
        <span v-if="item.error" class="studio-error">{{ item.error.message }}</span>
        <button v-if="['succeeded', 'failed', 'cancelled', 'unknown'].includes(item.state)" type="button" class="secondary"
          :data-test="(item.state === 'unknown' ? 'visual-redo-unknown-' : 'visual-redo-') + item.position"
          :disabled="!ready || creating || quoting || activeJob" @click="quoteThenConfirm([item.itemId])">
          {{ item.state === 'unknown' ? '重做（需确认可能再次计费）' : '重做' }}
        </button>
      </li>
    </ul>
    <div v-if="candidates.length" class="candidates">
      <VisualCandidateCard v-for="item in candidates" :key="item.artifact!.id" :item="item"
        :selected="props.job.selectedCandidate.value?.artifactId === item.artifact?.id"
        :adopted="isAdopted(item)" :disabled="!ready || adopting" @select="onSelect" @zoom="url => emit('zoom', url)" />
    </div>
    <button v-if="props.job.historyNextCursor?.value" type="button" class="secondary"
      :disabled="props.job.historyLoading.value" @click="props.job.loadHistory(true)">加载更多历史候选</button>
    <div v-if="props.job.selectedCandidate.value" class="studio-actions">
      <button v-if="!selectionAdopted" type="button" class="gl-btn-primary" data-test="visual-adopt"
        :disabled="!ready || adopting" @click="onAdopt">{{ adopting ? '采用中…' : '采用所选' }}</button>
      <span v-else class="badge badge-success" data-test="visual-adopted-badge">已采用（写入交付）</span>
    </div>
    <p v-if="adoptError" class="studio-error" data-test="visual-adopt-error" role="alert">{{ adoptError }}</p>
    <p v-if="error" class="studio-error" data-test="visual-job-error" role="alert">{{ error }}</p>

    <GlModal v-if="costConfirm" title="确认生成费用" trap-focus @close="costConfirm = null">
      <p data-test="visual-cost-text">{{ costText }}</p>
      <ul v-if="quote?.warnings.length"><li v-for="warning in quote.warnings" :key="warning">{{ warning }}</li></ul>
      <p class="studio-hint">报价在 120 秒内可用于开始新任务；已接受任务按该次报价处理，失败和结算结果以实际执行记录为准。</p>
      <p v-if="error" class="studio-error" role="alert">{{ error }}</p>
      <div class="studio-actions">
        <button type="button" class="secondary" data-test="visual-cost-cancel" @click="costConfirm = null">取消</button>
        <button type="button" class="gl-btn-primary" data-test="visual-cost-ok" :disabled="creating"
          @click="onConfirmCost">{{ creating ? '提交中…' : '开始生成' }}</button>
      </div>
    </GlModal>
    <GlModal v-if="unknownAck" title="确认重做未知结果项" trap-focus @close="unknownAck = null">
      <p>上次生成结果尚未核实，供应商可能已经计费。重做会新建请求，并可能再次计费；旧记录会保留。</p>
      <div class="studio-actions">
        <button type="button" class="secondary" data-test="visual-unknown-cancel" @click="unknownAck = null">暂不重做</button>
        <button type="button" class="gl-btn-primary" data-test="visual-unknown-ok" @click="onConfirmUnknownRedo">确认后查看本次费用</button>
      </div>
    </GlModal>
  </section>
</template>

<style scoped>
.visual-production-panel { display: grid; gap: var(--space-md); }
.panel-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: var(--space-sm); }
.panel-head h4 { margin: var(--space-none); }
.progress-list { list-style: none; margin: var(--space-none); padding: var(--space-none); display: grid; gap: var(--space-xs); }
.progress-row { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; padding: var(--space-xs) var(--space-none); border-bottom: var(--border-width) solid var(--color-border); }
.candidates { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--layout-rail)), 1fr)); gap: var(--space-sm); }
</style>
