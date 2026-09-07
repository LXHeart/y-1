<script setup lang="ts">
import { computed } from 'vue'
import ArticlePlatformPicker from './ArticlePlatformPicker.vue'
import StyleSkillsPicker from './StyleSkillsPicker.vue'
import type { ArticlePlatform, CreationStyleSkillOption } from '../../../types/article-creation'

/**
 * 回答模式第一步——目标问题（任务书 #91 R1 自 ArticleCreationView.vue 模板 80–178 整段迁入，
 * 纯搬运；任务书 #62）。D-07：v-if 挂在组件标签上、单根 section；questionInput/questionValid/
 * MIN_QUESTION_CHARS 随迁。函数依赖经 props 同名传入，模板表达式与迁出前逐字符一致。
 */
const props = defineProps<{
  fromCreationCenter: boolean
  goToCreationCenter: () => void
  question: string
  setQuestion: (value: string) => void
  questionRef: string
  questionValid: boolean
  taskQuestionLocked: boolean
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

const MIN_QUESTION_CHARS = 8

/** textarea 双向绑定要过 setQuestion（顺带本地提取 questionId，零网络请求）。 */
const questionInput = computed({
  get: () => props.question,
  set: (value: string) => props.setQuestion(value),
})

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
      <h2 class="card-title">先确定要回答哪个问题</h2>
      <p class="field-note">
        手动填写问题原文（可粘贴知乎问题链接辅助核对，系统不会访问链接）。开头候选会围绕这个问题生成。
      </p>
    </header>

    <div class="form-field">
      <label class="field-note" for="answer-question">目标问题 <span class="required-mark" aria-hidden="true">*</span></label>
      <textarea
        id="answer-question"
        v-model="questionInput"
        class="topic-input"
        data-testid="answer-question-input"
        placeholder="粘贴或手输问题原文，例如：为什么大厂都在弃用 Kubernetes？"
        rows="3"
        :readonly="taskQuestionLocked"
      ></textarea>
      <p v-if="questionRef" class="question-ref-hint" data-testid="question-ref-hint">
        已识别问题链接 #{{ questionRef }}，标题请手动填写
      </p>
      <p v-else-if="question.trim() && !questionValid" class="field-note" role="alert">
        问题至少 {{ MIN_QUESTION_CHARS }} 字，请补全问题原文。
      </p>
    </div>

    <div class="form-field">
      <label class="field-note" for="answer-supplement">补充说明（选填）</label>
      <textarea
        id="answer-supplement"
        v-model="topicModel"
        class="topic-input"
        data-testid="answer-supplement-input"
        placeholder="你想强调的角度、亲历经验或必须覆盖的信息…"
        rows="3"
        @keydown.ctrl.enter="fetchTitles"
      ></textarea>
    </div>

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
      <p class="field-note">Ctrl + Enter 可直接生成开头候选</p>
    </div>

    <!-- 任务书 #62：风格三选向知乎开放；标题套路在此约束开头候选 -->
    <StyleSkillsPicker
      v-if="styleChipsVisible"
      v-model:title-formula="titleFormulaModel"
      v-model:genre="genreModel"
      v-model:style="styleModel"
      variant="formula"
      test-scope="question"
      radio-name-base="answer-title-formula"
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
        data-testid="answer-generate-openings"
        :disabled="titlesLoading || !questionValid || (styleChipsVisible && !titleFormula)"
        @click="fetchTitles"
      >
        {{ titlesLoading ? '生成中…' : '生成开头候选' }}
      </button>
    </div>
  </section>
</template>

<style scoped src="../stage-shared.css"></style>

<style scoped>
.question-ref-hint {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.8rem;
}
</style>
