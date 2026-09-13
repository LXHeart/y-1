// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useCanvasDocument } from './useCanvasDocument'
import { useCanvasHistory } from './useCanvasHistory'
import { useCanvasGraph } from './useCanvasGraph'
import { useCanvasReferenceEditor } from './useCanvasReferenceEditor'
import type { CanvasDocumentBody } from '../../../types/video-canvas'
import type { CanvasShot } from '../useVideoCanvas'

afterEach(() => vi.unstubAllGlobals())
async function fixture() {
  const scope = effectScope(); const writes: CanvasDocumentBody[] = []
  let revision = 1; let fail = false
  const original: CanvasDocumentBody = { schemaVersion: 1, storyboardId: 'sb', viewport: { panX: 0, panY: 0, scale: 1 }, activeBranchId: null, edges: [], nodes: [
    { id: 'brief:draft', kind: 'brief', refType: 'draft', refId: 'draft', label: null, text: null, x: 0, y: -200 },
    { id: 'shot:shot', kind: 'shot', refType: 'shot', refId: 'shot', label: null, text: null, x: 40, y: 40 },
  ] }
  vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) => {
    let document = original
    if (init?.method === 'PUT') {
      document = JSON.parse(String(init.body)).document; writes.push(document)
      if (fail) return new Response(JSON.stringify({ success: false, error: '素材已撤权', code: 'CANVAS_MEDIA_UNAVAILABLE' }), { status: 409 })
      revision++
    }
    return new Response(JSON.stringify({ success: true, data: { id: 'canvas', draftId: 'draft', revision, document } }))
  }))
  const session = scope.run(() => useCanvasDocument(ref('draft')))!
  await session.load()
  const selected = ref<string | null>(null)
  const shots = ref([{ id: 'shot', seq: 1, visual: '画面', takes: [], x: 40, y: 40 } as unknown as CanvasShot])
  const media = ref([{ id: 'm1', name: '第一份素材', status: 'active' as const, authorized: true }, { id: 'm2', name: '第二份素材', status: 'active' as const, authorized: true }])
  const history = scope.run(() => useCanvasHistory())!
  const graph = scope.run(() => useCanvasGraph({ draftId: ref('draft'), document: session.document, shots, task: ref(null), mediaAssets: media }))!
  const editor = scope.run(() => useCanvasReferenceEditor({ graph, session, history, selectedNodeId: selected, shots,
    readonly: () => false, beforeSelect: session.flush, select: id => { selected.value = id } }))!
  return { scope, editor, graph, session, selected, writes, history, media, fail: (value: boolean) => { fail = value } }
}
describe('C102 reference operations use the document queue', () => {
  test('replacing a media reference clears the old preview and ignores its delayed authorization response', async () => {
    const f = await fixture(); await f.editor.addMedia(f.media.value[0]!)
    let finish!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve })))
    const old = f.editor.preview()
    expect(f.editor.previewLoading.value).toBe(true)
    f.editor.replaceMedia(f.media.value[1]!)
    finish(new Response(JSON.stringify({ success: true, data: { downloadUrl: '/old-reference', mimeType: 'image/png' } })))
    await old
    expect(f.editor.previewUrl.value).toBe(''); expect(f.editor.previewLoading.value).toBe(false)
    f.scope.stop()
  })
  test('note, edge and movement persist together; transient movement makes no request and undo restores the document', async () => {
    const f = await fixture(); expect(await f.editor.addNote()).toBe(true)
    const id = f.selected.value!; f.editor.editNote('镜头参考说明'); expect(f.editor.addReference('shot:shot')).toBe(true)
    await f.editor.flush(); expect(f.session.document.value?.edges).toHaveLength(1)
    const before = f.writes.length; const x = f.editor.selectedNode.value!.x
    f.editor.moveTransient(id, 777, 40); expect(f.writes).toHaveLength(before)
    f.editor.move(id, 777, 40); await f.editor.flush(); expect(f.writes).toHaveLength(before + 1)
    expect(f.session.document.value?.nodes.find(node => node.id === id)?.x).toBe(777)
    f.history.undo(); f.session.queue(f.history.restoredDocument.value!); await f.editor.flush()
    expect(f.session.document.value?.nodes.find(node => node.id === id)?.x).toBe(x)
    expect(f.editor.noteText.value).toBe('镜头参考说明')
    f.editor.removeNode(); await f.editor.flush(); expect(f.session.document.value?.edges).toEqual([])
    f.scope.stop()
  })
  test('revoked material keeps the local node; replacement keeps identity and retries the original revision', async () => {
    const f = await fixture(); f.fail(true)
    expect(await f.editor.addMedia(f.media.value[0]!)).toBe(false)
    const nodeId = f.selected.value!
    expect(f.session.conflict.value).toBe(false); expect(f.session.saveState.value).toBe('error')
    expect(f.session.revision.value).toBe(1)
    f.fail(false); expect(f.editor.replaceMedia(f.media.value[1]!)).toBe(true); expect(await f.editor.flush()).toBe(true)
    const node = f.session.document.value?.nodes.find(node => node.id === nodeId)
    expect(node?.refId).toBe('m2'); expect(node?.id).not.toBe('media:m2')
    expect(f.session.revision.value).toBe(2); f.scope.stop()
  })
  test('an oversized note keeps its input and blocks leaving until corrected', async () => {
    const f = await fixture(); await f.editor.addNote(); f.editor.editNote('😀'.repeat(1001))
    expect(f.editor.noteText.value).toBe('😀'.repeat(1001)); expect(await f.editor.flush()).toBe(false)
    f.editor.editNote('合法备注'); expect(await f.editor.flush()).toBe(true); f.scope.stop()
  })
})
