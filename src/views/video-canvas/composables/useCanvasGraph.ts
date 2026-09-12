import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type {
  CanvasDocumentBody,
  CanvasNodeRef,
  CanvasReferenceEdge,
} from '../../../types/video-canvas'
import type { CanvasShot } from '../useVideoCanvas'
import type { VideoTask } from '../../../types/video-production'

/**
 * 任务书 #100 C100-10：权威节点/边投影与 reference 编辑。
 *
 * 业务真相投影（不可伪造）：sequence 线由服务端镜序生成、derived-from 线由候选所属
 * 生成——客户端只从权威数据推导，不落库；拖动节点只改布局，不改镜序/来源。用户可编辑的
 * 只有 reference 线（§6.3 规则：brief/media/note→shot 或 note→media；禁自环/重复端点/
 * 悬空/环），编辑结果写回独立画布文档（C100-09），保存走文档 CAS。
 *
 * 媒体可用性（TC-023）：新增引用前校验（无权/已删/未激活/撤销授权 → 拒绝并说明原因，
 * 不泄露对象信息）；已存在的历史失效节点保留位置、标记不可执行。
 */
export interface GraphMediaAsset {
  id: string
  name: string
  /** inactive=个人库行存在但未生效（审核中/驳回/过期，C100-20 内容资产状态映射）。 */
  status: 'active' | 'deleted' | 'revoked' | 'inactive'
  authorized: boolean
}

export interface GraphNode extends CanvasNodeRef {
  /** 业务实体缺位（媒体失效/分镜已删）时的不可执行态与原因。 */
  unavailableReason: string | null
}

export interface GraphEdge {
  id: string
  kind: 'reference' | 'sequence' | 'derived-from'
  fromNodeId: string
  toNodeId: string
}

export interface ReferenceEditResult {
  ok: boolean
  error?: string
}

export interface UseCanvasGraphOptions {
  draftId: Ref<string>
  /** 独立画布文档的业务体（C100-09 会话权威）；null=未升级。 */
  document: Ref<CanvasDocumentBody | null>
  /** 权威镜头（含任务会话 takes 合并后的 liveShots）。 */
  shots: Ref<CanvasShot[]>
  /** 任务会话态（候选/交付）；null=无任务。 */
  task: Ref<VideoTask | null>
  /** 账号可用媒体（素材轨拉取；引用新增前校验）。 */
  mediaAssets: Ref<GraphMediaAsset[]>
}

export function useCanvasGraph(options: UseCanvasGraphOptions) {
  const { draftId, document, shots, task, mediaAssets } = options

  /** reference 编辑错误（最近一次）；UI 就地展示。 */
  const lastEditError = ref('')

  /** 文档内用户节点（media/note）+ 业务节点的可用性归并。 */
  const nodes: ComputedRef<GraphNode[]> = computed(() => {
    const docNodes = document.value?.nodes ?? []
    const shotIds = new Set(shots.value.map(shot => shot.id))
    const takeShotByTakeId = new Map<string, string>()
    for (const shot of shots.value) {
      for (const take of shot.takes) takeShotByTakeId.set(take.id, shot.id)
    }
    const mediaById = new Map(mediaAssets.value.map(asset => [asset.id, asset]))
    return docNodes.map(node => {
      let unavailableReason: string | null = null
      if (node.kind === 'shot' && node.refId && !shotIds.has(node.refId)) {
        unavailableReason = '分镜已删除'
      } else if (node.kind === 'take' && node.refId && !takeShotByTakeId.has(node.refId)) {
        unavailableReason = '候选不存在'
      } else if (node.kind === 'media' && node.refId) {
        const asset = mediaById.get(node.refId)
        if (!asset) {
          unavailableReason = '素材不存在或已删除'
        } else if (asset.status !== 'active') {
          unavailableReason = asset.status === 'revoked' ? '素材授权已撤销'
            : asset.status === 'inactive' ? '素材暂不可用' : '素材已删除'
        } else if (!asset.authorized) {
          unavailableReason = '素材未授权使用'
        }
      }
      return { ...node, unavailableReason }
    })
  })

  /** 权威投影 + 用户 reference：sequence 按镜序、derived-from 按候选所属。 */
  const edges: ComputedRef<GraphEdge[]> = computed(() => {
    const result: GraphEdge[] = []
    const ordered = [...shots.value].sort((a, b) => a.seq - b.seq)
    for (let index = 1; index < ordered.length; index += 1) {
      result.push({
        id: `sequence:${ordered[index - 1].id}:${ordered[index].id}`,
        kind: 'sequence',
        fromNodeId: `shot:${ordered[index - 1].id}`,
        toNodeId: `shot:${ordered[index].id}`,
      })
    }
    for (const shot of shots.value) {
      for (const take of shot.takes) {
        result.push({
          id: `derived:${take.id}`,
          kind: 'derived-from',
          fromNodeId: `take:${take.id}`,
          toNodeId: `shot:${shot.id}`,
        })
      }
    }
    if (task.value?.phase === 'succeeded') {
      result.push({
        id: `derived:delivery:${draftId.value}`,
        kind: 'derived-from',
        fromNodeId: `delivery:${draftId.value}`,
        toNodeId: `brief:${draftId.value}`,
      })
    }
    for (const edge of document.value?.edges ?? []) {
      result.push({ ...edge, kind: 'reference' as const })
    }
    return result
  })

  function wouldCycle(from: string, to: string, referenceEdges: CanvasReferenceEdge[]): boolean {
    const adjacency = new Map<string, string[]>()
    for (const edge of referenceEdges) {
      adjacency.set(edge.fromNodeId, [...(adjacency.get(edge.fromNodeId) ?? []), edge.toNodeId])
    }
    adjacency.set(from, [...(adjacency.get(from) ?? []), to])
    const state = new Map<string, number>()
    const visit = (node: string): boolean => {
      state.set(node, 1)
      for (const next of adjacency.get(node) ?? []) {
        if (state.get(next) === 1) return true
        if ((state.get(next) ?? 0) === 0 && visit(next)) return true
      }
      state.set(node, 2)
      return false
    }
    for (const node of adjacency.keys()) {
      if ((state.get(node) ?? 0) === 0 && visit(node)) return true
    }
    return false
  }

  /**
   * 新增 reference 线（TC-024 规则同服务端口径）：仅 brief/media/note→shot 或 note→media；
   * 禁自环、重复端点、悬空、环；media 起点必须可用（TC-023 新增校验——失效素材不能新连线）。
   * 成功返回新的 document body（调用方经画布文档 CAS 保存）。
   */
  function addReference(fromNodeId: string, toNodeId: string):
    { result: ReferenceEditResult; document: CanvasDocumentBody | null } {
    lastEditError.value = ''
    const body = document.value
    if (!body) {
      lastEditError.value = '画布文档尚未就绪'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    const from = body.nodes.find(node => node.id === fromNodeId)
    const to = body.nodes.find(node => node.id === toNodeId)
    if (!from || !to) {
      lastEditError.value = '参考线端点必须是画布内节点'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    if (fromNodeId === toNodeId) {
      lastEditError.value = '不能连接节点自身'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    const directionOk = (to.kind === 'shot' && ['brief', 'media', 'note'].includes(from.kind))
      || (to.kind === 'media' && from.kind === 'note')
    if (!directionOk) {
      lastEditError.value = '参考线只允许 brief/media/note→shot 或 note→media'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    const pairKey = fromNodeId < toNodeId ? `${fromNodeId}|${toNodeId}` : `${toNodeId}|${fromNodeId}`
    const duplicate = body.edges.some(edge =>
      (edge.fromNodeId < edge.toNodeId
        ? `${edge.fromNodeId}|${edge.toNodeId}` : `${edge.toNodeId}|${edge.fromNodeId}`) === pairKey)
    if (duplicate) {
      lastEditError.value = '两节点间已存在参考线'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    if (wouldCycle(fromNodeId, toNodeId, body.edges)) {
      lastEditError.value = '参考线不能构成环'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    if (from.kind === 'media' && from.refId) {
      const asset = mediaAssets.value.find(candidate => candidate.id === from.refId)
      if (!asset || asset.status !== 'active' || !asset.authorized) {
        lastEditError.value = '素材不可用（不存在/已删除/未授权），不能新增引用'
        return { result: { ok: false, error: lastEditError.value }, document: null }
      }
    }
    const next: CanvasDocumentBody = {
      ...body,
      edges: [...body.edges, {
        id: `reference:${fromNodeId}:${toNodeId}`,
        kind: 'reference',
        fromNodeId,
        toNodeId,
      }],
    }
    return { result: { ok: true }, document: next }
  }

  /** 删除 reference 线（只允许删 reference；sequence/derived 是权威投影）。 */
  function removeReference(edgeId: string):
    { result: ReferenceEditResult; document: CanvasDocumentBody | null } {
    lastEditError.value = ''
    const body = document.value
    if (!body) {
      return { result: { ok: false, error: '画布文档尚未就绪' }, document: null }
    }
    if (!body.edges.some(edge => edge.id === edgeId)) {
      return { result: { ok: false, error: '只有用户参考线可删除' }, document: null }
    }
    return {
      result: { ok: true },
      document: { ...body, edges: body.edges.filter(edge => edge.id !== edgeId) },
    }
  }

  /** 素材轨新增引用节点（media:{uuid}；note:{uuid} 备注节点同理）。 */
  function addUserNode(kind: 'media' | 'note', refId: string, x: number, y: number,
      text?: string): { result: ReferenceEditResult; document: CanvasDocumentBody | null } {
    lastEditError.value = ''
    const body = document.value
    if (!body) {
      return { result: { ok: false, error: '画布文档尚未就绪' }, document: null }
    }
    if (kind === 'media') {
      const asset = mediaAssets.value.find(candidate => candidate.id === refId)
      if (!asset || asset.status !== 'active' || !asset.authorized) {
        lastEditError.value = '素材不可用（不存在/已删除/未授权），不能添加'
        return { result: { ok: false, error: lastEditError.value }, document: null }
      }
    }
    const id = kind === 'media' ? `media:${refId}` : `note:${refId}`
    if (body.nodes.some(node => node.id === id)) {
      lastEditError.value = '该节点已在画布上'
      return { result: { ok: false, error: lastEditError.value }, document: null }
    }
    const node: CanvasNodeRef = {
      id,
      kind,
      refType: kind,
      refId: kind === 'media' ? refId : null,
      label: kind === 'media' ? (mediaAssets.value.find(a => a.id === refId)?.name ?? null) : null,
      text: kind === 'note' ? (text ?? '') : null,
      x,
      y,
    }
    return { result: { ok: true }, document: { ...body, nodes: [...body.nodes, node] } }
  }

  return {
    nodes,
    edges,
    lastEditError,
    addReference,
    removeReference,
    addUserNode,
  }
}
