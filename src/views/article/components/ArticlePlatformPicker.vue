<template>
  <div class="platform-toggle" role="tablist" aria-label="文章平台">
    <button
      type="button"
      class="platform-btn"
      :class="{ 'platform-btn-active': platform === 'wechat' }"
      :disabled="disabled"
      @click="emit('select', 'wechat')"
    >微信公众号</button>
    <button
      type="button"
      class="platform-btn"
      :class="{ 'platform-btn-active': platform === 'zhihu' }"
      :disabled="disabled"
      @click="emit('select', 'zhihu')"
    >知乎</button>
    <button
      type="button"
      class="platform-btn"
      :class="{ 'platform-btn-active': platform === 'xiaohongshu' }"
      :disabled="disabled"
      @click="emit('select', 'xiaohongshu')"
    >小红书</button>
    <button
      type="button"
      class="platform-btn"
      :class="{ 'platform-btn-active': platform === 'douyin' }"
      :disabled="disabled"
      @click="emit('select-douyin')"
    >抖音</button>
  </div>
</template>

<script setup lang="ts">
import type { ArticlePlatform } from '../../../types/article-creation'

/**
 * 平台四选（微信/知乎/小红书/抖音）。抖音已是一等 platform 值 `douyin`（任务书 #69 卡B）；
 * 仍单独 emit `select-douyin`——调用方切抖音要同步 isDouyinMode 标记，不让其从 platform 值反推。
 *
 * 抽出组件的动机（任务书 #62）：知乎回答模式的第一步是「问题」而非「主题」，
 * 两个首步都要能换平台，markup 只能有一份。
 */
defineProps<{
  platform: ArticlePlatform
  isDouyinMode: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  select: [target: Exclude<ArticlePlatform, 'douyin'>]
  'select-douyin': []
}>()
</script>

<style scoped>
.platform-toggle {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}

.platform-btn {
  min-height: 38px;
  padding: 0 14px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.platform-btn:hover:not(:disabled) {
  border-color: var(--color-accent);
  color: var(--color-text-primary);
}

.platform-btn-active {
  background: var(--gradient-accent);
  border-color: transparent;
  color: var(--color-on-accent);
}

.platform-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
