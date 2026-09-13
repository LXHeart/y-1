<script setup lang="ts">
import { computed, ref } from 'vue'
import DOMPurify from 'dompurify'
import type { useArticleRender } from '../composables/useArticleRender'

/**
 * 任务书 #101 C101-17（§8.1 排版预览区）：原稿与预览双栏。
 *
 * 只读预览经 DOMPurify 净化后插入限定内容区（服务端已受控，双保险）；主题
 * standard/compact 与 includeTitle/citeExternalLinks 只影响本次输出；缺图与未绑定段落
 * 明确标注（不伪装完整）；摘要建议是独立动作（emit 由父层走 TextProposal，禁止切主题
 * 触发 LLM）。移动端（<768px）双栏切页内切换。
 */
const props = defineProps<{
  render: ReturnType<typeof useArticleRender>
  /** 原稿 markdown（左栏只读展示）。 */
  content: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'render-requested', input: { theme: 'standard' | 'compact'; includeTitle: boolean; citeExternalLinks: boolean }): void
  (e: 'suggest-summary'): void
}>()

const theme = ref<'standard' | 'compact'>('standard')
const includeTitle = ref(false)
const citeExternalLinks = ref(false)
const mobileView = ref<'source' | 'preview'>('source')

const sanitizedHtml = computed(() => {
  const html = props.render.preview.value?.html
  if (!html) return ''
  // 只读预览净化：先剥 script 块与内联 on* 事件（纵深防御），再经 DOMPurify 白名单插入限定容器
  const stripped = html
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/\son\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  return DOMPurify.sanitize(stripped, {
    ALLOWED_TAGS: ['style', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'ul', 'ol', 'li',
      'blockquote', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'pre', 'code', 'em', 'strong',
      'a', 'figure', 'figcaption', 'img', 'span', 'sup', 'hr'],
    ALLOWED_ATTR: ['class', 'href', 'rel', 'src', 'alt', 'data-render', 'data-render-note',
      'role', 'aria-label', 'start'],
  })
})

function onRender(): void {
  emit('render-requested', { theme: theme.value, includeTitle: includeTitle.value,
    citeExternalLinks: citeExternalLinks.value })
}
</script>

<template>
  <section class="gl-zone article-format-panel" data-test="article-format-panel" aria-label="排版预览">
    <div class="panel-head">
      <h3>排版预览</h3>
      <div class="mobile-toggle" role="group" aria-label="移动端栏切换">
        <button type="button" :class="{ active: mobileView === 'source' }" @click="mobileView = 'source'">原稿</button>
        <button type="button" :class="{ active: mobileView === 'preview' }" @click="mobileView = 'preview'">预览</button>
      </div>
    </div>

    <div class="options-row">
      <label class="option">
        主题
        <select v-model="theme" data-test="format-theme" :disabled="disabled">
          <option value="standard">标准</option>
          <option value="compact">紧凑</option>
        </select>
      </label>
      <label class="option checkbox">
        <input v-model="includeTitle" type="checkbox" data-test="format-include-title" :disabled="disabled">
        含标题
      </label>
      <label class="option checkbox">
        <input v-model="citeExternalLinks" type="checkbox" data-test="format-cite-links" :disabled="disabled">
        外链转引用
      </label>
      <button
        type="button"
        class="primary gl-btn-primary"
        data-test="format-render"
        :disabled="render.rendering.value || disabled"
        @click="onRender"
      >{{ render.rendering.value ? '排版中…' : '生成排版预览' }}</button>
      <button
        type="button"
        class="secondary"
        data-test="format-suggest-summary"
        :disabled="disabled"
        @click="emit('suggest-summary')"
      >生成摘要建议</button>
    </div>

    <p v-if="render.error.value" class="error" data-test="format-error" role="alert">{{ render.error.value }}</p>

    <!-- 缺图/未绑定说明：不伪装完整 -->
    <div v-if="render.preview.value?.unresolvedMediaIds?.length" class="warn" data-test="format-unresolved">
      {{ render.preview.value.unresolvedMediaIds.length }} 张配图未绑定段落（按原顺序附于文末）；
      导出完整图片包前需先核对位置。
    </div>
    <ul v-if="render.preview.value?.warnings?.length" class="warn-list" data-test="format-warnings">
      <li v-for="warning in render.preview.value.warnings" :key="warning">{{ warning }}</li>
    </ul>

    <div class="columns" :data-mobile-view="mobileView">
      <div class="source-col" data-test="format-source">
        <h4>原稿</h4>
        <pre class="source-view">{{ content }}</pre>
      </div>
      <div class="preview-col" data-test="format-preview">
        <h4>预览 <span class="meta">（只读；净化后插入）</span></h4>
        <div v-if="render.preview.value" class="preview-body" data-test="format-preview-body" v-html="sanitizedHtml" />
        <p v-else class="placeholder">点击「生成排版预览」查看当前版本的排版效果。</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.article-format-panel { display: grid; gap: 14px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.panel-head h3 { margin: 0; }
.mobile-toggle { display: none; }
.options-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.option { display: inline-flex; align-items: center; gap: 6px; font-size: .86rem; }
.option.checkbox input { width: 16px; height: 16px; }
.columns { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.source-col, .preview-col { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 12px; min-width: 0; }
.source-col h4, .preview-col h4 { margin: 0 0 8px; font-size: .88rem; }
.meta { color: var(--color-text-muted); font-weight: 400; font-size: .78rem; }
.source-view { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: .84rem; max-height: 480px; overflow: auto; color: var(--color-text-secondary); }
.preview-body { max-height: 480px; overflow: auto; }
.preview-body :deep(.creation-render) { color: var(--color-text); }
.placeholder { color: var(--color-text-muted); font-size: .86rem; }
.warn { margin: 0; padding: 8px 12px; border-radius: var(--radius-md); border: 1px solid color-mix(in srgb, var(--color-warning, #b8860b) 32%, transparent); background: color-mix(in srgb, var(--color-warning, #b8860b) 8%, transparent); font-size: .84rem; }
.warn-list { margin: 0; padding-left: 18px; color: var(--color-text-muted); font-size: .8rem; }
.error { color: var(--color-danger); }
@media (max-width: 768px) {
  .columns { grid-template-columns: 1fr; }
  .columns[data-mobile-view='source'] .preview-col { display: none; }
  .columns[data-mobile-view='preview'] .source-col { display: none; }
  .mobile-toggle { display: flex; border-radius: var(--radius-pill); overflow: hidden; }
  .mobile-toggle button { border: none; padding: 4px 12px; font-size: .8rem; background: var(--color-surface); color: var(--color-text-secondary); }
  .mobile-toggle button.active { background: var(--color-primary); color: #fff; }
}
</style>
