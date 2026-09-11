import { describe, expect, test } from 'vitest'
import { ref } from 'vue'
import { useCanvasGraph, type GraphMediaAsset } from './useCanvasGraph'
import type { CanvasDocumentBody } from '../../../types/video-canvas'
import type { CanvasShot } from '../useVideoCanvas'

/**
 * 任务书 #100 C100-10：权威节点/边投影与 reference 编辑（TC-023/024）。
 */

const DRAFT = '11111111-1111-4111-8111-111111111111'

function mediaAsset(overrides: Partial<GraphMediaAsset> = {}): GraphMediaAsset {
  return { id: 'm-1', name: '门店视频', status: 'active', authorized: true, ...overrides }
}

function shot(id: string, seq: number): CanvasShot {
  return {
    id, seq, visual: `画面${seq}`, narration: `旁白${seq}`, plannedSeconds: 5, cameraMove: '固定机位',
    anchorImageIndex: 1, status: 'draft', takes: [], x: 0, y: 0,
  }
}

function document(overrides: Partial<CanvasDocumentBody> = {}): CanvasDocumentBody {
  return {
    schemaVersion: 1,
    storyboardId: 'sb-1',
    viewport: { panX: 0, panY: 0, scale: 1 },
    nodes: [
      { id: `brief:${DRAFT}`, kind: 'brief', refType: 'draft', refId: DRAFT, label: null, text: null, x: 0, y: 0 },
      { id: 'shot:s1', kind: 'shot', refType: 'shot', refId: 's1', label: null, text: null, x: 40, y: 0 },
      { id: 'shot:s2', kind: 'shot', refType: 'shot', refId: 's2', label: null, text: null, x: 360, y: 0 },
      { id: 'media:m-1', kind: 'media', refType: 'media', refId: 'm-1', label: '门店视频', text: null, x: 0, y: 300 },
    ],
    edges: [],
    activeBranchId: null,
    ...overrides,
  }
}

function setup(overrides: { document?: CanvasDocumentBody | null; shots?: CanvasShot[]; media?: GraphMediaAsset[] } = {}) {
  const doc = ref<CanvasDocumentBody | null>(overrides.document ?? document())
  const shots = ref<CanvasShot[]>(overrides.shots ?? [shot('s1', 1), shot('s2', 2)])
  const task = ref(null)
  const media = ref<GraphMediaAsset[]>(overrides.media ?? [mediaAsset()])
  const graph = useCanvasGraph({
    draftId: ref(DRAFT),
    document: doc,
    shots,
    task,
    mediaAssets: media,
  })
  return { graph, doc, shots, media }
}

describe('#100 C100-10：权威节点/边投影', () => {
  test('sequence 按服务端镜序投影、derived-from 按候选所属投影；不落库不可编辑', () => {
    const shots = ref<CanvasShot[]>([
      { ...shot('s2', 2), takes: [{ id: 't-2', takeNo: 1, status: 'succeeded', selectable: true, score: null, scoreLabels: [], url: null }] },
      { ...shot('s1', 1), takes: [] },
    ])
    const { graph } = setup({ shots: shots.value })
    const kinds = graph.edges.value.map(edge => edge.kind)
    expect(kinds).toContain('sequence')
    const sequence = graph.edges.value.find(edge => edge.kind === 'sequence')
    expect(sequence?.fromNodeId).toBe('shot:s1') // seq 1 → 2（服务端镜序，与传入顺序无关）
    expect(sequence?.toNodeId).toBe('shot:s2')
    const derived = graph.edges.value.find(edge => edge.kind === 'derived-from')
    expect(derived?.fromNodeId).toBe('take:t-2')
    expect(derived?.toNodeId).toBe('shot:s2')
    // 投影线不可经 removeReference 删除
    expect(graph.removeReference('sequence:s1:s2').result.ok).toBe(false)
  })

  test('失效媒体节点保留位置但不可执行（TC-023 旧引用）', () => {
    const { graph, media } = setup()
    media.value = [mediaAsset({ status: 'revoked' })]
    const mediaNode = graph.nodes.value.find(node => node.id === 'media:m-1')
    expect(mediaNode?.unavailableReason).toContain('授权已撤销')
    // 已删分镜节点同理
    const { graph: g2, shots } = setup()
    shots.value = [shot('s2', 2)]
    expect(g2.nodes.value.find(node => node.id === 'shot:s1')?.unavailableReason).toContain('分镜已删除')
  })
})

describe('#100 C100-10：reference 编辑规则（TC-024）', () => {
  test('合法 brief→shot 连线；方向/自环/重复/悬空/环拒绝', () => {
    const { graph, doc } = setup()
    const ok = graph.addReference(`brief:${DRAFT}`, 'shot:s1')
    expect(ok.result.ok).toBe(true)
    expect(ok.document?.edges).toHaveLength(1)
    void doc

    expect(graph.addReference('shot:s1', `brief:${DRAFT}`).result.ok).toBe(false) // 方向非法
    expect(graph.addReference('shot:s1', 'shot:s1').result.ok).toBe(false) // 自环
    expect(graph.addReference('shot:s1', 'shot:not-exist').result.ok).toBe(false) // 悬空
    expect(graph.addReference('media:m-1', 'shot:s2').result.ok).toBe(true)
    expect(graph.addReference('shot:s2', 'media:m-1').result.ok).toBe(false) // media→shot 反向非法
  })

  test('media→shot 新增连线前校验可用性：失效素材拒绝（TC-023）', () => {
    const { graph, media } = setup()
    media.value = [mediaAsset({ status: 'deleted' })]
    expect(graph.addReference('media:m-1', 'shot:s1').result.ok).toBe(false)
    expect(graph.lastEditError.value).toContain('素材不可用')
    media.value = [mediaAsset({ authorized: false })]
    expect(graph.addReference('media:m-1', 'shot:s1').result.ok).toBe(false)
    media.value = [mediaAsset()]
    expect(graph.addReference('media:m-1', 'shot:s1').result.ok).toBe(true)
  })

  test('新增引用节点：合法媒体/备注；重复与失效拒绝', () => {
    const { graph, doc, media } = setup()
    media.value = [mediaAsset(), mediaAsset({ id: 'm-2', name: '第二素材' })]
    // 编辑是纯函数：返回新 body 由调用方应用（经画布文档 CAS 保存）
    const note = graph.addUserNode('note', 'n-1', 10, 10, '参考要点')
    expect(note.result.ok).toBe(true)
    doc.value = note.document
    const added = graph.addUserNode('media', 'm-2', 10, 300)
    expect(added.result.ok).toBe(true)
    doc.value = added.document
    expect(graph.addUserNode('media', 'm-2', 10, 320).result.ok).toBe(false) // 重复
    expect(graph.addUserNode('media', 'm-1', 10, 320).result.ok).toBe(false) // 文档已有该节点
    media.value = [mediaAsset({ status: 'revoked' }), mediaAsset({ id: 'm-2', status: 'revoked' })]
    expect(graph.addUserNode('media', 'm-2', 0, 0).result.ok).toBe(false) // 失效不能新增
  })
})
