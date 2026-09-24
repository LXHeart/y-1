// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import {
  useDigitalHumanMicrophone,
  type MicStreamLike,
  type MicrophoneEnvironment,
  type MicWebSocketLike,
} from './useDigitalHumanMicrophone'
import DigitalHumanComposer from '../components/DigitalHumanComposer.vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { Session } from '../../../types/digital-human'

/** TC105E-04-02 权限晚到 / TC105E-04-03 长音频与背压 / TC105E-04-04 IME 与打断。 */

const SESSION: Session = {
  id: '44444444-4444-4444-8444-444444444444', profileId: '33333333-3333-4333-8333-333333333333',
  profileRevision: 1, state: 'ready', leaseEpoch: 1, mediaEpoch: 1, controllerId: 'c-1',
  serverNow: '2026-09-23T00:00:00Z', createdAt: '2026-09-23T00:00:00Z', readyAt: '2026-09-23T00:00:10Z',
  expiresAt: null, pausedUntil: null, leaseExpiresAt: '2026-09-23T00:00:30Z', lastSeq: 0,
  saveTranscript: false, transcriptVersion: 1, contentEpoch: 1,
  billing: { confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 0, priceTableVersion: 'pt-v1' },
  errorCode: null,
}

function makeTrack() {
  return { kind: 'audio', stop: vi.fn() }
}

function makeStream(): { stream: MicStreamLike; tracks: Array<ReturnType<typeof makeTrack>> } {
  const tracks = [makeTrack(), makeTrack()]
  return { stream: { getTracks: () => tracks }, tracks }
}

/** 可编程假环境：getUserMedia/openWebSocket 的时机与状态由各用例控制。 */
function makeEnvironment(overrides: Partial<Record<string, unknown>> = {}) {
  const calls: Record<string, number> = { addModule: 0 }
  const sockets: FakeSocket[] = []
  const worklets: Array<{ port: { onmessage: ((event: { data: ArrayBuffer }) => void) | null; postMessage: (message: unknown) => void }; disconnect: ReturnType<typeof vi.fn> }> = []
  class FakeSocket {
    bufferedAmount = 0
    readyState = 1
    sent: Array<string | ArrayBufferLike> = []
    onopen: (() => void) | null = null
    onclose: (() => void) | null = null
    onmessage: ((event: { data: unknown }) => void) | null = null
    send(data: string | ArrayBufferLike) { this.sent.push(data) }
    close() { this.readyState = 3 }
  }
  const environment: MicrophoneEnvironment = {
    async getUserMedia() {
      return makeStream().stream
    },
    createAudioContext() {
      return {
        audioWorklet: {
          async addModule() { calls.addModule += 1 },
        },
        createMediaStreamSource() { return { connect: vi.fn(), disconnect: vi.fn() } },
        close: vi.fn(async () => undefined),
      }
    },
    createWorkletNode() {
      const node = {
        port: { onmessage: null, postMessage: vi.fn() },
        disconnect: vi.fn(),
      }
      worklets.push(node)
      return node
    },
    openWebSocket() {
      const socket = new FakeSocket()
      sockets.push(socket)
      // 真实 WS 的 open 是异步事件：调用方先拿实例再挂 onopen，微任务后触发。
      void Promise.resolve().then(() => socket.onopen?.())
      return socket as unknown as MicWebSocketLike
    },
    workletUrl: () => new URL('http://127.0.0.1:3000/fake-worklet.ts'),
    ...overrides,
  } as MicrophoneEnvironment
  return { environment, sockets, calls, worklets }
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

function makeApi() {
  return {
    createConnectionGrant: vi.fn(async () => ({
      grant: 'grant-fixture', expiresAt: '2026-09-23T00:00:30Z', wsPath: '/api/digital-human/sessions/s-1/audio',
    })),
  }
}

function setup(overrides: Partial<Record<string, unknown>> = {}, options: Record<string, unknown> = {}) {
  const api = makeApi() as unknown as DigitalHumanApi
  const { port, switchAccount } = makeAccount()
  const harness = makeEnvironment(overrides)
  const mic = useDigitalHumanMicrophone(api, port, {
    session: () => SESSION,
    environment: harness.environment,
    ...options,
  })
  return { mic, harness, switchAccount, api }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('TC105E-04-02 权限晚到', () => {
  test('start 后换号再 resolve media：所有 tracks 立即 stop，state 不进 recording', async () => {
    let resolvePermission!: (stream: MicStreamLike) => void
    const tracks = [makeTrack(), makeTrack()]
    const pendingStream: MicStreamLike = { getTracks: () => tracks }
    const { mic, switchAccount, harness } = setup({
      getUserMedia: () => new Promise<MicStreamLike>((resolve) => { resolvePermission = resolve }),
    })
    const starting = mic.start()
    expect(mic.state.value).toBe('requesting')

    switchAccount('account-b')
    resolvePermission(pendingStream)
    await vi.advanceTimersByTimeAsync(50)
    await starting

    for (const track of tracks) expect(track.stop).toHaveBeenCalled()
    expect(mic.state.value).not.toBe('recording')
    // 权限迟到后不建 AudioContext/WS（零资源泄漏）。
    expect(harness.sockets).toHaveLength(0)
    expect(harness.calls.addModule).toBe(0)
  })

  test('正常路径：显式点击 → 权限 → 资格 WS → auth 帧 → worklet 挂载 → recording', async () => {
    const { mic, harness } = setup()
    const starting = mic.start()
    await vi.advanceTimersByTimeAsync(50)
    await starting

    expect(mic.state.value).toBe('recording')
    expect(harness.sockets).toHaveLength(1)
    const authFrame = JSON.parse(String(harness.sockets[0].sent[0]))
    expect(authFrame).toMatchObject({
      v: 1, type: 'auth', grant: 'grant-fixture', leaseEpoch: 1,
      format: 'pcm_s16le', sampleRate: 16000, channels: 1,
    })
    expect(harness.calls.addModule).toBe(1)
  })
})

describe('TC105E-04-03 长音频与背压', () => {
  test('60 秒到限自动 submit：发 end 帧并立即停采集', async () => {
    const { mic, harness } = setup({}, { maxDurationMs: 60_000 })
    await vi.advanceTimersByTimeAsync(50)
    await mic.start()

    expect(mic.state.value).toBe('recording')
    await vi.advanceTimersByTimeAsync(60_000)

    const socket = harness.sockets[0]
    const endFrame = JSON.parse(String(socket.sent[socket.sent.length - 1]))
    expect(endFrame).toMatchObject({ v: 1, type: 'end' })
    expect(mic.state.value).toBe('transcribing')
  })

  test('bufferedAmount 持续超阈值 2 秒：abort + 重新录制提示（不静默丢帧续录）', async () => {
    const { mic, harness } = setup()
    await vi.advanceTimersByTimeAsync(50)
    await mic.start()

    const socket = harness.sockets[0] as unknown as { bufferedAmount: number; sent: unknown[] }
    // 真实帧路径：worklet 推一段 320 样本 PCM（20ms@16k）→ K07 帧上 WS。
    const pcm = new Int16Array(320)
    for (let i = 0; i < pcm.length; i += 1) pcm[i] = Math.round(Math.sin(i / 10) * 1000)
    harness.worklets[0].port.onmessage?.({ data: pcm.buffer.slice(0) })

    const firstBinary = socket.sent.find((frame) => typeof frame !== 'string') as ArrayBuffer
    expect(firstBinary).toBeTruthy()
    const view = new DataView(firstBinary)
    expect(view.getUint32(0, false)).toBe(0) // sequence 从 0 递增
    expect(view.getUint32(4, false)).toBe(320) // sampleCount
    expect(firstBinary.byteLength).toBe(8 + 640)

    // 网络拥堵：bufferedAmount 超阈值的发送触发暂停 + 2 秒观察窗。
    socket.bufferedAmount = 70_000
    harness.worklets[0].port.onmessage?.({ data: pcm.buffer.slice(0) })
    const before = socket.sent.length
    await vi.advanceTimersByTimeAsync(2_100)

    expect(mic.state.value).toBe('error')
    expect(mic.errorMessage.value).toContain('重新录制')
    // 拥堵期间不再发 PCM 帧（暂停后丢入的帧不送）；唯一新增的是 abort 控制帧。
    expect(socket.sent.length).toBe(before + 1)
    const lastFrame = JSON.parse(String(socket.sent[socket.sent.length - 1]))
    expect(lastFrame.type).toBe('abort')
  })

  test('拥堵 2 秒内恢复（bufferedAmount 回落）：继续录音不 abort', async () => {
    const { mic, harness } = setup()
    await vi.advanceTimersByTimeAsync(50)
    await mic.start()

    const socket = harness.sockets[0] as unknown as { bufferedAmount: number }
    socket.bufferedAmount = 70_000
    await vi.advanceTimersByTimeAsync(1_000)
    socket.bufferedAmount = 1_000
    await vi.advanceTimersByTimeAsync(1_200)

    expect(mic.state.value).toBe('recording')
  })

  test('submit 后释放设备：tracks stop、worklet/context 关闭、WS 只等识别态', async () => {
    const tracks = [makeTrack()]
    const { mic } = setup({
      getUserMedia: async () => ({ getTracks: () => tracks }) as MicStreamLike,
    })
    await vi.advanceTimersByTimeAsync(50)
    await mic.start()
    await mic.submit()

    for (const track of tracks) expect(track.stop).toHaveBeenCalled()
    expect(mic.state.value).toBe('transcribing')
    // 识别态限时收口。
    await vi.advanceTimersByTimeAsync(15_100)
    expect(mic.state.value).toBe('idle')
  })
})

describe('TC105E-04-04 输入法与打断（Composer 纯组件）', () => {
  function mountComposer(overrides: Record<string, unknown> = {}) {
    return mount(DigitalHumanComposer, {
      props: {
        sessionState: 'ready',
        micState: 'idle',
        micReady: true,
        ...overrides,
      },
      attachTo: document.body,
    })
  }

  test('中文 IME 组合中 Enter 不提交；组合结束 Enter 正常提交', async () => {
    vi.useRealTimers()
    const wrapper = mountComposer()
    const input = wrapper.get('[data-testid="dh-composer-input"]')

    await input.setValue('帮我把卖点改成三句口播')
    await input.trigger('compositionstart')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toBeUndefined()

    await input.trigger('compositionend')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toEqual([['帮我把卖点改成三句口播']])
    // 发送后清空。
    expect((input.element as HTMLTextAreaElement).value).toBe('')
  })

  test('普通发送不重入：submitting 期间按钮禁用且 Enter 不发', async () => {
    vi.useRealTimers()
    const wrapper = mountComposer({ submitting: true })
    await wrapper.get('[data-testid="dh-composer-input"]').setValue('内容')
    await wrapper.get('[data-testid="dh-composer-input"]').trigger('keydown', { key: 'Enter' })
    expect((wrapper.get('[data-testid="dh-composer-send"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  test('发言期间文字发送禁用；mic 只在 micReady（新 peer ready）后可用', async () => {
    vi.useRealTimers()
    const recording = mountComposer({ micState: 'recording' })
    expect((recording.get('[data-testid="dh-composer-input"]').element as HTMLTextAreaElement).disabled).toBe(true)
    expect(recording.find('[data-testid="dh-composer-mic-submit"]').exists()).toBe(true)

    const notReady = mountComposer({ micReady: false, sessionState: 'ready' })
    expect((notReady.get('[data-testid="dh-composer-mic-start"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(notReady.get('[data-testid="dh-composer-mic-start"]').attributes('title')).toContain('等待媒体就绪')

    const ready = mountComposer({ micReady: true })
    expect((ready.get('[data-testid="dh-composer-mic-start"]').element as HTMLButtonElement).disabled).toBe(false)
    await ready.get('[data-testid="dh-composer-mic-start"]').trigger('click')
    expect(ready.emitted('mic-start')).toHaveLength(1)
  })

  test('responding 时显示打断并说话；点击 emit interrupt（不冒充立即生效）', async () => {
    vi.useRealTimers()
    const wrapper = mountComposer({ sessionState: 'responding' })
    const interrupt = wrapper.get('[data-testid="dh-composer-interrupt"]')
    await interrupt.trigger('click')
    expect(wrapper.emitted('interrupt')).toHaveLength(1)
  })

  test('拒权后可继续文字（error 态显示重新录音 + 文字输入不受阻）', async () => {
    vi.useRealTimers()
    const wrapper = mountComposer({ micState: 'error' })
    expect(wrapper.get('[data-testid="dh-composer-mic-start"]').text()).toContain('重新录音')
    expect((wrapper.get('[data-testid="dh-composer-input"]').element as HTMLTextAreaElement).disabled).toBe(false)
  })
})
