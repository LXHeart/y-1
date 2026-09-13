// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { computed, effectScope, nextTick, ref } from 'vue'
import { useCanvasAssistant } from './useCanvasAssistant'
import type { CanvasStoryboard } from '../useVideoCanvas'
import type { CanvasDocumentBody, CanvasPlanResult, ApplyCanvasPlanResult } from '../../../types/video-canvas'

afterEach(() => vi.unstubAllGlobals())
function fixture() {
  const scope = effectScope()
  const storyboard = ref({ id: 'sb-A', editVersion: 1, status: 'draft', targetDurationSeconds: 10, resolution: '1080x1920', grouping: null,
    shots: [1, 2].map(seq => ({ id: 'shot-' + seq, seq, visual: '原画面' + seq, narration: '旁白', plannedSeconds: 5,
      cameraMove: '固定机位', anchorImageIndex: 0, status: 'draft', takes: [], x: 40, y: seq * 100 })) } as CanvasStoryboard)
  const document = ref<CanvasDocumentBody>({ schemaVersion: 1, storyboardId: 'sb-A', viewport: { panX: 0, panY: 0, scale: 1 }, activeBranchId: null, edges: [],
    nodes: storyboard.value.shots.map(shot => ({ id: 'shot:' + shot.id, kind: 'shot', refType: 'shot', refId: shot.id, label: null, text: null, x: 40, y: 40 })) })
  const instruction = ref('只改第二镜\n保留品牌名'); const selected = ref(['shot:shot-2']); const epoch = ref('account:A')
  const revision = ref(1); const pending = ref(false); const flush = vi.fn(async () => true)
  const productionTask = ref<{ id: string; shots: { takes: { id: string }[] }[] } | null>(null)
  const navigate = vi.fn(async () => {}); const focus = vi.fn(); const reload = vi.fn(async () => {}); const save = vi.fn(async () => true)
  const state = scope.run(() => useCanvasAssistant({ graph: { nodes: computed(() => document.value.nodes.map(node => ({ ...node, unavailableReason: null }))) },
    storyboard, draftId: ref('draft-A'), storyboardIdRef: ref('sb-A'), canvasRevision: revision, instruction, selectedNodeIds: selected,
    epoch, draftVersion: () => 1, flushBeforeSubmit: flush, hasPending: () => pending.value, readonly: () => false,
    canvas: { document, save }, navigateVariant: navigate, focusShot: focus, reloadStoryboard: reload,
    productionTask: () => productionTask.value }))!
  return { scope, state, storyboard, document, instruction, selected, epoch, revision, pending, flush, navigate, focus, reload, save, productionTask }
}
function plan(): CanvasPlanResult {
  return { id: 'plan', status: 'ready', draftId: 'draft-A', storyboardId: 'sb-A', baseDraftVersion: 1, baseEditVersion: 1,
    baseCanvasRevision: 1, summary: '修改第二镜', clarification: null, action: { kind: 'edit', actions: [{ kind: 'update-shot', patch: { shotId: 'shot-2', visual: '新画面' } }] },
    runId: 'run', errorCode: null, expiresAt: new Date(Date.now() + 1800000).toISOString() }
}
function respond(value: unknown) { return new Response(JSON.stringify({ success: true, data: value }), { headers: { 'Content-Type': 'application/json' } }) }

describe('C102 assistant input and results', () => {
  test('request snapshots exact selection/instruction and confirmed versions after flush', async () => {
    const f = fixture(); const request = vi.fn(async () => respond(plan())); vi.stubGlobal('fetch', request)
    f.flush.mockImplementation(async () => { f.revision.value = 3; return true })
    expect(await f.state.submitAgent()).toBe(true)
    const body = JSON.parse(String((request.mock.calls[0] as unknown as [string, RequestInit])[1].body))
    expect(body).toMatchObject({ instruction: '只改第二镜\n保留品牌名', selectedNodeIds: ['shot:shot-2'], expectedCanvasRevision: 3 })
    expect(f.state.selectedNodeLabels.value[0]).toContain('镜头 2：原画面2')
    f.storyboard.value.shots[1]!.visual = '后来改动'; f.selected.value = ['shot:shot-1']
    expect(f.state.baseline.value?.shots[1]?.visual).toBe('原画面2')
    expect(f.state.planNodeLabels.value[0]).toContain('镜头 2：原画面2')
    f.scope.stop()
  })
  test('empty selection, save failure and project change during flush never submit', async () => {
    const f = fixture(); const request = vi.fn(); vi.stubGlobal('fetch', request)
    f.selected.value = []; expect(await f.state.submitAgent()).toBe(false)
    f.selected.value = ['shot:shot-2']; f.flush.mockResolvedValue(false); expect(await f.state.submitAgent()).toBe(false)
    f.flush.mockImplementation(async () => { f.epoch.value = 'B'; return true }); expect(await f.state.submitAgent()).toBe(false)
    expect(request).not.toHaveBeenCalled(); expect(f.instruction.value).toContain('第二镜'); f.scope.stop()
  })
  test('pending changes and version drift retain the plan but block apply', async () => {
    const f = fixture(); const request = vi.fn(); vi.stubGlobal('fetch', request)
    f.state.agent.plan.value = plan(); f.pending.value = true
    expect(await f.state.applyAgent()).toBe(false); expect(f.state.blockedReason.value).toContain('未保存')
    f.pending.value = false; f.revision.value++
    expect(await f.state.applyAgent()).toBe(false); expect(f.state.agent.plan.value?.id).toBe('plan')
    expect(request).not.toHaveBeenCalled(); f.scope.stop()
  })
  test('variant navigates to returned identity; prepare only focuses the existing action', async () => {
    const f = fixture()
    const base: ApplyCanvasPlanResult = { planId: 'plan', draftId: 'draft-A', storyboardId: 'sb-A', editVersion: 1, affectedShotIds: [], variant: null, preparedGeneration: null }
    const variant = { draftId: 'draft-B', storyboardId: 'sb-B', rootStoryboardId: 'sb-A', parentStoryboardId: 'sb-A', title: 'B', sourceEditVersion: 1, createdAt: new Date().toISOString() }
    const request = vi.fn(async () => respond({ ...base, variant })); vi.stubGlobal('fetch', request)
    f.state.agent.plan.value = plan(); expect(await f.state.applyAgent()).toBe(true); expect(f.navigate).toHaveBeenCalledWith(variant)
    request.mockImplementation(async () => respond({ ...base, planId: 'plan-2', preparedGeneration: { mode: 'regenerate', shotId: 'shot-2' } }))
    f.state.agent.plan.value = { ...plan(), id: 'plan-2' }; expect(await f.state.applyAgent()).toBe(true); expect(f.focus).toHaveBeenCalledWith('shot-2', true)
    expect(f.reload).not.toHaveBeenCalled(); expect(request).toHaveBeenCalledTimes(2); f.scope.stop()
  })
  test.each(['initial', 'regenerate', 'reroll'] as const)('%s preparation clears only when a task or new take is accepted', async mode => {
    const f = fixture()
    if (mode !== 'initial') f.productionTask.value = { id: 'task', shots: [{ takes: [{ id: 'take-1' }] }] }
    await nextTick()
    const preparedGeneration = { mode, shotId: mode === 'initial' ? null : 'shot-2' }
    const request = vi.fn(async () => respond({ planId: 'plan', draftId: 'draft-A', storyboardId: 'sb-A',
      editVersion: 1, affectedShotIds: [], variant: null, preparedGeneration }))
    vi.stubGlobal('fetch', request)
    f.state.agent.plan.value = plan()
    expect(await f.state.applyAgent()).toBe(true)
    expect(f.state.preparedGeneration.value).toEqual(preparedGeneration)
    // A polling refresh or a rejected generation has no new task/take identity.
    if (f.productionTask.value) f.productionTask.value = structuredClone({ id: 'task', shots: [{ takes: [{ id: 'take-1' }] }] })
    await nextTick()
    expect(f.state.preparedGeneration.value).toEqual(preparedGeneration)
    f.productionTask.value = { id: 'task', shots: [{ takes: [{ id: 'take-1' }, { id: 'take-2' }] }] }
    await nextTick()
    expect(f.state.preparedGeneration.value).toBeNull()
    expect(request).toHaveBeenCalledTimes(1)
    f.scope.stop()
  })
})
