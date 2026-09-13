// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { useCreationDraft } from '../../../composables/useCreationDraft'
import { queueDeliverySave } from './useCanvasDeliveryQueue'
import { useCanvasTaskRestore } from './useCanvasTaskRestore'
import type { CreationDraft } from '../../../types/creation-assistant'
import type { VideoTask } from '../../../types/video-production'

afterEach(() => vi.unstubAllGlobals())
const initial: CreationDraft = { id: 'draft', version: 1, title: '项目', sourceType: 'independent', status: 'completed',
  createdAt: '2026-09-12T00:00:00Z', updatedAt: '2026-09-12T00:00:00Z', capability: 'video', runIds: ['old-run'], resultAssetIds: [],
  workspace: { schemaVersion: 1, capability: 'video', inputs: { video: { storyboardId: 'sb', productionTaskId: 'task' }, preserved: 'input' },
    brief: { processingMode: 'create', objective: '保留任务要求' }, delivery: { version: 1, platform: 'douyin', contentForm: 'video', titleOrOpening: '旧标题' } } }
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status === 200 ? { success: true, data } : { success: false, error: data }), { status })

describe('C102 delivery queue uses confirmed draft versions', () => {
  test('queued fields and cover preserve inputs; export waits for the acknowledged version', async () => {
    const scope = effectScope(); const session = scope.run(() => useCreationDraft())!; session.adopt(structuredClone(initial))
    const queue = queueDeliverySave(() => session, () => 'draft', () => session.draft.value?.workspace?.delivery ?? {}, () => 'douyin')
    let finish!: (response: Response) => void; let sent: Record<string, unknown> = {}
    vi.stubGlobal('fetch', vi.fn((_url: unknown, init?: RequestInit) => {
      sent = JSON.parse(String(init?.body)); return new Promise<Response>(resolve => { finish = resolve })
    }))
    queue({ titleOrOpening: '新标题' }); queue({ bodyOrDescription: '新的发布描述', coverRef: { refType: 'media', id: 'cover', role: 'cover' } })
    let done = false; const exporting = queue.beforeExport().then(version => { done = true; return version })
    await flushPromises(); expect(done).toBe(false); expect(queue.state.value).toBe('saving')
    expect(sent.workspace).toMatchObject({ inputs: initial.workspace?.inputs, brief: initial.workspace?.brief,
      delivery: { titleOrOpening: '新标题', bodyOrDescription: '新的发布描述' }, resultRefs: [{ id: 'cover', role: 'cover' }] })
    finish(response({ ...initial, ...sent, version: 2 })); expect(await exporting).toBe(2)
    expect(queue.state.value).toBe('saved'); expect(session.draft.value?.runIds).toEqual(['old-run']); scope.stop()
  })
  test('failed save retains text and blocks export; explicit retry can confirm it', async () => {
    const scope = effectScope(); const session = scope.run(() => useCreationDraft())!; session.adopt(structuredClone(initial))
    const queue = queueDeliverySave(() => session, () => 'draft', () => session.draft.value?.workspace?.delivery ?? {}, () => 'douyin')
    vi.stubGlobal('fetch', vi.fn(async () => response('保存失败', 500)))
    queue({ titleOrOpening: '不能丢失' }); expect(await queue.beforeExport()).toBe(false)
    expect(queue.error.value).toContain('保存失败'); expect(session.draft.value?.workspace?.delivery?.titleOrOpening).toBe('不能丢失')
    vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) => response({ ...initial, ...JSON.parse(String(init?.body)), version: 2 })))
    expect(await queue.beforeExport()).toBe(2); scope.stop()
  })
  test('only explicit flush binds a succeeded task and its exact output identity', async () => {
    const scope = effectScope(); const session = scope.run(() => useCreationDraft())!; session.adopt(structuredClone(initial))
    const task = { id: 'task', storyboardId: 'sb', phase: 'succeeded', finalMediaId: 'master-1', srtMediaId: 'subtitle-1',
      recomposeSeq: 7, finalUrl: '/master', subtitleUrl: '/subtitle' } as VideoTask
    const restore = scope.run(() => useCanvasTaskRestore({ sessions: () => session, draftId: ref('draft'), taskId: ref('task'),
      currentTaskId: () => 'task', task: () => task, onError: vi.fn() }))!
    expect(session.draft.value?.workspace?.resultRefs).toBeUndefined()
    expect(restore.syncResultReferences()).toBe(true)
    expect(session.draft.value?.workspace?.resultRefs).toEqual([
      { id: 'master-1', refType: 'media', role: 'video', storyboardId: 'sb', productionTaskId: 'task', recomposeSeq: 7 },
      { id: 'subtitle-1', refType: 'media', role: 'subtitle', storyboardId: 'sb', productionTaskId: 'task', recomposeSeq: 7 },
    ])
    scope.stop()
  })
})
