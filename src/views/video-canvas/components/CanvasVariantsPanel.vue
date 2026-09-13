<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { VariantSummary } from '../../../types/video-canvas'
import { compareVariantFields } from '../composables/useCanvasVariants'

const props = withDefaults(defineProps<{
  variants: VariantSummary[]
  currentStoryboardId: string
  shots?: Array<{ id: string; seq: number; visual: string }>
  loading: boolean
  creating: boolean
  error: string
  hasPendingCreation: boolean
  readonly?: boolean
}>(), { shots: () => [] })
const emit = defineEmits<{
  (e: 'switch', target: { storyboardId: string; draftId?: string }): void
  (e: 'create', input: { title: string; shotIds: string[] }): void
  (e: 'retry-pending'): void
}>()
const title = ref(''); const included = ref<string[]>([]); const comparedId = ref('')
const current = computed(() => props.variants.find(item => item.storyboardId === props.currentStoryboardId))
const compared = computed(() => props.variants.find(item => item.storyboardId === comparedId.value))
const comparison = computed(() => current.value && compared.value ? compareVariantFields(current.value, compared.value, props.variants) : [])
const selectedShots = computed(() => props.shots.filter(shot => included.value.includes(shot.id)))
const valid = computed(() => title.value.trim().length > 0 && [...title.value.trim()].length <= 60 && selectedShots.value.length > 0)
watch(() => props.currentStoryboardId, () => { title.value = ''; included.value = []; comparedId.value = '' })
function lineageLabel(variant: VariantSummary): string {
  if (variant.parentStoryboardId == null) return '根方案'
  const parent = props.variants.find(item => item.storyboardId === variant.parentStoryboardId)?.title || '未载入的父方案'
  return `派生自「${parent}」· 来源 v${variant.sourceEditVersion ?? '?'}`
}
function create(): void {
  if (valid.value && !props.creating && !props.hasPendingCreation && !props.readonly)
    emit('create', { title: title.value.trim(), shotIds: selectedShots.value.map(shot => shot.id) })
}
</script>

<template>
  <section class="variants-panel" data-test="canvas-variants-panel" aria-label="独立方案">
    <h3 class="panel-title">独立方案</h3>
    <p class="field-note">独立内容方案保留各自的镜头、素材和交付，按当前镜序创建。</p>
    <p v-if="loading" class="field-note" role="status">方案列表读取中…</p>
    <p v-else-if="!variants.length" class="field-note" data-test="canvas-variants-empty">尚无方案，可从当前版本创建。</p>
    <p v-if="error" class="field-note" role="alert" data-test="canvas-variants-error">{{ error }}</p>
    <ul class="variant-list">
      <li v-for="variant in variants" :key="variant.storyboardId" class="variant-item" :data-test="`canvas-variant-${variant.storyboardId}`">
        <strong>{{ variant.title }}</strong>
        <span class="field-note">{{ lineageLabel(variant) }}</span>
        <span v-if="variant.storyboardId === currentStoryboardId" class="badge badge-accent" :data-test="`canvas-variant-current-${variant.storyboardId}`">当前</span>
        <button v-else type="button" class="gl-btn-ghost" :disabled="creating" :data-test="`canvas-variant-switch-${variant.storyboardId}`"
          @click="emit('switch', { storyboardId: variant.storyboardId })">切换到此方案</button>
      </li>
    </ul>
    <div v-if="variants.length > 1" class="gl-field">
      <label class="gl-form-field"><span>与当前方案比较</span>
        <select v-model="comparedId" data-test="canvas-variant-compare">
          <option value="">选择对比方案</option>
          <option v-for="item in variants.filter(item => item.storyboardId !== currentStoryboardId)" :key="item.storyboardId" :value="item.storyboardId">{{ item.title }}</option>
        </select>
      </label>
      <div v-if="comparison.length" class="variant-comparison" tabindex="0" role="region" aria-label="方案字段比较">
        <table class="gl-table" data-test="canvas-variant-comparison">
          <thead><tr><th scope="col">字段</th><th scope="col">当前方案</th><th scope="col">{{ compared?.title }}</th></tr></thead>
          <tbody><tr v-for="row in comparison" :key="row.field"><th scope="row">{{ row.field }}</th><td>{{ row.current }}</td><td>{{ row.other }}</td></tr></tbody>
        </table>
      </div>
    </div>
    <form class="gl-field variant-create-form" @submit.prevent="create">
      <label class="gl-form-field"><span>新方案名称</span>
        <input v-model="title" :disabled="readonly || creating || hasPendingCreation" data-test="canvas-variant-title" aria-describedby="canvas-variant-scope-note">
      </label>
      <fieldset :disabled="readonly || creating || hasPendingCreation">
        <legend>包含的镜头</legend>
        <button type="button" class="gl-btn-ghost" :disabled="!shots.length" data-test="canvas-variant-all" @click="included = shots.map(shot => shot.id)">使用全部镜头</button>
        <label v-for="shot in shots" :key="shot.id" class="variant-shot-option">
          <input v-model="included" type="checkbox" :value="shot.id" :data-test="`canvas-variant-shot-${shot.seq}`">
          <span>镜头 {{ shot.seq }}：{{ shot.visual }}</span>
        </label>
      </fieldset>
      <p id="canvas-variant-scope-note" class="field-note" data-test="canvas-variant-scope">{{ selectedShots.length ? `已选择 ${selectedShots.length} 个镜头` : '请明确选择镜头范围' }}；名称为 1～60 字。</p>
      <button type="submit" class="gl-btn-primary" :disabled="readonly || creating || hasPendingCreation || !valid" data-test="canvas-variant-create">{{ creating ? '创建中…' : '创建独立方案' }}</button>
    </form>
    <p v-if="hasPendingCreation" class="field-note" role="status" data-test="canvas-variant-pending">
      上次创建尚未确认，可恢复原请求。
      <button type="button" class="gl-btn-ghost" :disabled="creating" data-test="canvas-variant-retry" @click="emit('retry-pending')">原键重试</button>
    </p>
  </section>
</template>

<style scoped>
.variants-panel, .variant-list, .variant-create-form { display: flex; flex-direction: column; gap: var(--space-sm); min-width: 0; }
.panel-title { font-family: var(--font-display); font-size: var(--text-base); margin: 0; }
.variant-list { list-style: none; padding: 0; margin: 0; }
.variant-item { display: flex; flex-direction: column; align-items: flex-start; gap: var(--space-xxs); padding-block: var(--space-sm); border-bottom: var(--border-width) solid var(--color-border); overflow-wrap: anywhere; }
.variant-create-form fieldset { border: none; padding: 0; margin: 0; }
.variant-shot-option { display: flex; align-items: center; gap: var(--space-xs); min-height: var(--touch-target); }
.variant-shot-option span { overflow-wrap: anywhere; min-width: 0; }
.variant-shot-option input { flex: 0 0 var(--icon-size); width: var(--icon-size); height: var(--icon-size); accent-color: var(--color-accent); }
.variant-comparison { max-width: 100%; overflow-x: auto; }
</style>
