<script setup lang="ts">
import { computed, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CanvasBoard from './CanvasBoard.vue'
import DirectorPanel from './DirectorPanel.vue'
import { useVideoCanvas } from './useVideoCanvas'
import { useCanvasHistory } from './composables/useCanvasHistory'
import { useCanvasShotEditor } from './composables/useCanvasShotEditor'

/**
 * 画布式分镜导演台·专业模式（任务书 #66 C2/C3 + #100 C100-02/03）：/video-canvas?storyboard={id}。
 * 与快速模式（四步向导）同数据互切——仅前端路由，后端零感知；未保存态先提示。
 * 布局撤销/重做只覆盖节点移动（R07）；镜头编辑走每镜草稿会话（载入抑制/切镜 flush/冲突保留）。
 */
const route = useRoute()
const router = useRouter()
const emit = defineEmits<{ 'open-view': [view: 'ai-center'] }>()

const {
  storyboard, loading, error, dirty, branches, activeBranchId, visibleShots,
  loadStoryboard, saveGrouping, saveShotContent, moveShot, markDirty,
} = useVideoCanvas()

const history = useCanvasHistory()

/** 每镜草稿编辑会话（C100-03）：保存带 expectedEditVersion，409 冲突保留本地稿。 */
const editor = useCanvasShotEditor({
  loadFields: shotId => {
    const shot = storyboard.value?.shots.find(item => item.id === shotId)
    return shot
      ? {
          visual: shot.visual,
          narration: shot.narration,
          plannedSeconds: shot.plannedSeconds,
          cameraMove: shot.cameraMove,
        }
      : null
  },
  save: async (shotId, fields, expectedEditVersion) => {
    const result = await saveShotContent(shotId, fields, expectedEditVersion)
    return { ok: result.ok, conflict: result.conflict, message: result.message }
  },
  currentVersion: () => storyboard.value?.editVersion ?? null,
})

const selectedShotId = ref<string | null>(null)
const selectedShot = computed(() =>
  storyboard.value?.shots.find(shot => shot.id === selectedShotId.value) ?? null)

/** committed 分镜只读（§8.2：内容字段只读，旁边给「创建独立方案」提示）。 */
const storyboardReadonly = computed(() => storyboard.value?.status === 'committed')

const storyboardId = computed(() => {
  const value = route.query.storyboard
  return typeof value === 'string' && value.trim() ? value.trim() : ''
})

onMounted(() => {
  if (storyboardId.value) void loadStoryboard(storyboardId.value).then(() => history.clear())
  window.addEventListener('keydown', onHistoryKeydown)
})

onUnmounted(() => window.removeEventListener('keydown', onHistoryKeydown))
onDeactivated(() => window.removeEventListener('keydown', onHistoryKeydown))

/** 布局撤销/重做（§8.2）：只处理当前布局栈；输入框内的 Ctrl+Z 保持文本编辑语义。 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!target || typeof (target as HTMLElement).closest !== 'function') return false
  return !!(target as HTMLElement).closest('input,textarea,select,[contenteditable="true"]')
}

function onHistoryKeydown(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return
  if (isEditableTarget(event.target)) return
  const key = event.key.toLowerCase()
  if (key === 'z') {
    event.preventDefault()
    if (event.shiftKey) applyHistory(history.redo())
    else applyHistory(history.undo())
  } else if (key === 'y') {
    event.preventDefault()
    applyHistory(history.redo())
  }
}

function applyHistory(changes: ReturnType<typeof history.undo>): void {
  if (!changes) return
  for (const change of changes) {
    moveShot(change.shotId, change.to.x, change.to.y)
  }
}

/** 双模式互切（C3 + C100-03）：先 flush 草稿（失败停留），dirty 再确认；回快速模式同数据源。 */
async function switchToQuickMode(): Promise<void> {
  if (!(await editor.flush())) return
  if ((dirty.value || editor.state.dirty) && !window.confirm('有未保存的改动，确定切换到快速模式？未保存内容将丢失。')) return
  router.push({ name: 'video-production', query: { ...(storyboardId.value ? { storyboard: storyboardId.value } : {}) } })
}

async function goToCreationCenter(): Promise<void> {
  if (!(await editor.flush())) return
  if ((dirty.value || editor.state.dirty) && !window.confirm('有未保存的改动，确定返回创作中心？未保存内容将丢失。')) return
  emit('open-view', 'ai-center') // 共享视图双挂载（任务书 #76）：返回创作中心交给各壳路由
}

/** 选中镜头：切镜先 flush 当前草稿（失败停留原镜头、内容保留），再载入新镜头（hydration 抑制）。 */
async function onSelect(shotId: string): Promise<void> {
  if (editor.state.editingShotId === shotId) {
    selectedShotId.value = shotId
    return
  }
  if (editor.state.dirty && !(await editor.flush())) return
  editor.beginEdit(shotId)
  selectedShotId.value = shotId
}

/** 拖拽中的瞬时位置（不进历史、不触发保存）。 */
function onDragMove(shotId: string, x: number, y: number): void {
  moveShot(shotId, x, y)
}

/** 拖拽/键盘落位（一次一条历史；零位移不记录）。 */
function onMove(shotId: string, x: number, y: number, fromX: number, fromY: number): void {
  history.record([{ shotId, from: { x: fromX, y: fromY }, to: { x, y } }])
  moveShot(shotId, x, y)
}

function onSaveGrouping(grouping: Parameters<typeof saveGrouping>[0]): void {
  void saveGrouping(grouping, storyboard.value?.editVersion ?? null)
}

async function onSwitchBranch(branchId: string | null): Promise<void> {
  if (editor.state.dirty && !(await editor.flush())) return
  activeBranchId.value = branchId
  selectedShotId.value = null
  editor.beginEdit(null)
}
</script>

<template>
  <div class="video-canvas gl-field">
    <header class="canvas-header">
      <div class="canvas-title-row">
        <button class="btn-back" type="button" @click="goToCreationCenter">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回创作中心
        </button>
        <div class="canvas-title">
          <h2 class="eyebrow">专业模式</h2>
          <h3 class="card-title">分镜导演台</h3>
          <span v-if="storyboard" class="field-note gl-num">
            {{ storyboard.shots.length }} 镜 · 目标 {{ storyboard.targetDurationSeconds }}s · {{ storyboard.resolution }}
          </span>
        </div>
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
        :can-undo="history.canUndo.value"
        :can-redo="history.canRedo.value"
        @select="onSelect"
        @drag-move="onDragMove"
        @move="onMove"
        @undo="applyHistory(history.undo())"
        @redo="applyHistory(history.redo())"
      />
      <DirectorPanel
        :shot="selectedShot"
        :grouping="storyboard.grouping"
        :active-branch-id="activeBranchId"
        :dirty="dirty"
        :editor="editor"
        :readonly="storyboardReadonly"
        @edit="markDirty"
        @save-grouping="onSaveGrouping"
        @switch-branch="onSwitchBranch"
      />
    </div>
    <!-- #69 卡C：对齐卡面边界的诚实文案——合成与挑选不在画布内完成（画布内挑选属三期后续） -->
    <p v-if="storyboard" class="gl-hint canvas-compose-note" data-test="canvas-compose-note">
      合成与候选挑选在快速模式第三步完成（「切换到快速模式」会带回当前分镜）
    </p>
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
.canvas-title-row { display: flex; align-items: center; gap: var(--space-md); flex: 1; }
.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}
.btn-back:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}
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
.canvas-compose-note { text-align: right; }
</style>
