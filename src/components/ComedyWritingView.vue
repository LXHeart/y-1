<template>
  <section class="comedy-view">
    <header class="section-header">
      <h2 class="section-title">脱口秀创作</h2>
      <p class="section-desc">输入一个题材，选择时长，AI 帮你生成脱口秀文稿</p>
    </header>

    <div class="comedy-card">
      <textarea
        v-model="topic"
        class="topic-input"
        placeholder="输入题材，例如：社恐、上班摸鱼、相亲、拖延症、减肥..."
        rows="2"
        :disabled="generating"
        @keydown.ctrl.enter="handleGenerate"
        @keydown.meta.enter="handleGenerate"
      />
      <div class="input-footer">
        <div class="input-left">
          <span class="char-count">{{ topic.length }} / 200</span>
          <div class="duration-selector">
            <button
              v-for="opt in durationOptions"
              :key="opt.value"
              class="dur-btn"
              :class="{ 'dur-btn-active': duration === opt.value }"
              type="button"
              :disabled="generating"
              @click="duration = opt.value"
            >
              {{ opt.label }}
            </button>
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
            创作中...
          </template>
          <template v-else>开始创作</template>
        </button>
      </div>
    </div>

    <p v-if="error" class="error-msg">{{ error }}</p>

    <div v-if="script" class="script-card">
      <div class="script-header">
        <h3 class="script-label">脱口秀文稿</h3>
        <button class="copy-btn" type="button" @click="copyScript">
          {{ copied ? '已复制' : '复制全文' }}
        </button>
      </div>
      <pre class="script-content">{{ script }}</pre>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, inject, onActivated, ref, type Ref, watch } from 'vue'

const API_BASE = ''

const topic = ref('')
const duration = ref(60)
const script = ref('')
const generating = ref(false)
const error = ref('')
const copied = ref(false)

const durationOptions = [
  { value: 30, label: '30 秒' },
  { value: 60, label: '1 分钟' },
  { value: 90, label: '90 秒' },
  { value: 120, label: '2 分钟' },
]

const comedyInitialTopic = inject<Ref<string>>('comedyInitialTopic')

function consumeInitialTopic(): void {
  if (comedyInitialTopic?.value) {
    topic.value = comedyInitialTopic.value
    comedyInitialTopic.value = ''
  }
}

watch(comedyInitialTopic!, (val) => {
  if (val) {
    topic.value = val
    comedyInitialTopic!.value = ''
  }
})

onActivated(consumeInitialTopic)

const canGenerate = computed(() => topic.value.trim().length > 0 && !generating.value)

function copyScript(): void {
  navigator.clipboard.writeText(script.value).then(() => {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }).catch(() => {})
}

function readSSEStream(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncIterable<string> {
  const decoder = new TextDecoder()
  let buffer = ''
  const pendingLines: string[] = []

  return {
    [Symbol.asyncIterator]() {
      return {
        async next() {
          while (true) {
            while (pendingLines.length > 0) {
              const line = pendingLines.shift()!
              const trimmed = line.trim()
              if (!trimmed || !trimmed.startsWith('data: ')) continue

              const payload = trimmed.slice(6).trim()
              if (payload === '[DONE]') return { done: true as const, value: undefined }

              try {
                const parsed = JSON.parse(payload) as Record<string, unknown>
                if (typeof parsed.error === 'string') {
                  throw new Error(parsed.error)
                }
                if (typeof parsed.content === 'string') {
                  return { done: false, value: parsed.content }
                }
              } catch (e: unknown) {
                if (e instanceof Error && e.message !== 'Unexpected end of JSON input') {
                  throw e
                }
              }
            }

            const { done, value } = await reader.read()
            if (done) return { done: true as const, value: undefined }

            buffer += decoder.decode(value, { stream: true })
            const lines = buffer.split('\n')
            buffer = lines.pop() ?? ''
            pendingLines.push(...lines)
          }
        },
      }
    },
  }
}

async function handleGenerate(): Promise<void> {
  if (!canGenerate.value) return

  const trimmed = topic.value.trim()
  error.value = ''
  script.value = ''
  generating.value = true

  try {
    const response = await fetch(`${API_BASE}/api/comedy-generation/generate-script`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ topic: trimmed, duration: duration.value }),
    })

    if (!response.ok) {
      const data = await response.json().catch(() => null)
      error.value = (data as Record<string, unknown>)?.error as string || `请求失败 (${response.status})`
      return
    }

    const reader = response.body?.getReader()
    if (!reader) {
      error.value = '响应流不可用'
      return
    }

    const stream = readSSEStream(reader)
    for await (const chunk of stream) {
      script.value += chunk
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '网络错误，请重试'
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.comedy-view {
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

.comedy-card {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-lg);
  background: var(--gradient-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.topic-input {
  width: 100%;
  min-height: 60px;
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

.topic-input:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: var(--focus-ring);
}

.topic-input::placeholder {
  color: var(--color-text-muted);
}

.topic-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.input-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
}

.input-left {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.char-count {
  font-size: 0.78rem;
  color: var(--color-text-muted);
}

.duration-selector {
  display: flex;
  gap: 4px;
}

.dur-btn {
  padding: 3px 10px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
}

.dur-btn:hover:not(:disabled) {
  color: var(--color-text-secondary);
  border-color: var(--color-border-hover);
}

.dur-btn-active {
  background: var(--color-surface-highlight);
  border-color: var(--color-border-accent);
  color: var(--color-accent);
}

.dur-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.gen-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 40px;
  padding: 0 24px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--gradient-accent);
  color: #fff;
  font-size: 0.88rem;
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
  width: 14px;
  height: 14px;
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

.script-card {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-lg);
  background: var(--gradient-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.script-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.script-label {
  font-size: 0.92rem;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}

.copy-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-accent);
  font-size: 0.8rem;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
}

.copy-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
}

.script-content {
  margin: 0;
  padding: var(--space-md);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  font-size: 0.92rem;
  line-height: 1.8;
  color: var(--color-text);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  max-height: 600px;
  overflow-y: auto;
}
</style>
