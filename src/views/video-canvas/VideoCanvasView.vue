<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CanvasBoard from './CanvasBoard.vue'
import DirectorPanel from './DirectorPanel.vue'
import { useVideoCanvas } from './useVideoCanvas'

/**
 * 画布式分镜导演台·专业模式（任务书 #66 C2/C3）：/video-canvas?storyboard={id}。
 * 与快速模式（四步向导）同数据互切——仅前端路由，后端零感知；未保存态先提示。
 * 合成/挑选流程仍回快速模式第 3 步完成（卡面边界）。
 */
const route = useRoute()
const router = useRouter()

const {
  storyboard, loading, error, dirty, branches, activeBranchId, visibleShots,
  loadStoryboard, saveGrouping, saveShotContent, moveShot, markDirty,
} = useVideoCanvas()

const selectedShotId = ref<string | null>(null)
const selectedShot = computed(() =>
  storyboard.value?.shots.find(shot => shot.id === selectedShotId.value) ?? null)

const storyboardId = computed(() => {
  const value = route.query.storyboard
  return typeof value === 'string' && value.trim() ? value.trim() : ''
})

onMounted(() => {
  if (storyboardId.value) void loadStoryboard(storyboardId.value)
})

/** 双模式互切（C3）：dirty 先确认；回快速模式同 storyboard 数据源。 */
function switchToQuickMode(): void {
  if (dirty.value && !window.confirm('有未保存的改动，确定切换到快速模式？未保存内容将丢失。')) return
  router.push({ name: 'video-production', query: { ...(storyboardId.value ? { storyboard: storyboardId.value } : {}) } })
}

function onSelect(shotId: string): void {
  selectedShotId.value = shotId
}

function onMove(shotId: string, x: number, y: number): void {
  moveShot(shotId, x, y)
}

function onSaveShot(shotId: string, patch: {
  visual?: string; narration?: string; plannedSeconds?: number; cameraMove?: string
}): void {
  void saveShotContent(shotId, patch)
}

function onSaveGrouping(grouping: Parameters<typeof saveGrouping>[0]): void {
  void saveGrouping(grouping)
}

function onSwitchBranch(branchId: string | null): void {
  activeBranchId.value = branchId
  selectedShotId.value = null
}
</script>

<template>
  <div class="video-canvas gl-field">
    <header class="canvas-header">
      <div class="canvas-title">
        <h2 class="eyebrow">专业模式</h2>
        <h3 class="card-title">分镜导演台</h3>
        <span v-if="storyboard" class="field-note gl-num">
          {{ storyboard.shots.length }} 镜 · 目标 {{ storyboard.targetDurationSeconds }}s · {{ storyboard.resolution }}
        </span>
      </div>
      <div class="canvas-header-actions">
        <span v-if="dirty" class="badge badge-warning" data-test="canvas-dirty-badge">未保存</span>
        <button type="button" class="gl-btn-primary" data-test="switch-quick-mode" @click="switchToQuickMode">
          切换到快速模式
        </button>
      </div>
    </header>

    <p v-if="!storyboardId" class="canvas-empty" data-test="canvas-missing-id">
      缺少 storyboard 参数——请从快速模式的分镜步骤进入专业模式。
    </p>
    <p v-else-if="loading" class="canvas-empty" data-test="canvas-loading">分镜加载中…</p>
    <p v-else-if="error" class="canvas-empty" data-test="canvas-error">{{ error }}</p>

    <div v-else-if="storyboard" class="canvas-main">
      <CanvasBoard
        :shots="visibleShots"
        :selected-shot-id="selectedShotId"
        :branches="branches"
        :active-branch-id="activeBranchId"
        @select="onSelect"
        @move="onMove"
      />
      <DirectorPanel
        :shot="selectedShot"
        :grouping="storyboard.grouping"
        :active-branch-id="activeBranchId"
        :dirty="dirty"
        @edit="markDirty"
        @save-shot="onSaveShot"
        @save-grouping="onSaveGrouping"
        @switch-branch="onSwitchBranch"
      />
    </div>
  </div>
</template>

<style scoped>
.video-canvas {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  min-height: 0;
  height: 100%;
}
.canvas-header { display: flex; align-items: flex-end; gap: var(--space-md); }
.canvas-title { display: flex; align-items: baseline; gap: var(--space-md); }
.canvas-header-actions { margin-left: auto; display: flex; align-items: center; gap: var(--space-sm); }
.canvas-main { display: flex; gap: var(--space-md); flex: 1; min-height: 0; }
.canvas-empty { color: var(--color-text-secondary); padding: var(--space-xl); text-align: center; }
.eyebrow {
  font-size: var(--text-xs);
  color: var(--color-accent-2);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin: 0;
}
.card-title { font-size: var(--text-lg, 1.1rem); margin: 0; font-weight: 600; }
</style>
