<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <div class="card-head-row">
        <button class="btn-back" type="button" @click="$emit('goBack')">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回正文
        </button>
        <p class="eyebrow">第五步</p>
      </div>
      <h2 class="card-title">为文章配图</h2>
      <p class="field-note">AI 根据文章内容推荐封面图和正文插图，你可以从网络搜图或用 AI 生成。</p>
    </header>

    <div v-if="!imageRecommendations && !loadingRecommendations" class="action-row">
      <button class="btn-primary gl-btn-primary" @click="$emit('loadRecommendations')">
        获取配图推荐
      </button>
      <button class="btn-secondary" @click="$emit('finish')">跳过，直接完成</button>
    </div>

    <div v-if="loadingRecommendations" class="loading-hint">
      <span class="stream-dot"></span>
      正在分析文章内容并推荐配图…
    </div>

    <div v-if="imageRecommendations && imageSlots.length > 0" class="images-layout">
      <div v-for="(slot, slotIdx) in imageSlots" :key="slotIdx" class="image-slot-card" :class="{ 'slot-skipped': slot.skipped }">
        <div class="slot-head">
          <span class="slot-position">{{ slot.placement.position }}</span>
          <span class="slot-desc">{{ slot.placement.description }}</span>
          <button type="button" class="slot-toggle" @click="$emit('toggleSlot', slotIdx)">
            {{ slot.skipped ? '需要配图' : '跳过' }}
          </button>
        </div>

        <template v-if="!slot.skipped">
        <div v-if="slot.selectedImage" class="slot-selected">
          <img
            :src="'imageUrl' in slot.selectedImage ? slot.selectedImage.imageUrl : slot.selectedImage.thumbnailUrl"
            :alt="slot.placement.description"
            class="slot-preview-img clickable-img"
            @click="$emit('openLightbox', ($event.currentTarget as HTMLImageElement).src)"
          />
          <div class="slot-selected-actions">
            <button class="btn-secondary btn-sm" type="button" @click="$emit('clearImageForSlot', slotIdx)">移除重选</button>
          </div>
        </div>

        <div v-if="!slot.selectedImage" class="slot-actions">
          <div class="slot-tabs">
            <button
              type="button"
              class="slot-tab"
              :class="{ 'slot-tab-active': slot.mode === 'search' }"
              :disabled="slot.searching"
              @click="$emit('searchImageForSlot', slotIdx)"
            >搜图</button>
            <button
              type="button"
              class="slot-tab"
              :class="{ 'slot-tab-active': slot.mode === 'generate' }"
              :disabled="slot.generating"
              @click="$emit('generateImageForSlot', slotIdx)"
            >AI 生成</button>
          </div>

          <div v-if="slot.searching" class="loading-hint loading-hint-sm">
            <span class="stream-dot"></span> 搜索中…
          </div>
          <div v-else-if="slot.generating" class="loading-hint loading-hint-sm">
            <span class="stream-dot"></span> AI 生成中，可能需要 1-2 分钟…
          </div>

          <div v-if="slot.searchResults.length > 0 && !slot.selectedImage" class="search-results-area">
            <div class="search-grid">
            <div
              v-for="(img, imgIdx) in slot.searchResults"
              :key="imgIdx"
              class="search-thumb-wrap"
            >
              <button type="button" class="search-thumb" @click="$emit('selectImageForSlot', slotIdx, img)">
                <img :src="img.thumbnailUrl" :alt="img.description || slot.placement.description" />
              </button>
              <button type="button" class="thumb-zoom" @click="$emit('openLightbox', img.url)" title="放大查看">
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M6 10L10 6M10 6H6.5M10 6V9.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M2 6V4a2 2 0 012-2h2M10 2h2a2 2 0 012 2v2M14 10v2a2 2 0 01-2 2h-2M6 14H4a2 2 0 01-2-2v-2" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
            </div>
            </div>
            <button
              type="button"
              class="btn-secondary btn-sm btn-re-search"
              :disabled="slot.searching"
              @click="$emit('searchImageForSlot', slotIdx)"
            >重新搜索</button>
          </div>
        </div>
        </template>
      </div>
    </div>

    <div v-if="imageRecommendations" class="action-row">
      <button class="btn-primary gl-btn-primary" @click="$emit('finish')">完成</button>
      <button class="btn-secondary" @click="$emit('loadRecommendations')">重新推荐</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { ArticleImageSlot, ImageRecommendation } from '../../../types/article-creation'

defineProps<{
  imageSlots: ArticleImageSlot[]
  imageRecommendations: ImageRecommendation | null
  loadingRecommendations: boolean
}>()

defineEmits<{
  goBack: []
  loadRecommendations: []
  finish: []
  toggleSlot: [index: number]
  clearImageForSlot: [index: number]
  searchImageForSlot: [index: number]
  generateImageForSlot: [index: number]
  selectImageForSlot: [index: number, image: any]
  openLightbox: [src: string]
}>()
</script>

<style scoped>
.stage-card,
.card-head {
  display: grid;
  gap: 14px;
}

.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.eyebrow {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.card-title {
  margin: 0;
  font-size: 1.14rem;
  font-weight: 600;
  line-height: 1.25;
  color: var(--color-text);
}

.field-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.6;
}

.btn-back,
.btn-primary,
.btn-secondary {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.btn-sm {
  min-height: 30px;
  padding: 0 var(--space-sm);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.images-layout {
  display: grid;
  gap: 16px;
}

.image-slot-card {
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  display: grid;
  gap: 12px;
}

.slot-skipped {
  opacity: 0.5;
}

.slot-toggle {
  margin-left: auto;
  padding: 2px 10px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 0.76rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}

.slot-toggle:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
  color: var(--color-text);
}

.slot-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.slot-position {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  color: var(--color-accent);
  font-size: 0.76rem;
  font-weight: 700;
  white-space: nowrap;
}

.slot-desc {
  font-size: 0.85rem;
  color: var(--color-text-secondary);
  line-height: 1.5;
}

.slot-selected {
  display: grid;
  gap: 10px;
}

.slot-preview-img {
  width: 100%;
  max-height: 240px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.clickable-img {
  cursor: zoom-in;
  transition: opacity 0.15s ease;
}

.clickable-img:hover {
  opacity: 0.85;
}

.slot-actions {
  display: grid;
  gap: 10px;
}

.slot-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  width: fit-content;
}

.slot-tab {
  min-height: 32px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.slot-tab-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.slot-tab:not(.slot-tab-active):hover {
  background: var(--color-surface-hover);
}

.slot-tab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.search-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.search-results-area {
  display: grid;
  gap: 10px;
}

.btn-re-search {
  justify-self: start;
}

.search-thumb {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  padding: 0;
  background: var(--surface-card);
  transition: border-color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}

.search-thumb:hover {
  border-color: var(--color-border-accent);
  transform: translateY(-1px);
}

.search-thumb img {
  width: 100%;
  aspect-ratio: 4/3;
  object-fit: cover;
  display: block;
}

.search-thumb-wrap {
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.thumb-zoom {
  position: absolute;
  top: 4px;
  right: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: var(--color-on-accent);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.search-thumb-wrap:hover .thumb-zoom {
  opacity: 1;
}

.thumb-zoom:hover {
  background: rgba(0, 0, 0, 0.65);
}

.loading-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.loading-hint-sm {
  padding: 4px 0;
}

.stream-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-accent);
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

@media (max-width: 720px) {
  .card-head-row {
    flex-direction: column;
    align-items: stretch;
  }

  .btn-primary,
  .btn-secondary,
  .btn-back,
  .btn-sm {
    width: 100%;
  }
}
</style>
