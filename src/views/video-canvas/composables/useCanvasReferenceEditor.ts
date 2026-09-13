import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type { useCanvasDocument } from './useCanvasDocument'
import type { useCanvasHistory } from './useCanvasHistory'
import type { useCanvasGraph, GraphEdit, GraphMediaAsset } from './useCanvasGraph'
import type { CanvasShot } from '../useVideoCanvas'
import { clampPosition } from '../useCanvasViewport'

/** UI operations use the same document queue; the only local copies are unsaved note text and drag positions. */
export function useCanvasReferenceEditor(options: {
  graph: ReturnType<typeof useCanvasGraph>
  session: ReturnType<typeof useCanvasDocument>
  history: ReturnType<typeof useCanvasHistory>
  selectedNodeId: Ref<string | null>
  shots: Ref<CanvasShot[]>
  readonly: () => boolean
  beforeSelect: () => Promise<boolean>
  select: (id: string) => void
}) {
  const { graph, session, history } = options
  const localError = ref('')
  const noteDrafts = ref<Record<string, string>>({})
  const positions = ref<Record<string, { x: number; y: number }>>({})
  const previewUrl = ref(''); const previewMime = ref(''); const previewLoading = ref(false)
  let previewGeneration = 0
  const selectedNode = computed(() => graph.nodes.value.find(node => node.id === options.selectedNodeId.value) ?? null)
  const noteText = computed(() => selectedNode.value ? noteDrafts.value[selectedNode.value.id] ?? selectedNode.value.text ?? '' : '')
  const invalidNote = computed(() => Object.values(noteDrafts.value).some(text => [...text].length > 1000))
  const error = computed(() => localError.value || session.error.value)
  const nodes = computed(() => graph.nodes.value.map(node => positions.value[node.id] ? { ...node, ...positions.value[node.id] } : node))
  const targets = computed(() => graph.nodes.value.filter(node => !node.unavailableReason && (node.kind === 'shot'
    || (node.kind === 'media' && selectedNode.value?.kind === 'note'))))
  const referenceEdges = computed(() => (session.document.value?.edges ?? []).filter(edge => edge.fromNodeId === options.selectedNodeId.value || edge.toNodeId === options.selectedNodeId.value))
  const parentShotId = computed(() => options.shots.value.find(shot => shot.takes.some(take => take.id === selectedNode.value?.refId))?.id ?? null)

  watch(graph.projectedDocument, next => {
    if (!next || next === session.document.value || options.readonly()) return
    if (next.nodes.length > 200) { localError.value = '画布已达 200 个节点，请移除部分参考后同步业务节点'; return }
    session.queue(next)
  }, { immediate: true, flush: 'sync' })

  function reset(): void {
    previewGeneration++; positions.value = {}; noteDrafts.value = {}; localError.value = ''
    previewUrl.value = ''; previewMime.value = ''; previewLoading.value = false
  }
  watch(() => [options.selectedNodeId.value, selectedNode.value?.refType, selectedNode.value?.refId],
    () => { previewGeneration++; previewUrl.value = ''; previewMime.value = ''; previewLoading.value = false }, { flush: 'sync' })
  watch(() => session.document.value?.storyboardId, reset)
  onScopeDispose(reset)

  function commit(edit: GraphEdit, record = true): boolean {
    if (options.readonly()) { localError.value = '项目只读，不能修改参考'; return false }
    if (!edit.result.ok || !edit.document) { localError.value = edit.result.error || '修改参考失败'; return false }
    const previous = session.document.value
    if (!previous) return false
    if (record) history.recordDocument(previous, edit.document)
    const saved = session.queue(edit.document)
    if (!saved) { localError.value = '画布尚未就绪，请稍后重试'; return false }
    localError.value = ''
    if (edit.result.nodeId) options.select(edit.result.nodeId)
    return true
  }
  async function addMedia(asset: GraphMediaAsset): Promise<boolean> {
    if (!(await options.beforeSelect())) return false
    const point = initialPoint()
    const edit = graph.addUserNode('media', asset.id, point.x, point.y, '', asset.refType ?? 'media')
    if (edit.document === session.document.value && edit.result.nodeId) {
      const node = edit.document?.nodes.find(node => node.id === edit.result.nodeId)
      if (node && edit.document) edit.document = { ...edit.document, viewport: { ...edit.document.viewport,
        panX: clampPosition(40 - node.x * edit.document.viewport.scale), panY: clampPosition(40 - node.y * edit.document.viewport.scale) } }
    }
    return commit(edit) && await flush()
  }
  async function addNote(): Promise<boolean> {
    if (!(await options.beforeSelect())) return false
    const point = initialPoint()
    return commit(graph.addUserNode('note', '', point.x, point.y, '')) && await flush()
  }
  function initialPoint(): { x: number; y: number } {
    const viewport = session.document.value?.viewport ?? { panX: 0, panY: 0, scale: 1 }
    const offset = 40 + ((session.document.value?.nodes.filter(node => node.kind === 'media' || node.kind === 'note').length ?? 0) % 4) * 24
    return { x: clampPosition((offset - viewport.panX) / viewport.scale), y: clampPosition((offset - viewport.panY) / viewport.scale) }
  }
  function editNote(text: string): void {
    const node = selectedNode.value
    if (!node || node.kind !== 'note') return
    if ([...text].length > 1000) noteDrafts.value = { ...noteDrafts.value, [node.id]: text }
    else delete noteDrafts.value[node.id]
    commit(graph.updateNote(node.id, text))
  }
  function addReference(target: string): boolean {
    return !!selectedNode.value && commit(graph.addReference(selectedNode.value.id, target))
  }
  function removeReference(id: string): boolean { return commit(graph.removeReference(id)) }
  function replaceMedia(asset: GraphMediaAsset): boolean {
    return !!selectedNode.value && commit(graph.replaceMedia(selectedNode.value.id, asset))
  }
  function removeNode(): boolean {
    const id = selectedNode.value?.id
    if (!id) return false
    delete noteDrafts.value[id]
    return commit(graph.removeNode(id))
  }
  function moveTransient(id: string, x: number, y: number): void {
    const node = session.document.value?.nodes.find(node => node.id === id)
    if (!node || options.readonly()) return
    if (x === node.x && y === node.y) delete positions.value[id]
    else positions.value = { ...positions.value, [id]: { x, y } }
  }
  function move(id: string, x: number, y: number): void {
    commit(graph.moveNode(id, x, y)); delete positions.value[id]
  }
  async function flush(): Promise<boolean> {
    if (invalidNote.value) { localError.value = '备注最多 1000 字，请修改后保存'; return false }
    return session.flush()
  }
  async function preview(): Promise<void> {
    const node = selectedNode.value
    if (!node || node.kind !== 'media' || !node.refId) return
    const ticket = ++previewGeneration; previewLoading.value = true; localError.value = ''
    try {
      const result = await request<{ downloadUrl: string; mimeType?: string; contentType?: string }>(node.refType === 'content-asset'
        ? '/api/content-assets/' + encodeURIComponent(node.refId) + '/download-url' : '/api/media/' + encodeURIComponent(node.refId))
      if (ticket !== previewGeneration) return
      if (!result.downloadUrl) throw new Error('素材暂不可预览，请重试或重新选择')
      previewUrl.value = result.downloadUrl; previewMime.value = result.mimeType || result.contentType || ''
    } catch (failure) { if (ticket === previewGeneration) localError.value = failure instanceof Error ? failure.message : '素材预览失败' }
    finally { if (ticket === previewGeneration) previewLoading.value = false }
  }
  return { nodes, selectedNode, targets, referenceEdges, parentShotId, error, invalidNote, noteText, previewUrl, previewMime, previewLoading,
    addMedia, addNote, editNote, addReference, removeReference, replaceMedia, removeNode, moveTransient, move, flush, preview, reset }
}
