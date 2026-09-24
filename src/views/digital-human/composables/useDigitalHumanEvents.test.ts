// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { parseSse, useDigitalHumanEvents, type DhEvent } from './useDigitalHumanEvents'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'

/** TC105E-03-01 SSE 真实分块：UTF8 中文跨字节边界/CRLF/多行 data/注释；缺口触发 snapshot 重续订。 */

const ENCODER = new TextEncoder()

async function* chunksOf(parts: string[]): AsyncGenerator<Uint8Array> {
  for (const part of parts) yield ENCODER.encode(part)
}

async function collect(gen: AsyncGenerator<DhEvent>): Promise<DhEvent[]> {
  const events: DhEvent[] = []
  for await (const event of gen) events.push(event)
  return events
}

function envelope(seq: number, eventId: string, extra: Record<string, unknown> = {}): string {
  return JSON.stringify({
    v: 1, eventId, sessionId: 's-1', leaseEpoch: 1, mediaEpoch: 1, turnId: null, turnEpoch: null,
    seq, occurredAt: '2026-09-23T00:00:00Z', payload: {}, ...extra,
  })
}

describe('TC105E-03-01 parseSse 真实分块', () => {
  test('UTF8 中文按任意字节位切开不乱码（含 3 字节汉字被切在中间）', async () => {
    const text = 'data: ' + envelope(1, 'e-1', { payload: { text: '你好，数字人创作助手。🎉' } }) + '\n\n'
    // 逐 7 字节切开：汉字/emoji 的 UTF8 序列必然被切在字节中间。
    const bytes = ENCODER.encode(text)
    const parts: Uint8Array[] = []
    for (let index = 0; index < bytes.length; index += 7) {
      parts.push(bytes.slice(index, index + 7))
    }
    async function* split(): AsyncGenerator<Uint8Array> {
      for (const part of parts) yield part
    }
    const events = await collect(parseSse(split()))
    expect(events).toHaveLength(1)
    expect((events[0].payload as { text: string }).text).toBe('你好，数字人创作助手。🎉')
  })

  test('CRLF 边界、注释行忽略、event 行透传（缺省回落 snapshot）', async () => {
    const stream = [
      ': 20 秒心跳注释行\r\n\r\n',
      'event: transcript.final\r\n',
      'data: ' + envelope(2, 'e-2') + '\r\n\r\n',
      'data: ' + envelope(3, 'e-3') + '\r\n\r\n',
      'event: assistant.delta\n',
      'data: ' + JSON.stringify({ v: 1, eventId: 'e-4', sessionId: 's-1', leaseEpoch: 1, mediaEpoch: 1, turnId: null, turnEpoch: null, seq: 4, occurredAt: '2026-09-23T00:00:00Z', payload: { delta: '字' } }) + '\n\n',
    ]
    const events = await collect(parseSse(chunksOf(stream)))
    // 注释帧无 data → 不产出。
    expect(events.map((event) => event.seq)).toEqual([2, 3, 4])
    expect(events[0].type).toBe('transcript.final')
    expect(events[1].type).toBe('session.snapshot') // 无 event: 行回落
    expect(events[2].type).toBe('assistant.delta')
  })

  test('多行 data 拼接成合法 JSON 时完整交付（SSE 规范语义）', async () => {
    const json = envelope(7, 'e-7')
    const half = Math.floor(json.length / 2)
    const stream = [
      'event: usage.updated\n',
      `data: ${json.slice(0, half)}\n`,
      `data: ${json.slice(half)}\n\n`,
    ]
    const events = await collect(parseSse(chunksOf(stream)))
    expect(events).toHaveLength(1)
    expect(events[0].seq).toBe(7)
    expect(events[0].type).toBe('usage.updated')
  })

  test('帧切在边界符中间（\\r\\n|\\r\\n）也不裂帧', async () => {
    const stream = ['data: ' + envelope(1, 'e-1') + '\r', '\n\r', '\ndata: ' + envelope(2, 'e-2') + '\n\n']
    const events = await collect(parseSse(chunksOf(stream)))
    expect(events.map((event) => event.seq)).toEqual([1, 2])
  })

  test('单帧超过 64KiB 上限拒绝（不无限缓冲）', async () => {
    const huge = 'data: ' + envelope(1, 'e-1', { payload: { text: 'x'.repeat(70 * 1024) } }) + '\n\n'
    await expect(collect(parseSse(chunksOf([huge])))).rejects.toThrow('64KiB')
  })
})

/** 可换号账号桩 + 可编程 api.openEvents。 */
function makeHarness(streamTexts: string[]) {
  const state = { accountId: 'account-a', epoch: 0 }
  let controller = new AbortController()
  const port: AccountSessionPort = {
    capture: (): AccountTicket => ({ accountId: state.accountId, epoch: state.epoch, signal: controller.signal }),
    isCurrent: (ticket) => ticket.accountId === state.accountId && ticket.epoch === state.epoch,
  }
  let call = 0
  const openEvents = vi.fn(async () => {
    const text = streamTexts[Math.min(call, streamTexts.length - 1)]
    call += 1
    return new Response(ENCODER.encode(text))
  })
  const api = { openEvents } as unknown as DigitalHumanApi
  return { port, api, openEvents, switchAccount(id: string) { state.accountId = id; state.epoch += 1; controller.abort(); controller = new AbortController() } }
}

describe('TC105E-03-01 缺口与去重（useDigitalHumanEvents）', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  test('seq 缺口：标记「未恢复」并重连续订拉 snapshot（不重发 turn、事件不重复送达）', async () => {
    const first = `event: session.snapshot\ndata: ${envelope(1, 'e-1', { payload: { replayComplete: true } })}\n\n`
      + `event: session.state\ndata: ${envelope(2, 'e-2', { payload: { state: 'ready' } })}\n\n`
      // 缺 3、4：5 直接到达 → 触发 snapshot 重续订。
      + `event: usage.updated\ndata: ${envelope(5, 'e-5')}\n\n`
    const second = `event: session.snapshot\ndata: ${envelope(5, 'e-5-replay', { payload: { state: 'ready', replayComplete: true } })}\n\n`
    const harness = makeHarness([first, second])
    const state = useDigitalHumanEvents(harness.api, harness.port)
    const received: DhEvent[] = []
    state.onEvent((event) => received.push(event))

    await state.connect('s-1')
    // 缺口触发 abort → 重连（2 秒退避）。
    await vi.advanceTimersByTimeAsync(2500)

    expect(state.gapNotice.value).toBe(true)
    expect(state.stateSeq.value).toBeGreaterThanOrEqual(5)
    // e-5 重放帧与首帧 eventId 不同（e-5-replay）；e-5 已送达一次。不重复 = 未见相同 eventId 两次。
    const ids = received.map((event) => event.eventId)
    expect(new Set(ids).size).toBe(ids.length)
    // 重连续订带 afterSeq（只查状态，不重发 turn —— 无 POST 类调用面）。
    expect(harness.openEvents).toHaveBeenCalledTimes(2)
  })

  test('eventId 严格去重：同一事件重放不二次送达', async () => {
    const stream = `data: ${envelope(1, 'e-1')}\n\ndata: ${envelope(1, 'e-1')}\n\n`
    const harness = makeHarness([stream])
    const state = useDigitalHumanEvents(harness.api, harness.port)
    const received: DhEvent[] = []
    state.onEvent((event) => received.push(event))
    await state.connect('s-1')
    expect(received).toHaveLength(1)
  })

  test('换号：旧流上的事件全部丢弃（新账号不收旧字幕）', async () => {
    let releaseStream!: (value: string) => void
    const openEvents = vi.fn(async () => new Promise<Response>((resolve) => {
      releaseStream = (text: string) => resolve(new Response(ENCODER.encode(text)))
    }))
    const harness = makeHarness([])
    harness.openEvents.mockImplementation(openEvents)
    const state = useDigitalHumanEvents(harness.api, harness.port)
    const received: DhEvent[] = []
    state.onEvent((event) => received.push(event))

    const connecting = state.connect('s-1')
    releaseStream('')
    await connecting
    // 账号切到 B：旧连接随后推来的旧账号事件必须被丢弃。
    harness.switchAccount('account-b')
    releaseStream(`data: ${envelope(1, 'e-old')}\n\n`)
    await vi.advanceTimersByTimeAsync(0)
    expect(received).toHaveLength(0)
    expect(state.stateSeq.value).toBe(0)
  })

  test('snapshot 的 seq 只推进状态水位，不推进字幕水位（K06 双水位）', async () => {
    const stream = `event: session.snapshot\ndata: ${envelope(9, 'e-snap', { payload: { replayComplete: true } })}\n\n`
    const harness = makeHarness([stream])
    const state = useDigitalHumanEvents(harness.api, harness.port)
    await state.connect('s-1')
    expect(state.stateSeq.value).toBe(9)
    expect(state.contentSeq.value).toBe(0)
  })
})
