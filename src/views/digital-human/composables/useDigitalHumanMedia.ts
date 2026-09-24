/**
 * 数字人下行媒体（任务书 #105E C105E-03 / 共享契约 K07）。
 *
 * recvonly audio+video：addTransceiver → createOffer → setLocal → 等 ICE complete（≤10 秒）→
 * API16 提交 → setRemote(answer) → API17 media-ready（服务端事实）→ ontrack 出流。ICE 超时/协议错误
 * → 明确错误态（无假 ready），可重试且<b>不新建 session</b>。reset(epoch) 关旧 peer/track 后按新
 * mediaEpoch 重建（interrupt 语义）；每次回调回写前 ticket+generation+mediaEpoch 三重校验。
 */
import { ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { Session } from '../../../types/digital-human'

export const ICE_TIMEOUT_MS = 10_000

/** 浏览器 RTCPeerConnection 的最小结构面（测试注入假实现；happy-dom 无 WebRTC）。 */
export interface RtcPeerLike {
  localDescription: { sdp: string } | null
  addTransceiver(kind: 'audio' | 'video', init: { direction: 'recvonly' }): unknown
  createOffer(): Promise<{ sdp: string; type: 'offer' }>
  setLocalDescription(description: unknown): Promise<void>
  setRemoteDescription(description: unknown): Promise<void>
  close(): void
  ontrack: ((event: { streams: Array<{ id?: string } & object> }) => void) | null
  onicegatheringstatechange: (() => void) | null
  iceGatheringState: string
}

export interface MediaStreamLikeTrack {
  kind: string
  stop(): void
}

export interface MediaStreamLike {
  id?: string
  getTracks(): MediaStreamLikeTrack[]
}

export type MediaErrorCode = null | 'ice_timeout' | 'offer_rejected' | 'stale_result'

export function useDigitalHumanMedia(
  api: DigitalHumanApi,
  account: AccountSessionPort,
  options: {
    peerFactory?: (iceServers: unknown[]) => RtcPeerLike
    iceTimeoutMs?: number
  } = {},
): {
  stream: ShallowRef<MediaStreamLike | null>
  connecting: Ref<boolean>
  errorCode: Ref<MediaErrorCode>
  errorMessage: Ref<string | null>
  mediaEpoch: Ref<number>
  /** ICE 失败/中断后的重连入口：只重发 offer，绝不新建 session。 */
  retry: (session: Session) => Promise<void>
  connect: (session: Session) => Promise<void>
  reset: (epoch: number, session: Session) => Promise<void>
  stop: () => void
} {
  const stream = shallowRef<MediaStreamLike | null>(null)
  const connecting = ref(false)
  const errorCode = ref<MediaErrorCode>(null)
  const errorMessage = ref<string | null>(null)
  const mediaEpoch = ref(0)

  let peer: RtcPeerLike | null = null
  let generation = 0
  let lastIceServers: unknown[] = []
  const timeoutMs = options.iceTimeoutMs ?? ICE_TIMEOUT_MS
  const makePeer = options.peerFactory ?? (() => {
    throw new Error('当前环境不支持 WebRTC')
  })

  function teardownPeer(): void {
    if (peer == null) return
    const retired = peer
    peer = null
    retired.onicegatheringstatechange = null
    // 退役 peer 的迟到 track 一律立即停（不让旧流复活上屏）。
    retired.ontrack = (event) => {
      for (const incoming of event.streams) {
        for (const track of (incoming as MediaStreamLike).getTracks?.() ?? []) track.stop()
      }
    }
    for (const track of stream.value?.getTracks() ?? []) track.stop()
    try {
      retired.close()
    } catch {
      // 已关闭的 peer 再 close 是幂等噪声
    }
    stream.value = null
  }

  function stop(): void {
    generation += 1
    teardownPeer()
    connecting.value = false
  }

  /** ICE 收集完成等待（完整 ICE、非 trickle，K07）。 */
  function waitIceComplete(target: RtcPeerLike): Promise<void> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup()
        reject(new Error('ICE 收集超时（10 秒）'))
      }, timeoutMs)
      const check = () => {
        if (target.iceGatheringState === 'complete') {
          cleanup()
          resolve()
        }
      }
      const cleanup = () => {
        clearTimeout(timer)
        target.onicegatheringstatechange = null
      }
      target.onicegatheringstatechange = check
      check()
    })
  }

  async function connect(session: Session): Promise<void> {
    generation += 1
    const run = generation
    const ticket = account.capture()
    teardownPeer()
    connecting.value = true
    errorCode.value = null
    errorMessage.value = null
    try {
      const candidate = makePeer(lastIceServers)
      candidate.addTransceiver('audio', { direction: 'recvonly' })
      candidate.addTransceiver('video', { direction: 'recvonly' })
      const offer = await candidate.createOffer()
      await candidate.setLocalDescription(offer)
      peer = candidate
      // ontrack 回调先挂好：answer 设置后 track 可能立即到达。
      candidate.ontrack = (event) => {
        if (run !== generation || !account.isCurrent(ticket)) {
          // 迟到/换号：立刻停掉刚到达的 track，不上屏。
          for (const incoming of event.streams) {
            for (const track of (incoming as MediaStreamLike).getTracks?.() ?? []) track.stop()
          }
          return
        }
        stream.value = event.streams[0] as MediaStreamLike
      }
      await waitIceComplete(candidate)
      if (run !== generation || !account.isCurrent(ticket)) throw stale()
      const answer = await api.submitOffer(session.id, {
        requestId: crypto.randomUUID(),
        leaseEpoch: session.leaseEpoch,
        mediaEpoch: session.mediaEpoch,
        sdp: candidate.localDescription?.sdp ?? offer.sdp,
        type: 'offer',
      })
      if (run !== generation || !account.isCurrent(ticket)) throw stale()
      lastIceServers = answer.iceServers ?? []
      await candidate.setRemoteDescription({ sdp: answer.sdp, type: 'answer' })
      // media-ready：由服务端确认 runtime 已 ready（不是客户端自行证明）。
      await api.mediaReady(session.id, {
        requestId: crypto.randomUUID(),
        leaseEpoch: session.leaseEpoch,
        mediaEpoch: session.mediaEpoch,
      })
      if (run !== generation || !account.isCurrent(ticket)) throw stale()
      mediaEpoch.value = session.mediaEpoch
    } catch (error) {
      teardownPeer()
      if (run !== generation || !account.isCurrent(ticket)) {
        // 陈旧结果：静默丢弃（可能已被 reset/stop/换号接管），不算当前错误。
        if (connecting.value) connecting.value = false
        return
      }
      connecting.value = false
      errorCode.value = error instanceof Error && error.message.includes('ICE') ? 'ice_timeout' : 'offer_rejected'
      errorMessage.value = error instanceof Error ? error.message : '媒体连接失败'
      return
    }
    connecting.value = false
  }

  function stale(): Error {
    const failure = new Error('媒体连接结果已过期（换号或页面状态变化）')
    failure.name = 'StaleMediaResult'
    return failure
  }

  /** interrupt 后重建：关旧 peer，按新 epoch 重新 offer（同一 session，不新建）。 */
  async function reset(epoch: number, session: Session): Promise<void> {
    if (epoch <= mediaEpoch.value) return // 旧 epoch 迟到重置：忽略
    generation += 1
    teardownPeer()
    mediaEpoch.value = epoch
    await connect({ ...session, mediaEpoch: epoch })
  }

  async function retry(session: Session): Promise<void> {
    // 重试只重走 offer/media-ready（同一 session 原键幂等由服务端保证），不新建 session。
    await connect(session)
  }

  return { stream, connecting, errorCode, errorMessage, mediaEpoch, retry, connect, reset, stop }
}
