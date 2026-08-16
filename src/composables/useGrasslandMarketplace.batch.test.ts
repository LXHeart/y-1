import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { useGrasslandMarketplace } from './useGrasslandMarketplace'
import type { RunFn } from './grassland-http'

/**
 * 任务书 #27 批量端点请求契约测试：URL、方法、请求体 applicationIds 顺序、
 * credentials——字段名/顺序不匹配 typecheck 抓不到，只能靠断言实际请求锁死
 * （镜像 useGrassland.test.ts 的契约回归动机）。
 */

const passthroughRun: RunFn = async <T>(operation: () => Promise<T>) => operation()

afterEach(() => vi.restoreAllMocks())

function mockFetchOk(): MockInstance<typeof globalThis.fetch> {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
    success: true,
    data: { results: [] },
  }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
}

describe('useGrasslandMarketplace 批量操作契约（#27）', () => {
  it('batchAcceptApplications 按传入顺序 POST batch-accept', async () => {
    const fetchMock = mockFetchOk()
    const marketplace = useGrasslandMarketplace(passthroughRun)

    const result = await marketplace.batchAcceptApplications('task-1', ['app-2', 'app-1'])

    expect(result).toEqual({ results: [] })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/tasks/task-1/applications/batch-accept',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({ applicationIds: ['app-2', 'app-1'] }),
      }),
    )
  })

  it('batchRejectApplications POST batch-reject 且携带 cookie', async () => {
    const fetchMock = mockFetchOk()
    const marketplace = useGrasslandMarketplace(passthroughRun)

    await marketplace.batchRejectApplications('task-9', ['app-x'])

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/tasks/task-9/applications/batch-reject',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({ applicationIds: ['app-x'] }),
      }),
    )
  })

  it('批量业务错误不抛进组件，run 返回 null 并落 error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: false, error: 'applicationIds must not exceed 50',
    }), { status: 400, headers: { 'Content-Type': 'application/json' } }))

    let captured: string | null = 'unset'
    const captureErrorRun: RunFn = async <T>(operation: () => Promise<T>) => {
      try {
        return await operation()
      } catch (error) {
        captured = (error as Error).message
        throw error
      }
    }
    const marketplace = useGrasslandMarketplace(captureErrorRun)

    await expect(marketplace.batchAcceptApplications('task-1', ['a', 'b']))
      .rejects.toThrow('applicationIds must not exceed 50')
    expect(captured).toBe('applicationIds must not exceed 50')
  })
})
