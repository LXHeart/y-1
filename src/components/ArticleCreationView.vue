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

    <section v-if="completed" class="stage-card glass-card fade-in">
      <header class="card-head">
        <h2 class="card-title">文章已完成</h2>
        <p class="field-note">{{ selectedTitle }}</p>
      </header>

      <div class="completed-preview">
        <div v-if="imageSlots[0]?.selectedImage" class="completed-image">
          <img
            :src="'imageUrl' in imageSlots[0].selectedImage ? imageSlots[0].selectedImage.imageUrl : imageSlots[0].selectedImage.thumbnailUrl"
            :alt="imageSlots[0].placement?.description || '封面图'"
            class="completed-cover-img clickable-img"
            @click="openLightbox(($event.currentTarget as HTMLImageElement).src)"
          />
        </div>
        <div class="completed-body" v-html="renderMarkdown(content)"></div>
      </div>

      <div class="action-row">
        <button class="btn-primary" @click="copyContent">复制正文</button>
        <button class="btn-secondary" @click="reset">新建文章</button>
      </div>
    </section>

    <template v-else>
    <section v-if="stage === 'topic'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <p class="eyebrow">第一步</p>
        <h2 class="card-title">先确定主题和发布平台</h2>
        <p class="field-note">从一个明确主题开始，再决定内容更偏公众号、知乎还是小红书的表达方式。</p>
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
            @click="platform = 'wechat'"
          >微信公众号</button>
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'zhihu' }"
            :disabled="titlesLoading"
            @click="platform = 'zhihu'"
          >知乎</button>
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'xiaohongshu' }"
            :disabled="titlesLoading"
            @click="platform = 'xiaohongshu'"
          >小红书</button>
        </div>
        <p class="field-note">Ctrl + Enter 可直接生成标题</p>
      </div>

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

    <section v-if="stage === 'images'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goToContent">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回正文
          </button>
          <p class="eyebrow">第五步</p>
        </div>
        <h2 class="card-title">为文章配上封面图</h2>
        <p class="field-note">AI 根据文章内容推荐配图方案，你可以从网络搜图或用 AI 生成。</p>
      </header>

      <div v-if="!imageRecommendations && !loadingRecommendations" class="action-row">
        <button class="btn-primary" @click="loadImageRecommendations">
          获取配图推荐
        </button>
        <button class="btn-secondary" @click="finish">跳过，直接完成</button>
      </div>

      <div v-if="loadingRecommendations" class="loading-hint">
        <span class="stream-dot"></span>
        正在分析文章内容并推荐配图…
      </div>

      <div v-if="imageRecommendations && imageSlots.length > 0" class="images-layout">
        <div class="image-slot-card">
          <div class="slot-head">
            <span class="slot-position">{{ imageSlots[0].placement.position }}</span>
            <span class="slot-desc">{{ imageSlots[0].placement.description }}</span>
          </div>

          <div v-if="imageSlots[0].selectedImage" class="slot-selected">
            <img
              :src="'imageUrl' in imageSlots[0].selectedImage ? imageSlots[0].selectedImage.imageUrl : imageSlots[0].selectedImage.thumbnailUrl"
              :alt="imageSlots[0].placement.description"
              class="slot-preview-img clickable-img"
              @click="openLightbox(($event.currentTarget as HTMLImageElement).src)"
            />
            <div class="slot-selected-actions">
              <button class="btn-secondary btn-sm" type="button" @click="clearImageForSlot(0)">移除重选</button>
            </div>
          </div>

          <div v-if="!imageSlots[0].selectedImage" class="slot-actions">
            <div class="slot-tabs">
              <button
                type="button"
                class="slot-tab"
                :class="{ 'slot-tab-active': imageSlots[0].mode === 'search' }"
                :disabled="imageSlots[0].searching"
                @click="searchImageForSlot(0)"
              >搜图</button>
              <button
                type="button"
                class="slot-tab"
                :class="{ 'slot-tab-active': imageSlots[0].mode === 'generate' }"
                :disabled="imageSlots[0].generating"
                @click="generateImageForSlot(0)"
              >AI 生成</button>
            </div>

            <div v-if="imageSlots[0].searching" class="loading-hint loading-hint-sm">
              <span class="stream-dot"></span> 搜索中…
            </div>
            <div v-else-if="imageSlots[0].generating" class="loading-hint loading-hint-sm">
              <span class="stream-dot"></span> AI 生成中，可能需要 1-2 分钟…
            </div>

            <div v-if="imageSlots[0].searchResults.length > 0 && !imageSlots[0].selectedImage" class="search-grid">
              <div
                v-for="(img, imgIdx) in imageSlots[0].searchResults"
                :key="imgIdx"
                class="search-thumb-wrap"
              >
                <button type="button" class="search-thumb" @click="selectImageForSlot(0, img)">
                  <img :src="img.thumbnailUrl" :alt="img.description || imageSlots[0].placement.description" />
                </button>
                <button type="button" class="thumb-zoom" @click="openLightbox(img.url)" title="放大查看">
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="M6 10L10 6M10 6H6.5M10 6V9.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M2 6V4a2 2 0 012-2h2M10 2h2a2 2 0 012 2v2M14 10v2a2 2 0 01-2 2h-2M6 14H4a2 2 0 01-2-2v-2" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="imageRecommendations" class="action-row">
        <button class="btn-primary" @click="finish">完成</button>
        <button class="btn-secondary" @click="loadImageRecommendations">重新推荐</button>
      </div>
    </section>

    <section v-if="error" class="error-card glass-card fade-in">
      <p class="error-title">生成失败</p>
      <p class="error-text">{{ error }}</p>
    </section>
    </template>
    <Teleport to="body">
      <div v-if="lightboxSrc" class="lightbox-overlay" @click.self="closeLightbox">
        <button class="lightbox-close" type="button" @click="closeLightbox" aria-label="关闭">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M5 5l10 10M15 5L5 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
        </button>
        <img :src="lightboxSrc" class="lightbox-img" alt="放大预览" @click.stop />
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted, ref, type Ref, watch } from 'vue'
import { useArticleCreation } from '../composables/useArticleCreation'

const {
  stage, topic, platform, titles, selectedTitle, outline, content,
  titlesLoading, outlineLoading, contentLoading, error,
  imageSlots, imageRecommendations, loadingRecommendations, completed,
  fetchTitles, streamOutline, streamContent,
  selectTitle, goToTitles, goToOutline, goToContent,
  loadImageRecommendations, searchImageForSlot, generateImageForSlot,
  selectImageForSlot, clearImageForSlot,
  reset, cancel, setTopic, finish,
} = useArticleCreation()

const articleInitialTopic = inject<Ref<string>>('articleInitialTopic')

watch(articleInitialTopic!, (val) => {
  if (val) {
    setTopic(val)
  }
}, { immediate: true })

const copied = ref(false)
const lightboxSrc = ref('')

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

function handleLightboxKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' && lightboxSrc.value) {
    closeLightbox()
  }
}

onMounted(() => document.addEventListener('keydown', handleLightboxKey))
onBeforeUnmount(() => document.removeEventListener('keydown', handleLightboxKey))

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
    await navigator.clipboard.writeText(content.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = content.value
    textarea.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

function renderMarkdown(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/\n/g, '<br>')
    .replace(/^/, '<p>')
    .replace(/$/, '</p>')
}
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

.images-layout {
  display: grid;
  gap: 16px;
}

.images-slot-header {
  margin: 0;
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.image-slot-card {
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  display: grid;
  gap: 12px;
}

.slot-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.slot-position {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(114, 132, 248, 0.12);
  color: var(--color-accent);
  font-size: 0.76rem;
  font-weight: 700;
  white-space: nowrap;
}

.slot-desc {
  font-size: 0.85rem;
  color: var(--color-text-secondary);
  line-height: 1.5;
}

.slot-selected {
  display: grid;
  gap: 10px;
}

.slot-preview-img {
  width: 100%;
  max-height: 240px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.slot-actions {
  display: grid;
  gap: 10px;
}

.slot-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  width: fit-content;
}

.slot-tab {
  min-height: 32px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.slot-tab-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.slot-tab:not(.slot-tab-active):hover {
  background: var(--color-surface-hover);
}

.slot-tab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.search-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.search-thumb {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  padding: 0;
  background: var(--surface-card);
  transition: border-color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}

.search-thumb:hover {
  border-color: var(--color-border-accent);
  transform: translateY(-1px);
}

.search-thumb img {
  width: 100%;
  aspect-ratio: 4/3;
  object-fit: cover;
  display: block;
}

.loading-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.loading-hint-sm {
  padding: 4px 0;
}

.completed-preview {
  margin-top: 16px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 24px;
  border: 1px solid var(--color-border);
}

.completed-cover-img {
  width: 100%;
  max-height: 280px;
  object-fit: cover;
  border-radius: var(--radius-md);
  margin-bottom: 16px;
}

.completed-body {
  line-height: 1.75;
  color: var(--color-text);
}

.completed-body :is(h1, h2, h3) {
  margin: 1em 0 0.5em;
  font-weight: 600;
}

.completed-body h2 {
  font-size: 1.15em;
}

.completed-body h3 {
  font-size: 1.05em;
}

.completed-body p {
  margin: 0.5em 0;
}

.clickable-img {
  cursor: zoom-in;
  transition: opacity 0.15s ease;
}

.clickable-img:hover {
  opacity: 0.85;
}

.search-thumb-wrap {
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.thumb-zoom {
  position: absolute;
  top: 4px;
  right: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.search-thumb-wrap:hover .thumb-zoom {
  opacity: 1;
}

.thumb-zoom:hover {
  background: rgba(0, 0, 0, 0.65);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(4px);
  cursor: zoom-out;
  animation: lightbox-in 0.15s ease;
}

@keyframes lightbox-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  cursor: pointer;
  transition: background 0.15s ease;
}

.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.lightbox-img {
  max-width: 90vw;
  max-height: 85vh;
  object-fit: contain;
  border-radius: var(--radius-md);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  cursor: default;
}
</style>
