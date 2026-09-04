<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CreationHandoff } from '../../types/ai-creation'
import { getPlatformFormatRule } from '../../config/platform-format-rules'
import { MOMENTS_STYLES, useMomentsCreation } from '../../composables/useMomentsCreation'
import SafetyFindingsPanel from '../../components/SafetyFindingsPanel.vue'

/**
 * 朋友圈「图片+文字」创作视图（PRD §4.4）。
 * 主题/风格/感受/素材图 → 一次多模态 SSE 生成【精简文案 + 九宫格顺序建议 + 每图配文】。
 */

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const emit = defineEmits<{ 'open-view': [view: 'ai-center'] }>()

const {
  topic, style, feelings, images, result, safetyReport, generating, progressMessage, error, canGenerate,
  bindCreationContext, addImages, removeImage, generate, cancel, reset,
} = useMomentsCreation()

const formatRule = getPlatformFormatRule('moments')
const ruleSummary = computed(() => formatRule
  ? `${formatRule.platformLabel}：正文 ${formatRule.minChars}-${formatRule.maxChars} 字；${formatRule.structureHints.join('；')}。`
  : '')

const hydratedRevision = ref<number | null>(null)
const copied = ref(false)

function goToCreationCenter(): void {
  // 共享视图双挂载（任务书 #76）：返回创作中心交给各壳路由，不硬编码路由名
  emit('open-view', 'ai-center')
}

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
  <!-- 任务书 #47 S8：删页面级标题栏（它让这里看起来像独立工具）。原 aria-labelledby 指向被删的
       h2，改用 aria-label 直接给出可访问名，避免该 section 失去无障碍名称。 -->
  <section class="moments-view gl-field" aria-label="朋友圈创作">
    <nav class="page-back" aria-label="创作流程导航">
      <button class="btn-back" type="button" @click="goToCreationCenter">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        返回创作中心
      </button>
    </nav>

    <div class="gl-zone moments-form">
      <p v-if="ruleSummary" data-test="moments-rule" class="rule-hint">{{ ruleSummary }}</p>
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
          class="primary gl-btn-primary"
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

    <div v-if="result" class="gl-zone moments-result">
      <div class="form-field">
        <label for="moments-copy">朋友圈文案（可编辑）</label>
        <textarea id="moments-copy" v-model="result.copy" data-test="moments-copy" rows="4" />
        <button type="button" class="secondary" @click="copyResult">
          {{ copied ? '已复制' : '复制' }}
        </button>
      </div>

      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="result.copy"
        @updated="safetyReport = $event"
      />

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
.moments-view { display: grid; gap: var(--space-lg); max-width: 760px; margin: 0 auto; }
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
.rule-hint {
  margin: 0; padding: var(--space-xs) var(--space-sm); border-radius: var(--radius-sm);
  background: var(--surface-furrow); color: var(--color-text-muted);
  font-size: var(--text-sm); line-height: 1.5;
}
.moments-form { display: grid; gap: var(--space-md); }
.form-field { display: grid; gap: 6px; }
.form-field label, .form-field legend { font-size: var(--text-sm); color: var(--color-text); font-weight: 600; }
.form-field input[type='text'], .form-field textarea {
  padding: 8px var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  font: inherit; background: var(--color-surface); color: var(--color-text);
}
.style-options { display: flex; flex-wrap: wrap; gap: var(--space-xs); }
.style-option {
  display: inline-flex; align-items: center; gap: 6px; padding: 0 var(--space-sm); min-height: 32px;
  border: 1px solid var(--color-border); border-radius: var(--radius-pill); cursor: pointer;
  font-size: var(--text-sm); color: var(--color-text); background: transparent;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}
.style-option.active {
  border-color: var(--color-border-accent); color: var(--color-accent-2);
  background: color-mix(in srgb, var(--color-accent) 10%, transparent);
}
.image-list { list-style: none; margin: 4px 0 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: var(--space-sm); }
.image-item {
  display: grid; gap: 6px; padding: var(--space-xs); border-radius: var(--radius-md);
  background: var(--surface-furrow);
}
.image-item img { width: 100%; height: 96px; object-fit: cover; border-radius: var(--radius-sm); }
.image-meta { display: grid; gap: 2px; font-size: var(--text-xs); color: var(--color-text-muted); }
.image-caption { color: var(--color-text); }
.image-remove {
  justify-self: start; min-height: auto; padding: 3px 8px; font-size: var(--text-xs);
  border: none; background: none; color: var(--color-danger);
}
.actions { display: flex; gap: var(--space-sm); }
.primary, .secondary { min-height: 38px; padding: 0 var(--space-md); border-radius: var(--radius-sm); }
.progress { margin: 0; color: var(--color-text-muted); font-size: var(--text-sm); }
.error { margin: 0; color: var(--color-danger); font-size: var(--text-sm); }
.moments-result { display: grid; gap: var(--space-md); }
.moments-result h3 { margin: 0 0 6px; font-size: var(--text-base); font-weight: 700; color: var(--color-text); }
.order-block ol, .captions-block ul { margin: 0; padding-left: 20px; display: grid; gap: 4px; color: var(--color-text); font-size: var(--text-sm); }
.order-reason { color: var(--color-text-muted); }
</style>
