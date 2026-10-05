// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { effectScope, reactive } from 'vue'
import { useSavedWorkHandoff } from './useSavedWorkHandoff'
import type { CreationProject } from '../types/creation'
const api = vi.hoisted(() => vi.fn())
vi.mock('./grassland-http', () => ({ fetchApi: api }))
vi.mock('../stores/account-session', () => ({ useAccountSessionStore: () => session }))
const session = reactive({ epoch: 0, capture: () => ({ accountId: 'A', epoch: session.epoch }),
  isCurrent: (ticket: { epoch: number }) => ticket.epoch === session.epoch })
const scopes: ReturnType<typeof effectScope>[] = []
const source = { id: 'source', version: 3 } as CreationProject
function setup() {
  const scope = effectScope(); scopes.push(scope)
  const accept = vi.fn()
  return { handoff: scope.run(() => useSavedWorkHandoff(accept))!, accept, scope }
}
beforeEach(() => { api.mockReset(); session.epoch = 0 })
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()))
it('retries the exact saved revision with the same request id after an ambiguous response', async () => {
  const { handoff, accept } = setup()
  api.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { id: 'target', workspace: { capability: 'video' } } })))
  await handoff.create(source, 'video'); expect(handoff.error.value).toBe('offline')
  await handoff.create(source, 'video')
  const bodies = api.mock.calls.map(call => JSON.parse(call[1].body))
  expect(bodies[0]).toEqual(bodies[1]); expect(bodies[0].version).toBe(3)
  expect(accept).toHaveBeenCalledWith(expect.objectContaining({ id: 'target', capability: 'video' }))
})
it.each(['account', 'unmount'])('ignores a late response after %s change', async kind => {
  let resolve!: (response: Response) => void
  api.mockReturnValue(new Promise<Response>(done => { resolve = done }))
  const { handoff, accept, scope } = setup()
  const pending = handoff.create(source, 'video')
  await handoff.create(source, 'video'); expect(api).toHaveBeenCalledTimes(1)
  if (kind === 'account') session.epoch++; else scope.stop()
  resolve(new Response(JSON.stringify({ success: true, data: { id: 'old' } })))
  await pending; expect(accept).not.toHaveBeenCalled()
})
