<template>
  <section class="xhs-publish">
    <div class="xhs-publish-notice" role="note" data-test="xhs-publish-notice">
      <h3>发布方式说明</h3>
      <p>小红书未开放内容发布 API，草场只负责生成、校对与导出；请在小红书 App 内按配图顺序上传发布。</p>
    </div>

    <XhsImageOrderList
      :cards="cards.cards.value"
      :results="cards.results.value"
      :disabled="orderDisabled"
      @move="onOrderMove"
      @open="onOrderOpen"
    />

    <DeliveryPanel
      :model-value="autosave.deliveryValue.value"
      platform="xiaohongshu"
      :disabled="autosave.readonly.value"
      :media-expected="mediaExpected"
      :draft-id="autosave.draftId.value || undefined"
      :draft-version="autosave.draftVersion.value"
      :before-export="beforeExport"
      :export-title="engine.selectedTitle.value"
      @update:model-value="autosave.updateDelivery"
    />

    <div class="xhs-publish-copy">
      <button
        type="button"
        class="gl-btn-secondary"
        data-test="xhs-publish-copy"
        :disabled="copying"
        @click="onCopy"
      >{{ copying ? '复制中…' : '复制标题+正文+话题' }}</button>
      <p class="xhs-publish-copy-hint">复制后可粘贴到小红书 App 的发布编辑器继续编辑。</p>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 发布导出（方案 §3 pub-export / §4.4【F6】）：
 * - 平台约束警示为真实产品约束陈述（不做站外自动发布）；
 * - 配图顺序走 XhsImageOrderList + useXhsImageOrder（真实前端状态）；
 * - DeliveryPanel 原样复用：保留 modelValue/platform/disabled/mediaExpected/draftId/
 *   draftVersion/before-export/export-title；省略 suggest-summary（summary 区仅公众号
 *   可见）、studio 导出（studio 会话本轮不接入）、wechatEnabled、bodyReadonly；
 * - 复制按钮走 copyContent 同款双路径剪贴板（clipboard API → execCommand 兜底），
 *   execCommand 返回 false 时如实报失败（不假装已复制）；
 * - 发布账号/发布时间/笔记链接回填 defer：无数据通路，不渲染假控件。
 * 装配写在组件自身（inject 自取，F7），口径参照 ArticleCreationView 的 DeliveryPanel 挂载。
 */
import { computed, ref } from 'vue'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'
import { useXhsImageOrder } from '../composables/useXhsImageOrder'
import DeliveryPanel from '../../ai-center/components/DeliveryPanel.vue'
import XhsImageOrderList from './XhsImageOrderList.vue'

const emit = defineEmits<{ 'open-lightbox': [url: string] }>()

const { engine, cards, autosave, beforeExport, notify } = useXhsStudioContext()

const order = useXhsImageOrder(cards)
/** 排序在图卡生成中与只读（版本冲突）态禁用；生成中不动由 useXhsImageOrder 再兜底。 */
const orderDisabled = computed(() => cards.generating.value || autosave.readonly.value)

function onOrderMove(index: number, direction: -1 | 1): void {
  order.move(index, direction)
}

function onOrderOpen(url: string): void {
  emit('open-lightbox', url)
}

/** 【F6】mediaExpected：有已存图卡（persistedMediaIds）时交付检查含「选定媒体」。 */
const mediaExpected = computed(() => Object.keys(cards.persistedMediaIds.value).length > 0)

/** 复制文本 = 标题 + 空行 + 正文 + 空行 + 话题行（来源 deliveryValue，与导出交付同源）。 */
const copyText = computed(() => {
  const delivery = autosave.deliveryValue.value
  const topics = (delivery.topics ?? [])
    .filter((topic) => topic.trim() !== '')
    .map((topic) => `#${topic}`)
    .join(' ')
  return [
    (delivery.titleOrOpening ?? '').trim(),
    (delivery.bodyOrDescription ?? '').trim(),
    topics,
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
    notify('已复制笔记内容')
  } catch {
    notify('复制失败，请手动选择文本复制')
  } finally {
    copying.value = false
  }
}
</script>

<style scoped>
.xhs-publish {
  display: grid;
  gap: var(--space-md);
}

.xhs-publish-notice {
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-sm) var(--space-md);
  border: var(--border-width) solid var(--surface-info);
  border-radius: var(--radius-md);
  background: var(--surface-info);
}

.xhs-publish-notice h3 {
  margin: 0;
  font-size: var(--type-body-sm);
  font-weight: var(--weight-heading);
  color: var(--color-info);
}

.xhs-publish-notice p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-body-sm);
  line-height: var(--leading-body-sm);
}

.xhs-publish-copy {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.xhs-publish-copy-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}
</style>
