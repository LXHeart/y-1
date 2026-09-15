import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { useEngagementExitFunds } from './useEngagementExitFunds'
import type { EngagementExitFunds } from '../types/grassland/task'

/**
 * 任务书 #103 C103-04：useEngagementExitFunds——目标绑定/旧回包丢弃/轮询边界（TC103-04-01/06 契约侧）。
 */

type FundsMock = ReturnType<typeof vi.fn>

function makeClient() {
  const fetchFunds = vi.fn() as FundsMock
  return {
    fetchEngagementExitFunds: fetchFunds,
    error: { value: '' },
  } as unknown as ReturnType<typeof import('./useGrassland').useGrassland>
}

function pendingFunds(state: EngagementExitFunds['state']): EngagementExitFunds {
  return {
    operationId: 'op-1',
    kind: 'no_fault',
    state,
    amounts: { deposit_refundCents: 100, bounty_releaseCents: 500 },
    blockedReason: state === 'needs_review' ? 'funds_reconciliation_required' : 'funds_pending',
    updatedAt: '2026-09-15T00:00:00Z',
  }
}

describe('useEngagementExitFunds', () => {
  it('binds to the current application and discards stale responses after switching', async () => {
    const client = makeClient()
    let resolveFirst: (v: unknown) => void = () => {}
    vi.mocked(client.fetchEngagementExitFunds).mockImplementationOnce(() => new Promise<import('../types/grassland/task').EngagementExitFunds | null>((resolve) => {
      resolveFirst = resolve as (v: unknown) => void
    }))
    vi.mocked(client.fetchEngagementExitFunds).mockResolvedValueOnce(pendingFunds('succeeded'))

    const { funds, target } = useEngagementExitFunds(client)
    target('task-1', 'app-1')
    target('task-1', 'app-2')
    // app-1 的迟到回包不得写入 app-2。
    resolveFirst(pendingFunds('pending'))
    await nextTick()
    await Promise.resolve()
    expect(funds.value?.state === null || funds.value === null || funds.value.state !== 'pending' || funds.value === null).toBe(true)
    expect(client.fetchEngagementExitFunds).toHaveBeenNthCalledWith(2, 'task-1', 'app-2')
  })

  it('treats missing operation as no funds state instead of inferring success', async () => {
    const client = makeClient()
    vi.mocked(client.fetchEngagementExitFunds).mockResolvedValueOnce({ operationId: null, state: null })
    const { funds, target, error } = useEngagementExitFunds(client)
    target('task-1', 'app-1')
    await vi.waitFor(() => { expect(funds.value).toBeNull() })
    expect(error.value).toBe('')
  })

  it('exposes pending state and keeps funds for polling consumers', async () => {
    const client = makeClient()
    vi.mocked(client.fetchEngagementExitFunds).mockResolvedValue(pendingFunds('retry_wait'))
    const { funds, target } = useEngagementExitFunds(client)
    target('task-1', 'app-1')
    await vi.waitFor(() => { expect(funds.value?.state).toBe('retry_wait') })
    expect(funds.value?.blockedReason).toBe('funds_pending')
  })
})
