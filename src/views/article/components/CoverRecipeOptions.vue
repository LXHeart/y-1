<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  CARD_SERIES_LAYOUTS,
  CARD_SERIES_PALETTES,
  CARD_SERIES_STYLES,
} from '../../../constants/card-series-templates'

/**
 * 任务书 #101 C101-15：精简封面预设／画幅选项（风格/布局/配色/画幅四维，均为本书已定义
 * 的 DTO 字段，默认折叠）。复用现有选择控件样式；供文章配图与视频封面工具共用——
 * 视频侧只消费 presetDescription（可复制的预设描述），不接入 studio 计划。
 */
const props = defineProps<{
  /** 已选值（v-model:modelValue）。 */
  modelValue?: Partial<{ styleId: string; layoutId: string; paletteId: string; targetAspect: string }>
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: { styleId: string; layoutId: string; paletteId: string; targetAspect: string }): void
}>()

const ASPECTS = ['3:4', '9:16', '1:1', '16:9', '2.35:1'] as const

const expanded = ref(false)

const selected = computed(() => ({
  styleId: props.modelValue?.styleId ?? CARD_SERIES_STYLES[0].id,
  layoutId: props.modelValue?.layoutId ?? CARD_SERIES_LAYOUTS[0].id,
  paletteId: props.modelValue?.paletteId ?? CARD_SERIES_PALETTES[0].id,
  targetAspect: props.modelValue?.targetAspect ?? ASPECTS[0],
}))

function update(patch: Partial<{ styleId: string; layoutId: string; paletteId: string; targetAspect: string }>): void {
  if (props.disabled) return
  emit('update:modelValue', { ...selected.value, ...patch })
}

/** 可复制的预设描述：风格/布局/配色的提示词拼接（视频封面工具消费）。 */
const presetDescription = computed(() => {
  const style = CARD_SERIES_STYLES.find((item) => item.id === selected.value.styleId)
  const layout = CARD_SERIES_LAYOUTS.find((item) => item.id === selected.value.layoutId)
  const palette = CARD_SERIES_PALETTES.find((item) => item.id === selected.value.paletteId)
  return [style?.prompt, layout?.prompt, palette?.prompt].filter(Boolean).join('；')
})

defineExpose({ presetDescription })
</script>

<template>
  <section class="cover-recipe-options" data-test="cover-recipe-options">
    <button
      type="button"
      class="secondary"
      data-test="cover-recipe-toggle"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >{{ expanded ? '收起高级选项' : '高级选项（风格／布局／配色／画幅）' }}</button>
    <div v-if="expanded" class="options-grid">
      <div class="form-field">
        <label :for="`${'cro-style'}`">视觉风格</label>
        <select
          id="cro-style"
          data-test="cover-recipe-style"
          :value="selected.styleId"
          :disabled="disabled"
          @change="update({ styleId: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="item in CARD_SERIES_STYLES" :key="item.id" :value="item.id">{{ item.label }}</option>
        </select>
      </div>
      <div class="form-field">
        <label for="cro-layout">画面布局</label>
        <select
          id="cro-layout"
          data-test="cover-recipe-layout"
          :value="selected.layoutId"
          :disabled="disabled"
          @change="update({ layoutId: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="item in CARD_SERIES_LAYOUTS" :key="item.id" :value="item.id">{{ item.label }}</option>
        </select>
      </div>
      <div class="form-field">
        <label for="cro-palette">配色基调</label>
        <select
          id="cro-palette"
          data-test="cover-recipe-palette"
          :value="selected.paletteId"
          :disabled="disabled"
          @change="update({ paletteId: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="item in CARD_SERIES_PALETTES" :key="item.id" :value="item.id">{{ item.label }}</option>
        </select>
      </div>
      <div class="form-field">
        <label for="cro-aspect">画幅</label>
        <select
          id="cro-aspect"
          data-test="cover-recipe-aspect"
          :value="selected.targetAspect"
          :disabled="disabled"
          @change="update({ targetAspect: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="item in ASPECTS" :key="item" :value="item">{{ item }}</option>
        </select>
      </div>
      <p class="preset-description" data-test="cover-recipe-preset">{{ presetDescription }}</p>
    </div>
  </section>
</template>

<style scoped>
.cover-recipe-options { display: grid; gap: var(--space-sm); }
.options-grid { display: grid; gap: var(--space-sm); grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); padding: var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.form-field { display: grid; gap: var(--space-xxs); font-size: var(--type-caption); }
.form-field label { color: var(--color-text-muted); }
.preset-description { grid-column: 1 / -1; margin: 0; color: var(--color-text-muted); font-size: var(--type-caption); }
</style>
