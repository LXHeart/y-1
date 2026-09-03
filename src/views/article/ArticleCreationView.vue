<template>
  <div class="article-creation gl-field">
    <!-- 任务书 #62：知乎双模式选择（步骤条上方；默认写回答）。其余平台不渲染。 -->
    <div
      v-if="zhihuModeVisible && !completed"
      class="mode-toggle"
      role="radiogroup"
      aria-label="知乎内容形态"
      data-testid="zhihu-mode-toggle"
    >
      <button
        type="button"
        class="mode-btn"
        :class="{ 'mode-btn-active': contentMode === 'answer' }"
        :aria-checked="contentMode === 'answer'"
        role="radio"
        data-testid="zhihu-mode-answer"
        :disabled="taskQuestionLocked"
        @click="requestContentMode('answer')"
      >写回答</button>
      <button
        type="button"
        class="mode-btn"
        :class="{ 'mode-btn-active': contentMode === 'article' }"
        :aria-checked="contentMode === 'article'"
        role="radio"
        data-testid="zhihu-mode-article"
        :disabled="taskQuestionLocked"
        @click="requestContentMode('article')"
      >写文章</button>
      <p v-if="taskQuestionLocked" class="field-note mode-note" data-testid="task-mode-locked-note">
        任务指定回答形态，问题由商家给定，不可更改。
      </p>
      <p v-else class="field-note mode-note">
        {{ contentMode === 'answer'
          ? '回答挂在已有问题下，问题本身即标题，首屏前 100 字决定读者是否读完。'
          : '文章走搜索长尾，需要自己的标题。' }}
      </p>
    </div>

    <nav class="steps-bar" aria-label="创作步骤">
      <div
        v-for="(s, i) in steps"
        :key="s.key"
        class="step-dot"
        :class="{
          'step-active': !completed && stage === s.key,
          'step-done': completed || stepIndex(stage) > i,
        }"
      >
        <span class="step-num">{{ i + 1 }}</span>
        <span class="step-label">{{ s.label }}</span>
      </div>
    </nav>

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
    <section v-if="stage === 'question'" class="stage-card gl-zone fade-in">
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
          v-model="topic"
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
        v-model:title-formula="titleFormula"
        v-model:genre="genre"
        v-model:style="style"
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

    <section v-if="stage === 'topic'" class="stage-card gl-zone fade-in">
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
        v-model="topic"
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
        v-model:title-formula="titleFormula"
        v-model:genre="genre"
        v-model:style="style"
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

    <section v-if="stage === 'titles'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="stage = answerMode ? 'question' : 'topic'">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回
          </button>
          <p class="eyebrow">第二步</p>
        </div>
        <h2 class="card-title">{{ answerMode ? '从候选开头里选一个' : '从候选标题里选一个方向' }}</h2>
        <p class="field-note">
          {{ answerMode
            ? '开头决定读者是否读完；可直接点选，也可以在下方改写成你自己的开场。'
            : '可直接点选，也可以在下方手动改写成你更想要的标题。' }}
        </p>
      </header>

      <div v-if="formatRule && !answerMode" class="format-rule-bar" :class="{ 'format-rule-bar-warn': titleOverLimit }" role="note">
        <p class="format-rule-summary">{{ formatRuleSummary }}</p>
        <p v-if="titleOverLimit" class="format-rule-warn">标题已超过 {{ formatRule.maxTitleChars }} 字建议上限，建议精简后再发布。</p>
      </div>

      <ul class="title-list">
        <li v-for="(t, i) in titles" :key="i">
          <button
            type="button"
            class="title-item"
            :class="{ 'title-selected': selectedTitle === t.title }"
            :aria-pressed="selectedTitle === t.title"
            @click="selectTitle(t.title)"
          >
            <p class="title-text" :class="{ 'title-text-opening': answerMode }">{{ t.title }}</p>
            <p v-if="t.hook" class="title-hook">{{ t.hook }}</p>
          </button>
        </li>
      </ul>

      <div class="custom-title-area">
        <label class="field-note" for="custom-title">{{ answerMode ? '自定义开头' : '自定义标题' }}</label>
        <textarea
          v-if="answerMode"
          id="custom-title"
          v-model="selectedTitle"
          class="stream-textarea"
          data-testid="custom-opening-input"
          placeholder="写下你自己的开场（建议 60-120 字，先亮结论或抛判断）..."
          rows="4"
        ></textarea>
        <template v-else>
          <input
            id="custom-title"
            v-model="selectedTitle"
            class="custom-title-input"
            type="text"
            placeholder="输入你最终想用的标题..."
          >
          <!-- 任务书 #62：知乎文章标题上限 30 字（契约 platform-format-rules）-->
          <p
            v-if="formatRule && formatRule.maxTitleChars !== null"
            class="field-note title-counter"
            :class="{ 'title-counter-over': titleOverLimit }"
            data-testid="title-char-counter"
          >{{ selectedTitle.trim().length }} / {{ formatRule.maxTitleChars }} 字</p>
        </template>
      </div>

      <div class="action-row">
        <button
          class="btn-primary gl-btn-primary"
          :disabled="outlineLoading || !selectedTitle.trim()"
          @click="streamOutline"
        >
          {{ outlineLoading ? '生成中…' : '生成大纲' }}
        </button>
      </div>
      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="selectedTitle || titles.map((item) => item.title).join('\n')"
        :platform="platform"
        :content-form="checkContentForm"
        @updated="safetyReport = $event"
      />
    </section>

    <section v-if="stage === 'outline'" class="stage-card gl-zone fade-in">
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
          v-model="outline"
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
        v-model:title-formula="titleFormula"
        v-model:genre="genre"
        v-model:style="style"
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

    <section v-if="stage === 'content'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goToOutline">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回
          </button>
          <p class="eyebrow">第四步</p>
        </div>
        <div class="card-head-row card-head-row-wrap">
          <div>
            <h2 class="card-title">文章正文</h2>
            <p class="field-note">正文支持边生成边查看，完成后可继续人工润色。</p>
          </div>
          <button class="btn-secondary btn-sm" @click="copyContent">
            {{ copied ? '已复制' : '复制正文' }}
          </button>
        </div>
      </header>

      <div v-if="formatRule" class="format-rule-bar" :class="{ 'format-rule-bar-warn': formatIssues.length > 0 }" role="note">
        <p class="format-rule-summary">{{ formatRuleSummary }}</p>
        <p v-if="formatRule.tagHint" class="format-rule-hint">{{ formatRule.tagHint }}</p>
        <ul v-if="formatIssues.length > 0" class="format-rule-warnings">
          <li v-for="issue in formatIssues" :key="issue">{{ issue }}</li>
        </ul>
      </div>

      <div class="stream-area stream-area-large">
        <textarea
          v-model="content"
          class="stream-textarea"
          :class="{ 'stream-loading': contentLoading }"
          placeholder="正文会在这里实时生成..."
          rows="20"
        ></textarea>
        <div v-if="contentLoading" class="stream-badge">
          <span class="stream-dot"></span>
          生成中
        </div>
      </div>

      <p v-if="noteMode" class="field-note" data-test="note-mode-hint">
        小红书图文正文不配图：视觉素材用下方「拆成小红书图卡」生成；结尾话题标签已默认生成，可直接在正文末尾修改。
      </p>

      <div class="action-row">
        <button class="btn-secondary" @click="resetWorkflow">
          重新开始
        </button>
        <button
          v-if="contentLoading"
          class="btn-secondary"
          @click="cancel"
        >
          取消
        </button>
        <!-- 任务书 #63 卡5：正文步的收口统一走检查步（完成/软确认都在检查步），不在正文步直接完成 -->
        <button
          v-if="!contentLoading && content.trim()"
          class="btn-primary gl-btn-primary"
          data-test="go-check"
          @click="enterCheck"
        >
          去检查
        </button>
      </div>

      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="content"
        :platform="platform"
        :content-form="checkContentForm"
        @updated="safetyReport = $event"
      />

    </section>

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
import { useRouter } from 'vue-router'
import { useArticleCreation } from '../../composables/useArticleCreation'
import SafetyFindingsPanel from '../../components/SafetyFindingsPanel.vue'
import StyleSkillsPicker from './components/StyleSkillsPicker.vue'
import ArticleCheckStage from './components/ArticleCheckStage.vue'
import { useArticleFormatRule } from './composables/useArticleFormatRule'
import ArticleCompletedView from './components/ArticleCompletedView.vue'
import ArticleImageSlots from './components/ArticleImageSlots.vue'
import ArticleLightbox from './components/ArticleLightbox.vue'
import ArticlePlatformPicker from './components/ArticlePlatformPicker.vue'
import CardSeriesPanel from './components/CardSeriesPanel.vue'
import type { CreationHandoff } from '../../types/ai-creation'
import type { CreationStyleSkillOption } from '../../types/article-creation'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const router = useRouter()

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
  router.push({ name: 'ai-center' })
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

const MIN_QUESTION_CHARS = 8
const questionValid = computed(() => question.value.trim().length >= MIN_QUESTION_CHARS)

/** textarea 双向绑定要过 setQuestion（顺带本地提取 questionId，零网络请求）。 */
const questionInput = computed({
  get: () => question.value,
  set: (value: string) => setQuestion(value),
})

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

/** 已有产物时切模式要确认——两套 prompt 产物不可混用，切换必然清空。 */
function requestContentMode(mode: 'article' | 'answer'): void {
  if (taskQuestionLocked.value || contentMode.value === mode) return
  const hasProducts = titles.value.length > 0 || outline.value.trim() !== '' || content.value.trim() !== ''
  if (hasProducts && !window.confirm('切换模式会清空已生成的候选、大纲和正文，确定切换？')) return
  setContentMode(mode)
}

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

const { formatRule, formatRuleSummary, formatIssues, titleOverLimit } = useArticleFormatRule({
  platform,
  selectedTitle,
  content,
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

function stepIndex(s: string): number {
  return steps.value.findIndex((step) => step.key === s)
}

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

<style scoped>
.article-creation {
  display: grid;
  gap: var(--space-lg);
}

/* 任务书 #62：知乎模式分段控件（步骤条上方）。token 全部复用，不新增。 */
.mode-toggle {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 6px;
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.mode-btn {
  min-height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  background: transparent;
  /* secondary 而非 muted：未选档也是可点控件，muted 在暗色下只有 4.03:1 */
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.mode-btn:hover:not(:disabled) {
  color: var(--color-text-primary);
}

.mode-btn-active {
  background: var(--gradient-accent);
  color: var(--color-on-accent);
}

/* 任务书 #62 卡7：任务锁定形态时两档都禁用——激活档仍要看得清（保留渐变），
   只去掉可点手势与 hover 反馈，避免「像坏了」。 */
.mode-btn:disabled {
  cursor: not-allowed;
}

.mode-note {
  flex: 1 1 240px;
  margin: 0;
  padding-left: 4px;
}

.question-ref-hint {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.8rem;
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

.steps-bar {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.step-dot {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: var(--radius-pill);
  color: var(--color-text-muted);
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.step-active {
  background: var(--gradient-accent);
  border: 1px solid transparent;
  color: var(--color-on-accent);
}

.step-done {
  color: var(--color-text-secondary);
}

.step-num {
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  font-size: 0.74rem;
  font-weight: 700;
}

.step-active .step-num {
  background: var(--color-on-accent);
  border-color: transparent;
  color: var(--color-accent);
}

.step-done .step-num {
  color: var(--color-text-secondary);
}

.step-label {
  font-size: 0.83rem;
  font-weight: 600;
}

.stage-card,
.card-head,
.custom-title-area,
.stream-area,
.error-card {
  display: grid;
  gap: 14px;
}

.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-head-row-wrap {
  align-items: start;
}

.eyebrow,
.error-title {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.card-title {
  margin: 0;
  font-size: 1.14rem;
  font-weight: 600;
  line-height: 1.25;
  color: var(--color-text);
}

.field-note,
.error-text,
.title-hook {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.6;
}

.btn-back,
.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.topic-input,
.custom-title-input,
.stream-textarea {
  width: 100%;
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  font: inherit;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.topic-input,
.stream-textarea {
  resize: vertical;
  border-radius: var(--radius-md);
  padding: 14px 16px;
  line-height: 1.7;
}

.topic-input {
  min-height: 120px;
}

.custom-title-input {
  min-height: 42px;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
}

.stream-textarea {
  min-height: 220px;
}

.stream-area-large .stream-textarea {
  min-height: 420px;
}

.topic-input:focus,
.custom-title-input:focus,
.stream-textarea:focus {
  outline: none;
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.topic-input::placeholder,
.custom-title-input::placeholder,
.stream-textarea::placeholder {
  color: var(--color-text-muted);
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.platform-toggle {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.platform-locked {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.platform-locked .field-note {
  margin: 0;
}

.platform-btn {
  min-height: 30px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.platform-btn-active {
  background: color-mix(in srgb, var(--color-accent) 10%, transparent);
  border: 1px solid var(--color-border-accent);
  color: var(--color-accent-2);
}

.platform-btn:not(.platform-btn-active):hover {
  background: var(--color-surface-hover);
}

.platform-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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

.stream-area {
  position: relative;
}

.stream-loading {
  border-color: var(--color-border-accent);
}

.stream-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  color: var(--color-accent-2);
  font-size: 0.76rem;
  font-weight: 600;
}

.stream-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-accent);
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.btn-sm {
  min-height: 30px;
  padding: 0 var(--space-sm);
}

.error-card {
  border-color: color-mix(in srgb, var(--color-danger) 28%, transparent);
  background: color-mix(in srgb, var(--color-danger) 8%, transparent);
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

.format-rule-bar {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.format-rule-summary {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.55;
}

.format-rule-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  line-height: 1.5;
}

.format-rule-warnings {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 4px;
}

.format-rule-warnings li,
.format-rule-warn {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.82rem;
  line-height: 1.5;
}

.format-rule-bar-warn {
  border-color: color-mix(in srgb, var(--color-danger) 28%, transparent);
  background: color-mix(in srgb, var(--color-danger) 6%, transparent);
}

@media (max-width: 720px) {
  .card-head-row,
  .card-head-row-wrap {
    flex-direction: column;
    align-items: stretch;
  }

  .btn-primary,
  .btn-secondary,
  .btn-back,
  .btn-sm {
    width: 100%;
  }
}
</style>
