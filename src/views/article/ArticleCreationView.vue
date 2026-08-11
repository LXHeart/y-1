<template>
  <div class="article-creation">
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
      @copy="copyContent"
      @reset="reset"
    />

    <template v-else>
    <section v-if="stage === 'topic'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <p class="eyebrow">第一步</p>
        <h2 class="card-title">先确定主题和发布平台</h2>
        <p class="field-note">从一个明确主题开始，再决定内容更偏公众号、知乎、小红书还是抖音的表达方式。</p>
      </header>

      <textarea
        v-model="topic"
        class="topic-input"
        placeholder="输入你想创作的主题或关键词，例如：职场沟通技巧、自媒体运营心得、餐饮创业复盘..."
        rows="5"
        @keydown.ctrl.enter="fetchTitles"
      ></textarea>

      <div class="settings-row">
        <div class="platform-toggle" role="tablist" aria-label="文章平台">
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'wechat' }"
            :disabled="titlesLoading"
            @click="selectNonDouyinPlatform('wechat')"
          >微信公众号</button>
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'zhihu' }"
            :disabled="titlesLoading"
            @click="selectNonDouyinPlatform('zhihu')"
          >知乎</button>
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'xiaohongshu' && !isDouyinMode }"
            :disabled="titlesLoading"
            @click="selectNonDouyinPlatform('xiaohongshu')"
          >小红书</button>
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'xiaohongshu' && isDouyinMode }"
            :disabled="titlesLoading"
            @click="selectDouyin"
          >抖音</button>
        </div>
        <p class="field-note">Ctrl + Enter 可直接生成标题</p>
      </div>

      <p v-if="platform === 'xiaohongshu' && isDouyinMode" class="platform-mode-hint">
        抖音定位图集短文案：短句式表达、强开场突出卖点、结尾带话题标签，配图建议竖版封面并按顺序编排。
      </p>

      <div class="action-row">
        <button
          class="btn-primary"
          :disabled="titlesLoading || !topic.trim()"
          @click="fetchTitles"
        >
          {{ titlesLoading ? '生成中…' : '生成标题' }}
        </button>
      </div>
    </section>

    <section v-if="stage === 'titles'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="stage = 'topic'">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回
          </button>
          <p class="eyebrow">第二步</p>
        </div>
        <h2 class="card-title">从候选标题里选一个方向</h2>
        <p class="field-note">可直接点选，也可以在下方手动改写成你更想要的标题。</p>
      </header>

      <div v-if="formatRule" class="format-rule-bar" :class="{ 'format-rule-bar-warn': titleOverLimit }" role="note">
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
            <p class="title-text">{{ t.title }}</p>
            <p v-if="t.hook" class="title-hook">{{ t.hook }}</p>
          </button>
        </li>
      </ul>

      <div class="custom-title-area">
        <label class="field-note" for="custom-title">自定义标题</label>
        <input
          id="custom-title"
          v-model="selectedTitle"
          class="custom-title-input"
          type="text"
          placeholder="输入你最终想用的标题..."
        >
      </div>

      <div class="action-row">
        <button
          class="btn-primary"
          :disabled="outlineLoading || !selectedTitle.trim()"
          @click="streamOutline"
        >
          {{ outlineLoading ? '生成中…' : '生成大纲' }}
        </button>
      </div>
    </section>

    <section v-if="stage === 'outline'" class="stage-card glass-card fade-in">
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

      <div class="action-row">
        <button
          class="btn-primary"
          :disabled="contentLoading || outlineLoading || !outline.trim()"
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

    <section v-if="stage === 'content'" class="stage-card glass-card fade-in">
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

      <div class="action-row">
        <button class="btn-secondary" @click="reset">
          重新开始
        </button>
        <button
          v-if="contentLoading"
          class="btn-secondary"
          @click="cancel"
        >
          取消
        </button>
      </div>
    </section>

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

    <section v-if="error" class="error-card glass-card fade-in">
      <p class="error-title">生成失败</p>
      <p class="error-text">{{ error }}</p>
    </section>
    </template>
    <ArticleLightbox :src="lightboxSrc" @close="closeLightbox" />
  </div>
</template>

<script setup lang="ts">
import { computed, inject, ref, type Ref, watch } from 'vue'
import { useArticleCreation } from '../../composables/useArticleCreation'
import { useArticleFormatRule } from './composables/useArticleFormatRule'
import ArticleCompletedView from './components/ArticleCompletedView.vue'
import ArticleImageSlots from './components/ArticleImageSlots.vue'
import ArticleLightbox from './components/ArticleLightbox.vue'
import type { CreationHandoff } from '../../types/ai-creation'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const {
  stage, topic, platform, titles, selectedTitle, outline, content,
  titlesLoading, outlineLoading, contentLoading, error,
  imageSlots, imageRecommendations, loadingRecommendations, completed,
  fetchTitles, streamOutline, streamContent,
  selectTitle, goToTitles, goToOutline, goToContent,
  loadImageRecommendations, searchImageForSlot, generateImageForSlot,
  selectImageForSlot, clearImageForSlot, toggleSlot,
  reset, cancel, setTopic, finish,
} = useArticleCreation()

const articleInitialTopic = inject<Ref<string>>('articleInitialTopic')
const hydratedCreationRevision = ref<number | null>(null)

// 抖音（图集短文案）复用现有小红书平台契约，仅前端提示词/文案层差异，API 契约不变。
const isDouyinMode = ref(false)

function selectDouyin(): void {
  platform.value = 'xiaohongshu'
  isDouyinMode.value = true
}

function selectNonDouyinPlatform(target: 'wechat' | 'zhihu' | 'xiaohongshu'): void {
  platform.value = target
  isDouyinMode.value = false
}

watch(platform, (value) => {
  if (value !== 'xiaohongshu') isDouyinMode.value = false
})

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'article' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  setTopic(handoff.prefill?.topic || '')
  const platformByEntry = {
    'wechat-official': 'wechat',
    zhihu: 'zhihu',
    xiaohongshu: 'xiaohongshu',
  } as const
  if (handoff.platformId in platformByEntry) {
    platform.value = platformByEntry[handoff.platformId as keyof typeof platformByEntry]
    isDouyinMode.value = false
  } else if (handoff.platformId === 'douyin') {
    selectDouyin()
  }
}, { immediate: true })

watch(articleInitialTopic!, (val) => {
  if (val) {
    setTopic(val)
  }
}, { immediate: true })

const copied = ref(false)
const lightboxSrc = ref('')

const { formatRule, formatRuleSummary, formatIssues, titleOverLimit } = useArticleFormatRule({
  platform,
  isDouyinMode,
  selectedTitle,
  content,
})

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

const steps = [
  { key: 'topic' as const, label: '主题' },
  { key: 'titles' as const, label: '标题' },
  { key: 'outline' as const, label: '大纲' },
  { key: 'content' as const, label: '正文' },
  { key: 'images' as const, label: '配图' },
]

function stepIndex(s: string): number {
  return steps.findIndex((step) => step.key === s)
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

.steps-bar {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.step-dot {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: calc(var(--radius-md) - 4px);
  color: var(--color-text-muted);
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.step-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.step-done {
  color: var(--color-text-secondary);
}

.step-num {
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  font-size: 0.74rem;
  font-weight: 700;
}

.step-active .step-num {
  background: var(--color-accent);
  border-color: transparent;
  color: white;
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

.btn-back {
  min-height: 36px;
  padding: 0 12px;
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-back:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
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
  border-radius: var(--radius-lg);
  padding: 14px 16px;
  line-height: 1.7;
}

.topic-input {
  min-height: 120px;
}

.custom-title-input {
  min-height: 42px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
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
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.platform-btn {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.platform-btn-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
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
  padding: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
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
  background: var(--surface-card);
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
  border-radius: 999px;
  background: rgba(114, 132, 248, 0.12);
  color: var(--color-accent);
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
  min-height: 40px;
  padding: 0 16px;
}

.btn-primary {
  background: var(--color-accent);
  color: white;
  border: none;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.btn-secondary {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.btn-sm {
  min-height: 36px;
  padding: 0 14px;
}

.error-card {
  border-color: rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.08);
}

.platform-mode-hint {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid rgba(114, 132, 248, 0.28);
  background: rgba(114, 132, 248, 0.08);
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
  color: var(--color-danger, #d97706);
  font-size: 0.82rem;
  line-height: 1.5;
}

.format-rule-bar-warn {
  border-color: rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.06);
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
