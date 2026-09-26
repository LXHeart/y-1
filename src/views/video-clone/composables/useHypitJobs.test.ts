// @vitest-environment happy-dom
// useHypitJobs.test.ts — C107-21 (TC107-21-02 迟到事件/世代/清理)：SSE 事件按
// sequence 去重、终帧即停、stop 后不再接受事件。EventSource 以受控替身驱动。
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useHypitJobs } from './useHypitJobs'
import type { HypitJob } from '../../../types/hypit'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  constructor(public readonly url: string) {
    FakeEventSource.instances.push(this)
  }
  close(): void {
    this.closed = true
  }
  emit(payload: unknown): void {
    this.onmessage?.({ data: JSON.stringify(payload) })
  }
}

function jobOf(state: HypitJob['state'], id = 'job-1'): HypitJob {
  return {
    id, projectId: 'p1', kind: 'build.submit', state, phase: null, progress: null,
    checkpointSummary: null, blockedReason: null, nextActions: [],
    error: null, createdAt: '2026-09-26T00:00:00Z', updatedAt: '2026-09-26T00:00:00Z',
  }
}

// 共享 setup-env 的全局 afterEach 会 unstub globals：每个用例前重新挂替身。
beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource)
})

describe('useHypitJobs SSE 语义', () => {
  test('sequence 去重：同序号/更旧事件丢弃', () => {
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    const source = FakeEventSource.instances[FakeEventSource.instances.length - 1] as FakeEventSource
    source.emit({ sequence: 1, kind: 'progress', data: { pct: 1 }, job: jobOf('running') })
    source.emit({ sequence: 1, kind: 'progress', data: { pct: 1 } })
    source.emit({ sequence: 0, kind: 'progress', data: { pct: 0 } })
    expect(handle.events.value).toHaveLength(1)
    expect(handle.events.value[0]?.sequence).toBe(1)
    handle.stop()
  })

  test('终态事件停止连接并回调 onTerminal', () => {
    const { watchJob } = useHypitJobs()
    const terminalJobs: HypitJob[] = []
    const handle = watchJob('p1', 'job-1', (job) => terminalJobs.push(job))
    const source = FakeEventSource.instances[FakeEventSource.instances.length - 1] as FakeEventSource
    source.emit({ sequence: 2, kind: 'terminal', data: {}, job: jobOf('succeeded') })
    expect(terminalJobs).toHaveLength(1)
    expect(source.closed).toBe(true)
    expect(handle.connected.value).toBe(false)
    handle.stop()
  })

  test('stop 后新事件不再写入（组件卸载/切工程释放）', () => {
    const { watchJob } = useHypitJobs()
    const handle = watchJob('p1', 'job-1')
    const source = FakeEventSource.instances[FakeEventSource.instances.length - 1] as FakeEventSource
    handle.stop()
    expect(source.closed).toBe(true)
    source.emit({ sequence: 9, kind: 'progress', data: {}, job: jobOf('running') })
    expect(handle.events.value).toHaveLength(0)
  })
})
