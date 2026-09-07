<script setup lang="ts">
import { computed } from 'vue'
import ArticlePlatformPicker from './ArticlePlatformPicker.vue'
import StyleSkillsPicker from './StyleSkillsPicker.vue'
import type { ArticlePlatform, CreationStyleSkillOption } from '../../../types/article-creation'

/**
 * 主题步（任务书 #91 R2 自 ArticleCreationView.vue 模板 180–259 整段迁入，纯搬运）。
 * D-07：v-if 挂组件标签、单根 section；platform-mode-hint 样式随迁。
 */
const props = defineProps<{
  fromCreationCenter: boolean
  goToCreationCenter: () => void
  topic: string
  fetchTitles: () => void
  platformLocked: boolean
  platformLabel: string
  platform: ArticlePlatform
  isDouyinMode: boolean
  titlesLoading: boolean
  selectNonDouyinPlatform: (target: 'wechat' | 'zhihu' | 'xiaohongshu') => void
  selectDouyin: () => void
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
  'update:topic': [value: string]
  'update:titleFormula': [value: string]
  'update:genre': [value: string]
  'update:style': [value: string]
}>()

const topicModel = computed({
  get: () => props.topic,
  set: (value: string) => emit('update:topic', value),
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
        <p class="eyebrow">第一步</p>
        <button
          v-if="fromCreationCenter"
          class="btn-back"
          type="button"
          data-testid="back-to-center"
          @click="goToCreationCenter"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回创作中心
        </button>
      </div>
      <h2 class="card-title">{{ platformLocked ? '确定创作主题' : '先确定主题和发布平台' }}</h2>
      <p class="field-note">
        {{ platformLocked
          ? `主题确认后，将按${platformLabel}的表达方式生成标题与正文。`
          : '从一个明确主题开始，再决定内容更偏公众号、知乎、小红书还是抖音的表达方式。' }}
      </p>
    </header>

    <textarea
      v-model="topicModel"
      class="topic-input"
      placeholder="输入你想创作的主题或关键词，例如：职场沟通技巧、自媒体运营心得、餐饮创业复盘..."
      rows="5"
      @keydown.ctrl.enter="fetchTitles"
    ></textarea>

    <div class="settings-row">
      <div v-if="platformLocked" class="platform-locked">
        <span class="badge">{{ platformLabel }}</span>
        <p class="field-note">发布平台已在创作中心选定；如需更换平台或创作来源，请返回创作中心重新配置。</p>
      </div>
      <ArticlePlatformPicker
        v-else
        :platform="platform"
        :is-douyin-mode="isDouyinMode"
        :disabled="titlesLoading"
        @select="selectNonDouyinPlatform"
        @select-douyin="selectDouyin"
      />
      <p class="field-note">Ctrl + Enter 可直接生成标题</p>
    </div>

    <p v-if="platform === 'douyin'" class="platform-mode-hint">
      抖音定位图集短文案：短句式表达、强开场突出卖点、结尾带话题标签，配图建议竖版封面并按顺序编排。
    </p>

    <!-- 任务书 #57：小红书图文（非抖音）生成标题前必选标题套路；目录服务端下发 -->
    <StyleSkillsPicker
      v-if="styleChipsVisible"
      v-model:title-formula="titleFormulaModel"
      v-model:genre="genreModel"
      v-model:style="styleModel"
      variant="formula"
      test-scope="titles"
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
        :disabled="titlesLoading || !topic.trim() || (styleChipsVisible && !titleFormula)"
        @click="fetchTitles"
      >
        {{ titlesLoading ? '生成中…' : '生成标题' }}
      </button>
    </div>
  </section>
</template>

<style scoped src="../stage-shared.css"></style>

<style scoped>
.platform-mode-hint {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid color-mix(in srgb, var(--color-info) 28%, transparent);
  background: color-mix(in srgb, var(--color-info) 8%, transparent);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.6;
}
</style>
