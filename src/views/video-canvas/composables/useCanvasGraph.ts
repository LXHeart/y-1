import { computed, ref, type Ref } from 'vue'
import type { CanvasDocumentBody, CanvasNodeRef } from '../../../types/video-canvas'
import type { CanvasShot } from '../useVideoCanvas'
import type { VideoTask } from '../../../types/video-production'

export interface GraphMediaAsset {
  id: string
  name: string
  status: 'active' | 'deleted' | 'revoked' | 'inactive'
  authorized: boolean
  assetId?: string
  mimeType?: string | null
  refType?: 'media' | 'content-asset'
}
export interface GraphNode extends CanvasNodeRef { unavailableReason: string | null }
export interface GraphEdge { id: string; kind: 'reference' | 'sequence' | 'derived-from'; fromNodeId: string; toNodeId: string }
export interface ReferenceEditResult { ok: boolean; error?: string; nodeId?: string }
export interface GraphEdit { result: ReferenceEditResult; document: CanvasDocumentBody | null }
export interface UseCanvasGraphOptions {
  draftId: Ref<string>
  document: Ref<CanvasDocumentBody | null>
  shots: Ref<CanvasShot[]>
  task: Ref<VideoTask | null>
  mediaAssets: Ref<GraphMediaAsset[]>
  expandedShotId?: Ref<string | null>
}

export function useCanvasGraph(options: UseCanvasGraphOptions) {
  const { draftId, document, shots, task, mediaAssets } = options
  const lastEditError = ref('')
  const assetFor = (type: string, id: string) => mediaAssets.value.find(asset => type === 'content-asset'
    ? asset.assetId === id || (asset.refType === type && asset.id === id) : asset.id === id)
  const projectedDocument = computed<CanvasDocumentBody | null>(() => {
    const body = document.value
    if (!body || body.schemaVersion !== 1 || !draftId.value) return body
    const nodes = [...body.nodes]; const ids = new Set(nodes.map(node => node.id))
    const add = (node: CanvasNodeRef) => { if (!ids.has(node.id)) { nodes.push(node); ids.add(node.id) } }
    add({ id: 'brief:' + draftId.value, kind: 'brief', refType: 'draft', refId: draftId.value,
      label: '创作要求', text: null, x: 40, y: -200 })
    for (const shot of shots.value) {
      add({ id: 'shot:' + shot.id, kind: 'shot', refType: 'shot', refId: shot.id, label: null, text: null, x: shot.x, y: shot.y })
      if (!options.expandedShotId || options.expandedShotId.value === shot.id) {
        shot.takes.forEach((take, index) => add({ id: 'take:' + take.id, kind: 'take', refType: 'take', refId: take.id,
          label: '镜头 ' + shot.seq + ' · 候选 ' + take.takeNo, text: null, x: shot.x + 320, y: shot.y + (index + 1) * 200 }))
      }
    }
    if (task.value?.phase === 'succeeded' && task.value.finalUrl) {
      add({ id: 'delivery:' + draftId.value, kind: 'delivery', refType: 'draft', refId: draftId.value,
        label: '已完成的交付', text: null, x: 400, y: -200 })
    }
    return nodes.length === body.nodes.length ? body : { ...body, nodes }
  })
  const nodes = computed<GraphNode[]>(() => {
    const byShot = new Map(shots.value.map(shot => [shot.id, shot]))
    const byTake = new Map(shots.value.flatMap(shot => shot.takes.map(take => [take.id, { shot, take }] as const)))
    return (projectedDocument.value?.nodes ?? []).filter(node => node.kind !== 'take' || !options.expandedShotId
      || byTake.get(node.refId ?? '')?.shot.id === options.expandedShotId.value).map(node => {
      let unavailableReason: string | null = null
      let label = node.label
      if (node.kind === 'shot') {
        const shot = byShot.get(node.refId ?? '')
        if (!shot) unavailableReason = '分镜已删除'
        else label = '镜头 ' + shot.seq + '：' + shot.visual
      } else if (node.kind === 'take' && !byTake.has(node.refId ?? '')) unavailableReason = '候选不存在'
      else if (node.kind === 'media') {
        const asset = assetFor(node.refType, node.refId ?? '')
        if (!asset) unavailableReason = '素材不存在或已删除'
        else {
          label ||= asset.name
          if (asset.status !== 'active') unavailableReason = asset.status === 'revoked' ? '素材授权已撤销'
            : asset.status === 'inactive' ? '素材暂不可用' : '素材已删除'
          else if (!asset.authorized) unavailableReason = '素材未授权使用'
        }
      }
      return { ...node, label, unavailableReason }
    })
  })
  const edges = computed<GraphEdge[]>(() => {
    const present = new Set(nodes.value.map(node => node.id)); const result: GraphEdge[] = []
    const add = (edge: GraphEdge) => { if (present.has(edge.fromNodeId) && present.has(edge.toNodeId)) result.push(edge) }
    const ordered = [...shots.value].sort((a, b) => a.seq - b.seq)
    for (let index = 1; index < ordered.length; index++) add({ id: 'sequence:' + ordered[index - 1]!.id + ':' + ordered[index]!.id,
      kind: 'sequence', fromNodeId: 'shot:' + ordered[index - 1]!.id, toNodeId: 'shot:' + ordered[index]!.id })
    for (const shot of shots.value) for (const take of shot.takes) add({ id: 'derived:' + take.id, kind: 'derived-from',
      fromNodeId: 'take:' + take.id, toNodeId: 'shot:' + shot.id })
    if (task.value?.phase === 'succeeded') add({ id: 'derived:delivery:' + draftId.value, kind: 'derived-from',
      fromNodeId: 'brief:' + draftId.value, toNodeId: 'delivery:' + draftId.value })
    for (const edge of document.value?.edges ?? []) add(edge)
    return result
  })

  function failure(message: string): GraphEdit {
    lastEditError.value = message; return { result: { ok: false, error: message }, document: null }
  }
  function success(body: CanvasDocumentBody, nodeId?: string): GraphEdit {
    if (body.nodes.length > 200 || body.edges.length > 500) return failure('画布最多 200 个节点、500 条参考线')
    if (new TextEncoder().encode(JSON.stringify(body)).byteLength > 256 * 1024) return failure('画布文档超过 256 KiB')
    lastEditError.value = ''; return { result: { ok: true, nodeId }, document: body }
  }
  function cycle(from: string, to: string): boolean {
    const adjacency = new Map<string, string[]>()
    for (const edge of document.value?.edges ?? []) adjacency.set(edge.fromNodeId, [...(adjacency.get(edge.fromNodeId) ?? []), edge.toNodeId])
    const seen = new Set<string>(); const pending = [to]
    while (pending.length) {
      const id = pending.pop()!
      if (id === from) return true
      if (seen.has(id)) continue
      seen.add(id); pending.push(...(adjacency.get(id) ?? []))
    }
    return false
  }
  function addReference(fromNodeId: string, toNodeId: string): GraphEdit {
    const body = document.value
    if (!body) return failure('画布文档尚未就绪')
    const from = body.nodes.find(node => node.id === fromNodeId), to = body.nodes.find(node => node.id === toNodeId)
    if (!from || !to) return failure('参考线端点必须是画布内节点')
    if (fromNodeId === toNodeId) return failure('不能连接节点自身')
    if (!((to.kind === 'shot' && ['brief', 'media', 'note'].includes(from.kind)) || (to.kind === 'media' && from.kind === 'note')))
      return failure('参考线只允许创作要求、素材或备注连接镜头，或备注连接素材')
    if (body.edges.some(edge => (edge.fromNodeId === fromNodeId && edge.toNodeId === toNodeId)
      || (edge.toNodeId === fromNodeId && edge.fromNodeId === toNodeId))) return failure('两节点间已存在参考线')
    if (cycle(fromNodeId, toNodeId)) return failure('参考线不能构成环')
    if (from.kind === 'media') {
      const asset = assetFor(from.refType, from.refId ?? '')
      if (!asset || asset.status !== 'active' || !asset.authorized) return failure('素材不可用，不能新增引用')
    }
    return success({ ...body, edges: [...body.edges, { id: 'reference:' + crypto.randomUUID(), kind: 'reference', fromNodeId, toNodeId }] })
  }
  function removeReference(edgeId: string): GraphEdit {
    const body = document.value
    if (!body || !body.edges.some(edge => edge.id === edgeId)) return failure('只有用户参考线可删除')
    return success({ ...body, edges: body.edges.filter(edge => edge.id !== edgeId) })
  }
  function addUserNode(kind: 'media' | 'note', refId: string, x: number, y: number, text = '',
    refType: 'media' | 'content-asset' = 'media'): GraphEdit {
    const body = document.value
    if (!body) return failure('画布文档尚未就绪')
    let asset: GraphMediaAsset | undefined
    if (kind === 'media') {
      asset = assetFor(refType, refId)
      if (!asset || asset.status !== 'active' || !asset.authorized) return failure('素材不可用，不能添加')
      const existing = body.nodes.find(node => node.kind === 'media' && node.refType === refType && node.refId === refId)
      if (existing) return success(body, existing.id)
    } else if ([...text].length > 1000) return failure('备注最多 1000 字')
    const id = kind + ':' + crypto.randomUUID()
    const label = asset?.name && [...asset.name].length <= 60 ? asset.name : null
    return success({ ...body, nodes: [...body.nodes, { id, kind, refType: kind === 'note' ? 'note' : refType,
      refId: kind === 'media' ? refId : null, label, text: kind === 'note' ? text : null, x, y }] }, id)
  }
  function updateNote(id: string, text: string): GraphEdit {
    const body = document.value
    if (!body?.nodes.some(node => node.id === id && node.kind === 'note')) return failure('备注不存在')
    if ([...text].length > 1000) return failure('备注最多 1000 字')
    return success({ ...body, nodes: body.nodes.map(node => node.id === id ? { ...node, text } : node) }, id)
  }
  function replaceMedia(id: string, asset: GraphMediaAsset): GraphEdit {
    const body = document.value
    if (!body?.nodes.some(node => node.id === id && node.kind === 'media')) return failure('引用节点不存在')
    if (asset.status !== 'active' || !asset.authorized) return failure('素材不可用，不能替换')
    return success({ ...body, nodes: body.nodes.map(node => node.id === id ? { ...node,
      refType: asset.refType ?? 'media', refId: asset.id, label: [...asset.name].length <= 60 ? asset.name : null } : node) }, id)
  }
  function removeNode(id: string): GraphEdit {
    const body = document.value
    if (!body?.nodes.some(node => node.id === id && ['media', 'note'].includes(node.kind))) return failure('只能移除素材引用或备注')
    return success({ ...body, nodes: body.nodes.filter(node => node.id !== id),
      edges: body.edges.filter(edge => edge.fromNodeId !== id && edge.toNodeId !== id) })
  }
  function moveNode(id: string, x: number, y: number): GraphEdit {
    const body = document.value
    if (!body?.nodes.some(node => node.id === id)) return failure('节点不存在')
    if (![x, y].every(value => Number.isFinite(value) && Math.abs(value) <= 100000)) return failure('节点位置超出范围')
    return success({ ...body, nodes: body.nodes.map(node => node.id === id ? { ...node, x, y } : node) })
  }
  return { nodes, edges, projectedDocument, lastEditError, addReference, removeReference, addUserNode,
    updateNote, replaceMedia, removeNode, moveNode }
}
