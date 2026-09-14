<template>
  <article class="hot-panel glass-card">
    <header class="hot-head">
      <div>
        <p class="hot-kicker">抖音热点 · 实时选题灵感</p>
        <p class="hot-note">榜单实时抓取，链接经平台受信校验；只作创作参考，不直接复制原文。</p>
      </div>
      <button
        class="hot-refresh"
        type="button"
        :disabled="loading"
        @click="loadHotItems"
      >
        {{ loading ? '加载中…' : '刷新' }}
      </button>
    </header>

    <p v-if="error" class="hot-message hot-error">{{ error }}</p>
    <p v-else-if="loading && items.length === 0" class="hot-message">正在加载抖音热点…</p>

    <ol v-else-if="items.length > 0" class="hot-list">
      <li v-for="item in items" :key="`${item.rank}-${item.title}`" class="hot-row">
        <span class="hot-rank" :class="{ 'hot-rank-top': item.rank <= 3 }">{{ item.rank }}</span>
        <span class="hot-title">
          <a v-if="item.url" :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
          <template v-else>{{ item.title }}</template>
          <small v-if="item.hotValue">{{ item.hotValue }}</small>
        </span>
        <button
          v-if="item.url"
          class="hot-use"
          type="button"
          :disabled="loading"
          @click="emit('use-link', item.url)"
        >
          带入提取
        </button>
      </li>
    </ol>

    <p v-else class="hot-message">暂无抖音热点数据。</p>
  </article>
</template>

<script setup lang="ts">
import { useDouyinHotItems } from '../composables/useDouyinHotItems'

const emit = defineEmits<{
  'use-link': [url: string]
}>()

const { items, loading, error, loadHotItems } = useDouyinHotItems()

void loadHotItems()
</script>

<style scoped>
.hot-panel {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-md);
}

.hot-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.hot-head > div {
  display: grid;
  gap: var(--space-xxs);
}

.hot-kicker {
  margin: 0;
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  color: var(--color-accent-2);
}

.hot-note {
  margin: 0;
  font-size: var(--type-caption);
  line-height: 1.5;
  color: var(--color-text-muted);
  max-width: 40ch;
}

.hot-refresh {
  flex-shrink: 0;
  min-height: 32px;
  padding: 0 var(--space-sm);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  cursor: pointer;
}

.hot-refresh:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.hot-refresh:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.hot-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  max-height: 320px;
  overflow-y: auto;
}

.hot-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-micro);
  border-top: 1px solid var(--color-border);
}

.hot-row:last-child {
  border-bottom: 1px solid var(--color-border);
}

.hot-rank {
  flex-shrink: 0;
  width: 22px;
  text-align: center;
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.hot-rank-top {
  color: var(--color-accent-2);
}

.hot-title {
  display: grid;
  gap: var(--space-micro);
  min-width: 0;
  flex: 1;
  font-size: var(--type-caption);
  line-height: 1.4;
  color: var(--color-text);
}

.hot-title a {
  color: var(--color-text);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-title a:hover {
  color: var(--color-accent-2);
  text-decoration: underline;
}

.hot-title small {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.hot-use {
  flex-shrink: 0;
  min-height: 28px;
  padding: 0 var(--space-sm);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  cursor: pointer;
}

.hot-use:hover:not(:disabled) {
  border-color: var(--color-accent);
  color: var(--color-accent-2);
}

.hot-message {
  margin: 0;
  font-size: var(--type-caption);
  color: var(--color-text-muted);
}

.hot-error {
  color: var(--color-danger);
}
</style>
