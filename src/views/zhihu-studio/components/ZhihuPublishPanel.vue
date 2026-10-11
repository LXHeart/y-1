<template>
  <section class="zhihu-publish">
    <div class="zhihu-publish-notice" role="note" data-test="zhihu-publish-notice">
      <h3>发布方式说明</h3>
      <p>知乎未开放第三方内容发布 API，草场只负责生成、校对与导出；请在知乎 App / 网页端粘贴发布。<strong>知乎要求 AI 辅助创作须声明，发布时请勾选。</strong></p>
    </div>

    <DeliveryPanel
      :model-value="autosave.deliveryValue.value"
      platform="zhihu"
      :disabled="autosave.readonly.value"
      :media-expected="mediaExpected"
      :draft-id="autosave.draftId.value || undefined"
      :draft-version="autosave.draftVersion.value"
      :before-export="beforeExport"
      :export-title="exportTitle"
      @update:model-value="autosave.updateDelivery"
    />

    <div class="zhihu-publish-copy">
      <button
        type="button"
        class="gl-btn-secondary"
        data-test="zhihu-publish-copy"
        :disabled="copying"
        @click="onCopy"
      >{{ copying ? '复制中…' : copyLabel }}</button>
      <p class="zhihu-publish-copy-hint">复制后可粘贴到知乎的回答/文章编辑器继续编辑。</p>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 发布导出（对位 xhs-studio PublishPanel，知乎差异）：
 * - 发布约束含 AI 辅助创作声明提示（真实平台要求，旧视图 finish hints 同源）；
 * - 无图卡链 → mediaExpected 由配图结果（imageSlots 选中图）派生；
 * - 复制文本按形态分叉：回答=问题+正文；文章=标题+正文+话题（交付字段同源）；
 * - DeliveryPanel 原样复用（modelValue/platform/disabled/mediaExpected/draftId/
 *   draftVersion/before-export/export-title）；
 * - 复制走双路径剪贴板（clipboard API → execCommand 兜底），失败如实报错；
 * - 发布账号/定时发布/加入专栏 defer：无数据通路，不渲染假控件。
 */
import { computed, ref } from 'vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'
import DeliveryPanel from '../../ai-center/components/DeliveryPanel.vue'

const { engine, autosave, beforeExport, notify } = useZhihuStudioContext()

const answerMode = computed(() => engine.contentMode.value === 'answer')

const exportTitle = computed(() =>
  answerMode.value ? engine.question.value.trim().slice(0, 60) : engine.selectedTitle.value)

/** 配图结果里有选中图的槽位 → 交付检查含「选定媒体」。 */
const mediaExpected = computed(() =>
  engine.imageSlots.value.some((slot) => slot.selectedImage != null))

const copyLabel = computed(() => answerMode.value ? '复制问题+正文' : '复制标题+正文+话题')

/** 复制文本 = 形态分叉（回答=问题+正文；文章=标题+正文+话题行），交付字段同源。 */
const copyText = computed(() => {
  const delivery = autosave.deliveryValue.value
  const topics = (delivery.topics ?? [])
    .filter((topic) => topic.trim() !== '')
    .map((topic) => `#${topic}`)
    .join(' ')
  return [
    answerMode.value ? engine.question.value.trim() : (delivery.titleOrOpening ?? '').trim(),
    (delivery.bodyOrDescription ?? engine.content.value).trim(),
    answerMode.value ? '' : topics,
  ].filter(Boolean).join('\n\n')
})

const copying = ref(false)

/** 双路径剪贴板（ArticleCreationView.copyContent 同款）；兜底失败如实抛错。 */
async function copyWithFallback(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    return
  } catch {
    // 剪贴板 API 不可用/被拒 → execCommand 兜底。
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.cssText = 'position:fixed;opacity:0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!copied) throw new Error('copy failed')
}

async function onCopy(): Promise<void> {
  if (copying.value) return
  copying.value = true
  try {
    await copyWithFallback(copyText.value)
    notify('已复制内容')
  } catch {
    notify('复制失败，请手动选择文本复制')
  } finally {
    copying.value = false
  }
}
</script>

<style scoped>
.zhihu-publish {
  display: grid;
  gap: var(--space-md);
}

.zhihu-publish-notice {
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-sm) var(--space-md);
  border: var(--border-width) solid var(--surface-info);
  border-radius: var(--radius-md);
  background: var(--surface-info);
}

.zhihu-publish-notice h3 {
  margin: 0;
  font-size: var(--type-body-sm);
  font-weight: var(--weight-heading);
  color: var(--color-info);
}

.zhihu-publish-notice p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
  line-height: var(--leading-body-sm);
}

.zhihu-publish-notice strong {
  color: var(--color-text);
  font-weight: var(--weight-heading);
}

.zhihu-publish-copy {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.zhihu-publish-copy-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}
</style>
