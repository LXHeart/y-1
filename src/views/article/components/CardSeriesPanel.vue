<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { useCardSeries } from '../../../composables/useCardSeries'
import type { useVisualPlan, PreparePlanInput } from '../composables/useVisualPlan'
import type { useVisualJob } from '../composables/useVisualJob'
import VisualPlanEditor from './VisualPlanEditor.vue'
import VisualProductionPanel from './VisualProductionPanel.vue'
import {
  CARD_SERIES_LAYOUTS,
  CARD_SERIES_PALETTES,
  CARD_SERIES_PRESETS,
  CARD_SERIES_SIZES,
  CARD_SERIES_STYLES,
} from '../../../constants/card-series-templates'

/**
 * 系列图卡面板（任务书 #54 2026-08-30 修订）：拆卡对象是文章流已生成的正文（prop 传入），
 * 挂在小红书图文制作页正文之后：模板选择 → 拆卡计划 → 编辑 → 逐卡生成。
 * AI内容中心改造-02 §2.2：状态提升到工作流级——图卡实例由 useArticleWorkspace 持有并
 * 序列化进 workspace.inputs.cards（面板随步骤销毁/刷新不丢计划与成功卡），本组件只保留
 * 展开态/阶段等纯 UI 局部态。
 *
 * 任务书 #101 C101-06：studio 会话（prop plan 传入）改走新版视觉计划分支——
 * 服务端计划/修订/确认（API101-08~12）；旧版分支保留给存量草稿，旧结果不删除。
 * 任务书 #101 C101-11：计划确认后装配新版制作面（VisualProductionPanel——进度/候选/重做）。
 */

const props = defineProps<{
  platform: string
  content: string
  series: ReturnType<typeof useCardSeries>
  plan?: ReturnType<typeof useVisualPlan>
  job?: ReturnType<typeof useVisualJob>
  /** C101-12：采用面板透传（adopting/adoptedMediaIds/adoptError）。 */
  adopting?: boolean
  adoptedMediaIds?: string[]
  adoptError?: string
  disabled?: boolean
}>()

/** 任务书 #57：成功卡放大预览——按钮与缩略图点击双入口，lightbox 由父层 ArticleLightbox 承载。 */
const emit = defineEmits<{
  (e: 'open-lightbox', url: string): void
  (e: 'prepare-plan', input?: PreparePlanInput, fresh?: boolean): void
  (e: 'generate-requested'): void
  (e: 'candidate-selected', selection: { itemId: string; artifactId: string }): void
  (e: 'adopt-requested', selection: { itemId: string; artifactId: string }): void
}>()

const {
  cardCount, styleId, layoutId, paletteId, size,
  planning, planProgress, planError, cards,
  generating, generateError, results, persistedMediaIds,
  canPlan,
  plan: planLegacy, generateCards, removeCard, addCard, persistCard, downloadCardWith,
} = props.series

const expanded = ref(props.plan != null)
const stage = ref<'config' | 'edit' | 'result'>('config')

/** studio 会话走新版分支；存量 legacy 卡片结果仍可查看（旧版面板折叠开关）。 */
const studioMode = computed(() => props.plan != null)
/** C101-11：计划已确认（或已有进行中/终态任务）→ 制作面接管生成入口。 */
const productionReady = computed(() => props.plan != null && props.job != null
  && (props.plan.current.value?.confirmedRevision != null || props.job.current.value != null))
const hasLegacyResults = computed(() => Object.keys(props.series.persistedMediaIds.value).length > 0
  || props.series.results.value.length > 0
  || props.series.cards.value.length > 0)
const legacyVisible = ref(false)

watch(studioMode, (mode) => {
  if (mode) legacyVisible.value = false
}, { immediate: true })

watch(results, (value) => {
  if (value.length && stage.value === 'config') stage.value = 'result'
}, { immediate: true })

const canGenerate = computed(() => cards.value.length > 0
  && cards.value.every((card) => card.title.trim() !== '')
  && !generating.value)

const okCount = computed(() => results.value.filter((card) => card.ok).length)

function onPreset(event: Event): void {
  const preset = CARD_SERIES_PRESETS.find((item) => item.id === (event.target as HTMLSelectElement).value)
  if (!preset) return
  styleId.value = preset.styleId
  layoutId.value = preset.layoutId
  if (preset.paletteId) paletteId.value = preset.paletteId
}

function onBulletsInput(card: { bullets: string[] }, event: Event): void {
  const value = (event.target as HTMLTextAreaElement).value
  card.bullets = value.split('\n').map((line) => line.trim()).filter(Boolean).slice(0, 5)
}

async function onPlan(): Promise<void> {
  await planLegacy(props.content)
  if (cards.value.length) stage.value = 'edit'
}

async function onGenerate(): Promise<void> {
  await generateCards('all')
  stage.value = 'result'
}

async function onRetry(index: number): Promise<void> {
  await generateCards(index)
}

async function onSave(index: number): Promise<void> {
  const card = results.value[index]
  if (!card?.ok) return
  await persistCard(card)
}

function onDownload(index: number): void {
  const card = results.value[index]
  if (card?.ok) void downloadCardWith(card)
}

/** 成功卡放大（url 可能缺席于失败形态——守卫后 emit）。 */
function onZoom(card: { ok: boolean; url?: string }): void {
  if (card.ok && card.url) emit('open-lightbox', card.url)
}

function restart(): void {
  props.series.reset()
  stage.value = 'config'
}
</script>

<template>
  <section class="gl-zone card-series-panel studio-panel" data-test="card-series-panel">
    <div class="panel-head">
      <h3>{{ platform === 'douyin' ? '制作抖音图卡' : '拆成小红书图卡' }}</h3>
      <button type="button" class="secondary" data-test="card-series-toggle" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开' }}
      </button>
    </div>
    <p class="hint">基于当前正文制作 1–9 张图卡；先核对计划与来源，再生成并采用图片。</p>

    <template v-if="expanded">
      <!-- 任务书 #101 C101-06：studio 会话新版分支——服务端视觉计划（编辑/确认）；
           C101-11：确认后装配制作面（进度/费用确认/候选比较/单项重做） -->
      <template v-if="studioMode && plan">
        <section v-if="!plan.current.value" aria-label="发起视觉计划" class="studio-launch">
          <p class="hint">新版拆卡：先冻结当前正文为来源，再由服务端生成一套可编辑、可确认的视觉计划（逐页目的、原文依据与布局）。</p>
          <button
            type="button"
            class="primary gl-btn-primary"
            data-test="studio-plan-launch"
            :disabled="plan.preparing.value || disabled"
            @click="emit('prepare-plan')"
          >{{ plan.preparing.value ? '正在发起…' : '发起视觉策划' }}</button>
          <p v-if="plan.error.value" class="error" data-test="studio-plan-launch-error" role="alert">{{ plan.error.value }}</p>
        </section>
        <template v-else>
          <VisualPlanEditor
            :plan="plan"
            :disabled="disabled"
            @prepare-requested="input => emit('prepare-plan', input, true)"
          />
          <VisualProductionPanel
            v-if="job && productionReady"
            :plan="plan"
            :job="job"
            :disabled="disabled"
            :adopting="adopting"
            :adopted-media-ids="adoptedMediaIds"
            :adopt-error="adoptError"
            @candidate-selected="(selection) => emit('candidate-selected', selection)"
            @adopt-requested="(selection) => emit('adopt-requested', selection)"
            @zoom="(url) => emit('open-lightbox', url)"
          />
        </template>
        <!-- 存量旧图卡结果兼容：保留查看入口，不删除旧结果（§7.4） -->
        <div v-if="hasLegacyResults" class="legacy-toggle">
          <button type="button" class="secondary" data-test="legacy-cards-toggle" @click="legacyVisible = !legacyVisible">
            {{ legacyVisible ? '收起旧版图卡' : '查看旧版图卡结果' }}
          </button>
        </div>
      </template>

      <template v-if="!studioMode || legacyVisible">
      <!-- 配置与拆卡 -->
      <section v-if="stage === 'config'" aria-label="图卡配置">
        <div class="form-field">
          <label for="card-series-preset">快速模板</label>
          <select id="card-series-preset" data-test="card-series-preset" @change="onPreset">
            <option value="">选择组合模板…</option>
            <option v-for="preset in CARD_SERIES_PRESETS" :key="preset.id" :value="preset.id">
              {{ preset.label }}
            </option>
          </select>
        </div>

        <div class="form-field">
          <label for="card-series-count">卡片数量（1-9 张）</label>
          <input
            id="card-series-count"
            v-model.number="cardCount"
            data-test="card-series-count"
            type="number"
            min="1"
            max="9"
          >
        </div>

        <fieldset class="form-field">
          <legend>视觉风格 *</legend>
          <div class="option-grid">
            <label
              v-for="item in CARD_SERIES_STYLES"
              :key="item.id"
              class="style-option"
              :class="{ active: styleId === item.id }"
            >
              <input v-model="styleId" type="radio" name="card-style" :value="item.id">
              {{ item.label }}
            </label>
          </div>
        </fieldset>

        <fieldset class="form-field">
          <legend>画面布局 *</legend>
          <div class="option-grid">
            <label
              v-for="item in CARD_SERIES_LAYOUTS"
              :key="item.id"
              class="style-option"
              :class="{ active: layoutId === item.id }"
            >
              <input v-model="layoutId" type="radio" name="card-layout" :value="item.id">
              {{ item.label }}
            </label>
          </div>
        </fieldset>

        <fieldset class="form-field">
          <legend>配色基调</legend>
          <div class="option-grid">
            <label
              v-for="item in CARD_SERIES_PALETTES"
              :key="item.id"
              class="style-option"
              :class="{ active: paletteId === item.id }"
            >
              <input v-model="paletteId" type="radio" name="card-palette" :value="item.id">
              {{ item.label }}
            </label>
          </div>
        </fieldset>

        <div class="form-field">
          <label for="card-series-size">图片尺寸</label>
          <select id="card-series-size" v-model="size" data-test="card-series-size">
            <option v-for="item in CARD_SERIES_SIZES" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </div>

        <div class="actions">
          <button
            type="button"
            data-test="card-series-plan"
            class="primary gl-btn-primary"
            :disabled="!canPlan"
            @click="onPlan"
          >
            {{ planning ? '拆解中…' : '按正文拆卡' }}
          </button>
        </div>
        <p v-if="planning && planProgress" class="progress">{{ planProgress }}</p>
        <p v-if="planError" data-test="card-series-plan-error" class="error" role="alert">{{ planError }}</p>
      </section>

      <!-- 计划编辑 -->
      <section v-else-if="stage === 'edit'" aria-label="卡片计划编辑">
        <p class="hint">标题与要点会直接绘制在画面里；<strong>画面描述</strong>决定整张卡画什么、画得多细——改完重新生成即生效；配文（caption）在发布时随图使用。</p>
        <div v-for="(card, index) in cards" :key="index" class="plan-card" data-test="card-series-plan-card">
          <div class="plan-card-head">
            <span class="badge">第 {{ index + 1 }} 张{{ index === 0 ? ' · 封面' : '' }}</span>
            <button
              v-if="cards.length > 1"
              type="button"
              class="secondary"
              data-test="card-series-remove"
              @click="removeCard(index)"
            >删除</button>
          </div>
          <div class="form-field">
            <label :for="`card-title-${index}`">标题 *（绘制在画面中）</label>
            <input
              :id="`card-title-${index}`"
              v-model="card.title"
              :data-test="`card-series-title-${index}`"
              maxlength="100"
            >
          </div>
          <div class="form-field">
            <label :for="`card-bullets-${index}`">要点（每行一条，最多 5 条，绘制在画面中）</label>
            <textarea
              :id="`card-bullets-${index}`"
              :value="card.bullets.join('\n')"
              :data-test="`card-series-bullets-${index}`"
              rows="3"
              @input="onBulletsInput(card, $event)"
            />
          </div>
          <div class="form-field">
            <label :for="`card-illustration-${index}`">画面描述 *（直接决定画面内容，可修改）</label>
            <textarea
              :id="`card-illustration-${index}`"
              v-model="card.illustration"
              :data-test="`card-series-illustration-${index}`"
              rows="4"
              maxlength="600"
            />
          </div>
          <div class="form-field">
            <label :for="`card-caption-${index}`">配文（发布文案，不进图）</label>
            <input :id="`card-caption-${index}`" v-model="card.caption" maxlength="200">
          </div>
        </div>
        <div class="actions">
          <button v-if="cards.length < 9" type="button" class="secondary" @click="addCard">加一张</button>
          <button type="button" class="secondary" @click="stage = 'config'">返回配置</button>
          <button
            type="button"
            data-test="card-series-generate"
            class="primary gl-btn-primary"
            :disabled="!canGenerate"
            @click="onGenerate"
          >
            {{ generating ? '生成中…' : `生成 ${cards.length} 张图卡` }}
          </button>
        </div>
        <p v-if="generateError" data-test="card-series-error" class="error" role="alert">{{ generateError }}</p>
      </section>

      <!-- 结果 -->
      <section v-else aria-label="图卡结果">
        <p class="hint">已生成 {{ okCount }} / {{ results.length }} 张。失败卡可单卡重试；保存的卡会转入素材库（其余 30 分钟后过期）。</p>
        <div class="result-grid">
          <figure v-for="(card, index) in results" :key="index" class="result-card" data-test="card-series-result">
            <img
              v-if="card.ok && card.url"
              :src="card.url"
              :alt="card.title"
              loading="lazy"
              data-test="card-series-image"
              @click="onZoom(card)"
            >
            <div v-else class="failed-card" data-test="card-series-failed">
              <span>生成失败</span>
              <small>{{ card.errorReason }}</small>
            </div>
            <figcaption>
              <strong>{{ card.title }}</strong>
              <div class="result-actions">
                <button v-if="!card.ok" type="button" class="secondary" data-test="card-series-retry" @click="onRetry(index)">重试</button>
                <template v-if="card.ok">
                  <button
                    type="button"
                    class="secondary"
                    data-test="card-series-zoom"
                    title="放大查看"
                    @click="onZoom(card)"
                  >
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <path d="M6 10L10 6M10 6H6.5M10 6V9.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M2 6V4a2 2 0 012-2h2M10 2h2a2 2 0 012 2v2M14 10v2a2 2 0 01-2 2h-2M6 14H4a2 2 0 01-2-2v-2" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    放大
                  </button>
                  <button type="button" class="secondary" @click="onDownload(index)">下载</button>
                  <button
                    v-if="!persistedMediaIds[card.cardId ?? '']"
                    type="button"
                    class="secondary"
                    data-test="card-series-save"
                    @click="onSave(index)"
                  >存素材库</button>
                  <span v-else class="badge">已保存</span>
                </template>
              </div>
            </figcaption>
          </figure>
        </div>
        <div class="actions">
          <button type="button" class="secondary" @click="stage = 'edit'">调整计划</button>
          <button type="button" class="secondary" @click="restart">重新开始</button>
        </div>
        <p v-if="generateError" data-test="card-series-error" class="error" role="alert">{{ generateError }}</p>
      </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.card-series-panel { display: grid; gap: var(--space-md); }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.panel-head h3 { margin: 0; }
.hint { margin: 0; color: var(--color-text-muted); font-size: var(--text-base); }
.studio-launch { display: grid; gap: var(--space-sm); }
.legacy-toggle { margin-top: var(--space-xxs); }
.plan-card { border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-md); display: grid; gap: var(--space-sm); background: var(--color-surface); }
.plan-card-head { display: flex; justify-content: space-between; align-items: center; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, var(--layout-rail)), 1fr)); gap: var(--space-md); }
.result-card { margin: 0; border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; background: var(--color-surface); }
.result-card img { display: block; width: 100%; aspect-ratio: 5 / 8; object-fit: cover; cursor: zoom-in; }
.failed-card { aspect-ratio: 5 / 8; display: grid; place-content: center; gap: var(--space-xs); text-align: center; color: var(--color-text-muted); padding: var(--space-sm); }
.result-card figcaption { padding: var(--space-sm) var(--space-sm); display: grid; gap: var(--space-xs); font-size: var(--text-base); }
.result-actions { display: flex; flex-wrap: wrap; gap: var(--space-xs); }
.actions { display: flex; flex-wrap: wrap; gap: var(--space-sm); align-items: center; }
.progress { color: var(--color-text-muted); font-size: var(--text-base); }
.error { color: var(--color-danger); }
</style>
