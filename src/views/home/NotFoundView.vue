<script setup lang="ts">
/**
 * 用户端 404 内容页（任务书 #85 C-02）：catch-all 兜底，纯静态。
 * 文案为固定常量，不回显当前路径/URL（#79 安全口径），不自动跳转。
 */
</script>

<template>
  <section class="not-found" aria-labelledby="not-found-title">
    <p class="not-found-code" aria-hidden="true">404</p>
    <h1 id="not-found-title">页面不存在</h1>
    <p class="not-found-copy">你访问的页面不存在或已被移动。</p>
    <RouterLink to="/" class="gl-btn-primary">返回首页</RouterLink>
  </section>
</template>

<style scoped>
.not-found { min-height: 60vh; max-width: 720px; margin: 0 auto; padding: 64px 24px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; text-align: center; }
.not-found-code { margin: 0; font-family: var(--font-display, inherit); font-weight: 300; font-size: 5rem; line-height: 1; letter-spacing: -0.04em; color: var(--color-text); }
.not-found h1 { margin: 0; font-family: var(--font-display, inherit); font-weight: 300; font-size: 1.5rem; color: var(--color-text); }
.not-found-copy { margin: 0; font-size: 0.92rem; color: var(--color-text-muted); }
.not-found .gl-btn-primary { margin-top: 12px; }

@media (max-width: 767px) {
  .not-found { width: 100%; max-width: none; padding: 48px 16px; }
}
</style>
