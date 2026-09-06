<script setup lang="ts">
import { computed } from 'vue'
import { renderSafeMarkdown } from '../../lib/safe-markdown'
import { LEGAL_DOCS, PLACEHOLDER_NOTICE } from './legal-docs'
import type { LegalDocKind } from './legal-docs'

/**
 * 法律文档视图（任务书 #85 C-01）：公开静态页，无请求、无本地状态。
 * 正文是仓库静态 md 经 renderSafeMarkdown 白名单净化后的 HTML——本组件
 * 唯一的 v-html 就在这一处，MUST NOT 对其他字符串 v-html。
 */
const props = defineProps<{ kind: LegalDocKind }>()

const doc = computed(() => LEGAL_DOCS[props.kind])
</script>

<template>
  <article v-if="doc" class="legal-doc" aria-labelledby="legal-doc-title">
    <header class="legal-doc-head">
      <p class="legal-doc-kicker">草场 · 法律文档</p>
      <h1 id="legal-doc-title">{{ doc.title }}</h1>
      <p class="legal-doc-version">{{ doc.version }} · {{ doc.effectiveStatus }}</p>
    </header>

    <p v-if="doc.placeholder" class="legal-doc-notice" role="note">{{ PLACEHOLDER_NOTICE }}</p>

    <!-- 正文：仅此一处 v-html，输入恒为 LEGAL_DOCS 的仓库静态 md（净化渲染）。 -->
    <div class="legal-doc-body" v-html="renderSafeMarkdown(doc.bodyMarkdown)" />

    <footer class="legal-doc-foot">
      <RouterLink to="/" class="gl-btn-primary">返回首页</RouterLink>
    </footer>
  </article>

  <section v-else class="legal-doc" aria-labelledby="legal-doc-missing-title">
    <h1 id="legal-doc-missing-title">文档不存在</h1>
    <p class="legal-doc-version">你访问的文档不存在或尚未发布。</p>
    <footer class="legal-doc-foot">
      <RouterLink to="/" class="gl-btn-primary">返回首页</RouterLink>
    </footer>
  </section>
</template>

<style scoped>
.legal-doc { max-width: 720px; margin: 0 auto; padding: 48px 24px 64px; display: flex; flex-direction: column; gap: 20px; }
.legal-doc-head { display: flex; flex-direction: column; gap: 8px; }
.legal-doc-kicker { margin: 0; font-size: 12px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--color-text-muted); }
.legal-doc-head h1 { margin: 0; font-family: var(--font-display, inherit); font-weight: 300; font-size: 1.5rem; color: var(--color-text); }
.legal-doc-version { margin: 0; font-size: 0.78rem; color: var(--color-text-muted); }
.legal-doc-notice {
  margin: 0; padding: 10px 14px; font-size: 0.8rem; line-height: 1.6;
  color: var(--color-text); border: 1px solid var(--color-border-accent);
  background: var(--color-surface-highlight); border-radius: var(--radius-md);
}
.legal-doc-body { font-size: 0.92rem; line-height: 1.75; color: var(--color-text); }
.legal-doc-body :deep(h2) { margin: 28px 0 10px; font-size: 1.05rem; font-weight: 600; color: var(--color-text); }
.legal-doc-body :deep(h2:first-child) { margin-top: 0; }
.legal-doc-body :deep(p) { margin: 0 0 12px; }
.legal-doc-body :deep(ul), .legal-doc-body :deep(ol) { margin: 0 0 12px; padding-left: 20px; display: flex; flex-direction: column; gap: 6px; }
.legal-doc-body :deep(blockquote) { margin: 0 0 12px; padding: 8px 14px; border-left: 3px solid var(--color-border-accent); color: var(--color-text-muted); }
.legal-doc-body :deep(a) { color: var(--color-accent-2); }
.legal-doc-body :deep(code) { font-size: 0.85em; padding: 2px 6px; border-radius: var(--radius-md); background: var(--color-surface-highlight); }
.legal-doc-body :deep(pre) { padding: 12px 14px; border-radius: var(--radius-lg); background: var(--color-surface-highlight); overflow-x: auto; }
.legal-doc-foot { margin-top: 12px; }
.legal-doc-foot .gl-btn-primary { display: inline-block; }

@media (max-width: 767px) {
  .legal-doc { width: 100%; max-width: none; padding: 32px 16px 48px; }
  .legal-doc-body :deep(h2) { overflow-wrap: anywhere; }
}
</style>
