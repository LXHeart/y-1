// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { SAVE_WINDOW_MS, useDigitalHumanTranscript, type LiveGeneration } from './useDigitalHumanTranscript'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { DhEvent } from './useDigitalHumanEvents'
import type { Session } from '../../../types/digital-human'

/** TC105E-05-01 同意与生成区分 / TC105E-05-02 删除墓碑 / TC105E-05-03 窗口与待核对。 */

const SESSION: Session = {
  id: '44444444-4444-4444-8444-444444444444', profileId: '33333333-3333-4333-8333-333333333333',
  profileRevision: 1, state: 'ready', leaseEpoch: 1, mediaEpoch: 1, controllerId: 'c-1',
  serverNow: '2026-09-23T00:00:00Z', createdAt: '2026-09-23T00:00:00Z', readyAt: null, expiresAt: null,
  pausedUntil: null, leaseExpiresAt: '2026-09-23T00:00:30Z', lastSeq: 0, saveTranscript: false,
  transcriptVersion: 1, contentEpoch: 1,
  billing: { confirmedCents: 0, platformCostCents: 180, subsidizedCents: 155, pendingCount: 2, priceTableVersion: 'pt-v1' },
  errorCode: null,
}

function eventOf(type: string, seq: number, payload: Record<string, unknown>): DhEvent {
  return {
    type, v: 1, eventId: `e-${seq}`, sessionId: SESSION.id, leaseEpoch: 1, mediaEpoch: 1,
    turnId: 't-1', turnEpoch: 1, seq, occurredAt: '2026-09-23T00:00:00Z', payload,
  } as DhEvent
}

function makeAccount() {
  const state = { accountId: 'account-a', epoch: 0 }
  const controller = new AbortController()
  const port: AccountSessionPort = {
    capture: (): AccountTicket => ({ accountId: state.accountId, epoch: state.epoch, signal: controller.signal }),
    isCurrent: (ticket) => ticket.accountId === state.accountId && ticket.epoch === state.epoch,
  }
  return { port, switchAccount() { state.epoch += 1; controller.abort() } }
}

function makeApi() {
  return {
    listTranscript: vi.fn(async () => ({
      items: [{ id: 'u-1', utteranceSeq: 1, role: 'user', text: '已保存的用户句', status: 'complete' as const, startedAt: '2026-09-23T00:00:00Z', endedAt: '2026-09-23T00:00:01Z' }],
      nextCursor: null,
    })),
    setTranscriptPreference: vi.fn(async () => ({ saveTranscript: true, version: 2 })),
    saveTranscript: vi.fn(async () => ({ savedUtterances: 3, version: 3 })),
    exportTranscript: vi.fn(async () => new Response('已保存文本\n第二行', { headers: { 'Content-Type': 'text/plain' } })),
    deleteTranscript: vi.fn(async () => ({ id: 'op-1', kind: 'transcript_delete' as const, state: 'pending' as const, resourceId: SESSION.id, resultRef: null, errorCode: null, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z' })),
    getSession: vi.fn(async () => ({ ...SESSION, saveTranscript: false, transcriptVersion: 4, contentEpoch: 5 })),
  }
}

function setup(now: () => number = () => 1_000) {
  const api = makeApi()
  const { port } = makeAccount()
  const state = useDigitalHumanTranscript(api as unknown as DigitalHumanApi, port, {
    session: () => SESSION,
    now,
  })
  return { state, api }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('TC105E-05-01 同意与生成区分', () => {
  test('delta 累积为「生成中」；final 按 status 落条目并清 delta；interrupted 不当完整播报', () => {
    const { state } = setup()

    state.applyEvent(eventOf('assistant.delta', 2, { delta: '帮你想了三句', contentEpoch: 1 }))
    state.applyEvent(eventOf('assistant.delta', 3, { delta: '口播文案。', contentEpoch: 1 }))
    expect(state.live.value).toMatchObject<LiveGeneration>({ turnId: 't-1', text: '帮你想了三句口播文案。', interrupted: false })
    expect(state.entries.value).toHaveLength(0)

    // 中断标记先到、final 带 interrupted status。
    state.applyEvent(eventOf('turn.completed', 4, { status: 'interrupted' }))
    state.applyEvent(eventOf('transcript.final', 5, {
      utteranceId: 'a-1', utteranceSeq: 2, role: 'assistant', text: '帮你想了三句口播文',
      status: 'interrupted', contentEpoch: 1,
    }))

    expect(state.live.value).toBeNull()
    const entry = state.entries.value.find((item) => item.id === 'a-1')
    expect(entry).toMatchObject({ status: 'interrupted', text: '帮你想了三句口播文' })
  })

  test('toggle 保存：API22 带 expectedVersion，回填版本；默认 false', async () => {
    const { state, api } = setup()
    expect(state.saved.value).toBe(false)

    const ok = await state.setSave(true)
    expect(ok).toBe(true)
    expect(api.setTranscriptPreference).toHaveBeenCalledWith(SESSION.id, expect.objectContaining({
      expectedVersion: 1, saveTranscript: true,
    }))
    expect(state.saved.value).toBe(true)
    expect(state.transcriptVersion.value).toBe(2)

    // 版本冲突（他处已更新）：拉权威状态对齐，不猜。
    ;(api.setTranscriptPreference as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new GrasslandHttpError(409, '版本冲突', 'dh_version_conflict'))
    await state.setSave(false)
    expect(api.getSession).toHaveBeenCalled()
    expect(state.transcriptVersion.value).toBe(4)
  })
})

describe('TC105E-05-02 删除后旧事件（P0）', () => {
  test('删除成功后旧 contentEpoch 的字幕/生成事件不复活', async () => {
    const { state } = setup()
    await state.reload()
    expect(state.entries.value).toHaveLength(1)

    state.applyEvent(eventOf('assistant.delta', 6, { delta: '删除前的在途生成', contentEpoch: 1 }))
    const ok = await state.deleteSaved()
    expect(ok).toBe(true)
    expect(state.entries.value).toHaveLength(0)
    expect(state.live.value).toBeNull()

    // 旧 epoch 迟到：final/delta 都被墓碑拦截（不复活、不重开生成区）。
    state.applyEvent(eventOf('transcript.final', 7, {
      utteranceId: 'a-2', utteranceSeq: 3, role: 'assistant', text: '迟到的旧字幕', status: 'complete', contentEpoch: 1,
    }))
    state.applyEvent(eventOf('assistant.delta', 8, { delta: '迟到 delta', contentEpoch: 1 }))
    expect(state.entries.value).toHaveLength(0)
    expect(state.live.value).toBeNull()
  })

  test('删除说明保留展示（已保存素材删除说明在组件层，见 Transcript.test）', () => {
    const { state } = setup()
    expect(state.tombstoneEpoch.value).toBe(0)
  })
})

describe('TC105E-05-03 窗口与待核对', () => {
  test('ended 后 10 分钟内可保存；过期后 saveNow 直接拒绝（零上传）', async () => {
    let clock = 100_000
    const { state, api } = setup(() => clock)

    state.observeEnded()
    expect(state.saveWindowExpired.value).toBe(false)

    clock += SAVE_WINDOW_MS / 2
    await state.saveNow()
    expect(api.saveTranscript).toHaveBeenCalledTimes(1)

    clock += SAVE_WINDOW_MS
    // 到点翻转由截止定时器驱动（fake timers 推进）。
    await vi.advanceTimersByTimeAsync(SAVE_WINDOW_MS + 10)
    expect(state.saveWindowExpired.value).toBe(true)
    await state.saveNow()
    // 过期后不再发起上传（不能从内存悄悄重新上传已过期内容）。
    expect(api.saveTranscript).toHaveBeenCalledTimes(1)
  })

  test('刷新后窗口未知：服务端 410 → 本地置过期并禁用后续尝试', async () => {
    const clock = 100_000
    const { state, api } = setup(() => clock)
    // 未 observeEnded（模拟刷新后窗口未知）。
    ;(api.saveTranscript as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new GrasslandHttpError(410, '保存窗口已过', 'dh_content_expired'))

    const first = await state.saveNow()
    expect(first).toBe(false)
    expect(state.saveWindowExpired.value).toBe(true)

    await state.saveNow()
    expect(api.saveTranscript).toHaveBeenCalledTimes(1)
  })

  test('导出：仅已保存文本接口（API25）；ObjectURL 用后即撤；失败 false 可重试', async () => {
    const { state, api } = setup()
    const created: string[] = []
    const revoked: string[] = []
    Object.assign(URL, {
      createObjectURL: vi.fn((blob: Blob) => {
        const url = `blob:export-${created.length + 1}`
        created.push(url)
        void blob
        return url
      }),
      revokeObjectURL: vi.fn((url: string) => revoked.push(url)),
    })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    const ok = await state.exportTxt()
    expect(ok).toBe(true)
    expect(created).toHaveLength(1)
    expect(revivedOk(revoked, created[0])).toBe(true)
    expect(clickSpy).toHaveBeenCalled()

    // 失败路径：保留手动重试（返回 false，不抛出）。
    ;(api.exportTranscript as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('网络中断'))
    const retry = await state.exportTxt()
    expect(retry).toBe(false)

    // 正文不进任何持久存储（只有内存与一次性 ObjectURL）。
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })
})

function revivedOk(revoked: string[], url: string): boolean {
  return revoked.includes(url)
}

describe('TC105E-05-03 结束面板：金额与窗口展示（EndPanel 纯组件）', () => {
  test('confirmedCents null → 待核对（不以 0 冒充）；真正 0 显示 ¥0.00；pending 徽标', async () => {
    const { mount } = await import('@vue/test-utils')
    const { default: DigitalHumanEndPanel } = await import('../components/DigitalHumanEndPanel.vue')
    const unknown = mount(DigitalHumanEndPanel, {
      props: { confirmedCents: null, pendingCount: 1, windowDeadline: null, windowExpired: false },
    })
    expect(unknown.get('[data-testid="dh-end-pending"]').text()).toContain('1 笔待核对')
    expect(unknown.text()).toContain('待核对')

    const realZero = mount(DigitalHumanEndPanel, {
      props: { confirmedCents: 0, pendingCount: 0, windowDeadline: Date.now() + 60_000, windowExpired: false },
    })
    expect(realZero.text()).toContain('¥0.00')
    expect(realZero.find('[data-testid="dh-end-pending"]').exists()).toBe(false)

    const expired = mount(DigitalHumanEndPanel, {
      props: { confirmedCents: 120, pendingCount: 0, windowDeadline: Date.now() - 1, windowExpired: true },
    })
    expect(expired.get('[data-testid="dh-end-window-expired"]').text()).toContain('已过期')
    expect((expired.get('[data-testid="dh-end-save"]').element as HTMLButtonElement).disabled).toBe(true)
  })
})
