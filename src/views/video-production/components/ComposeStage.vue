<script setup lang="ts">
import { ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type { VideoTask } from '../../../composables/useVideoProduction'

/**
 * 第四步：合成成片（任务书 #91 V2 自 VideoProductionView.vue 模板 372–424 整段迁入，纯搬运）。
 * exportArtifact 及其 fetch（视图唯一直接 request）随迁；task/taskError 留视图 props 下传，
 * 导出错误经 reportError 回写视图的 taskError。
 */
const props = defineProps<{
  task: VideoTask | null
  taskError: string
  goBackToStoryboard: () => void
  handleResetAll: () => void
  downloadSubtitle: () => void
  reportError: (message: string) => void
}>()

/**
 * 剪映草稿适配区间（任务书 #69 卡A）：先按与后端 JianyingDraftBuilder.SUPPORTED_JIANYING_RANGE
 * 同步的常量展示，导出成功后以响应值（响应体 supportedVersionRange，与响应头同源）为准覆盖。
 */
const SUPPORTED_JIANYING_RANGE = '剪映专业版 6.0 – 6.9'
const jianyingRange = ref(SUPPORTED_JIANYING_RANGE)
const exportLoading = ref('')

/** 合成完成后导出剪映草稿 / 通用素材包（#66 双轨端点，#69 卡A 前端入口）。 */
async function exportArtifact(kind: 'jianying' | 'bundle'): Promise<void> {
  if (!props.task || exportLoading.value) return
  const fallback = kind === 'jianying' ? '剪映草稿导出失败' : '素材包导出失败'
  exportLoading.value = kind
  try {
    const body = await request<{ downloadUrl: string; supportedVersionRange?: string }>(
      `/api/video-production/tasks/${props.task.id}/export/${kind}`, {},
      { fallbackError: fallback })
    if (kind === 'jianying' && body?.supportedVersionRange) {
      jianyingRange.value = body.supportedVersionRange
    }
    if (body?.downloadUrl) {
      window.open(body.downloadUrl, '_blank', 'noopener')
    }
  } catch (err: unknown) {
    props.reportError(err instanceof Error ? err.message : fallback)
  } finally {
    exportLoading.value = ''
  }
}
</script>

<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <p class="eyebrow">第四步</p>
      <h2 class="card-title">合成成片</h2>
    </header>

    <div v-if="task && task.phase === 'composing'" class="progress-area">
      <div class="progress-bar-track">
        <div class="progress-bar-fill" :style="{ width: task.progress + '%' }"></div>
      </div>
      <p class="field-note">{{ task.progress }}% — 正在合成成片（拼接/字幕/BGM）…</p>
    </div>

    <div v-else-if="task && task.phase === 'succeeded' && task.finalUrl" class="result-area">
      <video :src="task.finalUrl" controls class="result-video"></video>
      <p class="field-note">
        成片 {{ task.actualDurationSeconds }} 秒 · 预估 {{ task.estimatedCostCents }} 分 · 实结
        {{ task.actualCostCents }} 分（一口价按实际秒数多退少补）
      </p>
      <div class="action-row">
        <a :href="task.finalUrl" download class="btn-primary gl-btn-primary" target="_blank">下载成片</a>
        <button type="button" class="btn-secondary" data-test="download-srt" @click="downloadSubtitle">下载字幕（SRT）</button>
        <button
          type="button"
          class="btn-secondary"
          :disabled="exportLoading !== ''"
          data-test="export-jianying"
          @click="exportArtifact('jianying')"
        >
          {{ exportLoading === 'jianying' ? '导出中…' : '导出剪映草稿' }}
        </button>
        <button
          type="button"
          class="btn-secondary"
          :disabled="exportLoading !== ''"
          data-test="export-bundle"
          @click="exportArtifact('bundle')"
        >
          {{ exportLoading === 'bundle' ? '导出中…' : '导出素材包' }}
        </button>
        <button class="btn-secondary" @click="handleResetAll">新建视频</button>
      </div>
      <p class="gl-hint export-hint" data-test="jianying-range-hint">导出剪映草稿适配 {{ jianyingRange }}</p>
    </div>

    <div v-else-if="task" class="result-area">
      <p class="error-hint">{{ task.errorMessage || taskError || '成片未就绪' }}</p>
      <div class="action-row">
        <button class="btn-secondary" @click="goBackToStoryboard">返回分镜</button>
        <button class="btn-secondary" @click="handleResetAll">新建视频</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ===== 自 VideoProductionView.vue 随迁 ===== */
.progress-area {
  margin-bottom: var(--space-md);
}

.progress-bar-track {
  height: 6px;
  border-radius: var(--radius-xs);
  background: var(--color-border-hover);
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-bar-fill {
  height: 100%;
  border-radius: var(--radius-xs);
  background: var(--color-accent);
  transition: width 0.3s ease;
}

.result-area {
  text-align: center;
}

.result-video {
  width: 100%;
  max-width: 480px;
  border-radius: var(--radius-md);
  margin-bottom: var(--space-md);
}

.error-hint {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: var(--space-sm);
}

.action-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  text-decoration: none;
}

.card-head {
  margin-bottom: var(--space-md);
}

.eyebrow {
  font-size: 12px;
  color: var(--color-accent);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}

.field-note {
  font-size: 13px;
  color: var(--color-text-muted);
}

.fade-in {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

/* #69 卡A：剪映适配区间提示（.gl-hint 全局类之上的间距） */
.export-hint { margin-top: var(--space-xs); }
</style>
