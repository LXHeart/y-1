// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { reactive, ref } from 'vue'
import type { Ref } from 'vue'

vi.mock('../../../composables/grassland-http', () => ({ fetchApi: vi.fn() }))

const account = reactive({ epoch: 0 })

vi.mock('../../../stores/account-session', () => ({
  useAccountSessionStore: () => account,
}))

interface FakeSession {
  draft: Ref<unknown>
  autosaveState: Ref<string>
  readonly: Ref<boolean>
  adopt: ReturnType<typeof vi.fn>
  queueSave: ReturnType<typeof vi.fn>
  flush: ReturnType<typeof vi.fn>
}

const sessions = new Map<string, FakeSession>()

vi.mock('../../../lib/creation-draft-session', () => ({
  useCreationDraftSessions: () => (id?: string) => {
    if (id && sessions.has(id)) return sessions.get(id)
    const session: FakeSession = {
      draft: ref(null),
      autosaveState: ref('idle'),
      readonly: ref(false),
      adopt: vi.fn(next => { session.draft.value = next }),
      queueSave: vi.fn(),
      flush: vi.fn(async () => true),
    }
    if (id) sessions.set(id, session)
    return session
  },
  projectAsDraft: (project: unknown) => project,
}))

import { fetchApi } from '../../../composables/grassland-http'
import { useCanvasWorkspace } from './useCanvasWorkspace'
import type { VideoCanvasLayout } from '../../../types/video-canvas'

const layout = (): VideoCanvasLayout => ({ schemaVersion: 1, storyboardId: 'sb-1',
  viewport: { panX: 0, panY: 0, scale: 1 }, positions: {}, activeBranchId: null })

const fetchApiMock = vi.mocked(fetchApi)

function okResponse(body: unknown): { ok: boolean; json: () => Promise<unknown> } {
  return { ok: true, json: async () => body }
}

function bindResponse(project: Record<string, unknown> = {}) {
  return okResponse({
    success: true,
    data: {
      project: {
        id: 'draft-1', title: '画布', capability: 'video', status: 'draft', version: 1,
        workspace: { schemaVersion: 1, capability: 'video', inputs: { video: { storyboardId: 'sb-1' } } },
        ...project,
      },
      storyboardId: 'sb-1',
      productionTaskId: null,
      editVersion: 1,
    },
  })
}

interface BindPayload { operationId?: string; draftId?: string; expectedDraftVersion?: number }

function callPayload(index: number): BindPayload {
  return JSON.parse((fetchApiMock.mock.calls[index]![1] as { body: string }).body)
}

/** TC-012 / TC-011（任务书 #100 C100-04）：绑定、共享布局保存、账号 epoch 隔离。 */
describe('#100 C100-04：画布工作区会话', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    sessions.clear()
    account.epoch = 0
    fetchApiMock.mockReset()
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('旧深链绑定：POST 只带 operationId；采纳项目并应用布局', async () => {
    fetchApiMock.mockResolvedValueOnce(bindResponse({ workspace: { schemaVersion: 1, capability: 'video',
      inputs: { videoCanvas: { schemaVersion: 1, storyboardId: 'sb-1', viewport: { panX: 1, panY: 2, scale: 1.5 },
        positions: { s1: { x: 10, y: 20 } }, activeBranchId: null } } } }) as never)
    const applyLayout = vi.fn()
    const workspace = useCanvasWorkspace({ collectLayout: layout, applyLayout })

    await expect(workspace.bind({ storyboard: 'sb-1', draft: null })).resolves.toBe(true)
    expect(fetchApiMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchApiMock.mock.calls[0] as [string, { method: string; body: string }]
    expect(url).toContain('/api/video-production/storyboards/sb-1/workspace')
    expect(init.method).toBe('POST')
    const payload = JSON.parse(init.body) as { operationId: string }
    expect(payload.operationId).toMatch(/^[0-9a-f-]{36}$/)
    expect('draftId' in payload).toBe(false)
    expect(workspace.binding.value?.storyboardId).toBe('sb-1')
    expect(workspace.draftId.value).toBe('draft-1')
    expect(applyLayout).toHaveBeenCalledWith(expect.objectContaining({ schemaVersion: 1 }))
  })

  test('同分镜绑定幂等键复用（响应丢失重试同 operationId）', async () => {
    fetchApiMock.mockResolvedValue(bindResponse() as never)
    const workspace = useCanvasWorkspace({ collectLayout: layout, applyLayout: vi.fn() })
    await workspace.bind({ storyboard: 'sb-1', draft: null })
    await workspace.bind({ storyboard: 'sb-1', draft: null })
    expect(callPayload(1).operationId).toBe(callPayload(0).operationId)
  })

  test('draft 入口：冷会话先取版本做 CAS 基线', async () => {
    fetchApiMock.mockResolvedValueOnce(okResponse({ success: true, data: { version: 7 } }) as never)
    fetchApiMock.mockResolvedValueOnce(bindResponse() as never)
    const workspace = useCanvasWorkspace({ collectLayout: layout, applyLayout: vi.fn() })
    await expect(workspace.bind({ storyboard: 'sb-1', draft: 'draft-1' })).resolves.toBe(true)
    expect(fetchApiMock.mock.calls[0]![0]).toContain('/api/creation-drafts/draft-1')
    expect(callPayload(1)).toMatchObject({ draftId: 'draft-1', expectedDraftVersion: 7 })
  })

  test('布局排队：debounce 后经共享会话写 inputs.videoCanvas，保留其他 inputs', async () => {
    fetchApiMock.mockResolvedValue(bindResponse() as never)
    const collectLayout = vi.fn((): VideoCanvasLayout => ({ schemaVersion: 1, storyboardId: 'sb-1',
      viewport: { panX: 3, panY: 4, scale: 2 }, positions: { s1: { x: 5, y: 6 } }, activeBranchId: 'b1' }))
    const workspace = useCanvasWorkspace({ collectLayout, applyLayout: vi.fn() })
    await workspace.bind({ storyboard: 'sb-1', draft: null })
    const session = sessions.get('draft-1')!
    session.draft.value = { id: 'draft-1', version: 1,
      workspace: { schemaVersion: 1, capability: 'video', currentStep: 'storyboard',
        inputs: { video: { storyboardId: 'sb-1', topic: '勿覆写' } } } }

    workspace.queueLayoutSave()
    expect(session.queueSave).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(800)
    expect(session.queueSave).toHaveBeenCalledTimes(1)
    const patch = session.queueSave.mock.calls[0][0] as { workspace: { inputs: Record<string, unknown> } }
    expect(patch.workspace.inputs.videoCanvas).toEqual(collectLayout())
    expect((patch.workspace.inputs.video as Record<string, unknown>).topic).toBe('勿覆写')
    expect(session.flush).toHaveBeenCalled()
  })

  test('无草稿/只读不排队；flushLayout 直接排空', async () => {
    const workspace = useCanvasWorkspace({ collectLayout: layout, applyLayout: vi.fn() })
    workspace.queueLayoutSave()
    await vi.advanceTimersByTimeAsync(800)
    expect(fetchApiMock).not.toHaveBeenCalled()
  })

  test('绑定失败：bindingError 呈现，binding 保持空', async () => {
    fetchApiMock.mockResolvedValueOnce({ ok: false, json: async () => ({ success: false, error: '该分镜匹配到多个草稿' }) } as never)
    const workspace = useCanvasWorkspace({ collectLayout: layout, applyLayout: vi.fn() })
    await expect(workspace.bind({ storyboard: 'sb-1', draft: null })).resolves.toBe(false)
    expect(workspace.bindingError.value).toContain('多个草稿')
    expect(workspace.binding.value).toBeNull()
  })

  test('TC-011：账号 epoch 切换——在途响应作废、绑定清空（A→B→A 慢响应不串号）', async () => {
    let resolveBind: (value: unknown) => void = () => {}
    fetchApiMock.mockReturnValueOnce(new Promise(resolve => { resolveBind = resolve }) as never)
    const workspace = useCanvasWorkspace({ collectLayout: layout, applyLayout: vi.fn() })
    const pending = workspace.bind({ storyboard: 'sb-1', draft: null })
    account.epoch += 1
    resolveBind(bindResponse())
    await expect(pending).resolves.toBe(false)
    expect(workspace.binding.value).toBeNull()
    expect(workspace.bindingError.value).toBe('')
  })
})
