// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useDigitalHumanMedia, type MediaStreamLike, type RtcPeerLike } from './useDigitalHumanMedia'
import DigitalHumanStage from '../components/DigitalHumanStage.vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { Session } from '../../../types/digital-human'

/** TC105E-03-04 自动播放/ICE 失败：有手动动作与超时错误，无假 ready；重试不新建 session。 */

const SESSION: Session = {
  id: '44444444-4444-4444-8444-444444444444',
  profileId: '33333333-3333-4333-8333-333333333333',
  profileRevision: 1,
  state: 'connecting',
  leaseEpoch: 1,
  mediaEpoch: 1,
  controllerId: 'c-1',
  serverNow: '2026-09-23T00:00:00Z',
  createdAt: '2026-09-23T00:00:00Z',
  readyAt: null,
  expiresAt: null,
  pausedUntil: null,
  leaseExpiresAt: '2026-09-23T00:00:30Z',
  lastSeq: 0,
  saveTranscript: false,
  transcriptVersion: 1,
  contentEpoch: 1,
  billing: { confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 0, priceTableVersion: 'pt-v1' },
  errorCode: null,
}

function makeAccount(initial = 'account-a') {
  const state = { accountId: initial, epoch: 0 }
  let controller = new AbortController()
  const port: AccountSessionPort = {
    capture: (): AccountTicket => ({ accountId: state.accountId, epoch: state.epoch, signal: controller.signal }),
    isCurrent: (ticket) => ticket.accountId === state.accountId && ticket.epoch === state.epoch,
  }
  return {
    port,
    switchAccount(id: string) {
      state.accountId = id
      state.epoch += 1
      controller.abort()
      controller = new AbortController()
    },
  }
}

function makeTrack(kind = 'video'): { kind: string; stop: ReturnType<typeof vi.fn> } {
  return { kind, stop: vi.fn() }
}

function makeStream(tracks: Array<{ kind: string; stop: ReturnType<typeof vi.fn> }>): MediaStreamLike {
  return { id: `stream-${Math.random()}`, getTracks: () => tracks }
}

/** 可编程假 peer：ICE 完成时机/track 投递由测试控制。 */
function makePeerHarness() {
  const created: Array<FakePeer> = []
  class FakePeer {
    iceGatheringState = 'gathering'
    localDescription: { sdp: string } | null = null
    ontrack: RtcPeerLike['ontrack'] = null
    onicegatheringstatechange: (() => void) | null = null
    closed = false
    transceivers: string[] = []
    constructor() { created.push(this) }
    addTransceiver(kind: string, init: { direction: string }) { this.transceivers.push(`${kind}:${init.direction}`) }
    async createOffer() { return { sdp: 'offer-sdp', type: 'offer' as const } }
    async setLocalDescription(desc: { sdp: string }) { this.localDescription = { sdp: desc.sdp } }
    async setRemoteDescription() { /* answer accepted */ }
    close() { this.closed = true }
    completeIce() { this.iceGatheringState = 'complete'; this.onicegatheringstatechange?.() }
    deliver(stream: MediaStreamLike) { this.ontrack?.({ streams: [stream] }) }
  }
  return { created, factory: () => new FakePeer() as unknown as RtcPeerLike }
}

function makeApi() {
  return {
    submitOffer: vi.fn(async (_input: unknown) => ({ sdp: 'answer-sdp', type: 'answer' as const, mediaEpoch: SESSION.mediaEpoch, iceServers: [] as unknown[] })),
    mediaReady: vi.fn(async () => ({ ...SESSION, state: 'ready' as const })),
    createSession: vi.fn(async () => ({ ...SESSION })),
  }
}

/** 传给 composable 时按接口收窄（保留 mock 断言能力）。 */
function asApi(mocks: ReturnType<typeof makeApi>): DigitalHumanApi {
  return mocks as unknown as DigitalHumanApi
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('TC105E-03-04 ICE 失败与自动播放', () => {
  test('ICE 10 秒未完成：超时错误、无假 ready（mediaReady 零调用）、旧 peer 关闭', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const { created, factory } = makePeerHarness()
    const media = useDigitalHumanMedia(asApi(api), port, { peerFactory: factory, iceTimeoutMs: 10_000 })

    const pending = media.connect(SESSION)
    const assertion = pending.catch(() => undefined)
    await vi.advanceTimersByTimeAsync(10_100)
    await assertion

    expect(media.errorCode.value).toBe('ice_timeout')
    expect(media.errorMessage.value).toContain('ICE')
    // 无假 ready：未提交 offer、未上报 media-ready、无流。
    expect(api.submitOffer).not.toHaveBeenCalled()
    expect(api.mediaReady).not.toHaveBeenCalled()
    expect(media.stream.value).toBeNull()
    expect(created[0].closed).toBe(true)
  })

  test('ICE 超时后 retry：只重走 offer（同 session 原键幂等），不新建 session', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const { created, factory } = makePeerHarness()
    const media = useDigitalHumanMedia(asApi(api), port, { peerFactory: factory, iceTimeoutMs: 10_000 })

    const first = media.connect(SESSION).catch(() => undefined)
    await vi.advanceTimersByTimeAsync(10_100)
    await first
    expect(api.createSession).not.toHaveBeenCalled()

    // 第二次 ICE 正常完成 → 全链走通（offer→answer→media-ready→stream）。
    const second = media.connect(SESSION)
    const peer = created[1]
    await vi.advanceTimersByTimeAsync(20)
    peer.completeIce()
    const stream = makeStream([makeTrack('video'), makeTrack('audio')])
    peer.deliver(stream)
    await vi.advanceTimersByTimeAsync(20)
    await second

    expect(api.submitOffer).toHaveBeenCalledTimes(1)
    expect(api.mediaReady).toHaveBeenCalledWith(SESSION.id, expect.objectContaining({ mediaEpoch: SESSION.mediaEpoch }))
    expect(media.stream.value).toBe(stream)
    expect(media.errorCode.value).toBeNull()
  })

  test('A→B→A 迟到 offer/track：结果丢弃、迟到 track 立即 stop、无假 ready', async () => {
    const api = makeApi()
    let resolveOffer!: (value: { sdp: string; type: 'answer'; mediaEpoch: number; iceServers: unknown[] }) => void
    api.submitOffer.mockImplementation(() => new Promise((resolve) => { resolveOffer = resolve }))
    const { port, switchAccount } = makeAccount()
    const { created, factory } = makePeerHarness()
    const media = useDigitalHumanMedia(asApi(api), port, { peerFactory: factory, iceTimeoutMs: 10_000 })

    const pending = media.connect(SESSION)
    const peer = created[0]
    peer.completeIce()
    // 先让 offer 真正发出（挂起在 submitOffer），再换号。
    await vi.advanceTimersByTimeAsync(20)
    // offer 在途时换号（A→B→A 都使旧票失效——epoch 已变）。
    switchAccount('account-b')
    switchAccount('account-a')
    resolveOffer({ sdp: 'answer-sdp', type: 'answer', mediaEpoch: 1, iceServers: [] })
    await vi.advanceTimersByTimeAsync(20)
    await pending

    // 迟到 answer/track 全部丢弃。
    const lateTrack = makeTrack('video')
    peer.deliver(makeStream([lateTrack]))
    expect(lateTrack.stop).toHaveBeenCalled()
    expect(media.stream.value).toBeNull()
    expect(api.mediaReady).not.toHaveBeenCalled()
    // 陈旧结果不算当前错误（等待用户/上层显式重连）。
    expect(media.errorMessage.value).toBeNull()
  })

  test('reset(epoch)：旧 peer 关闭并按新 mediaEpoch 重建；旧 epoch 迟到重置被忽略', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const { created, factory } = makePeerHarness()
    const media = useDigitalHumanMedia(asApi(api), port, { peerFactory: factory, iceTimeoutMs: 10_000 })

    const first = media.connect(SESSION)
    // 先等 connect 注册好 ontrack，再完成 ICE/投递流（与真实时序一致）。
    await vi.advanceTimersByTimeAsync(20)
    created[0].completeIce()
    created[0].deliver(makeStream([makeTrack('video')]))
    await vi.advanceTimersByTimeAsync(20)
    await first
    expect(media.stream.value).not.toBeNull()

    const reset = media.reset(2, SESSION)
    expect(created[0].closed).toBe(true)
    expect(media.stream.value).toBeNull()
    // 等新 peer 建立并注册好 ontrack 后再完成 ICE/投递流。
    await vi.advanceTimersByTimeAsync(20)
    created[1].completeIce()
    created[1].deliver(makeStream([makeTrack('video')]))
    await vi.advanceTimersByTimeAsync(20)
    await reset

    expect(api.submitOffer).toHaveBeenLastCalledWith(SESSION.id, expect.objectContaining({ mediaEpoch: 2 }))
    // 旧 epoch 重置（interrupt 乱序迟到）：忽略，不再动 peer。
    const peerCount = created.length
    await media.reset(1, SESSION)
    expect(created.length).toBe(peerCount)
  })

  test('autoplay 被拒：显示「点击播放」手动动作而非静默无声（Stage 纯组件）', async () => {
    vi.useRealTimers()
    // happy-dom 的 srcObject setter 校验 MediaStream 类型；用实例可写属性替身让赋值通过。
    Object.defineProperty(HTMLMediaElement.prototype, 'srcObject', {
      configurable: true,
      get(this: HTMLVideoElement & { __srcObject?: unknown }) { return this.__srcObject ?? null },
      set(this: HTMLVideoElement & { __srcObject?: unknown }, value: unknown) { this.__srcObject = value },
    })
    const play = vi.fn<() => Promise<void>>(() => Promise.reject(new DOMException('NotAllowed', 'NotAllowedError')))
    const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(play)

    const stream = makeStream([makeTrack('video')])
    const wrapper = mount(DigitalHumanStage, {
      props: { stream, state: 'ready', connecting: false, errorCode: null, errorMessage: null },
      attachTo: document.body,
    })
    await flushPromises()

    expect(playSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="dh-stage-play"]').exists()).toBe(true)

    // 点击后恢复播放。
    play.mockResolvedValueOnce(undefined)
    await wrapper.get('[data-testid="dh-stage-play"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="dh-stage-play"]').exists()).toBe(false)
  })

  test('Stage 错误态：错误文案 + 重新连接入口；AI 标识常驻', () => {
    const wrapper = mount(DigitalHumanStage, {
      props: { stream: null, state: 'ready', connecting: false, errorCode: 'ice_timeout', errorMessage: 'ICE 收集超时（10 秒）', testOnly: true },
    })
    expect(wrapper.get('[data-testid="dh-stage-error"]').text()).toContain('ICE')
    expect(wrapper.find('button.gl-link').exists()).toBe(true)
    expect(wrapper.get('[data-testid="dh-ai-badge"]').text()).toContain('AI 生成')
    expect(wrapper.get('[data-testid="dh-ai-badge"]').text()).toContain('测试画面')
  })
})
