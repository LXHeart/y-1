<script setup lang="ts">
import { computed, ref } from 'vue'
import { useCardSeries } from '../../../composables/useCardSeries'
import {
  CARD_SERIES_LAYOUTS,
  CARD_SERIES_PALETTES,
  CARD_SERIES_PRESETS,
  CARD_SERIES_SIZES,
  CARD_SERIES_STYLES,
} from '../../../constants/card-series-templates'

/**
 * 系列图卡面板（任务书 #54 2026-08-30 修订）：拆卡对象是文章流已生成的正文（prop 传入），
 * 不再是独立制作方式——挂在小红书图文制作页正文之后：模板选择 → 拆卡计划 → 编辑 → 逐卡生成。
 * 2026-09-02 文字策略改版：标题/要点由生图模型直接绘制进画面（字图一体），画面描述
 * （illustration）决定整张卡的画面质量，编辑区可见可改；导出即原图，不再 canvas 叠排。
 */

const props = defineProps<{
  platform: string
  content: string
}>()

/** 任务书 #57：成功卡放大预览——按钮与缩略图点击双入口，lightbox 由父层 ArticleLightbox 承载。 */
const emit = defineEmits<{
  (e: 'open-lightbox', url: string): void
}>()

const {
  cardCount, styleId, layoutId, paletteId, size,
  planning, planProgress, planError, cards,
  generating, generateError, results,
  canPlan,
  plan, generateCards, removeCard, addCard, persistCard, downloadCardWith, reset,
} = useCardSeries(props.platform)

const expanded = ref(false)
const stage = ref<'config' | 'edit' | 'result'>('config')
const savedMediaIds = ref<Record<number, string>>({})

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
  await plan(props.content)
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
  const mediaId = await persistCard(card)
  if (mediaId) savedMediaIds.value = { ...savedMediaIds.value, [index]: mediaId }
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
  reset()
  savedMediaIds.value = {}
  stage.value = 'config'
}
</script>

<template>
  <section class="gl-zone card-series-panel" data-test="card-series-panel">
    <div class="panel-head">
      <h3>拆成小红书图卡</h3>
      <button type="button" class="secondary" data-test="card-series-toggle" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开' }}
      </button>
    </div>
    <p class="hint">基于右侧已生成的正文，拆成 1-10 张轮播图卡（12 风格 × 8 布局 × 3 配色）。标题与要点由 AI 直接绘制在画面中，字图一体。</p>

    <template v-if="expanded">
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
                    v-if="!savedMediaIds[index]"
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
  </section>
</template>

<style scoped>
.card-series-panel { display: grid; gap: 16px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.panel-head h3 { margin: 0; }
.hint { margin: 0; color: var(--color-text-muted); font-size: .86rem; }
.plan-card { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 14px; display: grid; gap: 10px; background: var(--color-surface); }
.plan-card-head { display: flex; justify-content: space-between; align-items: center; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 14px; }
.result-card { margin: 0; border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; background: var(--color-surface); }
.result-card img { display: block; width: 100%; aspect-ratio: 5 / 8; object-fit: cover; cursor: zoom-in; }
.failed-card { aspect-ratio: 5 / 8; display: grid; place-content: center; gap: 6px; text-align: center; color: var(--color-text-muted); padding: 12px; }
.result-card figcaption { padding: 10px 12px; display: grid; gap: 8px; font-size: .85rem; }
.result-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.progress { color: var(--color-text-muted); font-size: .85rem; }
.error { color: var(--color-danger); }
</style>
