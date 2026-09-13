<script setup lang="ts">
import { computed, ref } from 'vue'
import type { VisualJobItem } from '../../../types/creation-studio'
import GlModal from '../../../components/GlModal.vue'
import VisualCandidateCard from './VisualCandidateCard.vue'
import type { useVisualJob } from '../composables/useVisualJob'
import type { useVisualPlan } from '../composables/useVisualPlan'

/**
 * 任务书 #101 C101-11（§8.1 图片制作区）：进度、生成范围与费用确认。
 *
 * 先确认计划和 quote 再发 job；每项真实阶段（完成/待封面/失败/待核实），不用假进度百分比；
 * 重做需明确范围，unknown 逐项告知并确认（acknowledgedUnknownAttemptIds）；
 * 选择候选只 emit 引用——父层确认成功前不显示「已采用」。
 * C101-12：新增采用入口（§8.1 采用本项）——emit 后由父层走 API101-17；已采用判定以
 * adoptedMediaIds（服务端 resultRefs/delivery 快照）为准，本组件不假报成功。
 */
const props = defineProps<{
  plan: ReturnType<typeof useVisualPlan>
  job: ReturnType<typeof useVisualJob>
  disabled?: boolean
  /** C101-12：采用中（runExternalMutation 互斥）。 */
  adopting?: boolean
  /** C101-12：已采用媒体 ID 集（服务端权威）。 */
  adoptedMediaIds?: string[]
  /** C101-12：采用失败文案。 */
  adoptError?: string
}>()

const emit = defineEmits<{
  (e: 'candidate-selected', selection: { itemId: string; artifactId: string }): void
  (e: 'zoom', url: string): void
  (e: 'adopt-requested', selection: { itemId: string; artifactId: string }): void
}>()

const { current, quote, quoting, creating, cancelling, error, polling, pollTimedOut } = props.job

/** 生成范围（§4.2：默认先生成封面，可明确选整套）。 */
const scope = ref<'cover' | 'all'>('cover')

/** unknown 重做确认弹窗：候选旧 attempt 列表。 */
const unknownAck = ref<{ itemIds: string[]; attemptIds: string[] } | null>(null)

const items = computed<VisualJobItem[]>(() => current.value?.items ?? [])
const planItems = computed(() => props.plan.document.value?.items ?? [])
const allItemIds = computed(() => planItems.value.map((item) => item.itemId))
const coverItemId = computed(() => planItems.value.find((item) => item.role === 'cover')?.itemId
  ?? planItems.value[0]?.itemId ?? '')
const selectedIds = computed(() => (scope.value === 'cover' ? [coverItemId.value] : allItemIds.value))

const stateLabels: Record<string, string> = {
  waiting_anchor: '待封面', queued: '排队中', prepared: '准备中', dispatching: '生成中',
  generated_unsettled: '结算确认中', succeeded: '完成', failed: '失败', cancelled: '已取消', unknown: '待核实',
}
const jobStateLabels: Record<string, string> = {
  queued: '排队中', running: '生成中', succeeded: '已成功', partial: '部分成功', failed: '失败',
  cancelled: '已取消', unknown: '结果待核实',
}

const doneCount = computed(() => items.value.filter((item) => item.state === 'succeeded').length)
const unknownItems = computed(() => items.value.filter((item) => item.state === 'unknown'))
const activeJob = computed(() => current.value != null
  && !['succeeded', 'partial', 'failed', 'cancelled'].includes(current.value.state))

/** 生成入口：quote → 用户确认费用 → create。 */
const costConfirm = ref<{ mode: 'create' | 'redo'; itemIds: string[]; quoteId: string } | null>(null)

async function onQuoteThenConfirm(mode: 'create' | 'redo', itemIds: string[], ack?: string[]): Promise<void> {
  if (!itemIds.length || quoting.value) return
  const fresh = await props.job.estimate(itemIds)
  if (!fresh) return
  if (mode === 'redo' && ack?.length) {
    // unknown 重做：直接携带确认执行（弹窗已在 onRedo 里确认过）
    await props.job.create({ selectedItemIds: itemIds, quoteId: fresh.id, acknowledgedUnknownAttemptIds: ack })
    return
  }
  costConfirm.value = { mode, itemIds, quoteId: fresh.id }
}

function costText(): string {
  const value = quote.value
  if (!value) return ''
  if (value.billingSource === 'platform') {
    return `预计消耗平台预算 ¥${(value.platformBudgetCents / 100).toFixed(2)}（${value.imageCalls} 次图片生成）`
  }
  return `将由你的自有模型密钥计费（${value.imageCalls} 次图片生成，平台不代扣）`
}

async function onConfirmCost(): Promise<void> {
  const target = costConfirm.value
  costConfirm.value = null
  if (!target) return
  await props.job.create({ selectedItemIds: target.itemIds, quoteId: target.quoteId })
}

/** 重做单项：新 quote + 新 key + 显式范围；unknown 旧 attempt 需逐项确认。 */
async function onRedo(item: VisualJobItem): Promise<void> {
  const relatedUnknown = unknownItems.value.filter((entry) => entry.itemId === item.itemId)
  if (relatedUnknown.length) {
    unknownAck.value = {
      itemIds: [item.itemId],
      attemptIds: relatedUnknown.map((entry) => entry.attemptId),
    }
    return
  }
  await onQuoteThenConfirm('redo', [item.itemId])
}

async function onConfirmUnknownRedo(): Promise<void> {
  const target = unknownAck.value
  unknownAck.value = null
  if (!target) return
  await onQuoteThenConfirm('redo', target.itemIds, target.attemptIds)
}

function onSelect(selection: { itemId: string; artifactId: string }): void {
  props.job.selectCandidate(selection.itemId, selection.artifactId)
  emit('candidate-selected', selection)
}

/** 已采用判定：候选的交付媒体 ID 出现在服务端引用快照中。 */
const adoptedSet = computed(() => new Set(props.adoptedMediaIds ?? []))
function isAdopted(item: VisualJobItem): boolean {
  const deliveryId = item.artifact?.deliveryMediaRef?.id
  return deliveryId != null && adoptedSet.value.has(deliveryId)
}

/** 预选候选是否已采用（按钮态切换）。 */
const selectionAdopted = computed(() => {
  const selection = props.job.selectedCandidate.value
  if (!selection) return false
  const item = items.value.find((entry) => entry.itemId === selection.itemId)
  return item != null && isAdopted(item)
})

function onAdopt(): void {
  const selection = props.job.selectedCandidate.value
  if (!selection) return
  emit('adopt-requested', selection)
}
</script>

<template>
  <section class="gl-zone visual-production-panel" data-test="visual-production-panel" aria-label="图片制作">
    <div class="panel-head">
      <h4>图片制作</h4>
      <span v-if="current" class="badge" data-test="visual-job-state">
        {{ jobStateLabels[current.state] ?? current.state }} · {{ doneCount }}/{{ items.length }} 完成
      </span>
    </div>

    <template v-if="!current">
      <!-- 生成范围与费用确认 -->
      <fieldset class="form-field">
        <legend>生成范围</legend>
        <div class="option-grid">
          <label class="style-option" :class="{ active: scope === 'cover' }">
            <input v-model="scope" type="radio" name="visual-scope" value="cover">
            先生成封面
          </label>
          <label class="style-option" :class="{ active: scope === 'all' }">
            <input v-model="scope" type="radio" name="visual-scope" value="all">
            生成整套（{{ allItemIds.length }} 张）
          </label>
        </div>
      </fieldset>
      <div class="actions">
        <button
          type="button"
          class="primary gl-btn-primary"
          data-test="visual-quote-start"
          :disabled="quoting || creating || disabled || !selectedIds.length"
          @click="onQuoteThenConfirm('create', selectedIds)"
        >{{ quoting ? '估算中…' : '查看费用并生成' }}</button>
      </div>
    </template>

    <template v-else>
      <!-- 进度：真实阶段，不用假百分比 -->
      <p v-if="polling" class="hint" aria-live="polite">正在生成…（页面可以离开，任务在后台继续）</p>
      <p v-if="pollTimedOut" class="warn" data-test="visual-poll-timeout">
        任务耗时较长，已暂停自动刷新。
        <button type="button" class="secondary" data-test="visual-refresh" @click="props.job.refresh()">刷新状态</button>
      </p>

      <ul class="progress-list">
        <li v-for="item in items" :key="item.attemptId" class="progress-row" :data-test="`visual-item-${item.position}`">
          <span class="badge" :class="{ ok: item.state === 'succeeded' }">
            {{ stateLabels[item.state] ?? item.state }}
          </span>
          <span class="item-title">第 {{ item.position }} 张</span>
          <span v-if="item.error" class="error-text">{{ item.error.message }}</span>
          <span class="row-actions">
            <button
              v-if="item.state === 'failed' || item.state === 'cancelled'"
              type="button"
              class="secondary"
              :data-test="`visual-redo-${item.position}`"
              :disabled="creating || quoting || disabled"
              @click="onRedo(item)"
            >重做</button>
            <button
              v-if="item.state === 'unknown'"
              type="button"
              class="secondary"
              :data-test="`visual-redo-unknown-${item.position}`"
              :disabled="creating || quoting || disabled"
              @click="onRedo(item)"
            >重做（需确认可能再次计费）</button>
          </span>
        </li>
      </ul>

      <!-- 候选（成功项） -->
      <div v-if="items.some((item) => item.state === 'succeeded')" class="candidates">
        <div
          v-for="item in items.filter((entry) => entry.state === 'succeeded')"
          :key="item.attemptId"
          class="candidate-slot"
        >
          <VisualCandidateCard
            :item="item"
            :selected="props.job.selectedCandidate.value?.artifactId === item.artifact?.id"
            :adopted="isAdopted(item)"
            @select="onSelect"
            @zoom="(url: string) => emit('zoom', url)"
          />
        </div>
      </div>

      <!-- C101-12：采用本项（§8.1）——emit 引用，成功与否由父层/服务端决定 -->
      <div v-if="props.job.selectedCandidate.value" class="adopt-bar">
        <button
          v-if="!selectionAdopted"
          type="button"
          class="primary gl-btn-primary"
          data-test="visual-adopt"
          :disabled="props.adopting || props.disabled"
          @click="onAdopt"
        >{{ props.adopting ? '采用中…' : '采用所选' }}</button>
        <span v-else class="badge ok" data-test="visual-adopted-badge">已采用（写入交付）</span>
      </div>
      <p v-if="props.adoptError" class="error" data-test="visual-adopt-error" role="alert">{{ props.adoptError }}</p>

      <div class="actions">
        <button
          v-if="activeJob"
          type="button"
          class="secondary"
          data-test="visual-cancel"
          :disabled="cancelling || disabled"
          @click="props.job.cancel()"
        >{{ cancelling ? '取消中…' : '取消剩余生成' }}</button>
        <button
          type="button"
          class="secondary"
          data-test="visual-new-job"
          :disabled="creating || quoting || disabled || activeJob"
          @click="onQuoteThenConfirm('create', selectedIds)"
        >再生成一次（新费用）</button>
      </div>
    </template>

    <p v-if="error" class="error" data-test="visual-job-error" role="alert">{{ error }}</p>

    <!-- 费用确认（§5.5：估算区分预算/外部费用，不伪造 0 元） -->
    <GlModal v-if="costConfirm" title="确认生成费用" @close="costConfirm = null">
      <p data-test="visual-cost-text">{{ costText() }}</p>
      <ul v-if="quote?.warnings?.length" class="warn-list">
        <li v-for="warning in quote.warnings" :key="warning">{{ warning }}</li>
      </ul>
      <p class="hint">报价 120 秒内有效；生成开始后不支持退款（失败项按规则补偿）。</p>
      <div class="modal-actions">
        <button type="button" class="secondary" data-test="visual-cost-cancel" @click="costConfirm = null">取消</button>
        <button
          type="button"
          class="primary gl-btn-primary"
          data-test="visual-cost-ok"
          @click="onConfirmCost"
        >开始生成</button>
      </div>
    </GlModal>

    <!-- unknown 重做确认（§6.6：用户确认可能再次计费；后台不代填） -->
    <GlModal v-if="unknownAck" title="确认重做未知结果项" @close="unknownAck = null">
      <p>该页上次生成的<b>外部结果未知</b>（无法确认供应商是否已出图、是否已计费）。</p>
      <p>重做将建立<b>新的生成请求并可能再次计费</b>；旧结果保留在历史中可核对。确认继续？</p>
      <div class="modal-actions">
        <button type="button" class="secondary" data-test="visual-unknown-cancel" @click="unknownAck = null">
          暂不重做
        </button>
        <button
          type="button"
          class="primary gl-btn-primary"
          data-test="visual-unknown-ok"
          @click="onConfirmUnknownRedo"
        >确认重做</button>
      </div>
    </GlModal>
  </section>
</template>

<style scoped>
.visual-production-panel { display: grid; gap: 14px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.panel-head h4 { margin: 0; }
.progress-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.progress-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.progress-row .badge.ok { background: var(--color-success, #2e7d32); color: #fff; }
.item-title { font-size: .9rem; }
.row-actions { margin-left: auto; display: flex; gap: 8px; }
.candidates { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; }
.adopt-bar { display: flex; align-items: center; gap: 10px; }
.adopt-bar .badge.ok { background: var(--color-success); color: #fff; padding: 4px 12px; border-radius: var(--radius-pill); }
.hint { margin: 0; color: var(--color-text-muted); font-size: .84rem; }
.warn { margin: 0; padding: 8px 12px; border-radius: var(--radius-md); border: 1px solid color-mix(in srgb, var(--color-warning, #b8860b) 32%, transparent); background: color-mix(in srgb, var(--color-warning, #b8860b) 8%, transparent); font-size: .85rem; }
.warn-list { margin: 4px 0 0; padding-left: 18px; color: var(--color-text-muted); font-size: .84rem; }
.error { color: var(--color-danger); }
.error-text { color: var(--color-danger); font-size: .82rem; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 12px; }
</style>
