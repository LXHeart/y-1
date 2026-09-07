<script setup lang="ts">
import { computed } from 'vue'
import StyleSkillsPicker from './StyleSkillsPicker.vue'
import type { CreationStyleSkillOption } from '../../../types/article-creation'

/**
 * 大纲步（任务书 #91 R3 自 ArticleCreationView.vue 模板 348–410 整段迁入，纯搬运）。
 */
const props = defineProps<{
  outline: string
  outlineLoading: boolean
  goToTitles: () => void
  streamOutline: () => void
  streamContent: () => void
  contentLoading: boolean
  cancel: () => void
  styleChipsVisible: boolean
  titleFormula: string
  genre: string
  style: string
  styleSkillsLoading: boolean
  styleSkillsError: string
  fetchStyleSkills: () => void
  formulaOptions: CreationStyleSkillOption[]
  genreOptions: CreationStyleSkillOption[]
  styleOptions: CreationStyleSkillOption[]
}>()

const emit = defineEmits<{
  'update:outline': [value: string]
  'update:titleFormula': [value: string]
  'update:genre': [value: string]
  'update:style': [value: string]
}>()

const outlineModel = computed({
  get: () => props.outline,
  set: (value: string) => emit('update:outline', value),
})
const titleFormulaModel = computed({
  get: () => props.titleFormula,
  set: (value: string) => emit('update:titleFormula', value),
})
const genreModel = computed({
  get: () => props.genre,
  set: (value: string) => emit('update:genre', value),
})
const styleModel = computed({
  get: () => props.style,
  set: (value: string) => emit('update:style', value),
})
</script>

<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <div class="card-head-row">
        <button class="btn-back" type="button" @click="goToTitles">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回
        </button>
        <p class="eyebrow">第三步</p>
      </div>
      <h2 class="card-title">编辑大纲后再生成正文</h2>
      <p class="field-note">流式生成时会实时写入，你可以在完成后继续微调结构和段落顺序。</p>
    </header>

    <div class="stream-area">
      <textarea
        v-model="outlineModel"
        class="stream-textarea"
        :class="{ 'stream-loading': outlineLoading }"
        placeholder="大纲会在这里实时生成..."
        rows="12"
      ></textarea>
      <div v-if="outlineLoading" class="stream-badge">
        <span class="stream-dot"></span>
        生成中
      </div>
    </div>

    <!-- 任务书 #57：生成正文前必选体裁+文风（仅小红书非抖音） -->
    <StyleSkillsPicker
      v-if="styleChipsVisible"
      v-model:title-formula="titleFormulaModel"
      v-model:genre="genreModel"
      v-model:style="styleModel"
      variant="full"
      test-scope="content"
      radio-name-base="title-formula"
      :formula-options="formulaOptions"
      :genre-options="genreOptions"
      :style-options="styleOptions"
      :loading="styleSkillsLoading"
      :error="styleSkillsError"
      @retry="fetchStyleSkills"
    />

    <div class="action-row">
      <button
        class="btn-primary gl-btn-primary"
        :disabled="contentLoading || outlineLoading || !outline.trim() || (styleChipsVisible && (!genre || !style))"
        @click="streamContent"
      >
        {{ contentLoading ? '生成中…' : '生成正文' }}
      </button>
      <button
        v-if="outlineLoading"
        class="btn-secondary"
        @click="cancel"
      >
        取消
      </button>
    </div>
  </section>
</template>

<style scoped src="../stage-shared.css"></style>
