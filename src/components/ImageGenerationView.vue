<template>
  <section class="image-gen-view">
    <header class="section-header">
      <h2 class="section-title">图片生成</h2>
      <p class="section-desc">输入描述提示词，AI 帮你生成图片</p>
    </header>

    <div class="gen-card">
      <div class="prompt-area">
        <textarea
          v-model="prompt"
          class="prompt-input"
          placeholder="描述你想生成的图片，例如：一只橘色的猫坐在窗台上，窗外是夕阳，油画风格"
          rows="4"
          :disabled="generating"
          @keydown.ctrl.enter="handleGenerate"
          @keydown.meta.enter="handleGenerate"
        />
        <div class="prompt-footer">
          <span class="char-count">{{ prompt.length }} / 4000</span>
          <div class="size-selector">
            <button
              v-for="option in sizeOptions"
              :key="option.value"
              class="size-btn"
              :class="{ 'size-btn-active': selectedSize === option.value }"
              type="button"
              :disabled="generating"
              @click="selectedSize = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>
      </div>

      <button
        class="gen-btn"
        :class="{ 'gen-btn-loading': generating }"
        type="button"
        :disabled="!canGenerate"
        @click="handleGenerate"
      >
        <template v-if="generating">
          <span class="spinner" />
          生成中...
        </template>
        <template v-else>生成图片</template>
      </button>
    </div>

    <p v-if="error" class="error-msg">{{ error }}</p>

    <div v-if="results.length > 0" class="results-grid">
      <div
        v-for="(item, index) in results"
        :key="index"
        class="result-card"
      >
        <div class="result-img-wrap" @click="openLightbox(item.imageUrl)">
          <img :src="item.imageUrl" :alt="item.revisedPrompt || prompt" class="result-img" loading="lazy" />
        </div>
        <p v-if="item.revisedPrompt" class="result-prompt">{{ item.revisedPrompt }}</p>
        <div class="result-actions">
          <a :href="item.imageUrl" target="_blank" rel="noopener" class="result-action-btn" download>下载</a>
          <button class="result-action-btn" type="button" @click="copyPrompt(item.revisedPrompt || prompt)">复制提示词</button>
        </div>
      </div>
    </div>

    <div v-if="lightboxUrl" class="lightbox-overlay" @click.self="lightboxUrl = ''">
      <button class="lightbox-close" type="button" @click="lightboxUrl = ''" aria-label="关闭">&times;</button>
      <img :src="lightboxUrl" class="lightbox-img" alt="放大查看" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

interface GenerateResult {
  imageUrl: string
  revisedPrompt?: string
}

const API_BASE = ''

const prompt = ref('')
const selectedSize = ref<'1024x1024' | '1024x1792' | '1792x1024'>('1024x1024')
const generating = ref(false)
const error = ref('')
const results = ref<GenerateResult[]>([])
const lightboxUrl = ref('')

const sizeOptions = [
  { value: '1024x1024' as const, label: '1:1' },
  { value: '1024x1792' as const, label: '2:3' },
  { value: '1792x1024' as const, label: '3:2' },
]

const canGenerate = computed(() => prompt.value.trim().length > 0 && !generating.value)

function copyPrompt(text: string): void {
  navigator.clipboard.writeText(text).catch(() => {})
}

function openLightbox(url: string): void {
  lightboxUrl.value = url
}

async function handleGenerate(): Promise<void> {
  if (!canGenerate.value) return

  const trimmed = prompt.value.trim()
  error.value = ''
  generating.value = true

  try {
    const res = await fetch(`${API_BASE}/api/article-generation/generate-image`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ prompt: trimmed, size: selectedSize.value }),
    })

    const data = await res.json()

    if (!data.success) {
      error.value = data.error || '图片生成失败'
      return
    }

    results.value = [
      {
        imageUrl: data.data.imageUrl,
        revisedPrompt: data.data.revisedPrompt,
      },
      ...results.value,
    ]
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '网络错误，请重试'
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.image-gen-view {
  display: grid;
  gap: var(--space-lg);
  max-width: 820px;
  margin: 0 auto;
}

.section-header {
  display: grid;
  gap: var(--space-xs);
}

.section-title {
  font-size: 1.3rem;
  font-weight: 700;
  color: var(--color-text);
  margin: 0;
}

.section-desc {
  font-size: 0.88rem;
  color: var(--color-text-muted);
  margin: 0;
}

.gen-card {
  display: grid;
  gap: var(--space-md);
  padding: var(--space-lg);
  background: var(--gradient-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.prompt-input {
  width: 100%;
  min-height: 100px;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 0.92rem;
  line-height: 1.6;
  resize: vertical;
  font-family: inherit;
  transition: border-color var(--duration-fast) var(--ease-out);
  box-sizing: border-box;
}

.prompt-input:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: var(--focus-ring);
}

.prompt-input::placeholder {
  color: var(--color-text-muted);
}

.prompt-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.prompt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
}

.char-count {
  font-size: 0.78rem;
  color: var(--color-text-muted);
}

.size-selector {
  display: flex;
  gap: 6px;
}

.size-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
}

.size-btn:hover:not(:disabled) {
  color: var(--color-text-secondary);
  border-color: var(--color-border-hover);
}

.size-btn-active {
  background: var(--color-surface-highlight);
  border-color: var(--color-border-accent);
  color: var(--color-accent);
}

.size-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.gen-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 44px;
  padding: 0 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--gradient-accent);
  color: #fff;
  font-size: 0.92rem;
  font-weight: 600;
  cursor: pointer;
  transition: opacity var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}

.gen-btn:hover:not(:disabled) {
  opacity: 0.92;
  transform: translateY(-1px);
}

.gen-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.gen-btn-loading {
  pointer-events: none;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-msg {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: rgba(239, 107, 107, 0.1);
  border: 1px solid rgba(239, 107, 107, 0.2);
  color: var(--color-danger);
  font-size: 0.86rem;
  margin: 0;
}

.results-grid {
  display: grid;
  gap: var(--space-lg);
}

.result-card {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-md);
  background: var(--gradient-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.result-img-wrap {
  cursor: pointer;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-muted);
}

.result-img-wrap:hover {
  box-shadow: 0 0 0 2px var(--color-border-accent);
}

.result-img {
  display: block;
  width: 100%;
  height: auto;
  max-height: 600px;
  object-fit: contain;
}

.result-prompt {
  font-size: 0.82rem;
  color: var(--color-text-muted);
  margin: 0;
  line-height: 1.5;
}

.result-actions {
  display: flex;
  gap: var(--space-sm);
}

.result-action-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.8rem;
  cursor: pointer;
  text-decoration: none;
  transition: all var(--duration-fast) var(--ease-out);
}

.result-action-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-overlay);
  backdrop-filter: blur(12px);
  cursor: pointer;
}

.lightbox-close {
  position: absolute;
  top: 20px;
  right: 24px;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 1.4rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--duration-fast) var(--ease-out);
}

.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.2);
}

.lightbox-img {
  max-width: 92vw;
  max-height: 88vh;
  object-fit: contain;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-elevated);
}
</style>
