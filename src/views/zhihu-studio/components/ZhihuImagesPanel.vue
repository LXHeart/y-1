<template>
  <section class="zhihu-images" aria-label="文章配图">
    <header class="zhihu-images-head">
      <h2>配图</h2>
      <p class="gl-hint">知乎回答与文章都支持配图：按段落绑定插图，可选搜索图或生成图；也可以全部留空直接完成。</p>
    </header>

    <!-- legacy 配图链整块复用（旧视图 ArticleCreationView.vue:235-254 同款接线） -->
    <ArticleImageSlots
      :image-slots="engine.imageSlots.value"
      :image-recommendations="engine.imageRecommendations.value"
      :loading-recommendations="engine.loadingRecommendations.value"
      :legacy-preferred="false"
      @load-recommendations="() => void engine.loadImageRecommendations()"
      @finish="onFinish"
      @toggle-slot="engine.toggleSlot"
      @clear-image-for-slot="engine.clearImageForSlot"
      @search-image-for-slot="(index: number) => void engine.searchImageForSlot(index)"
      @generate-image-for-slot="(index: number) => void engine.generateImageForSlot(index)"
      @select-image-for-slot="engine.selectImageForSlot"
      @open-lightbox="onOpenLightbox"
    />
  </section>
</template>

<script setup lang="ts">
/**
 * 配图区块（engine imageSlots legacy 链整块复用，知乎不跳配图——与小红书 noteMode
 * 的关键差异）：ArticleImageSlots 是 props 驱动的独立组件，事件全部直连引擎函数
 * （旧视图同款接线）；finish → engine.finish() 置 completed，步骤机唯一自动跳转
 * 进发布交付区。studio 视觉计划链（ArticleVisualPanel）本轮不接入（recipe 会话
 * 留旧视图，方案 §2 同 xhs 边界）。
 */
import ArticleImageSlots from '../../article/components/ArticleImageSlots.vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'

const emit = defineEmits<{ 'open-lightbox': [src: string] }>()

const { engine, notify } = useZhihuStudioContext()

function onFinish(): void {
  engine.finish()
  notify('配图完成，可导出交付')
}

function onOpenLightbox(src: string): void {
  emit('open-lightbox', src)
}
</script>

<style scoped>
.zhihu-images {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-images-head {
  display: grid;
  gap: var(--space-xs);
}

.zhihu-images-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-images-head .gl-hint {
  margin: 0;
}
</style>
