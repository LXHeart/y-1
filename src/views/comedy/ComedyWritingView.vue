<template>
  <section class="comedy-view gl-field">
    <nav class="page-back" aria-label="创作流程导航">
      <button class="btn-back" type="button" @click="goToCreationCenter">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        返回创作中心
      </button>
    </nav>
    <div class="style-selector">
      <p class="style-selector-label">表达风格</p>
      <div class="style-grid">
        <button
          v-for="tpl in styleTemplates"
          :key="tpl.id"
          class="style-card"
          :class="{ 'style-card-active': styleId === tpl.id }"
          type="button"
          :aria-pressed="styleId === tpl.id"
          @click="styleId = tpl.id"
        >
          <span class="style-card-title">{{ tpl.label }}</span>
          <span class="style-card-desc">{{ tpl.description }}</span>
        </button>
      </div>
    </div>

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
          class="gen-btn gl-btn-primary"
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
      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="script"
        @updated="safetyReport = $event"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getStyleTemplate, STYLE_TEMPLATES, type StyleTemplateId } from '../../config/style-templates'
import type { CreationHandoff } from '../../types/ai-creation'
import SafetyFindingsPanel from '../../components/SafetyFindingsPanel.vue'
import { parseSafetyFrame } from '../../composables/useContentSafety'
import type { SafetyReport } from '../../composables/useContentSafety'
import { fetchApi } from '../../composables/grassland-http'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const emit = defineEmits<{ 'open-view': [view: 'ai-center'] }>()


const topic = ref('')
const duration = ref(60)
const styleId = ref<StyleTemplateId>('light-comedy')
const styleTemplates = STYLE_TEMPLATES
const script = ref('')
const safetyReport = ref<SafetyReport | null>(null)
const generating = ref(false)
const error = ref('')
const copied = ref(false)
const taskMode = ref(false)
const contextSnapshotId = ref<string | null>(null)
const targetPlatform = ref<string | null>(null)
const hydratedRevision = ref<number | null>(null)

const durationOptions = [
  { value: 30, label: '30 秒' },
  { value: 60, label: '1 分钟' },
  { value: 90, label: '90 秒' },
  { value: 120, label: '2 分钟' },
]

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'comedy' || handoff.workflowId !== 'comedy-script') return
  if (hydratedRevision.value === handoff.revision) return
  hydratedRevision.value = handoff.revision
  taskMode.value = handoff.source.type === 'task'
  contextSnapshotId.value = handoff.contextSnapshotId || null
  targetPlatform.value = handoff.platformId
  topic.value = handoff.prefill?.topic || ''
  script.value = ''
  safetyReport.value = null
  error.value = ''
}, { immediate: true })

const canGenerate = computed(() => topic.value.trim().length > 0 && !generating.value)

function goToCreationCenter(): void {
  // 共享视图双挂载（任务书 #76）：返回创作中心交给各壳路由，不硬编码路由名
  emit('open-view', 'ai-center')
}

function copyScript(): void {
  navigator.clipboard.writeText(script.value).then(() => {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }).catch(() => {})
}

function readSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onSafety: (report: SafetyReport) => void,
): AsyncIterable<string> {
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
                const report = parseSafetyFrame(parsed)
                if (report) {
                  onSafety(report)
                  continue
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

/**
 * 风格仅影响前端提示词拼装：把选中风格的抽象表达特征描述并入 topic 文本，
 * 请求字段名（topic/duration）与后端契约保持不变。
 */
function buildStyledTopic(rawTopic: string): string {
  const template = getStyleTemplate(styleId.value)
  if (!template) return rawTopic
  return `请以「${template.label}」的表达风格创作：${template.description}\n\n题材：${rawTopic}`
}

async function handleGenerate(): Promise<void> {
  if (!canGenerate.value) return

  const trimmed = topic.value.trim()
  error.value = ''
  script.value = ''
  safetyReport.value = null
  generating.value = true

  try {
    const response = await fetchApi('/api/comedy-generation/generate-script', {
      method: 'POST',
      body: JSON.stringify({
        topic: buildStyledTopic(trimmed),
        duration: duration.value,
        ...(taskMode.value ? {
          targetPlatform: targetPlatform.value,
          taskMode: true,
          contextSnapshotId: contextSnapshotId.value,
        } : {}),
      }),
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

    const stream = readSSEStream(reader, (report) => {
      safetyReport.value = report
    })
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

.page-back {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.btn-back:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.style-selector {
  display: grid;
  gap: var(--space-sm);
}

.style-selector-label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0;
}

.style-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: var(--space-sm);
}

.style-card {
  display: grid;
  gap: 4px;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--gradient-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.style-card:hover {
  border-color: var(--color-border-hover);
}

.style-card-active {
  border-color: var(--color-border-accent);
  background: var(--color-surface-highlight);
}

.style-card-title {
  font-size: 0.86rem;
  font-weight: 600;
  color: var(--color-text);
}

.style-card-active .style-card-title {
  color: var(--color-accent);
}

.style-card-desc {
  font-size: 0.76rem;
  line-height: 1.5;
  color: var(--color-text-muted);
}

.comedy-card {
  display: grid;
  gap: var(--space-sm);
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--space-md);
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
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.dur-btn:hover:not(:disabled) {
  color: var(--color-text-secondary);
  border-color: var(--color-border-hover);
}

.dur-btn-active {
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  border-color: var(--color-border-accent);
  color: var(--color-accent-2);
  font-weight: 600;
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
  border-top-color: var(--color-on-accent);
  border-radius: var(--radius-pill);
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-msg {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent);
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
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent-2);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
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
