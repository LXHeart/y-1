<template>
  <div class="article-creation gl-field">
    <!-- 任务书 #62：知乎双模式选择（步骤条上方；默认写回答）。其余平台不渲染。
         任务书 #91 R1：面板化 components/ZhihuModeToggle.vue，v-if 挂组件标签。 -->
    <ZhihuModeToggle
      v-if="zhihuModeVisible && !completed"
      :content-mode="contentMode"
      :task-question-locked="taskQuestionLocked"
      :has-products="hasProducts"
      @set-mode="setContentMode"
    />

    <StepsBar :steps="steps" :stage="stage" :completed="completed" />

    <ArticleCompletedView
      v-if="completed"
      :selected-title="selectedTitle"
      :content-with-images="contentWithImages"
      :format-rule="formatRule"
      :format-rule-summary="formatRuleSummary"
      :format-issues="formatIssues"
      :answer-mode="answerMode"
      :publish-hints="publishHints"
      @copy="copyContent"
      @reset="resetWorkflow"
    />

    <SafetyFindingsPanel
      v-if="completed && safetyReport"
      :report="safetyReport"
      :text="content"
      :platform="platform"
      :content-form="checkContentForm"
      @updated="safetyReport = $event"
    />

    <template v-if="!completed">
    <!-- 任务书 #62：回答模式第一步——目标问题（纯手输，P2 拍板；链接只本地提取 id，零网络请求） -->
    <QuestionStage
      v-if="stage === 'question'"
      :from-creation-center="fromCreationCenter"
      :go-to-creation-center="goToCreationCenter"
      :question="question" :set-question="setQuestion"
      :question-ref="questionRef" :question-valid="questionValid"
      :task-question-locked="taskQuestionLocked"
      v-model:topic="topic"
      v-model:title-formula="titleFormula"
      v-model:genre="genre"
      v-model:style="style"
      :fetch-titles="fetchTitles"
      :platform-locked="platformLocked" :platform-label="platformLabel" :platform="platform"
      :is-douyin-mode="isDouyinMode" :titles-loading="titlesLoading"
      :select-non-douyin-platform="selectNonDouyinPlatform" :select-douyin="selectDouyin"
      :style-chips-visible="styleChipsVisible"
      :style-skills-loading="styleSkillsLoading" :style-skills-error="styleSkillsError"
      :fetch-style-skills="fetchStyleSkills"
      :formula-options="formulaOptions" :genre-options="genreOptions" :style-options="styleOptions"
    />

    <TopicStage
      v-if="stage === 'topic'"
      :from-creation-center="fromCreationCenter" :go-to-creation-center="goToCreationCenter"
      v-model:topic="topic"
      v-model:title-formula="titleFormula"
      v-model:genre="genre"
      v-model:style="style"
      :fetch-titles="fetchTitles"
      :platform-locked="platformLocked" :platform-label="platformLabel" :platform="platform"
      :is-douyin-mode="isDouyinMode" :titles-loading="titlesLoading"
      :select-non-douyin-platform="selectNonDouyinPlatform" :select-douyin="selectDouyin"
      :style-chips-visible="styleChipsVisible"
      :style-skills-loading="styleSkillsLoading" :style-skills-error="styleSkillsError"
      :fetch-style-skills="fetchStyleSkills"
      :formula-options="formulaOptions" :genre-options="genreOptions" :style-options="styleOptions"
    />

    <TitlesStage
      v-if="stage === 'titles'"
      :answer-mode="answerMode" :titles="titles"
      v-model:selected-title="selectedTitle"
      :select-title="selectTitle"
      :format-rule="formatRule" :format-rule-summary="formatRuleSummary" :title-over-limit="titleOverLimit"
      :outline-loading="outlineLoading" :stream-outline="streamOutline"
      :safety-report="safetyReport" :platform="platform" :check-content-form="checkContentForm"
      @update:safety-report="safetyReport = $event"
      @go-back="stage = answerMode ? 'question' : 'topic'"
    />

    <OutlineStage
      v-if="stage === 'outline'"
      v-model:outline="outline"
      v-model:title-formula="titleFormula"
      v-model:genre="genre"
      v-model:style="style"
      :outline-loading="outlineLoading" :go-to-titles="goToTitles"
      :stream-outline="streamOutline" :stream-content="streamContent"
      :content-loading="contentLoading" :cancel="cancel"
      :style-chips-visible="styleChipsVisible"
      :style-skills-loading="styleSkillsLoading" :style-skills-error="styleSkillsError"
      :fetch-style-skills="fetchStyleSkills"
      :formula-options="formulaOptions" :genre-options="genreOptions" :style-options="styleOptions"
    />

    <ContentStage
      v-if="stage === 'content'"
      v-model:content="content"
      :content-loading="contentLoading" :go-to-outline="goToOutline"
      :copy-content="copyContent" :copied="copied"
      :format-rule="formatRule" :format-rule-summary="formatRuleSummary" :format-issues="formatIssues"
      :cancel="cancel" :note-mode="noteMode" :reset-workflow="resetWorkflow" :enter-check="enterCheck"
      :safety-report="safetyReport" :platform="platform" :check-content-form="checkContentForm"
      @update:safety-report="safetyReport = $event"
    />

    <!-- 任务书 #63 卡5：独立检查步——正文只读预览 + 修复面板（enableFix），软确认放行 -->
    <ArticleCheckStage
      v-if="stage === 'check'"
      :content="content"
      :safety-report="safetyReport"
      :platform="platform"
      :content-form="checkContentForm"
      :safety-checking="safetyChecking"
      :images-stage-skipped="imagesStageSkipped"
      :genre-name="selectedGenre?.name"
      :style-name="selectedStyle?.name"
      @recheck="checkSafety"
      @go-edit="goEditContent"
      @proceed="proceedFromCheck"
      @rechecked="onPanelRechecked"
      @apply-fix="applySafetyFix"
    />

    <ArticleImageSlots
      v-if="stage === 'images'"
      :image-slots="imageSlots"
      :image-recommendations="imageRecommendations"
      :loading-recommendations="loadingRecommendations"
      @go-back="goToContent"
      @load-recommendations="loadImageRecommendations"
      @finish="finish"
      @toggle-slot="toggleSlot"
      @clear-image-for-slot="clearImageForSlot"
      @search-image-for-slot="searchImageForSlot"
      @generate-image-for-slot="generateImageForSlot"
      @select-image-for-slot="selectImageForSlot"
      @open-lightbox="openLightbox"
    />

    <SafetyFindingsPanel
      v-if="stage === 'images' && safetyReport"
      :report="safetyReport"
      :text="content"
      :platform="platform"
      :content-form="checkContentForm"
      @updated="safetyReport = $event"
    />

    <!-- 任务书 #54 2026-08-30 修订：图卡并入小红书图文流；任务书 #60：小红书（非抖音）正文流
         完成后停留 content 阶段（不再进配图），抖音仍经 content→images，故保持两阶段挂载不变；
         #69 卡B：douyin 一等 platform 值，图卡同样挂抖音流（后端 cardseries 平台值域已认 douyin） -->
    <CardSeriesPanel
      v-if="(platform === 'xiaohongshu' || platform === 'douyin') && (stage === 'content' || stage === 'images') && content.trim().length >= 50"
      :platform="platform"
      :content="content"
      @open-lightbox="openLightbox"
    />

    <section v-if="error" class="error-card gl-zone fade-in">
      <p class="error-title">生成失败</p>
      <p class="error-text">{{ error }}</p>
    </section>
    </template>

    <ArticleLightbox :src="lightboxSrc" @close="closeLightbox" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useArticleCreation } from '../../composables/useArticleCreation'
import SafetyFindingsPanel from '../../components/SafetyFindingsPanel.vue'
import ZhihuModeToggle from './components/ZhihuModeToggle.vue'
import StepsBar from './components/StepsBar.vue'
import QuestionStage from './components/QuestionStage.vue'
import TopicStage from './components/TopicStage.vue'
import TitlesStage from './components/TitlesStage.vue'
import OutlineStage from './components/OutlineStage.vue'
import ContentStage from './components/ContentStage.vue'
import ArticleCheckStage from './components/ArticleCheckStage.vue'
import { useArticleFormatRule } from './composables/useArticleFormatRule'
import ArticleCompletedView from './components/ArticleCompletedView.vue'
import ArticleImageSlots from './components/ArticleImageSlots.vue'
import ArticleLightbox from './components/ArticleLightbox.vue'
import CardSeriesPanel from './components/CardSeriesPanel.vue'
import type { CreationHandoff } from '../../types/ai-creation'
import type { CreationStyleSkillOption } from '../../types/article-creation'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const emit = defineEmits<{ 'open-view': [view: 'ai-center'] }>()

const {
  stage, topic, platform, titles, selectedTitle, outline, content, safetyReport,
  safetyChecking,
  titlesLoading, outlineLoading, contentLoading, error,
  titleFormula, genre, style, styleSkillOptions,
  styleSkillsLoading, styleSkillsError, styleSkillsActive, imagesStageSkipped,
  contentMode, question, questionRef, setContentMode, setQuestion,
  imageSlots, imageRecommendations, loadingRecommendations, completed,
  fetchTitles, streamOutline, streamContent, fetchStyleSkills,
  selectTitle, goToTitles, goToOutline, goToContent,
  checkSafety, enterCheck, onPanelRechecked, applySafetyFix, proceedFromCheck,
  loadImageRecommendations, searchImageForSlot, generateImageForSlot,
  selectImageForSlot, clearImageForSlot, toggleSlot,
  reset, cancel, setTopic, bindCreationContext, finish,
} = useArticleCreation()

const hydratedCreationRevision = ref<number | null>(null)

// 抖音（图集短文案）已升格为一等 platform 值 'douyin'（任务书 #69 卡B）——生成链路直连后端
// DOUYIN 模板；isDouyinMode 保留作视图标记（选择器分组与 UI 提示仍用，不再决定 platform 值）。
const isDouyinMode = ref(false)

/**
 * 创作中心 handoff 会话：平台/形式在创作中心配置完毕后带入，此视图内不再提供二次切换
 * （换平台=换创作上下文，应回创作中心重新配置）；直入 /article 无 handoff 时保持四选。
 */
const platformLocked = ref(false)

const fromCreationCenter = computed(() => props.creationHandoff != null)

const platformLabel = computed(() => {
  if (platform.value === 'douyin') return '抖音'
  if (platform.value === 'zhihu') return '知乎'
  if (platform.value === 'xiaohongshu') return '小红书'
  return '微信公众号'
})

function goToCreationCenter(): void {
  // 共享视图双挂载（任务书 #76）：返回创作中心交给各壳路由，不硬编码路由名
  emit('open-view', 'ai-center')
}

/** 锁定会话内「重新开始/完成再来一篇」保留平台，其余状态照常清空。 */
function resetWorkflow(): void {
  reset({ keepPlatform: platformLocked.value })
}

function selectDouyin(): void {
  platform.value = 'douyin'
  isDouyinMode.value = true
}

function selectNonDouyinPlatform(target: 'wechat' | 'zhihu' | 'xiaohongshu'): void {
  platform.value = target
  isDouyinMode.value = false
}

watch(platform, (value) => {
  if (value !== 'douyin') isDouyinMode.value = false
})

/**
 * 任务书 #62：知乎回答/文章双模式。模式选择只在知乎出现（P1 拍板：platform 值不拆，
 * 进入知乎后再选形态）；离开知乎强制回文章模式——**显式同步给 composable**（全局约束 5），
 * 不让 composable 自己从 platform 反推。
 */
const zhihuModeVisible = computed(() => platform.value === 'zhihu')

/** 回答模式（视图口径）：知乎 + mode=answer。步骤流与文案分叉都以它为准。 */
const answerMode = computed(() => platform.value === 'zhihu' && contentMode.value === 'answer')


/** 平台 → 模式的唯一归一处：知乎默认写回答（推流优先级 回答 > 文章），其余恒文章。 */
function syncContentModeToPlatform(): void {
  setContentMode(platform.value === 'zhihu' ? 'answer' : 'article')
}

watch(platform, (value, previous) => {
  if (value === 'zhihu' && previous === 'zhihu') return
  syncContentModeToPlatform()
}, { immediate: true })

/**
 * 任务书 #62 卡7：任务指定了目标问题 → 交付形态由商家决定，模式不可改、问题只读。
 * 冻结上下文是权威（同卡4 后端「快照 question 优先于请求体」的前端对偶）。
 */
const taskQuestionLocked = ref(false)

const MIN_QUESTION_CHARS = 8
const questionValid = computed(() => question.value.trim().length >= MIN_QUESTION_CHARS)

/** 已有产物判定（R1 起经 props 传入 ZhihuModeToggle，confirm 逻辑在组件内原样保留）。 */
const hasProducts = computed(() =>
  titles.value.length > 0 || outline.value.trim() !== '' || content.value.trim() !== '')

// 任务书 #57：三选择器只在「小红书 && 非抖音」出现——抖音 platform 值同为 xiaohongshu，
// 是否携带必须由视图显式同步给 composable（styleSkillsActive），不能只看 platform。
// 任务书 #62：风格三选向知乎开放（回答与文章两模式都有）。
const styleChipsVisible = computed(() =>
  (platform.value === 'xiaohongshu' && !isDouyinMode.value) || platform.value === 'zhihu')

/** 目录过滤口径（任务书 #62）：与 skill 的 `applicablePlatforms` 值域对齐。 */
const skillPlatformId = computed(() => (isDouyinMode.value ? 'douyin' : platform.value))

/** 空 applicablePlatforms = 全平台通用；否则只在列出的平台可选。 */
function appliesToCurrentPlatform(option: CreationStyleSkillOption): boolean {
  const scope = option.applicablePlatforms
  return !scope || scope.length === 0 || scope.includes(skillPlatformId.value)
}

const formulaOptions = computed(() => styleSkillOptions.value.TITLE_FORMULA.filter(appliesToCurrentPlatform))
const genreOptions = computed(() => styleSkillOptions.value.GENRE.filter(appliesToCurrentPlatform))
const styleOptions = computed(() => styleSkillOptions.value.STYLE.filter(appliesToCurrentPlatform))

/**
 * 换平台后清掉已不适用的选择——后端风格注入平台无关（不校验），
 * 留着会把知乎专属套路发给小红书。
 */
watch(skillPlatformId, () => {
  if (titleFormula.value && !formulaOptions.value.some((item) => item.code === titleFormula.value)) titleFormula.value = ''
  if (genre.value && !genreOptions.value.some((item) => item.code === genre.value)) genre.value = ''
  if (style.value && !styleOptions.value.some((item) => item.code === style.value)) style.value = ''
})
const selectedGenre = computed(() => genreOptions.value.find((item) => item.code === genre.value))
const selectedStyle = computed(() => styleOptions.value.find((item) => item.code === style.value))

let styleSkillsFetchAttempted = false
watch(styleChipsVisible, (visible) => {
  styleSkillsActive.value = visible
  // 懒拉目录（一次）：失败不重拉自动，chips 区内提供显式重试
  if (visible && !styleSkillsFetchAttempted) {
    styleSkillsFetchAttempted = true
    void fetchStyleSkills()
  }
}, { immediate: true })

// 任务书 #60：小红书图文（非抖音）= 纯文字正文 + 图卡，不进入正文配图阶段。
// 与 styleChipsVisible 同条件但语义独立，各自显式同步给 composable。
const noteMode = computed(() => platform.value === 'xiaohongshu' && !isDouyinMode.value)

watch(noteMode, (mode) => {
  imagesStageSkipped.value = mode
}, { immediate: true })

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'article' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  const initialTopic = handoff.source.type === 'reference'
    ? [handoff.prefill?.topic, handoff.prefill?.instructions].filter(Boolean).join('\n\n')
    : handoff.prefill?.topic || ''
  setTopic(initialTopic)
  bindCreationContext(
    handoff.source.type === 'task', handoff.contextSnapshotId, handoff.platformId,
  )
  const platformByEntry = {
    'wechat-official': 'wechat',
    zhihu: 'zhihu',
    xiaohongshu: 'xiaohongshu',
    douyin: 'douyin',
  } as const
  if (handoff.platformId in platformByEntry) {
    platform.value = platformByEntry[handoff.platformId as keyof typeof platformByEntry]
    isDouyinMode.value = handoff.platformId === 'douyin'
  }
  // 任务书 #62：同步定模式，别等 platform watcher 的 pre-flush——否则知乎 handoff
  // 首帧会先渲染文章模式的主题步再跳到问题步（可见闪一下）。
  syncContentModeToPlatform()
  // 任务书 #62 卡7：知乎任务带目标问题 → 锁回答形态并预填只读问题；不带则用户自选
  // （默认写回答）。问题原文取自 accept 时冻结的 taskContext，不信任前端 task JSON。
  const taskQuestion = handoff.taskContext?.questionText?.trim() || ''
  taskQuestionLocked.value = handoff.platformId === 'zhihu' && taskQuestion !== ''
  if (taskQuestionLocked.value) {
    setQuestion(taskQuestion)
    setContentMode('answer')
  }
  platformLocked.value = true
}, { immediate: true })

const copied = ref(false)
const lightboxSrc = ref('')

/**
 * 任务书 #70 卡C：任务要求 mustInclude（必须关键词）从 accept 时冻结的 taskContext 提取，
 * 供规范检查做覆盖检查——不信任前端 task JSON，同 questionText 口径。
 */
const mustIncludeTerms = computed<readonly string[]>(() => {
  const raw = props.creationHandoff?.taskContext?.requirements?.mustInclude
  return Array.isArray(raw) ? raw.filter((term): term is string => typeof term === 'string') : []
})

const { formatRule, formatRuleSummary, formatIssues, titleOverLimit } = useArticleFormatRule({
  platform,
  selectedTitle,
  content,
  mustInclude: mustIncludeTerms,
})

/**
 * 任务书 #62 完成步提示条：知乎回答无话题标签（问题下发布），文章模式沿用契约 tagHint
 * 并追加 AI 辅助创作声明提醒——只提示，不改正文（advisory，与内容安全同姿态）。
 */
const publishHints = computed(() => {
  if (platform.value !== 'zhihu') return []
  if (answerMode.value) return ['回答已就绪，发布时挂回原问题。']
  const hints: string[] = []
  if (formatRule.value?.tagHint) hints.push(formatRule.value.tagHint)
  hints.push('知乎要求 AI 辅助创作须声明，发布时请勾选。')
  return hints
})

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

// 任务书 #63 卡5：所有平台正文之后插入独立「检查」步——有配图流 …正文 → 检查 → 配图；
// noteMode（小红书图文）检查为收尾步；知乎回答六步同插。
const steps = computed(() => {
  // 任务书 #62：知乎回答模式首步是「问题」而非「主题」。
  if (answerMode.value) {
    return [
      { key: 'question' as const, label: '问题' },
      { key: 'titles' as const, label: '开头' },
      { key: 'outline' as const, label: '大纲' },
      { key: 'content' as const, label: '正文' },
      { key: 'check' as const, label: '检查' },
      { key: 'images' as const, label: '配图' },
    ]
  }
  const base = [
    { key: 'topic' as const, label: '主题' },
    { key: 'titles' as const, label: '标题' },
    { key: 'outline' as const, label: '大纲' },
    { key: 'content' as const, label: '正文' },
    { key: 'check' as const, label: '检查' },
  ]
  return noteMode.value ? base : [...base, { key: 'images' as const, label: '配图' }]
})

// ==================== 任务书 #63 卡5：检查步 ====================

/** 修复请求的 contentForm：仅知乎携带 answer|article（其余平台传了会触发知乎形态句）。 */
const checkContentForm = computed(() =>
  platform.value === 'zhihu' ? contentMode.value : undefined)

/** 返回正文编辑（保留检查状态；改完经「去检查」回来会自动复查）。 */
function goEditContent(): void {
  stage.value = 'content'
}

async function copyContent(): Promise<void> {
  try {
    await navigator.clipboard.writeText(contentWithImages.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = contentWithImages.value
    textarea.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

const contentWithImages = computed(() => {
  if (imageSlots.value.length === 0) return content.value

  const paragraphs = content.value.split(/\n\n+/)
  const parts: string[] = []

  for (let i = 0; i < paragraphs.length; i++) {
    parts.push(paragraphs[i])
    const slot = imageSlots.value[i]
    if (slot && !slot.skipped && slot.selectedImage) {
      const img = slot.selectedImage
      const src = 'imageUrl' in img ? img.imageUrl : img.thumbnailUrl
      parts.push(`![${slot.placement.description}](${src})`)
    }
  }

  return parts.join('\n\n')
})
</script>

<style scoped src="./stage-shared.css"></style>

<style scoped>
/* 任务书 #91 R1：跨阶段原语已上提 stage-shared.css（mode-toggle/steps-bar/question-ref-hint
   随组件迁出；.platform-toggle/.platform-btn 死样式已删——指向 ArticlePlatformPicker 内部，
   scoped 穿不透，今日即未生效）。title-list/platform-mode-hint 暂留（R2 迁出）。 */
.article-creation {
  display: grid;
  gap: var(--space-lg);
}

/* 任务书 #91 R2：title-list/platform-mode-hint 样式已随阶段组件迁出。 */
</style>

<style scoped>
/* 任务书 #91 R1：跨阶段原语已上提 stage-shared.css（mode-toggle/steps-bar/question-ref-hint
   随组件迁出；.platform-toggle/.platform-btn 死样式已删——指向 ArticlePlatformPicker 内部，
   scoped 穿不透，今日即未生效）。title-list/platform-mode-hint 暂留（R2 迁出）。 */
.article-creation {
  display: grid;
  gap: var(--space-lg);
}

.title-text-opening {
  white-space: pre-wrap;
}

.title-counter {
  margin: 0;
}

.title-counter-over {
  color: var(--color-danger);
  font-weight: 600;
}

.title-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.title-item {
  width: 100%;
  display: grid;
  gap: 6px;
  padding: var(--space-md);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--gradient-surface);
  cursor: pointer;
  text-align: left;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.title-item:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  transform: translateY(-1px);
}

.title-item:focus-visible {
  outline: none;
  border-color: var(--color-border-accent);
  box-shadow: var(--focus-ring);
}

.title-selected {
  background: var(--color-surface-highlight);
  border-color: var(--color-border-accent);
  box-shadow: var(--focus-ring);
}

.title-text {
  margin: 0;
  color: var(--color-text);
  font-size: 0.96rem;
  font-weight: 600;
  line-height: 1.45;
}

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
