<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CreationDeliveryContract } from '../../../types/creation'
import { SUPPORTED_JIANYING_RANGE, exportTaskArtifact } from '../../video-production/components/ComposeStage.vue'
import DeliveryPanel from '../../ai-center/components/DeliveryPanel.vue'
import type { VideoTask } from '../../../types/video-production'

/**
 * 任务书 #100 C100-07：画布交付面板——复用 AI 中心交付组件 + 成片导出。
 *
 * 交付字段（标题/描述/话题/配文）只写草稿 delivery，不触发任何媒体重生成；
 * 指定版本导出绑定草稿版本（draftVersion），任务产物导出（剪映/素材包）每次
 * 点击重取新授权——签名过期重试即取新 URL，失败不生成本地文件。
 */
const props = defineProps<{
  task: VideoTask | null
  delivery: Partial<CreationDeliveryContract>
  platform: string
  draftId: string
  draftVersion: number | null
  disabled?: boolean
  downloadSubtitle: () => void
  reportError: (message: string) => void
}>()

const emit = defineEmits<{
  (e: 'update-delivery', value: Partial<CreationDeliveryContract>): void
}>()

const jianyingRange = ref(SUPPORTED_JIANYING_RANGE)
const exportLoading = ref('')

const succeeded = computed(() => props.task?.phase === 'succeeded')

async function exportArtifact(kind: 'jianying' | 'bundle'): Promise<void> {
  if (!props.task || exportLoading.value) return
  const fallback = kind === 'jianying' ? '剪映草稿导出失败' : '素材包导出失败'
  exportLoading.value = kind
  try {
    const result = await exportTaskArtifact(props.task.id, kind)
    if (kind === 'jianying' && result.supportedVersionRange) {
      jianyingRange.value = result.supportedVersionRange
    }
  } catch (err: unknown) {
    props.reportError(err instanceof Error ? err.message : fallback)
  } finally {
    exportLoading.value = ''
  }
}
</script>

<template>
  <section v-if="succeeded" class="canvas-delivery" data-test="canvas-delivery">
    <div v-if="task?.finalUrl" class="delivery-result">
      <video :src="task.finalUrl" controls playsinline preload="metadata" class="result-video" data-test="canvas-delivery-video"></video>
      <p class="field-note gl-num" data-test="canvas-delivery-meta">
        成片 {{ task.actualDurationSeconds ?? '—' }} 秒 · 实结 {{ task.actualCostCents ?? '—' }} 分（一口价按实际秒数多退少补）
      </p>
      <div class="delivery-actions">
        <a :href="task.finalUrl" download class="btn-primary gl-btn-primary" target="_blank" data-test="canvas-delivery-download">下载成片</a>
        <button type="button" class="btn-secondary" data-test="canvas-delivery-srt" @click="downloadSubtitle">下载字幕（SRT）</button>
        <button
          type="button"
          class="btn-secondary"
          :disabled="exportLoading !== ''"
          data-test="canvas-delivery-export-jianying"
          @click="exportArtifact('jianying')"
        >{{ exportLoading === 'jianying' ? '导出中…' : '导出剪映草稿' }}</button>
        <button
          type="button"
          class="btn-secondary"
          :disabled="exportLoading !== ''"
          data-test="canvas-delivery-export-bundle"
          @click="exportArtifact('bundle')"
        >{{ exportLoading === 'bundle' ? '导出中…' : '导出素材包' }}</button>
      </div>
      <p class="gl-hint jianying-hint">导出剪映草稿适配 {{ jianyingRange }}</p>
    </div>

    <DeliveryPanel
      :model-value="delivery"
      :platform="platform"
      :disabled="disabled"
      :draft-id="draftId"
      :draft-version="draftVersion ?? undefined"
      export-title="视频交付"
      @update:model-value="emit('update-delivery', $event)"
    />
  </section>
</template>

<style scoped>
.canvas-delivery {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.delivery-result {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.result-video {
  width: 100%;
  max-width: 360px;
  border-radius: var(--radius-md);
  background: var(--color-surface-strong);
}

.delivery-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text);
  font-size: var(--text-sm);
  text-decoration: none;
  cursor: pointer;
}

.btn-secondary:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--surface-hover);
}

.jianying-hint {
  margin: 0;
}

@media (max-width: 767px) {
  .btn-secondary {
    min-height: 44px;
  }
}
</style>
