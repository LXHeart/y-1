<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CreationDeliveryContract } from '../../../types/creation'
import { SUPPORTED_JIANYING_RANGE, exportTaskArtifact } from '../../video-production/components/ComposeStage.vue'
import DeliveryPanel from '../../ai-center/components/DeliveryPanel.vue'
import type { VideoTask } from '../../../types/video-production'
import type { PersonalMediaAsset } from '../composables/usePersonalMediaLibrary'

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
  saving?: boolean
  saveState?: string
  saveError?: string
  beforeExport?: () => Promise<number | false>
  coverOptions?: PersonalMediaAsset[]
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
    const task = props.task
    if (props.beforeExport && await props.beforeExport() === false) throw new Error('修改尚未保存，未开始导出')
    if (props.task?.id !== task.id || props.task.recomposeSeq !== task.recomposeSeq) throw new Error('成片已变化，请重新确认后导出')
    const result = await exportTaskArtifact(task.id, kind, task.recomposeSeq)
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
    <p v-if="saveState" class="field-note" role="status" data-test="canvas-delivery-save-state">
      {{ saveState === 'saving' ? '交付保存中…' : saveState === 'pending' ? '交付有待保存修改' : saveState === 'saved' ? '交付已保存' : '' }}
    </p>
    <p v-if="saveError" class="field-note" role="alert" data-test="canvas-delivery-save-error">{{ saveError }}</p>
    <div v-if="task?.finalUrl" class="delivery-result">
      <video :src="task.finalUrl" controls playsinline preload="none" class="result-video" data-test="canvas-delivery-video"></video>
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

    <label v-if="coverOptions" class="gl-form-field">
      <span class="field-label">交付封面</span>
      <select :value="delivery.coverRef?.id ?? ''" :disabled="disabled || saving" data-test="canvas-delivery-cover"
        @change="emit('update-delivery', { coverRef: ($event.target as HTMLSelectElement).value ? { id: ($event.target as HTMLSelectElement).value, refType: 'media', role: 'cover' } : undefined })">
        <option value="">暂不选择封面</option>
        <option v-for="asset in coverOptions.filter(item => item.status === 'active' && item.mimeType?.startsWith('image/'))" :key="asset.mediaId" :value="asset.mediaId">{{ asset.title }}</option>
      </select>
    </label>
    <DeliveryPanel
      :model-value="delivery"
      :platform="platform"
      :disabled="disabled || saving"
      :draft-id="draftId"
      :draft-version="draftVersion ?? undefined"
      :before-export="beforeExport"
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
  min-height: var(--control-height);
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
    min-height: var(--touch-target);
  }
}
</style>
