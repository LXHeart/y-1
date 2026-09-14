<template>
  <section class="choice-band source-entry studio-panel" aria-labelledby="creation-source-entry-title">
    <div class="choice-title-row">
      <h3 id="creation-source-entry-title">从已有内容开始</h3>
      <span>粘贴原稿或导入 TXT/MD，先排版或拆成图卡</span>
    </div>
    <p v-if="loading" class="source-entry-empty" role="status">正在确认可用的创作模板…</p>
    <p v-else-if="availabilityError" class="source-entry-empty" role="status">{{ availabilityError }}</p>
    <div class="source-entry-grid" role="group" aria-label="从已有内容开始">
      <button
        v-for="option in options"
        :key="option.id"
        type="button"
        class="source-entry-option"
        :data-recipe-id="option.id"
        :disabled="option.disabled || disabled"
        :aria-disabled="option.disabled || disabled"
        @click="select(option)"
      >
        <strong>{{ option.label }}</strong>
        <span>{{ option.note }}</span>
        <em v-if="option.disabled">{{ option.unavailableReason }}</em>
      </button>
    </div>
    <p v-if="!applicableRecipes.length" class="source-entry-empty" role="note">
      当前平台／形式暂不支持从已有内容开始，可先按主题创作。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CREATION_RECIPES } from '../../../config/creation-recipes'
import { studioRequest, useStudioGuard } from '../../../lib/creation-studio-http'
import type { RecipeDefinition } from '../../../types/creation-studio'
import type { CreationProcessingMode } from '../../../types/creation'

/**
 * 任务书 #101 C101-03（§4.1/§8.1）：「从已有内容开始」内嵌来源区——不新增顶级导航。
 * 四模板按当前平台/形式过滤；禁用模板展示不可用原因（不静默隐藏、不静默切换平台）。
 * 选择即把 recipe + 加工方式带入 handoff；不伪造任务快照，后端仍核验范围。
 */

const props = defineProps<{
  platform: string
  contentForm: string
  disabled?: boolean
  authenticated?: boolean
}>()

const emit = defineEmits<{
  select: [selection: { recipeId: 'social-card-series' | 'article-visuals' | 'article-format' | 'cover-only'
    ; version: string; processingMode: CreationProcessingMode }]
}>()

interface EntryOption {
  id: 'social-card-series' | 'article-visuals' | 'article-format' | 'cover-only'
  label: string
  note: string
  disabled: boolean
  unavailableReason: string
  version: string
  processingMode: CreationProcessingMode
}
const enabledIds = ref(new Set<string>())
const availabilityError = ref('')
const loading = ref(false)
const guard = useStudioGuard(() => [props.platform, props.contentForm, props.authenticated].join(':'))
guard.onInvalidate(() => { enabledIds.value = new Set(); loading.value = false })
watch(() => [props.platform, props.contentForm, props.authenticated], async () => {
  if (!props.authenticated) { availabilityError.value = '登录后可使用原稿工作流'; return }
  const valid = guard.capture()
  loading.value = true
  try {
    const result = await studioRequest<{ items: RecipeDefinition[] }>('/api/creation-studio/recipes?'
      + new URLSearchParams({ platform: props.platform, contentForm: props.contentForm }))
    if (!valid()) return
    enabledIds.value = new Set(result.items.filter(item => item.enabled).map(item => item.id))
    availabilityError.value = enabledIds.value.size ? '' : '原稿工作流暂未开放，仍可使用已有创作方式'
  } catch { if (valid()) availabilityError.value = '原稿工作流暂不可用，仍可使用已有创作方式' }
  finally { if (valid()) loading.value = false }
}, { immediate: true })

/** 「从已有内容开始」的隐含加工方式：图卡/配图→adapt，原稿排版→format。 */
const ENTRY_NOTES: Record<EntryOption['id'], { note: string; processingMode: CreationProcessingMode }> = {
  'social-card-series': { note: '攻略／知识图卡 · 原稿拆卡', processingMode: 'adapt' },
  'article-visuals': { note: '文章封面与配图', processingMode: 'adapt' },
  'article-format': { note: '原稿排版 · 不改文字', processingMode: 'format' },
  'cover-only': { note: '单张封面', processingMode: 'adapt' },
}

const applicableRecipes = computed(() => CREATION_RECIPES.filter((recipe) =>
  recipe.platformIds.includes(props.platform as never) && recipe.contentForms.includes(props.contentForm as never)))

const options = computed<EntryOption[]>(() => applicableRecipes.value.map((recipe) => ({
  id: recipe.id,
  label: recipe.label,
  note: ENTRY_NOTES[recipe.id].note,
  disabled: !recipe.enabled || (props.authenticated === true && !enabledIds.value.has(recipe.id)),
  unavailableReason: enabledIds.value.has(recipe.id) ? '' : '该模板暂未开放',
  version: recipe.version,
  processingMode: ENTRY_NOTES[recipe.id].processingMode,
})))

function select(option: EntryOption): void {
  if (option.disabled || props.disabled) return
  emit('select', { recipeId: option.id, version: option.version, processingMode: option.processingMode })
}
</script>

<style scoped>
.source-entry-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--layout-rail)), 1fr)); gap: var(--space-sm); }
.source-entry-option { min-height: var(--space-section); display: grid; gap: var(--space-xxs); align-content: center; padding: var(--space-md);
  text-align: left; border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface);
  color: var(--color-text); cursor: pointer; transition: border-color var(--duration-fast) var(--ease-out); }
.source-entry-option:hover:not(:disabled) { border-color: var(--color-accent); box-shadow: var(--shadow-card); }
.source-entry-option span { color: var(--color-text-muted); font-size: var(--text-xs); }
.source-entry-option:disabled { cursor: default; opacity: 1; }
.source-entry-option em { color: var(--color-text-muted); font-size: var(--text-xs); font-style: normal; }
.source-entry-empty { margin: 0; color: var(--color-text-muted); font-size: var(--text-xs); }
</style>
