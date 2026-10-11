<template>
  <section class="xhs-body-editor" :class="{ 'xhs-body-streaming': loading }">
    <header class="xhs-block-head">
      <div>
        <h2>正文编辑</h2>
        <p class="gl-hint">{{ effectiveSummary }}</p>
      </div>
      <p class="xhs-body-stats">
        <span class="gl-num">{{ charCount }}</span> 字 ·
        <span class="gl-num">{{ paragraphCount }}</span> 段
      </p>
    </header>

    <details v-if="outline" class="xhs-body-outline" :open="loading">
      <summary>大纲{{ loading ? '（生成中…）' : '' }}</summary>
      <p class="xhs-body-outline-text">{{ outline }}</p>
    </details>

    <div class="gl-form-field">
      <label for="xhs-body-input">正文（{{ minChars }}-{{ maxChars }} 字建议内直接编辑）</label>
      <textarea
        id="xhs-body-input"
        v-model="contentModel"
        rows="16"
        :readonly="disabled"
        placeholder="正文会在这里实时生成，完成后可继续人工润色…"
      ></textarea>
    </div>

    <p v-if="loading" class="xhs-body-loading-note" role="status">
      <span class="xhs-body-dot" aria-hidden="true"></span>正文生成中，可稍候或点「取消」中止…
    </p>

    <div class="xhs-body-actions">
      <button v-if="loading" type="button" @click="onCancel">取消</button>
      <!-- F-05：正文为空但大纲残留（流失败/取消）时提供「生成正文」入口，不留死路。 -->
      <button
        v-if="!loading && (effectiveContent.trim() || outline.trim())"
        type="button"
        :disabled="disabled"
        @click="onRegenerate"
      >{{ effectiveContent.trim() ? '⟳ 重新生成' : '✦ 生成正文' }}</button>
      <button
        v-if="proofReady"
        type="button"
        class="gl-btn-primary"
        @click="onEnterProof"
      >校对就绪 · 去校对</button>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 正文编辑+实时统计（方案 §4.2 工程师 A，featureId=gen-body reuse）：
 * streamContent SSE 真实流式正文 + textarea 双向绑定（可边流边看、完成后润色）。
 *
 * - 统计为纯前端真实计算：字数口径同 useArticleFormatRule（剥图片 markdown 后去空白）；
 *   段落=非空行数（小红书笔记单行成段的形态）。
 * - 大纲折叠区展示 engine.outline 流式文本（真实流非装饰）；流式时正文区降透明。
 * - 【F3】正文流完成（引擎已置 stage='check'）本组件仍留在创作步，显示
 *   「校对就绪 · 去校对」入口（emit enterProof → 视图接 steps.go('proof')，
 *   go 内部先 engine.enterCheck() 幂等复查）。
 * - 「重新生成」= goToOutline() 清空后重流大纲→正文（方案 §2.3）。
 */
import { computed } from 'vue'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'

/** props 仅作最小测试注入口（F7）：缺省全部 inject 自取真实引擎值。 */
const props = withDefaults(defineProps<{
  content?: string
  loading?: boolean
  formatRuleSummary?: string
  disabled?: boolean
}>(), {
  content: undefined,
  // boolean prop 未传缺省 false（Vue 3 cast），显式 undefined 才能让 ?? 回落引擎值。
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

const ctx = useXhsStudioContext()

const effectiveContent = computed(() => props.content ?? ctx.engine.content.value)
const loading = computed(() => props.loading ?? ctx.engine.contentLoading.value)
const disabled = computed(() => props.disabled ?? ctx.autosave.readonly.value)
const outline = computed(() => ctx.engine.outline.value)

const effectiveSummary = computed(() =>
  props.formatRuleSummary ?? ctx.format.formatRuleSummary.value)
const minChars = computed(() => ctx.format.formatRule.value?.minChars ?? 50)
const maxChars = computed(() => ctx.format.formatRule.value?.maxChars ?? 1000)

/** 字数口径同 useArticleFormatRule.ts:34-36：剥图片 markdown 后 trim 计长（图片行不是文本段）。 */
const strippedContent = computed(() =>
  effectiveContent.value.replace(/!\[[^\]]*\]\([^)]*\)/g, '').trim())
const charCount = computed(() => strippedContent.value.length)

/** 段落数：剥图后文本的非空行数（笔记单行成段；空行与图片行不计）。 */
const paragraphCount = computed(() =>
  strippedContent.value.split('\n').map(line => line.trim()).filter(Boolean).length)

const contentModel = computed({
  get: () => effectiveContent.value,
  set: (value: string) => {
    ctx.engine.content.value = value
    emit('update:content', value)
  },
})

/** 【F3】引擎 stage 已到 check 且正文非空 → 编辑工位上显示去校对入口（不自动切步）。 */
const proofReady = computed(() =>
  ctx.engine.stage.value === 'check' && effectiveContent.value.trim().length > 0 && !loading.value)

function onCancel(): void {
  emit('cancel')
  ctx.engine.cancel()
}

async function onRegenerate(): Promise<void> {
  if (disabled.value) return
  emit('regenerate')
  // goToOutline 清空正文/报告并回大纲阶段（引擎函数），随后重流大纲→正文。
  ctx.engine.goToOutline()
  await ctx.engine.streamOutline()
  if (!ctx.engine.error.value) await ctx.engine.streamContent()
}

function onEnterProof(): void {
  emit('enterProof')
}
</script>

<style scoped>
.xhs-body-editor {
  display: grid;
  gap: var(--space-sm);
}

.xhs-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.xhs-block-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-block-head .gl-hint {
  margin: var(--space-xxs) 0 0;
}

.xhs-body-stats {
  flex: 0 0 auto;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

/* 流式时编辑区整体降透明（可读但明示「非最终态」）。 */
.xhs-body-streaming .gl-form-field {
  opacity: 0.72;
}

.xhs-body-outline {
  padding: var(--space-xs) var(--space-sm);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--type-caption);
  color: var(--color-text-secondary);
}

.xhs-body-outline summary {
  cursor: pointer;
  color: var(--color-text-muted);
}

.xhs-body-outline-text {
  margin: var(--space-xs) 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.xhs-body-loading-note {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.xhs-body-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  animation: xhs-body-pulse 1.2s ease-in-out infinite;
}

@keyframes xhs-body-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.xhs-body-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-xs);
}
</style>
