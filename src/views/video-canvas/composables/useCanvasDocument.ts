import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type {
  CanvasDocument,
  CanvasDocumentBody,
  CanvasNodeRef,
  CanvasViewport,
  VideoCanvasLayout,
} from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-09：独立画布文档会话（API-08/09 / §7.3 布局升级）。
 *
 * 载入/保存/一次性旧布局升级：
 *  - GET 返回 null 才允许以旧轻量布局构建初始 document 并 expectedRevision=0 创建；
 *    创建成功后独立文档成为布局权威（新客户端不再双写两份布局）。
 *  - 并发首建冲突（409）读取胜出版本采纳，不覆盖对方。
 *  - 保存走 revision CAS；409 置 conflict 并保留本地输入，可显式「载入最新」；
 *    网络失败不伪造成功、不回落覆盖旧状态。
 *  - 未知 schemaVersion 只读（保存禁用），仍可安全展示基础信息。
 */
export interface UseCanvasDocumentOptions {
  /** 旧草稿无 videoCanvas 时的确定性布局口径（服务端镜序网格，与画布首次排布一致）。 */
  fallbackShots?: () => Array<{ id: string }>
}

const GRID_COLUMN = 320
const GRID_ROW = 290

export function useCanvasDocument(draftId: Ref<string>, options: UseCanvasDocumentOptions = {}) {
  const document = ref<CanvasDocumentBody | null>(null)
  /** 当前已确认 revision；0 = 尚无独立文档（未升级/首建前）。 */
  const revision = ref(0)
  const loading = ref(false)
  const error = ref('')
  const conflict = ref(false)
  const upgrading = ref(false)
  const readOnly = computed(() => document.value !== null && document.value.schemaVersion !== 1)

  function adoptRemote(remote: CanvasDocument): void {
    document.value = remote.document
    revision.value = remote.revision
    conflict.value = false
  }

  /** GET：无文档返回 false；失败只置 error，不动既有状态（§7.3 不回退覆盖）。 */
  async function load(): Promise<boolean> {
    if (!draftId.value) return false
    loading.value = true
    error.value = ''
    try {
      const remote = await request<CanvasDocument | null>(
        `/api/creation-drafts/${encodeURIComponent(draftId.value)}/canvas`)
      if (!remote) {
        // 远端无文档：保留本地未升级状态，不清理（调用方决定是否 upgradeFromLegacy）
        return false
      }
      adoptRemote(remote)
      return true
    } catch {
      error.value = '画布读取失败，已保留当前内容'
      return false
    } finally {
      loading.value = false
    }
  }

  /** 旧轻量布局 → 初始 document（shot 节点 + 视口 + 分支）。仅在无独立文档时可用。 */
  function buildInitialBody(legacy: VideoCanvasLayout | null): CanvasDocumentBody {
    const shots = options.fallbackShots?.() ?? []
    const positions = legacy?.positions ?? {}
    const viewport: CanvasViewport = legacy?.viewport ?? { panX: 0, panY: 0, scale: 1 }
    const nodes: CanvasNodeRef[] = shots.map((shot, index) => {
      const fallback = { x: 40 + (index % 3) * GRID_COLUMN, y: 40 + Math.floor(index / 3) * GRID_ROW }
      const position = positions[shot.id] ?? fallback
      return {
        id: `shot:${shot.id}`,
        kind: 'shot' as const,
        refType: 'shot' as const,
        refId: shot.id,
        label: null,
        text: null,
        x: position.x,
        y: position.y,
      }
    })
    return {
      schemaVersion: 1,
      storyboardId: legacy?.storyboardId ?? '',
      viewport,
      nodes,
      edges: [],
      activeBranchId: legacy?.activeBranchId ?? null,
    }
  }

  /** 一次性升级（§7.3）：仅 GET 为 null 时执行；409 读胜出版本，失败不回落覆盖。 */
  async function upgradeFromLegacy(legacy: VideoCanvasLayout | null): Promise<boolean> {
    if (!draftId.value || upgrading.value) return false
    if (document.value !== null || revision.value > 0) return false
    upgrading.value = true
    error.value = ''
    try {
      await load()
      if (revision.value > 0) return true
      const created = await put(0, buildInitialBody(legacy))
      adoptRemote(created)
      return true
    } catch (err) {
      // 首建冲突：并发标签页已升级——读取胜出版本采纳，不覆盖对方
      const winner = await load()
      if (winner) return true
      error.value = err instanceof Error ? err.message : '画布升级失败，已保留轻量布局'
      return false
    } finally {
      upgrading.value = false
    }
  }

  async function put(expectedRevision: number, body: CanvasDocumentBody): Promise<CanvasDocument> {
    return request<CanvasDocument>(`/api/creation-drafts/${encodeURIComponent(draftId.value)}/canvas`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedRevision, document: body }),
    })
  }

  /** CAS 保存：成功采纳新 revision；409 置 conflict 保留本地；失败置 error 保留本地。 */
  async function save(next: CanvasDocumentBody): Promise<boolean> {
    if (!draftId.value || readOnly.value) return false
    error.value = ''
    try {
      const saved = await put(revision.value, next)
      adoptRemote(saved)
      return true
    } catch (err) {
      const status = (err as { status?: number }).status
      if (status === 409) {
        conflict.value = true
        error.value = '画布已在其他窗口更新，已保留本地修改'
      } else {
        error.value = err instanceof Error ? err.message : '画布保存失败，已保留本地修改'
      }
      document.value = next
      return false
    }
  }

  return {
    document,
    revision,
    loading,
    error,
    conflict,
    upgrading,
    readOnly,
    load,
    save,
    upgradeFromLegacy,
    /** 409 后显式采纳远端最新（调用方确认放弃本地）。 */
    adoptLatest: load,
  }
}
