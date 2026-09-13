// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useCanvasProjectSession } from './useCanvasProjectSession'

const scopes: ReturnType<typeof effectScope>[] = []
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.restoreAllMocks() })
function setup() {
  const scope = effectScope(); scopes.push(scope)
  const key = ref({ storyboard: 'A', draft: 'draft-A' as string | null })
  const epoch = ref(1)
  const bound = ref('')
  const queues = [0, 1, 2, 3].map(() => vi.fn(async () => true))
  const bind = vi.fn(async (next: { storyboard: string }) => { bound.value = `draft-${next.storyboard}`; return true })
  const load = vi.fn(async () => undefined)
  const reset = vi.fn(() => { bound.value = '' })
  const focusError = vi.fn()
  const onKeydown = vi.fn()
  const session = scope.run(() => useCanvasProjectSession({ key, accountEpoch: () => epoch.value,
    authenticated: () => true, boundDraftId: () => bound.value, bind, load, reset,
    syncDraft: vi.fn(), queues, hasPending: () => true, focusError, onKeydown }))!
  return { key, epoch, bound, queues, bind, load, reset, focusError, onKeydown, session }
}

describe('C102-02 project lifetime', () => {
  test('all four queues flush in order, failure stops navigation and focuses error', async () => {
    const f = setup(); await f.session.ensure()
    const order: number[] = []
    f.queues.forEach((queue, i) => queue.mockImplementation(async () => { order.push(i); return i !== 2 }))
    expect(await f.session.flushBeforeLeave()).toBe(false)
    expect(order).toEqual([0, 1, 2]); expect(f.focusError).toHaveBeenCalledOnce()
    f.queues[2]!.mockResolvedValue(true)
    expect(await f.session.flushBeforeLeave()).toBe(true)
    expect(f.queues[3]).toHaveBeenCalledOnce()
  })
  test('project and account changes invalidate tickets, including A to B to A', async () => {
    const f = setup(); await f.session.ensure()
    const a = f.session.capture()
    f.key.value = { storyboard: 'B', draft: 'draft-B' }; await f.session.ensure()
    expect(f.session.isCurrent(a)).toBe(false)
    f.key.value = { storyboard: 'A', draft: 'draft-A' }; await f.session.ensure()
    expect(f.session.isCurrent(a)).toBe(false)
    const next = f.session.capture(); f.epoch.value += 1; await f.session.ensure()
    expect(f.session.isCurrent(next)).toBe(false)
  })
  test('draft query normalization does not reload or reset a bound project', async () => {
    const f = setup(); await f.session.ensure()
    f.key.value = { storyboard: 'A', draft: null }; await f.session.ensure()
    f.bind.mockClear(); f.load.mockClear(); f.reset.mockClear()
    f.key.value = { storyboard: 'A', draft: 'draft-A' }
    await Promise.resolve()
    expect(f.bind).not.toHaveBeenCalled(); expect(f.reset).not.toHaveBeenCalled()
    f.key.value = { storyboard: 'A', draft: 'wrong' }; await f.session.ensure()
    expect(f.reset).toHaveBeenCalledOnce()
  })
  test('deactivation removes listeners, reactivation installs once and invalidates late reads', async () => {
    const f = setup(); await f.session.ensure()
    f.session.activate(); f.session.activate()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z' }))
    expect(f.onKeydown).toHaveBeenCalledOnce()
    const ticket = f.session.capture(); f.session.deactivate()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z' }))
    expect(f.onKeydown).toHaveBeenCalledOnce(); expect(f.session.isCurrent(ticket)).toBe(false)
    f.session.activate(); window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z' }))
    expect(f.onKeydown).toHaveBeenCalledTimes(2)
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true)
  })
})
