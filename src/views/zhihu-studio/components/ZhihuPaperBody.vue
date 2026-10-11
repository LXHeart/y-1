<template>
  <section class="zhihu-paper-body" :class="{ 'zhihu-paper-streaming': loading }" aria-label="稿纸编辑">
    <header class="zhihu-block-head">
      <div>
        <h2>{{ answerMode ? '回答稿纸' : '文章稿纸' }}</h2>
        <p class="gl-hint">{{ effectiveSummary }}</p>
      </div>
      <p class="zhihu-paper-stats" data-test="zhihu-paper-stats">
        <span class="gl-num">{{ charCount }}</span> 字 ·
        <span class="gl-num">{{ paragraphCount }}</span> 段
        <span v-if="readMinutes" class="zhihu-paper-read">约 {{ readMinutes }} 分钟读完</span>
      </p>
    </header>

    <!-- 回答：问题卡（原型的真身——题干来自左栏输入/任务下发，questionId 仅本地溯源） -->
    <div v-if="answerMode && question" class="zhihu-paper-qcard" data-test="zhihu-paper-qcard">
      <h3 class="zhihu-paper-qtitle">{{ question }}</h3>
      <p v-if="questionRef" class="zhihu-paper-qref">questionId {{ questionRef }} · 本地溯源</p>
    </div>

    <details v-if="outline" class="zhihu-paper-outline" :open="loading">
      <summary>大纲{{ loading ? '（生成中…）' : '' }}</summary>
      <p class="zhihu-paper-outline-text">{{ outline }}</p>
    </details>

    <!-- 宣纸稿纸：固定浅色媒体画布（--zhihu-paper-*，亮暗同值不随主题），正文本体可编辑 -->
    <div class="zhihu-paper-sheet" data-test="zhihu-paper-sheet">
      <label class="zhihu-paper-label" for="zhihu-paper-input">
        {{ answerMode ? '回答正文（可编辑，流式生成中也可润色）' : `文章正文（${minChars}-${maxChars} 字建议内直接编辑）` }}
      </label>
      <textarea
        id="zhihu-paper-input"
        v-model="contentModel"
        class="zhihu-paper-input"
        rows="18"
        :readonly="disabled"
        :placeholder="answerMode ? '回答会在这里实时生成：先结论后论据，完成后可继续人工润色…' : '正文会在这里实时生成，完成后可继续人工润色…'"
      ></textarea>
    </div>

    <p v-if="loading" class="zhihu-paper-loading-note" role="status">
      <span class="zhihu-paper-dot" aria-hidden="true"></span>正文生成中，可稍候或点「取消」中止…
    </p>

    <div class="zhihu-paper-actions">
      <button v-if="loading" type="button" @click="onCancel">取消</button>
      <!-- 正文为空但大纲残留（流失败/取消）时提供「生成正文」入口，不留死路。 -->
      <button
        v-if="!loading && (effectiveContent.trim() || outline.trim())"
        type="button"
        :disabled="disabled"
        data-test="zhihu-paper-regenerate"
        @click="onRegenerate"
      >{{ effectiveContent.trim() ? '⟳ 换角度重写' : '✦ 生成正文' }}</button>
      <button
        v-if="proofReady"
        type="button"
        class="gl-btn-primary"
        data-test="zhihu-paper-enter-proof"
        @click="onEnterProof"
      >校对就绪 · 去校对</button>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 宣纸稿纸正文编辑（原型「稿纸」的真身）：固定浅色纸面 + content 双向绑定 +
 * 字数/段落本地统计 + 大纲流折叠（真实 SSE 文本非装饰）+ 问题卡（回答模式）。
 *
 * - 纸面走 --zhihu-paper-* 固定浅色 token（稿纸=媒体画布不随主题，与 --xhs-phone-*
 *   同策略）；朱批（AI 行内批注）无生成流帧支撑，不渲染（defer）。
 * - 阅读时长=本地真实计算（字数/300 每分钟，向上取整），非平台预估数据。
 * - 正文流完成（引擎 stage='check'）仍留在创作步，「校对就绪 · 去校对」显式入口
 *   （F3 同款）；「换角度重写」= goToOutline() 清空后重流（原型同名按钮的真身）。
 */
import { computed } from 'vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'

/** props 仅作最小测试注入口（F7 同款）：缺省全部 inject 自取真实引擎值。 */
const props = withDefaults(defineProps<{
  content?: string
  loading?: boolean
  formatRuleSummary?: string
  disabled?: boolean
}>(), {
  content: undefined,
  loading: undefined,
  formatRuleSummary: undefined,
  disabled: undefined,
})

const emit = defineEmits<{
  'update:content': [value: string]
  cancel: []
  regenerate: []
  enterProof: []
}>()

const ctx = useZhihuStudioContext()

const effectiveContent = computed(() => props.content ?? ctx.engine.content.value)
const loading = computed(() => props.loading ?? ctx.engine.contentLoading.value)
const disabled = computed(() => props.disabled ?? ctx.autosave.readonly.value)
const outline = computed(() => ctx.engine.outline.value)
const answerMode = computed(() => ctx.engine.contentMode.value === 'answer')
const question = computed(() => ctx.engine.question.value.trim())
const questionRef = computed(() => ctx.engine.questionRef.value)

const effectiveSummary = computed(() =>
  props.formatRuleSummary ?? ctx.format.formatRuleSummary.value)
const minChars = computed(() => ctx.format.formatRule.value?.minChars ?? 200)
const maxChars = computed(() => ctx.format.formatRule.value?.maxChars ?? 3000)

/** 校对就绪：正文非空且流已结束（引擎已置 stage='check'）。 */
const proofReady = computed(() =>
  !loading.value && effectiveContent.value.trim() !== '' && ctx.engine.stage.value === 'check')

/** 字数口径同 useArticleFormatRule：剥图片 markdown 后 trim 计长。 */
const strippedContent = computed(() =>
  effectiveContent.value.replace(/!\[[^\]]*\]\([^)]*\)/g, '').trim())
const charCount = computed(() => strippedContent.value.length)

/** 段落数：非空段（连续非空行归段）——知乎长文按空行分段。 */
const paragraphCount = computed(() =>
  strippedContent.value.split(/\n\s*\n/).map(p => p.trim()).filter(Boolean).length)

/** 阅读时长：中文阅读速度按 300 字/分钟估算（本地真实计算）。 */
const readMinutes = computed(() => {
  if (charCount.value === 0) return 0
  return Math.max(1, Math.ceil(charCount.value / 300))
})

const contentModel = computed<string>({
  get: () => effectiveContent.value,
  set: (value) => { ctx.engine.content.value = value },
})

function onCancel(): void {
  emit('cancel')
  ctx.engine.cancel()
}

function onRegenerate(): void {
  if (disabled.value) return
  emit('regenerate')
  ctx.engine.goToOutline()
  void ctx.engine.streamOutline().then(() => {
    if (!ctx.engine.error.value) void ctx.engine.streamContent()
  })
}

function onEnterProof(): void {
  emit('enterProof')
  ctx.steps.go('proof')
}
</script>

<style scoped>
.zhihu-paper-body {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.zhihu-block-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-block-head .gl-hint {
  margin: var(--space-xxs) 0 0;
}

.zhihu-paper-stats {
  margin: 0;
  flex: none;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.zhihu-paper-read {
  margin-left: var(--space-xs);
  color: var(--color-text-muted);
}

/* 问题卡：知乎页面语汇的题干区（应用侧表面，随主题） */
.zhihu-paper-qcard {
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-sm) var(--space-md);
  border: var(--border-width) solid var(--color-border);
  border-left: 3px solid var(--color-accent);
  border-radius: var(--radius-md);
  background: var(--surface-card);
}

.zhihu-paper-qtitle {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-section-title);
  font-weight: var(--weight-heading);
  line-height: 1.5;
  color: var(--color-text);
}

.zhihu-paper-qref {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.zhihu-paper-outline {
  border: var(--border-width) dashed var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-xs) var(--space-sm);
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
}

.zhihu-paper-outline summary {
  cursor: pointer;
  color: var(--color-text-secondary);
  font-weight: var(--weight-heading);
}

.zhihu-paper-outline-text {
  margin: var(--space-xs) 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: var(--leading-body-sm);
}

/* 宣纸稿纸：固定浅色（稿纸=媒体画布不随主题），token 见 --zhihu-paper-*。 */
.zhihu-paper-sheet {
  display: grid;
  gap: var(--space-xs);
  padding: var(--space-lg) var(--space-lg) var(--space-md);
  border-radius: var(--radius-md);
  background: var(--zhihu-paper-bg);
  border: 1px solid var(--zhihu-paper-border);
  box-shadow: var(--shadow-card);
}

.zhihu-paper-label {
  color: var(--zhihu-paper-muted);
  font-size: var(--type-caption);
}

.zhihu-paper-input {
  width: 100%;
  border: 0;
  outline: none;
  resize: vertical;
  min-height: 320px;
  background: transparent;
  color: var(--zhihu-paper-text);
  font-family: var(--font-body);
  font-size: var(--type-body);
  line-height: 1.9;
  overflow-wrap: anywhere;
}

.zhihu-paper-input::placeholder {
  color: var(--zhihu-paper-muted);
}

.zhihu-paper-input:focus-visible {
  outline: 2px solid var(--zhihu-paper-accent);
  outline-offset: 2px;
}

.zhihu-paper-loading-note {
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.zhihu-paper-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  animation: zhihu-paper-pulse 1.1s ease-in-out infinite;
}

.zhihu-paper-streaming .zhihu-paper-input {
  opacity: 0.72;
}

@keyframes zhihu-paper-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.zhihu-paper-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.zhihu-paper-actions button {
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.zhihu-paper-actions button:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.zhihu-paper-actions button:disabled {
  background: var(--surface-muted);
  cursor: not-allowed;
}
</style>
