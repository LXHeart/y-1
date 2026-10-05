<template>
  <nav class="ai-workspace-nav" aria-label="主导航">
    <RouterLink v-for="item in WORKSPACE_LINKS" :key="item.name" :to="{ name: item.name }"
      class="ai-workspace-nav-link" :class="{ 'is-current': active === item.name }"
      :aria-current="active === item.name ? 'page' : undefined" :data-testid="`nav-${item.name}`">
      {{ item.label }}
    </RouterLink>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { WORKSPACE_LINKS, activeWorkspace } from '../workspace/navigation'
const route = useRoute()
const active = computed(() => activeWorkspace(route.name))
</script>

<style scoped>
.ai-workspace-nav { display: flex; flex-wrap: wrap; gap: var(--space-xs); padding-block: var(--space-sm); border-bottom: 1px solid var(--color-border); }
.ai-workspace-nav-link { display: inline-flex; align-items: center; min-height: var(--control-height); padding: var(--space-xs) var(--space-md); border-radius: var(--radius-md); font-size: var(--type-label); color: var(--color-text-secondary); text-decoration: none; }
.ai-workspace-nav-link:hover { background: var(--color-surface-hover); color: var(--color-text); }
.ai-workspace-nav-link.is-current { color: var(--color-accent-2); background: var(--color-surface-highlight); font-weight: var(--weight-heading); }
.ai-workspace-nav-link:focus-visible { outline: var(--focus-width) solid var(--focus-color); outline-offset: var(--focus-offset); }
</style>
