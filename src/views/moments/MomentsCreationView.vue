<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CreationHandoff } from '../../types/ai-creation'
import { getPlatformFormatRule } from '../../config/platform-format-rules'
import { MOMENTS_STYLES, useMomentsCreation } from '../../composables/useMomentsCreation'

/**
 * 朋友圈「图片+文字」创作视图（PRD §4.4）。
 * 主题/风格/感受/素材图 → 一次多模态 SSE 生成【精简文案 + 九宫格顺序建议 + 每图配文】。
 */

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const {
  topic, style, feelings, images, result, generating, progressMessage, error, canGenerate,
  bindCreationContext, addImages, removeImage, generate, cancel, reset,
} = useMomentsCreation()

const formatRule = getPlatformFormatRule('moments')
const ruleSummary = computed(() => formatRule
  ? `${formatRule.platformLabel}：正文 ${formatRule.minChars}-${formatRule.maxChars} 字；${formatRule.structureHints.join('；')}。`
  : '')

const hydratedRevision = ref<number | null>(null)
const copied = ref(false)

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'moments' || hydratedRevision.value === handoff.revision) return
  hydratedRevision.value = handoff.revision
  if (handoff.prefill?.topic) topic.value = handoff.prefill.topic
  if (handoff.prefill?.instructions) feelings.value = handoff.prefill.instructions
  if (handoff.prefill?.storeName && !topic.value) topic.value = handoff.prefill.storeName
  bindCreationContext(handoff.source.type === 'task', handoff.contextSnapshotId ?? null)
}, { immediate: true })

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (files.length) await addImages(files)
  input.value = ''
}

function captionFor(index: number): string {
  return result.value?.captions.find((caption) => caption.index === index)?.text ?? ''
}

async function copyResult(): Promise<void> {
  if (!result.value) return
  const text = result.value.copy
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    copied.value = true
  }
  setTimeout(() => { copied.value = false }, 2000)
}
</script>

<template>
  <section class="moments-view" aria-labelledby="moments-title">
    <header class="moments-head">
      <h2 id="moments-title">朋友圈创作</h2>
      <p class="moments-sub">精简文案 + 九宫格顺序建议 + 每图配文，一次生成</p>
    </header>

    <p v-if="ruleSummary" data-test="moments-rule" class="rule-hint">{{ ruleSummary }}</p>

    <div class="moments-form">
      <div class="form-field">
        <label for="moments-topic">主题 *</label>
        <input
          id="moments-topic"
          v-model="topic"
          data-test="moments-topic"
          type="text"
          maxlength="500"
          placeholder="这次朋友圈想分享什么？例如：新店开业、周末活动、到店体验"
        >
      </div>

      <fieldset class="form-field">
        <legend>风格 *</legend>
        <div class="style-options">
          <label
            v-for="item in MOMENTS_STYLES"
            :key="item.id"
            data-test="moments-style"
            class="style-option"
            :class="{ active: style === item.id }"
          >
            <input
              v-model="style"
              data-test="moments-style-input"
              type="radio"
              name="moments-style"
              :value="item.id"
            >
            {{ item.label }}
          </label>
        </div>
      </fieldset>

      <div class="form-field">
        <label for="moments-feelings">补充感受（选填）</label>
        <textarea
          id="moments-feelings"
          v-model="feelings"
          data-test="moments-feelings"
          rows="3"
          maxlength="200"
          placeholder="真实的感受、想强调的细节（200 字内）"
        />
      </div>

      <div class="form-field">
        <label for="moments-images">素材图片（选填，最多 9 张）</label>
        <input
          id="moments-images"
          data-test="moments-images"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          @change="onFileChange"
        >
        <ul v-if="images.length" class="image-list" aria-label="已上传素材图">
          <li v-for="(image, order) in images" :key="image.id" class="image-item">
            <img :src="image.dataUrl" :alt="image.name">
            <div class="image-meta">
              <span>图 {{ order + 1 }}</span>
              <span v-if="captionFor(order + 1)" class="image-caption">{{ captionFor(order + 1) }}</span>
            </div>
            <button type="button" class="image-remove" @click="removeImage(image.id)">移除</button>
          </li>
        </ul>
      </div>

      <div class="actions">
        <button
          type="button"
          data-test="moments-generate"
          class="primary"
          :disabled="!canGenerate"
          @click="generate"
        >
          {{ generating ? '生成中…' : '生成朋友圈内容' }}
        </button>
        <button v-if="generating" type="button" class="secondary" @click="cancel">停止</button>
        <button v-if="result" type="button" class="secondary" @click="reset">重新开始</button>
      </div>
    </div>

    <p v-if="generating && progressMessage" class="progress">{{ progressMessage }}</p>
    <p v-if="error" data-test="moments-error" class="error" role="alert">{{ error }}</p>

    <div v-if="result" class="moments-result">
      <div class="form-field">
        <label for="moments-copy">朋友圈文案（可编辑）</label>
        <textarea id="moments-copy" v-model="result.copy" data-test="moments-copy" rows="4" />
        <button type="button" class="secondary" @click="copyResult">
          {{ copied ? '已复制' : '复制' }}
        </button>
      </div>

      <div v-if="result.imageOrder.length" data-test="moments-order" class="order-block">
        <h3>发布顺序建议</h3>
        <ol>
          <li v-for="(suggestion, position) in result.imageOrder" :key="`${suggestion.index}-${position}`">
            第 {{ position + 1 }} 位：图 {{ suggestion.index }}
            <span v-if="suggestion.reason" class="order-reason">（{{ suggestion.reason }}）</span>
          </li>
        </ol>
      </div>

      <div v-if="result.captions.length" class="captions-block">
        <h3>每图配文</h3>
        <ul>
          <li v-for="caption in result.captions" :key="caption.index">
            图 {{ caption.index }}：{{ caption.text }}
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.moments-view { display: grid; gap: 18px; max-width: 760px; margin: 0 auto; }
.moments-head h2 { margin: 0; font-size: 1.4rem; color: var(--color-text); }
.moments-sub { margin: 4px 0 0; color: var(--color-text-muted, #6b7280); font-size: 0.92rem; }
.rule-hint {
  margin: 0; padding: 10px 12px; border-radius: 8px;
  background: var(--color-bg-subtle, #f5f5f4); color: var(--color-text-muted, #6b7280);
  font-size: 0.86rem; line-height: 1.5;
}
.moments-form { display: grid; gap: 16px; }
.form-field { display: grid; gap: 6px; }
.form-field label, .form-field legend { font-size: 0.9rem; color: var(--color-text); font-weight: 600; }
.form-field input[type='text'], .form-field textarea {
  padding: 9px 11px; border: 1px solid var(--color-border, #d6d3d1); border-radius: 8px;
  font: inherit; background: var(--color-bg, #fff); color: var(--color-text);
}
.style-options { display: flex; flex-wrap: wrap; gap: 8px; }
.style-option {
  display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px;
  border: 1px solid var(--color-border, #d6d3d1); border-radius: 999px; cursor: pointer;
  font-size: 0.9rem; color: var(--color-text); background: var(--color-bg, #fff);
}
.style-option.active { border-color: var(--color-primary, #059669); color: var(--color-primary, #059669); }
.image-list { list-style: none; margin: 4px 0 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; }
.image-item {
  display: grid; gap: 6px; padding: 8px; border: 1px solid var(--color-border, #d6d3d1);
  border-radius: 10px; background: var(--color-bg, #fff);
}
.image-item img { width: 100%; height: 96px; object-fit: cover; border-radius: 6px; }
.image-meta { display: grid; gap: 2px; font-size: 0.8rem; color: var(--color-text-muted, #6b7280); }
.image-caption { color: var(--color-text); }
.image-remove { justify-self: start; padding: 3px 8px; font-size: 0.78rem; border: none; background: none; color: var(--color-danger, #dc2626); cursor: pointer; }
.actions { display: flex; gap: 10px; }
.primary, .secondary {
  padding: 9px 16px; border-radius: 8px; font: inherit; cursor: pointer;
  border: 1px solid transparent;
}
.primary { background: var(--color-primary, #059669); color: #fff; }
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.secondary { background: none; border-color: var(--color-border, #d6d3d1); color: var(--color-text); }
.progress { margin: 0; color: var(--color-text-muted, #6b7280); font-size: 0.88rem; }
.error { margin: 0; color: var(--color-danger, #dc2626); font-size: 0.9rem; }
.moments-result { display: grid; gap: 14px; padding-top: 6px; border-top: 1px solid var(--color-border, #d6d3d1); }
.moments-result h3 { margin: 0 0 6px; font-size: 1rem; color: var(--color-text); }
.order-block ol, .captions-block ul { margin: 0; padding-left: 20px; display: grid; gap: 4px; color: var(--color-text); font-size: 0.92rem; }
.order-reason { color: var(--color-text-muted, #6b7280); }
</style>
