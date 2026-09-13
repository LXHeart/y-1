<script setup lang="ts">
import { computed, ref } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import EmptyState from '../../../components/shared/EmptyState.vue'
import VisualPlanItemEditor from './VisualPlanItemEditor.vue'
import type { VisualStrategy } from '../../../types/creation-studio'
import type { useVisualPlan } from '../composables/useVisualPlan'
import {
  CARD_SERIES_STYLES, CARD_SERIES_PALETTES, CARD_SERIES_PRESETS,
} from '../../../constants/card-series-templates'

/**
 * 任务书 #101 C101-06：视觉计划编辑器（§8.1 视觉计划区）。
 *
 * 一套推荐默认展开；整组共享 style/palette、每项独立 layout；保存成功后才能确认；
 * 生成入口对未保存／stale 计划不可用。换策略=新建计划，弹窗说明影响（§8.2）。
 */
const props = defineProps<{
  plan: ReturnType<typeof useVisualPlan>
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'generate-requested'): void
}>()

const { current, document, sourceBlocks, preparing, saving, confirming, error, dirty } = props.plan

const strategyLabels: Record<VisualStrategy, string> = {
  story: '体验叙事', information: '信息干货', visual: '视觉优先',
}
const allStrategies: VisualStrategy[] = ['information', 'story', 'visual']

/** 换策略确认弹窗（§8.2：切换可能丢上下文，需说明影响；取消完全保留编辑）。 */
const strategySwitch = ref<VisualStrategy | null>(null)

const status = computed(() => current.value?.status ?? 'idle')
const canConfirm = computed(() => status.value === 'ready' && !dirty.value && !saving.value
  && !confirming.value && !current.value?.stale && !props.disabled)
const canGenerate = computed(() => status.value === 'ready' && !dirty.value && !saving.value
  && !current.value?.stale && current.value?.confirmedRevision != null && !props.disabled)
const uncoveredCount = computed(() => current.value?.document?.uncoveredBlockIds.length ?? 0)

function touch(): void {
  props.plan.touch()
}

function onStyle(field: 'styleId' | 'paletteId', event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  if (document.value) document.value.style = { ...document.value.style, [field]: value }
  touch()
}

function onPreset(event: Event): void {
  const preset = CARD_SERIES_PRESETS.find((item) => item.id === (event.target as HTMLSelectElement).value)
  if (!preset || !document.value) return
  document.value.style = {
    styleId: preset.styleId, layoutId: preset.layoutId,
    paletteId: preset.paletteId ?? document.value.style.paletteId,
  }
  touch()
}

async function onSave(): Promise<void> {
  await props.plan.flush()
}

async function onConfirm(): Promise<void> {
  await props.plan.confirm()
}

function onRemove(index: number): void {
  props.plan.removeItem(index)
}

/** 单页字段补丁应用到父层文档副本（子组件不改 props）后按 800ms 队列保存。 */
function onItemUpdate(index: number, patch: Record<string, unknown>): void {
  const target = document.value?.items[index]
  if (!target) return
  Object.assign(target, patch)
  touch()
}

function confirmStrategySwitch(): void {
  const target = strategySwitch.value
  strategySwitch.value = null
  if (target) void props.plan.prepare({ strategy: target })
}
</script>

<template>
  <section class="gl-zone visual-plan-editor" data-test="visual-plan-editor" aria-label="视觉计划">
    <!-- 策略与整组风格 -->
    <div class="panel-head">
      <h4>视觉计划</h4>
      <span v-if="current" class="badge" data-test="plan-revision-badge">
        修订 {{ current.revision }}{{ current.confirmedRevision != null ? ` · 已确认 ${current.confirmedRevision}` : '' }}
      </span>
    </div>

    <template v-if="preparing && !current">
      <p class="progress" data-test="plan-preparing" aria-live="polite">正在生成一套推荐计划…</p>
    </template>

    <template v-else-if="!current || !document">
      <EmptyState
        v-if="!preparing"
        title="还没有视觉计划"
        description="先导入原稿，再从上方发起一次策划，得到一套可编辑的推荐计划。"
      />
    </template>

    <template v-else>
      <p v-if="current.status === 'failed'" class="error" data-test="plan-error" role="alert">
        {{ current.error?.message ?? '计划生成失败' }}
        <button type="button" class="secondary" :disabled="disabled" @click="props.plan.prepare()">重试</button>
      </p>
      <p v-else-if="current.status === 'unknown'" class="warn" data-test="plan-unknown" role="alert">
        计划状态未知（生成中断）。可读取现有结果；重新生成将新建计划。
      </p>

      <div class="plan-meta">
        <div class="form-field">
          <label for="plan-strategy">当前策略</label>
          <select id="plan-strategy" :value="document.strategy" data-test="plan-strategy" disabled>
            <option v-for="strategy in allStrategies" :key="strategy" :value="strategy">
              {{ strategyLabels[strategy] }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="plan-preset">快速风格组合</label>
          <select id="plan-preset" data-test="plan-preset" :disabled="disabled" @change="onPreset">
            <option value="">选择组合模板…</option>
            <option v-for="preset in CARD_SERIES_PRESETS" :key="preset.id" :value="preset.id">
              {{ preset.label }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="plan-style">整组风格</label>
          <select
            id="plan-style" :value="document.style.styleId" data-test="plan-style"
            :disabled="disabled" @change="onStyle('styleId', $event)"
          >
            <option v-for="style in CARD_SERIES_STYLES" :key="style.id" :value="style.id">{{ style.label }}</option>
          </select>
        </div>
        <div class="form-field">
          <label for="plan-palette">整组配色</label>
          <select
            id="plan-palette" :value="document.style.paletteId" data-test="plan-palette"
            :disabled="disabled" @change="onStyle('paletteId', $event)"
          >
            <option v-for="palette in CARD_SERIES_PALETTES" :key="palette.id" :value="palette.id">
              {{ palette.label }}
            </option>
          </select>
        </div>
      </div>

      <p v-if="document.explanation" class="hint" data-test="plan-explanation">{{ document.explanation }}</p>
      <p v-if="uncoveredCount" class="warn" data-test="plan-uncovered" role="status">
        有 {{ uncoveredCount }} 个来源块未被本计划覆盖，生成结果不会包含它们的内容。
      </p>
      <p v-if="current.stale" class="warn" data-test="plan-stale" role="alert">
        正文或来源已变化，本计划已过期：需重新核对后新建或重新确认计划，生成与采用已停用。
      </p>

      <!-- 换策略（次要动作，说明会新建计划） -->
      <div class="strategy-row">
        <span class="source-label">想换一种推荐思路？</span>
        <button
          v-for="strategy in allStrategies.filter((item) => item !== document!.strategy)"
          :key="strategy"
          type="button"
          class="secondary"
          :data-test="`plan-switch-${strategy}`"
          :disabled="disabled"
          @click="strategySwitch = strategy"
        >换「{{ strategyLabels[strategy] }}」</button>
      </div>

      <!-- 逐页编辑 -->
      <div class="plan-items">
        <VisualPlanItemEditor
          v-for="(item, index) in document.items"
          :key="item.itemId"
          :item="item"
          :index="index"
          :total="document.items.length"
          :source-blocks="sourceBlocks"
          :disabled="disabled"
          @move="props.plan.moveItem"
          @remove="onRemove"
          @promote="props.plan.promoteToCover"
          @update="onItemUpdate"
        />
      </div>

      <div class="actions">
        <span v-if="dirty" class="badge" data-test="plan-dirty-badge">未保存</span>
        <button
          type="button"
          class="secondary"
          data-test="plan-save"
          :disabled="!dirty || saving || disabled"
          @click="onSave"
        >{{ saving ? '保存中…' : '保存计划' }}</button>
        <button
          type="button"
          class="primary gl-btn-primary"
          data-test="plan-confirm"
          :disabled="!canConfirm"
          @click="onConfirm"
        >{{ confirming ? '确认中…' : '确认此版本' }}</button>
        <button
          type="button"
          class="primary gl-btn-primary"
          data-test="plan-generate"
          :disabled="!canGenerate"
          @click="emit('generate-requested')"
        >生成图片</button>
      </div>
      <p v-if="dirty" class="hint">有未保存的编辑；确认与生成前会先自动保存。</p>
    </template>

    <p v-if="error" class="error" data-test="plan-request-error" role="alert">{{ error }}</p>

    <GlModal
      v-if="strategySwitch"
      title="换一种推荐思路"
      @close="strategySwitch = null"
    >
      <p>换策略会<b>新建一份计划</b>并重新策划一次（按现有计费规则计一次策划调用）。</p>
      <p>当前计划的编辑与已确认版本不会被带过去；已生成的图片结果仍保留在历史里。</p>
      <div class="modal-actions">
        <button type="button" class="secondary" data-test="plan-switch-cancel" @click="strategySwitch = null">
          取消（保留当前编辑）
        </button>
        <button
          type="button"
          class="primary gl-btn-primary"
          data-test="plan-switch-ok"
          @click="confirmStrategySwitch"
        >新建计划</button>
      </div>
    </GlModal>
  </section>
</template>

<style scoped>
.visual-plan-editor { display: grid; gap: 14px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.panel-head h4 { margin: 0; }
.plan-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
.plan-items { display: grid; gap: 12px; }
.strategy-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.hint { margin: 0; color: var(--color-text-muted); font-size: .84rem; }
.progress { color: var(--color-text-muted); }
.warn { margin: 0; padding: 8px 12px; border-radius: var(--radius-md); border: 1px solid color-mix(in srgb, var(--color-warning, #b8860b) 32%, transparent); background: color-mix(in srgb, var(--color-warning, #b8860b) 8%, transparent); font-size: .85rem; }
.error { color: var(--color-danger); }
.source-label { color: var(--color-text-muted); font-size: .84rem; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 12px; }
</style>
