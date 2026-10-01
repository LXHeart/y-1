// @vitest-environment happy-dom
// useHypitJobs.test.ts — C107F2-13（F24/F06，§6.6）：fetch 流式 SSE——envelope
// 消费、sequence 去重、terminal 即停、stop 只断观察；Last-Event-ID 续接（重复
// sequence 不重复展示）、1/2/4/8/15 秒退避 5 次后手动重连、未登录停止重连、
// 半包/多行帧解析。旧 C107-21 三条不变量（去重/终态停/停后不写）保留。
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useHypitJobs } from './useHypitJobs'
import type { HypitJob } from '../../../types/hypit'

type CapturedRequest = { url: string; headers: Record<string, string>; signal?: AbortSignal };
type FakeStream = { enqueue(text: string): void; close(): void };

function jobOf(state: HypitJob['state'], id = 'job-1'): HypitJob {
  return {
    id, projectId: 'p1', kind: 'build.submit', state, phase: null, progress: null,
    checkpointSummary: null, blockedReason: null, nextActions: [],
    error: null, createdAt: '2026-09-26T00:00:00Z', updatedAt: '2026-09-26T00:00:00Z',
  }
}

/** SSE fetch 替身：每次 fetch 新建一个可注入字节的流；记录 URL/头/signal。 */
function installSseFetch(status = 200): { streams: FakeStream[]; requests: CapturedRequest[] } {
  const streams: FakeStream[] = []
  const requests: CapturedRequest[] = []
  const fetchImpl = (async (url: string, init: RequestInit) => {
    requests.push({ url, headers: (init.headers ?? {}) as Record<string, string>, signal: init.signal ?? undefined })
    let controller: ReadableStreamDefaultController<Uint8Array> | null = null
    const body = new ReadableStream<Uint8Array>({
      start(c) { controller = c },
    })
    streams.push({
      enqueue: (text: string) => controller?.enqueue(new TextEncoder().encode(text)),
      close: () => controller?.close(),
    })
    return { ok: status < 400, status, body } as unknown as Response
  }) as typeof fetch
  vi.stubGlobal('fetch', fetchImpl)
  return { streams, requests }
}

function envelope(jobId: string, sequence: number, type: string, data: unknown): Record<string, unknown> {
  return { id: `${jobId}:${sequence}`, sequence, type, projectId: 'p1', jobId, at: '2026-09-28T00:00:00Z', data }
}

function sseFrame(event: string, payload: unknown): string {
  const lines: string[] = [`event: ${event}`]
  for (const line of JSON.stringify(payload).split('\n')) lines.push(`data: ${line}`)
  return `${lines.join('\n')}\n\n`
}

function snapshotFrame(job: HypitJob, latestSequence: number): string {
  return sseFrame('snapshot', envelope('job-1', latestSequence, 'snapshot',
    { reset: true, job, latestSequence }))
}

const flush = (): Promise<void> => new Promise((resolve) => { setTimeout(resolve, 0) })

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useHypitJobs fetch 流式 SSE 语义', () => {
  test('sequence 去重：同序号/更旧事件丢弃，白名单外与跨 job 帧不采信', async () => {
    const { streams } = installSseFetch()
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    const stream = streams[streams.length - 1] as FakeStream
    stream.enqueue(snapshotFrame(jobOf('running'), 0))
    await vi.waitFor(() => expect(handle.job.value?.state).toBe('running'))
    stream.enqueue(sseFrame('progress', envelope('job-1', 1, 'progress', { phase: 'compiling' })))
    stream.enqueue(sseFrame('progress', envelope('job-1', 1, 'progress', { phase: 'again' })))
    stream.enqueue(sseFrame('progress', envelope('job-1', 0, 'progress', { phase: 'stale' })))
    stream.enqueue(sseFrame('progress', envelope('other-job', 5, 'progress', { phase: 'stranger' })))
    stream.enqueue(sseFrame('mystery', envelope('job-1', 6, 'progress', {})))
    await vi.waitFor(() => expect(handle.events.value).toHaveLength(1))
    expect(handle.events.value[0]?.sequence).toBe(1)
    expect(handle.events.value[0]?.type).toBe('progress')
    handle.stop()
  })

  test('terminal 事件停止连接并回调 onTerminal（快照 job 合并终态）', async () => {
    const { streams } = installSseFetch()
    const terminalJobs: HypitJob[] = []
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1', (job) => terminalJobs.push(job))
    const stream = streams[streams.length - 1] as FakeStream
    stream.enqueue(snapshotFrame(jobOf('running'), 1))
    await vi.waitFor(() => expect(handle.job.value?.state).toBe('running'))
    expect(handle.connected.value).toBe(true)
    stream.enqueue(sseFrame('terminal', envelope('job-1', 2, 'terminal', { state: 'succeeded' })))
    await vi.waitFor(() => expect(terminalJobs).toHaveLength(1))
    expect(handle.job.value?.state).toBe('succeeded')
    expect(handle.connected.value).toBe(false)
    handle.stop()
  })

  test('stop 后新事件不再写入（fetch signal 中止，只断观察不冒充取消）', async () => {
    const { streams, requests } = installSseFetch()
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    const stream = streams[streams.length - 1] as FakeStream
    await flush()
    handle.stop()
    expect(requests[0]?.signal?.aborted).toBe(true)
    stream.enqueue(snapshotFrame(jobOf('running'), 0))
    stream.enqueue(sseFrame('progress', envelope('job-1', 9, 'progress', {})))
    await flush()
    expect(handle.events.value).toHaveLength(0)
    expect(handle.job.value).toBeNull()
  })

  test('断线带 Last-Event-ID={jobId}:{sequence} 重连：重复 sequence 不展示、新帧接收', async () => {
    vi.useFakeTimers()
    const { streams, requests } = installSseFetch()
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    const first = streams[streams.length - 1] as FakeStream
    first.enqueue(snapshotFrame(jobOf('running'), 2))
    await vi.waitFor(() => expect(handle.job.value?.state).toBe('running'))
    first.close()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(requests.length).toBe(2)
    expect(requests[1]?.headers['Last-Event-ID']).toBe('job-1:2')
    const second = streams[streams.length - 1] as FakeStream
    // 注入重复 7（旧帧）与新 8：7 丢弃、8 接收、terminal 后停。
    second.enqueue(sseFrame('progress', envelope('job-1', 2, 'progress', { phase: 'dup' })))
    second.enqueue(sseFrame('progress', envelope('job-1', 3, 'progress', { phase: 'fresh' })))
    second.enqueue(sseFrame('terminal', envelope('job-1', 4, 'terminal', { state: 'succeeded' })))
    await vi.waitFor(() => expect(handle.job.value?.state).toBe('succeeded'))
    expect(handle.events.value.map((event) => event.sequence)).toEqual([3, 4])
    handle.stop()
  })

  test('退避 1/2/4/8/15 秒共 5 次，用尽转手动重连', async () => {
    vi.useFakeTimers()
    const { requests } = installSseFetch(500)
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    for (const delay of [1_000, 2_000, 4_000, 8_000, 15_000]) {
      await vi.advanceTimersByTimeAsync(delay)
    }
    // 初次 + 5 次自动重连 = 6 次 fetch；第 5 次失败后不再排定。
    expect(requests.length).toBe(6)
    expect(handle.reconnectExhausted.value).toBe(true)
    expect(handle.connected.value).toBe(false)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(requests.length).toBe(6)
    handle.reconnect()
    await vi.advanceTimersByTimeAsync(0)
    expect(requests.length).toBe(7)
    expect(handle.reconnectExhausted.value).toBe(false)
    handle.stop()
  })

  test('401 未登录：停止重连并置 authRequired', async () => {
    vi.useFakeTimers()
    const { requests } = installSseFetch(401)
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    await vi.waitFor(() => expect(handle.authRequired.value).toBe(true))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(requests.length).toBe(1)
    expect(handle.connected.value).toBe(false)
    handle.stop()
  })

  test('半包帧缓冲与多行 data 拼接（JSON 允许的换行空白）', async () => {
    const { streams } = installSseFetch()
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    const stream = streams[streams.length - 1] as FakeStream
    // 帧载荷是完整 envelope（服务端口径）；envelope JSON 在 token 边界拆成两个
    // data: 行（SSE 规范：多行载荷每行自带前缀），换行是 JSON 合法空白。
    const half = 'event: progress\ndata: {"id":"job-1:5","sequence":5,"type":"progress",'
      + '"projectId":"p1","jobId":"job-1","at":"t","data":{"phase":\n'
    stream.enqueue(half)
    await flush()
    // 半包：帧未闭合不处理。
    expect(handle.events.value).toHaveLength(0)
    stream.enqueue('data: "compiling"}}\n\n')
    await vi.waitFor(() => expect(handle.events.value).toHaveLength(1))
    expect((handle.events.value[0]?.data as { phase?: string })?.phase).toBe('compiling')
    handle.stop()
  })
})
