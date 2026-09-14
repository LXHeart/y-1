<template>
  <section class="session-versions-card gl-zone" aria-label="本次会话版本对比">
    <header class="session-versions-head">
      <div>
        <p class="session-versions-title">多版本对比</p>
        <p class="session-versions-copy">本次会话内保存的评价草稿版本，可切换查看对比，仅保存在当前页面内存。</p>
      </div>
      <button
        class="btn-secondary btn-sm"
        type="button"
        :disabled="loading || !result || isEditing"
        @click="$emit('save-version')"
      >
        保存当前版本
      </button>
    </header>

    <p v-if="versions.length === 0" class="session-versions-empty">
      暂无保存的版本。生成或编辑评价后，点「保存当前版本」即可积累可对比的版本。
    </p>

    <template v-else>
      <ol class="session-versions-list">
        <li v-for="version in versions" :key="version.id" class="session-version-item">
          <button
            type="button"
            class="session-version-btn"
            :class="{ 'session-version-btn-active': selectedId === version.id }"
            @click="$emit('select-version', version.id)"
          >
            {{ version.label }} · {{ version.platformLabel }} · {{ version.savedAt }}
          </button>
          <button class="session-version-remove" type="button" :aria-label="`删除 ${version.label}`" @click="$emit('remove-version', version.id)">&times;</button>
        </li>
      </ol>

      <div v-if="selectedVersion" class="session-version-detail">
        <h4 v-if="selectedVersion.data.title" class="result-label">标题</h4>
        <p v-if="selectedVersion.data.title" class="result-text result-emphasis">{{ selectedVersion.data.title }}</p>
        <h4 class="result-label">评价内容</h4>
        <p class="result-text">{{ selectedVersion.data.review }}</p>
        <div v-if="selectedVersion.data.tags?.length" class="result-tags-wrap">
          <h4 class="result-label">标签</h4>
          <div class="result-tags">
            <span v-for="tag in selectedVersion.data.tags" :key="tag" class="result-tag">{{ tag }}</span>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ImageAnalysisResult } from '../../../types/image-analysis'

export interface SessionVersion {
  id: string
  label: string
  platformLabel: string
  savedAt: string
  data: ImageAnalysisResult
}

const props = defineProps<{
  versions: SessionVersion[]
  selectedId: string | null
  loading: boolean
  result: ImageAnalysisResult | null
  isEditing: boolean
}>()

defineEmits<{
  'save-version': []
  'select-version': [id: string]
  'remove-version': [id: string]
}>()

const selectedVersion = computed(() =>
  props.versions.find((v) => v.id === props.selectedId) ?? null,
)
</script>

<style scoped>
.session-versions-card {
  display: grid;
  gap: var(--space-md);
  align-content: start;
}

.session-versions-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.session-versions-title {
  margin: 0;
  color: var(--color-text);
  font-size: var(--type-body);
  font-weight: var(--weight-heading);
}

.session-versions-copy,
.session-versions-empty {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  line-height: 1.55;
}

.session-versions-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-xs);
}

.session-version-item {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.session-version-btn {
  flex: 1;
  min-width: 0;
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  text-align: left;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.session-version-btn:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.session-version-btn-active {
  background: var(--surface-card);
  border-color: var(--color-border-accent);
  color: var(--color-text);
}

.session-version-remove {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--type-body-sm);
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
}

.session-version-remove:hover {
  color: var(--color-danger);
  background: color-mix(in srgb, var(--color-danger) 8%, transparent);
}

.session-version-detail {
  display: grid;
  gap: var(--space-xs);
  padding: var(--space-md);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.result-label {
  margin: 0;
  font-size: var(--type-caption);
  letter-spacing: 0;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: var(--weight-heading);
}

.result-text {
  margin: 0;
  color: var(--color-text);
  line-height: 1.75;
  white-space: pre-wrap;
}

.result-emphasis { font-weight: var(--weight-heading); }

.result-tags-wrap { display: grid; gap: var(--space-sm); }

.result-tags { display: flex; flex-wrap: wrap; gap: var(--space-xs); }

.result-tag {
  display: inline-flex;
  align-items: center;
  padding: var(--space-xxs) var(--space-sm);
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.btn-secondary {
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  cursor: pointer;
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.btn-secondary:disabled { opacity: 0.6; cursor: not-allowed; }

.btn-sm { min-height: var(--control-height); padding: 0 var(--space-sm); font-size: var(--type-caption); }
</style>
