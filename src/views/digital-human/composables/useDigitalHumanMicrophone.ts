/**
 * 按键麦克风状态机（任务书 #105E C105E-04 / 共享契约 K07）。
 *
 * MicState = idle→requesting→recording→transcribing→idle / error。getUserMedia 仅显式点击触发
 * （audio echoCancellation/noiseSuppression 可请求、绝不假设浏览器采样率=16k；不申请 camera）。
 * AudioWorklet 经 `new URL('./pcm-worklet.ts', import.meta.url)` 本地加载（无 CDN/无
 * ScriptProcessor）；PCM 按 K07 帧（8 字节 BE header + LE int16）走一次性资格 WS。
 * end 后立即停采集（tracks/worklet/AudioContext），WS 只等识别状态；60 秒到限自动 submit；
 * bufferedAmount>64000 持续 2 秒 → abort 并提示重新录制（不静默丢帧）。权限 Promise 迟到且
 * ticket 已失效 → 立即 stop 刚拿到的 tracks、不进 recording。
 */
import { onScopeDispose, ref, type Ref } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { Session } from '../../../types/digital-human'

export type MicState = 'idle' | 'requesting' | 'recording' | 'transcribing' | 'error'

/** 浏览器媒体/WS 环境的最小结构面（测试注入假实现；happy-dom 无 WebRTC/WebSocket 语义）。 */
export interface MicStreamTrack {
  kind: string
  stop(): void
}

export interface MicStreamLike {
  getTracks(): MicStreamTrack[]
}

export interface MicWorkletNodeLike {
  port: { onmessage: ((event: { data: ArrayBuffer }) => void) | null; postMessage(message: unknown): void }
  disconnect(): void
}

export interface MicAudioContextLike {
  audioWorklet: { addModule(url: URL | string): Promise<void> }
  createMediaStreamSource(stream: MicStreamLike): { connect(node: MicWorkletNodeLike): void; disconnect(): void }
  close(): Promise<void>
}

export interface MicWebSocketLike {
  send(data: string | ArrayBufferLike): void
  close(code?: number, reason?: string): void
  bufferedAmount: number
  readyState: number
  onopen: (() => void) | null
  onclose: (() => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
}

export interface MicrophoneEnvironment {
  getUserMedia(constraints: MediaStreamConstraints): Promise<MicStreamLike>
  createAudioContext(): MicAudioContextLike
  createWorkletNode(context: MicAudioContextLike, name: string): MicWorkletNodeLike
  openWebSocket(url: string): MicWebSocketLike
  workletUrl(): URL
}

export const K07_MAX_SAMPLE_COUNT = 1600 // 100ms 上限；默认块 20ms=320
export const MIC_MAX_DURATION_MS = 60_000
export const MIC_BACKPRESSURE_BYTES = 64_000
export const MIC_BACKPRESSURE_WINDOW_MS = 2_000

/** 浏览器真实环境实现（E-06/H 真机路径；单测注入 fake）。 */
function browserEnvironment(): MicrophoneEnvironment {
  return {
    async getUserMedia(constraints) {
      const stream = await navigator.mediaDevices.getUserMedia(constraints)
      return stream as unknown as MicStreamLike
    },
    createAudioContext() {
      return new AudioContext() as unknown as MicAudioContextLike
    },
    createWorkletNode(context, name) {
      return new AudioWorkletNode(context as unknown as BaseAudioContext, name) as unknown as MicWorkletNodeLike
    },
    openWebSocket(url) {
      return new WebSocket(url) as unknown as MicWebSocketLike
    },
    workletUrl() {
      return new URL('../audio/pcm-worklet.ts', import.meta.url)
    },
  }
}

export function useDigitalHumanMicrophone(
  api: DigitalHumanApi,
  account: AccountSessionPort,
  options: {
    session: () => Session | null
    environment?: MicrophoneEnvironment
    maxDurationMs?: number
    transcribeTimeoutMs?: number
  } = { session: () => null },
): {
  state: Ref<MicState>
  errorMessage: Ref<string | null>
  /** 停止采集后等待识别完成的确认（由 SSE turn 事件或 WS 关闭推进）。 */
  start: () => Promise<void>
  submit: () => Promise<void>
  abort: () => Promise<void>
  dispose: () => void
} {
  const state = ref<MicState>('idle')
  const errorMessage = ref<string | null>(null)
  const environment = options.environment ?? browserEnvironment()
  const maxDurationMs = options.maxDurationMs ?? MIC_MAX_DURATION_MS
  const transcribeTimeoutMs = options.transcribeTimeoutMs ?? 15_000

  let stream: MicStreamLike | null = null
  let context: MicAudioContextLike | null = null
  let source: { connect(node: MicWorkletNodeLike): void; disconnect(): void } | null = null
  let worklet: MicWorkletNodeLike | null = null
  let socket: MicWebSocketLike | null = null
  let ticket: ReturnType<AccountSessionPort['capture']> | null = null
  let generation = 0
  let sequence = 0
  let totalSamples = 0
  let maxDurationTimer: ReturnType<typeof setTimeout> | null = null
  let backpressureTimer: ReturnType<typeof setTimeout> | null = null
  let transcribeTimer: ReturnType<typeof setTimeout> | null = null
  let sendingPaused = false
  let recording = false

  function stopCapture(): void {
    recording = false
    if (maxDurationTimer != null) {
      clearTimeout(maxDurationTimer)
      maxDurationTimer = null
    }
    if (backpressureTimer != null) {
      clearTimeout(backpressureTimer)
      backpressureTimer = null
    }
    worklet?.disconnect()
    worklet = null
    source?.disconnect()
    source = null
    void context?.close().catch(() => undefined)
    context = null
    for (const track of stream?.getTracks() ?? []) track.stop()
    stream = null
  }

  function teardown(): void {
    generation += 1
    stopCapture()
    if (transcribeTimer != null) {
      clearTimeout(transcribeTimer)
      transcribeTimer = null
    }
    if (socket != null) {
      const socketToClose = socket
      socket = null
      try {
        socketToClose.onclose = null
        socketToClose.close()
      } catch {
        // 已断开的 socket 再 close 是幂等噪声
      }
    }
    ticket = null
  }

  function finishTranscribing(): void {
    if (transcribeTimer != null) {
      clearTimeout(transcribeTimer)
      transcribeTimer = null
    }
    if (socket != null) {
      const socketToClose = socket
      socket = null
      try {
        socketToClose.close()
      } catch {
        // 幂等噪声
      }
    }
    state.value = 'idle'
  }

  /** K07 帧：8 字节 BE header（sequence/sampleCount）+ LE int16。 */
  function frameOf(pcm: Int16Array): ArrayBuffer {
    const frame = new Uint8Array(8 + pcm.byteLength)
    const view = new DataView(frame.buffer)
    view.setUint32(0, sequence, false)
    view.setUint32(4, pcm.length, false)
    frame.set(new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength), 8)
    return frame.buffer
  }

  function sendFrame(pcm: Int16Array): void {
    if (!recording || socket == null || socket.readyState !== 1) return
    if (pcm.length === 0 || pcm.length > K07_MAX_SAMPLE_COUNT) {
      // 超限块拆分发送（默认 20ms=320 远小于上限，兜底长块）。
      for (let offset = 0; offset < pcm.length; offset += K07_MAX_SAMPLE_COUNT) {
        sendFrame(pcm.subarray(offset, Math.min(offset + K07_MAX_SAMPLE_COUNT, pcm.length)))
      }
      return
    }
    if (sendingPaused) return // 背压暂停：不静默丢帧（2 秒未恢复由 abort 收口）
    socket.send(frameOf(pcm))
    sequence += 1
    totalSamples += pcm.length
    if (socket.bufferedAmount > MIC_BACKPRESSURE_BYTES) {
      if (backpressureTimer == null) {
        sendingPaused = true
        backpressureTimer = setTimeout(() => {
          backpressureTimer = null
          if (socket != null && socket.bufferedAmount > MIC_BACKPRESSURE_BYTES) {
            errorMessage.value = '网络拥堵，本段录音已放弃——请重新录制这段话。'
            void abort()
          } else {
            sendingPaused = false
          }
        }, MIC_BACKPRESSURE_WINDOW_MS)
      }
    }
  }

  async function start(): Promise<void> {
    if (state.value === 'requesting' || state.value === 'recording') return
    const session = options.session()
    if (!session) {
      errorMessage.value = '会话未就绪，无法开始录音。'
      state.value = 'error'
      return
    }
    generation += 1
    const run = generation
    ticket = account.capture()
    state.value = 'requesting'
    errorMessage.value = null
    try {
      const granted = await environment.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
      })
      if (run !== generation || !account.isCurrent(ticket!)) {
        // 权限迟到且票据失效：立即停掉刚拿到的 tracks，绝不进 recording。
        for (const track of granted.getTracks()) track.stop()
        return
      }
      stream = granted
      // 一次性连接资格（30 秒）→ 同源 WS。
      const grant = await api.createConnectionGrant(session.id, { leaseEpoch: session.leaseEpoch, channel: 'audio' })
      if (run !== generation || !account.isCurrent(ticket!)) {
        stopCapture()
        return
      }
      const wsUrl = new URL(grant.wsPath, window.location.origin).toString()
      socket = environment.openWebSocket(wsUrl)
      await new Promise<void>((resolve, reject) => {
        const openTimer = setTimeout(() => reject(new Error('音频通道连接超时')), 8_000)
        socket!.onopen = () => {
          clearTimeout(openTimer)
          resolve()
        }
        socket!.onclose = () => {
          clearTimeout(openTimer)
          reject(new Error('音频通道已关闭'))
        }
      })
      if (run !== generation || !account.isCurrent(ticket!)) {
        stopCapture()
        return
      }
      socket.onclose = () => {
        if (state.value === 'transcribing') finishTranscribing()
      }
      socket.onmessage = () => {
        // accepted/错误状态经 SSE 权威推进；WS 消息只作采集生命周期参考。
      }
      socket.send(JSON.stringify({
        v: 1, type: 'auth', grant: grant.grant, leaseEpoch: session.leaseEpoch,
        requestId: crypto.randomUUID(), format: 'pcm_s16le', sampleRate: 16000, channels: 1,
      }))

      context = environment.createAudioContext()
      await context.audioWorklet.addModule(environment.workletUrl())
      if (run !== generation || !account.isCurrent(ticket!)) {
        stopCapture()
        return
      }
      worklet = environment.createWorkletNode(context, 'pcm-16k')
      // PCM 通路：worklet 每次 render 量子 → K07 帧 → WS（背压/暂停在 sendFrame 内收口）。
      worklet.port.onmessage = (event) => {
        if (run !== generation || ticket == null || !account.isCurrent(ticket)) return
        sendFrame(new Int16Array(event.data))
      }
      source = context.createMediaStreamSource(stream)
      source.connect(worklet)
      sequence = 0
      totalSamples = 0
      recording = true
      state.value = 'recording'
      maxDurationTimer = setTimeout(() => {
        // 60 秒到限自动提交（不静默截断）。
        void submit()
      }, maxDurationMs)
    } catch (failure) {
      if (run !== generation) return
      stopCapture()
      if (ticket != null && !account.isCurrent(ticket)) return
      state.value = 'error'
      errorMessage.value = failure instanceof Error ? failure.message : '无法开始录音（请检查麦克风权限）。'
    }
  }

  async function submit(): Promise<void> {
    if (!recording || socket == null) return
    const summary = { v: 1 as const, type: 'end' as const, nextSequence: sequence, totalSamples }
    stopCapture() // end 发出后立即停采集；WS 只等识别状态。
    try {
      socket.send(JSON.stringify(summary))
    } catch {
      // 发送失败由服务端收口：本段无派发时用户可重录（K07）。
    }
    state.value = 'transcribing'
    transcribeTimer = setTimeout(finishTranscribing, transcribeTimeoutMs)
  }

  async function abort(): Promise<void> {
    const socketRef = socket
    stopCapture()
    if (socketRef != null && socketRef.readyState === 1) {
      try {
        socketRef.send(JSON.stringify({ v: 1, type: 'abort' }))
      } catch {
        // 忽略：abort 尽力而为
      }
    }
    if (socketRef != null) {
      try {
        socketRef.close()
      } catch {
        // 幂等噪声
      }
      socket = null
    }
    if (transcribeTimer != null) {
      clearTimeout(transcribeTimer)
      transcribeTimer = null
    }
    state.value = errorMessage.value != null ? 'error' : 'idle'
  }

  function dispose(): void {
    teardown()
    state.value = 'idle'
    errorMessage.value = null
  }

  onScopeDispose(dispose)

  return { state, errorMessage, start, submit, abort, dispose }
}
