<template>
  <!-- K11：跨路由链接用 nav（非 tablist）；aria-current 由 RouterLink 按 exact active 自动落 page。 -->
  <nav class="ai-workspace-nav" aria-label="工作区">
    <RouterLink :to="{ name: 'create' }" class="ai-workspace-nav-link" data-testid="nav-create">
      创作中心
    </RouterLink>
    <RouterLink :to="{ name: 'digital-human' }" class="ai-workspace-nav-link" data-testid="nav-digital-human">
      数字人
    </RouterLink>
  </nav>
</template>

<script setup lang="ts">
// 纯装配（C105E-01）：真实导航链接，不携带业务状态；登录/主题等壳层能力留在壳。
import { RouterLink } from 'vue-router'
</script>

<style scoped>
.ai-workspace-nav {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.ai-workspace-nav-link {
  display: inline-flex;
  align-items: center;
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  color: var(--color-text-secondary);
  font-size: var(--type-button);
  font-weight: var(--weight-label);
  letter-spacing: 0;
  text-decoration: none;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.ai-workspace-nav-link:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.ai-workspace-nav-link:focus-visible {
  outline: var(--focus-width) solid var(--focus-color);
  outline-offset: var(--focus-offset);
}

/* RouterLink exact active 落 aria-current="page"；选中态=高亮底+品牌紫文字（不冒充 tab）。 */
.ai-workspace-nav-link[aria-current='page'] {
  background: var(--color-surface-highlight);
  border-color: var(--color-border);
  color: var(--color-accent-2);
  font-weight: var(--weight-heading);
}
</style>
