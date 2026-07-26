import { afterEach, describe, expect, test, vi } from 'vitest'
import { useGrassland } from './useGrassland'

/**
 * 请求契约回归测试。
 *
 * 背景：e2e 联调发现 `openIdentity`/`activateIdentity` 曾把字段写成 `identityType`，
 * 而 identity-service 的 `OpenIdentityRequest`/`ActivateIdentityRequest` 要求 **`type`** → 400。
 * 这类字段名不匹配 **typecheck 抓不到**（两个名字都是合法 TS），只能靠断言实际请求体锁死。
 *
 * 注意请求/响应字段不对称：请求用 `type`，响应返回 `identityType`。
 */

function mockFetchOk(): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockResolvedValue({
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data: {} }),
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function bodyOf(spy: ReturnType<typeof vi.fn>, callIndex = 0): Record<string, unknown> {
  const init = spy.mock.calls[callIndex][1] as RequestInit
  return JSON.parse(init.body as string) as Record<string, unknown>
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('身份端点请求契约', () => {
  test('openIdentity 发送 type 而非 identityType', async () => {
    const spy = mockFetchOk()
    const { openIdentity } = useGrassland()

    await openIdentity('merchant', 'org-1')

    const body = bodyOf(spy)
    expect(body.type).toBe('merchant')
    expect(body.organizationId).toBe('org-1')
    expect(body).not.toHaveProperty('identityType')  // 写成 identityType 后端会 400
  })

  test('openIdentity 无 org 时不带 organizationId（推荐官）', async () => {
    const spy = mockFetchOk()
    const { openIdentity } = useGrassland()

    await openIdentity('recommender')

    const body = bodyOf(spy)
    expect(body.type).toBe('recommender')
    expect(body).not.toHaveProperty('organizationId')
  })

  test('activateIdentity 发送 type', async () => {
    const spy = mockFetchOk()
    const { activateIdentity } = useGrassland()

    await activateIdentity('merchant')

    const body = bodyOf(spy)
    expect(body.type).toBe('merchant')
    expect(body).not.toHaveProperty('identityType')
  })

  test('请求携带 cookie（BFF 靠 session 换发内部断言）', async () => {
    const spy = mockFetchOk()
    const { activateIdentity } = useGrassland()

    await activateIdentity('merchant')

    const init = spy.mock.calls[0][1] as RequestInit
    expect(init.credentials).toBe('include')
  })
})

describe('错误处理', () => {
  test('后端 {success:false,error} 被提取为 error 消息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: false, error: '当前等级不可发布任务' }),
    }))
    const { createTask, error } = useGrassland()

    const result = await createTask({ organizationId: 'o', title: 't' })

    expect(result).toBeNull()
    expect(error.value).toBe('当前等级不可发布任务')
  })
})
