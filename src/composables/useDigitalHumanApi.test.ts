// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GrasslandHttpError } from './grassland-http'
import { createDigitalHumanApi, decodeSession, isPersonalCatalog, isPublicCatalog } from './useDigitalHumanApi'
import type { Session } from '../types/digital-human'

/**
 * 数字人 API 层（任务书 #105B C105B-04 / TC105B-04-01～04）。
 *
 * - 标准信封：真实 Response 成功/错误；data 解包、status/code 保留、错误体只读一次。
 * - 幂等键保留：写请求 timeout 后重试同 input → requestId 相同，调用层不生成新 key。
 * - 文件/SSE 例外：event-stream/MP4 响应不走 json()；cookie 仅同源控制端。
 * - 单位/null：pending 费用 null 原样；超 JS 安全整数序号拒绝。
 */

const fetchMock = vi.fn()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function sessionFixture(overrides: Partial<Session> = {}): Session {
  return {
    id: 's-1', profileId: 'p-1', profileRevision: 1, state: 'ready', leaseEpoch: 1, mediaEpoch: 1,
    controllerId: 'c-1', serverNow: '2026-09-23T00:00:00Z', createdAt: '2026-09-23T00:00:00Z',
    readyAt: null, expiresAt: null, pausedUntil: null, leaseExpiresAt: '2026-09-23T00:00:30Z',
    lastSeq: 3, saveTranscript: false, transcriptVersion: 1, contentEpoch: 1,
    billing: {
      confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 0,
      priceTableVersion: 'v1',
    },
    errorCode: null,
    ...overrides,
  }
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('TC105B-04-01 标准信封', () => {
  it('tc105b_04_01 成功解包 data、错误保留 status/code、错误体只读一次', async () => {
    const api = createDigitalHumanApi()
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { id: 'p-1', name: '角色' } }))
    const profile = await api.getProfile('p-1')
    expect(profile).toEqual({ id: 'p-1', name: '角色' })

    // 错误：真实 Response 409 + 信封 code；GrasslandHttpError 保留两者。
    let textCalls = 0
    const errorResponse = new Response(
      JSON.stringify({ success: false, error: '角色已被其它修改更新，请刷新后重试。', code: 'dh_version_conflict' }),
      { status: 409, headers: { 'content-type': 'application/json' } })
    const counting = errorResponse.clone?.() ?? errorResponse
    // 计数 text()：#104 单次读——包装原 text 断言只被消费一次。
    const originalText = counting.text.bind(counting)
    ;(counting as Response & { text: () => Promise<string> }).text = async () => {
      textCalls += 1
      return originalText()
    }
    fetchMock.mockResolvedValueOnce(counting)
    const failure = await api.updateProfile('p-1', {
      name: 'x', persona: 'y', greeting: '', tone: 'natural', avatarId: 'a', voiceId: 'v',
      catalogVersion: 1, expectedVersion: 1, requestId: 'r-1',
    }).catch((error: unknown) => error as GrasslandHttpError)
    expect(failure).toBeInstanceOf(GrasslandHttpError)
    expect((failure as GrasslandHttpError).status).toBe(409)
    expect((failure as GrasslandHttpError).code).toBe('dh_version_conflict')
    expect((failure as GrasslandHttpError).message).toContain('角色已被其它修改更新')
    expect(textCalls).toBe(1)
  })
})

describe('TC105B-04-02 幂等键保留', () => {
  it('tc105b_04_02 timeout 后同 input 重试：requestId 相同、调用层不生成新 key', async () => {
    const api = createDigitalHumanApi()
    const input = {
      name: '角色', persona: '人设', greeting: '你好', tone: 'natural' as const, avatarId: 'a-1',
      voiceId: 'v-1', catalogVersion: 1, requestId: '22222222-2222-4222-8222-222222222222',
    }
    // 第一次：网络超时（客户端 abort 不推断后端撤销）。
    fetchMock.mockRejectedValueOnce(new TypeError('network timeout'))
    await expect(api.createProfile(input)).rejects.toThrow('network timeout')
    // 第二次：同 input 重试成功 → 同 requestId 原键幂等。
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { id: 'p-9', version: 1 } }, 201))
    await api.createProfile(input)

    const bodies = fetchMock.mock.calls.map((call) => String(call[1]?.body))
    expect(bodies).toHaveLength(2)
    expect(JSON.parse(bodies[0]!)).toEqual(input)
    expect(bodies[1]).toBe(bodies[0])  // 同键同体；层内无 randomUUID / 改写
  })
})

describe('TC105B-04-03 文件/SSE 例外', () => {
  it('tc105b_04_03 event-stream 与 MP4 不走 json()；同源控制端带 cookie', async () => {
    const api = createDigitalHumanApi()
    const sseBody = 'id: 1\nevent: session.state\ndata: {"v":1,"seq":1}\n\n'
    let jsonCalls = 0
    const sseResponse = new Response(sseBody, {
      status: 200, headers: { 'content-type': 'text/event-stream' },
    })
    ;(sseResponse as Response & { json: () => Promise<unknown> }).json = async () => {
      jsonCalls += 1
      throw new Error('SSE 不得走 json()')
    }
    fetchMock.mockResolvedValueOnce(sseResponse)
    const events = await api.openEvents('s-1', 0)
    expect(events.headers.get('content-type')).toBe('text/event-stream')
    expect(await events.text()).toBe(sseBody)
    expect(jsonCalls).toBe(0)
    // SSE 请求不带 JSON Content-Type（GET 流），且经 fetchApi credentials=include（仅同源控制端）。
    const sseInit = fetchMock.mock.calls[0]![1]!
    expect(sseInit.credentials).toBe('include')
    expect((sseInit.headers as Record<string, string> | undefined)?.['Content-Type']).toBeUndefined()

    // MP4 下载（认证文件流）：同样原样返回 Response。
    const mp4 = new Response(new Uint8Array([0, 0, 0, 1]), {
      status: 200, headers: { 'content-type': 'video/mp4' },
    })
    fetchMock.mockResolvedValueOnce(mp4)
    const download = await api.downloadRecording('r-1', 'mp4')
    expect(download.headers.get('content-type')).toBe('video/mp4')
  })
})

describe('TC105B-04-04 单位/null', () => {
  it('tc105b_04_04 pending 费用 null 原样、超 JS 安全整数序号拒绝', async () => {
    // pending：confirmedCents=null + pendingCount=1 → null 原样保留，不填 0。
    const pending = sessionFixture({
      billing: {
        confirmedCents: null as unknown as number, platformCostCents: null as unknown as number,
        subsidizedCents: 0, pendingCount: 1, priceTableVersion: 'v1',
      },
    })
    expect(() => decodeSession(pending)).not.toThrow()
    expect(decodeSession(pending).billing.confirmedCents).toBeNull()

    // 2^53（上界）合法；2^53+1（JSON 可表示但不安全）拒绝。
    expect(() => decodeSession(sessionFixture({ lastSeq: 9007199254740991 }))).not.toThrow()
    expect(() => decodeSession(sessionFixture({ lastSeq: 9007199254740992 })))
      .toThrowError(RangeError)

    // 经 createSession 端到端：后端回不安全 lastSeq → 解码层拒绝（不静默收窄）。
    const api = createDigitalHumanApi()
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true, data: sessionFixture({ lastSeq: 9007199254740992 }),
    }, 202))
    await expect(api.createSession({
      preflightId: 'f-1', requestId: 'r-1', saveTranscript: false,
    })).rejects.toThrowError(RangeError)
  })

  it('目录判别：authenticated 字面量区分游客/个人形态', () => {
    expect(isPersonalCatalog({ authenticated: true } as never)).toBe(true)
    expect(isPublicCatalog({ authenticated: false, enabled: false, description: '未开放' } as never))
      .toBe(true)
    expect(isPublicCatalog({ authenticated: true } as never)).toBe(false)
  })
})
