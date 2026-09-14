// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import type { SourceDocument, TextProposal, VisualPlan } from '../../../types/creation-studio'
import { useVisualPlan } from './useVisualPlan'
import { useTextProposal } from './useTextProposal'

enableAutoUnmount(afterEach)
const fetchMock = vi.fn()
let hidden = false
const source: SourceDocument = {
  id: 'source-1', draftId: 'draft-1', schemaVersion: 1, kind: 'draft-content', title: '',
  rawText: '人均 68 元', normalizedMarkdown: '人均 68 元', contentHash: 'a'.repeat(64),
  blocks: [{ id: 'b1', kind: 'paragraph', position: 1, startCodePoint: 0, endCodePoint: 7,
    text: '人均 68 元', textHash: '' }], sourceRefs: [], warnings: [], createdAt: '',
}
const readyPlan: VisualPlan = {
  id: 'plan-1', draftId: 'draft-1', status: 'ready', revision: 1, confirmedRevision: null,
  source: { id: source.id, contentHash: source.contentHash }, baseDraftVersion: 1,
  baseContentHash: 'b'.repeat(64), stale: false, runId: null, error: null, createdAt: '',
  document: { recipe: { id: 'social-card-series', version: '1.0.0' }, strategy: 'information',
    style: { styleId: 's1', layoutId: 'l1', paletteId: 'p1' }, explanation: '', uncoveredBlockIds: [],
    items: [{ itemId: 'i1', cardId: 'c1', position: 1, role: 'cover', title: '封面', bullets: [],
      caption: '', purpose: '', illustration: '', sourceBlockIds: ['b1'], criticalText: [],
      layoutId: 'l1', targetAspect: '3:4', placement: null, inputMediaRef: null }] },
}
const readyProposal: TextProposal = {
  id: 'proposal-1', draftId: 'draft-1', requestId: 'request-1', action: 'adapt-body', status: 'ready',
  baseDraftVersion: 1, baseContentHash: 'b'.repeat(64), source: readyPlan.source,
  result: { title: null, body: '人均 68 元的午餐', summary: null, changes: ['调整表达'], sourceBlockIds: ['b1'] },
  runId: null, safety: null, appliedDraftVersion: null, error: null, createdAt: '', expiresAt: '',
}
const response = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 })

function host<T>(factory: () => T) {
  let controller!: T
  const visible = ref(true)
  const Subject = defineComponent({ setup() { controller = factory(); return () => h('div') } })
  const wrapper = mount(defineComponent({ setup: () => () => h(KeepAlive, null,
    { default: () => visible.value ? h(Subject) : null }) }))
  return { controller, wrapper, async show(value: boolean) { visible.value = value; await nextTick() } }
}
function planHost() {
  const onPlanCreated = vi.fn()
  return { onPlanCreated, ...host(() => {
    const controller = useVisualPlan({ draftId: () => 'draft-1', draftVersion: () => 1,
      sourceDocumentId: () => source.id, sourceContentHash: () => source.contentHash,
      recipe: () => readyPlan.document!.recipe, onPlanCreated })
    controller.bindSource(source)
    return controller
  }) }
}
function visibility(value: boolean): void { hidden = value; document.dispatchEvent(new Event('visibilitychange')) }

beforeEach(() => {
  hidden = false
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers()
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

test('计划失活丢弃在途轮询，重新激活立即读回同一计划', async () => {
  const { controller, show } = planHost()
  fetchMock.mockResolvedValueOnce(response({ ...readyPlan, status: 'preparing', document: null }))
  await controller.prepare()
  let resolve!: (value: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
  const reading = controller.refresh()
  await show(false)
  resolve(response(readyPlan)); await reading
  expect(controller.current.value?.status).toBe('preparing')
  await vi.advanceTimersByTimeAsync(10_000)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  fetchMock.mockResolvedValueOnce(response(readyPlan))
  await show(true); await flushPromises()
  expect(controller.current.value?.status).toBe('ready')
  expect(fetchMock).toHaveBeenCalledTimes(3)
})

test('失活期间收到的创建结果在激活后接管，不重发 POST', async () => {
  const { controller, show, onPlanCreated } = planHost()
  let resolve!: (value: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
  const creating = controller.prepare()
  await show(false)
  resolve(response(readyPlan)); await flushPromises()
  expect(controller.current.value).toBeNull()
  expect(onPlanCreated).not.toHaveBeenCalled()
  await show(true); await creating
  expect(controller.current.value?.id).toBe('plan-1')
  expect(onPlanCreated).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

test('失活保留未保存计划；浏览器重新可见不会唤醒失活页面', async () => {
  const { controller, show } = planHost()
  fetchMock.mockResolvedValueOnce(response(readyPlan))
  await controller.prepare()
  controller.document.value!.items[0].title = '保留人工编辑'
  controller.touch()
  await show(false)
  visibility(true); visibility(false)
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(controller.dirty.value).toBe(true)
  fetchMock.mockResolvedValueOnce(response(readyPlan)).mockImplementationOnce((_url, init) =>
    Promise.resolve(response({ ...readyPlan, revision: 2, document: JSON.parse(init.body).document })))
  await show(true); await flushPromises()
  await vi.advanceTimersByTimeAsync(850)
  expect(controller.document.value!.items[0].title).toBe('保留人工编辑')
  expect(controller.current.value?.revision).toBe(2)
  expect(controller.dirty.value).toBe(false)
})

test('建议在页面隐藏后不更新；恢复后读取原建议，不重新调用模型', async () => {
  const { controller } = host(() => {
    const value = useTextProposal({ draftId: () => 'draft-1', draftVersion: () => 1,
      sourceDocumentId: () => source.id, runExternalMutation: async () => null, onApplied: vi.fn() })
    value.bindSource(source)
    return value
  })
  fetchMock.mockResolvedValueOnce(response({ ...readyProposal, status: 'preparing', result: null }))
  await controller.prepare('adapt-body')
  let resolve!: (value: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
  const reading = controller.refresh('proposal-1')
  visibility(true)
  resolve(response(readyProposal)); await reading
  expect(controller.current.value?.status).toBe('preparing')
  await vi.advanceTimersByTimeAsync(10_000)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  fetchMock.mockResolvedValueOnce(response(readyProposal))
  visibility(false); await flushPromises()
  expect(controller.current.value?.status).toBe('ready')
  expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})

test('隐藏后卸载会释放待接管创建结果，不落地也不保留计时器', async () => {
  const { controller, show, wrapper, onPlanCreated } = planHost()
  let resolve!: (value: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
  const creating = controller.prepare()
  await show(false)
  resolve(response(readyPlan)); await flushPromises()
  wrapper.unmount(); await creating
  expect(onPlanCreated).not.toHaveBeenCalled()
  expect(controller.current.value).toBeNull()
  expect(vi.getTimerCount()).toBe(0)
})
