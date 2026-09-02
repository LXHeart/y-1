import { computed, ref } from 'vue'
import { fetchApi } from '../../composables/grassland-http'

/**
 * 画布数据源（任务书 #66 卡C2/C3）：分镜只读详情 + 分组分支 + 镜头内容编辑。
 * 与快速模式同数据同源（storyboard 行是唯一真相），互切仅前端路由。
 */
export interface CanvasTake {
  id: string
  takeNo: number
  status: string
  selectable: boolean
  score: number | null
  scoreLabels: string[]
  url: string | null
}

export interface CanvasShot {
  id: string
  seq: number
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
  anchorImageIndex: number
  status: string
  takes: CanvasTake[]
  /** 画布本地态：节点坐标（不落库，会话内保持）。 */
  x: number
  y: number
}

export interface GroupingBranch {
  id: string
  name: string
  shotIds: string[]
}

export interface StoryboardGrouping {
  shots: Array<{ id: string; groupId?: string }>
  branches: GroupingBranch[]
}

export interface CanvasStoryboard {
  id: string
  targetDurationSeconds: number
  resolution: string
  status: string
  grouping: StoryboardGrouping | null
  shots: CanvasShot[]
}

const NODE_WIDTH = 232
const NODE_HEIGHT = 190
const GRID_COLUMN = 320
const GRID_ROW = 290

/** 首次布局：按镜序自左上网格排布（拖拽后坐标存内存，互切不丢）。 */
function layoutShots(shots: Array<Omit<CanvasShot, 'x' | 'y'>>): CanvasShot[] {
  return shots.map((shot, index) => ({
    ...shot,
    x: 40 + (index % 3) * GRID_COLUMN,
    y: 40 + Math.floor(index / 3) * GRID_ROW,
  }))
}

export function useVideoCanvas() {
  const storyboard = ref<CanvasStoryboard | null>(null)
  const loading = ref(false)
  const error = ref('')
  const dirty = ref(false)
  const activeBranchId = ref<string | null>(null)

  const branches = computed<GroupingBranch[]>(() => storyboard.value?.grouping?.branches ?? [])

  /** 当前分支渲染的镜头（无分组=全部；选分支=该分支序列）。 */
  const visibleShots = computed<CanvasShot[]>(() => {
    const all = storyboard.value?.shots ?? []
    if (!activeBranchId.value) return all
    const branch = branches.value.find(candidate => candidate.id === activeBranchId.value)
    if (!branch) return all
    const order = new Map(branch.shotIds.map((id, index) => [id, index]))
    return all.filter(shot => order.has(shot.id))
      .sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0))
  })

  async function loadStoryboard(id: string): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const response = await fetchApi(`/api/video-production/storyboards/${id}`)
      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '分镜加载失败')
      }
      const body = await response.json() as { success: boolean; data: Omit<CanvasStoryboard, 'shots'> & {
        shots: Array<Omit<CanvasShot, 'x' | 'y'>>
      } }
      const previous = storyboard.value
      storyboard.value = {
        ...body.data,
        // 互切/重载保位：已有坐标沿用（同 storyboard 幂等）
        shots: layoutShots(body.data.shots).map(shot => {
          const kept = previous?.shots.find(old => old.id === shot.id)
          return kept ? { ...shot, x: kept.x, y: kept.y } : shot
        }),
      }
      activeBranchId.value = null
      dirty.value = false
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '分镜加载失败'
    } finally {
      loading.value = false
    }
  }

  /** 分组与分支落库（§3 契约载荷）；成功后本地同步。 */
  async function saveGrouping(grouping: StoryboardGrouping): Promise<boolean> {
    if (!storyboard.value) return false
    try {
      const response = await fetchApi(`/api/video-production/storyboards/${storyboard.value.id}/grouping`, {
        method: 'PATCH',
        body: JSON.stringify(grouping),
      })
      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '分组保存失败')
      }
      storyboard.value = { ...storyboard.value, grouping }
      dirty.value = false
      return true
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '分组保存失败'
      return false
    }
  }

  /** 镜头属性编辑（同快速模式字段，写通服务端）。 */
  async function saveShotContent(shotId: string, patch: {
    visual?: string; narration?: string; plannedSeconds?: number; cameraMove?: string
  }): Promise<boolean> {
    try {
      const response = await fetchApi(`/api/video-production/shots/${shotId}/content`, {
        method: 'PUT',
        body: JSON.stringify(patch),
      })
      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '镜头保存失败')
      }
      const shots = storyboard.value?.shots ?? []
      const index = shots.findIndex(shot => shot.id === shotId)
      if (index >= 0 && storyboard.value) {
        storyboard.value = {
          ...storyboard.value,
          shots: shots.map((shot, position) => position === index ? { ...shot, ...patch } : shot),
        }
      }
      dirty.value = false
      return true
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '镜头保存失败'
      return false
    }
  }

  function moveShot(shotId: string, x: number, y: number): void {
    if (!storyboard.value) return
    storyboard.value = {
      ...storyboard.value,
      shots: storyboard.value.shots.map(shot =>
        shot.id === shotId ? { ...shot, x, y } : shot),
    }
  }

  function markDirty(): void {
    dirty.value = true
  }

  return {
    storyboard, loading, error, dirty, branches, activeBranchId, visibleShots,
    loadStoryboard, saveGrouping, saveShotContent, moveShot, markDirty,
  }
}

export const CANVAS_NODE_SIZE = { width: NODE_WIDTH, height: NODE_HEIGHT }
