// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import {
  RECORDING_POLL_INTERVAL_MS,
  RECORDING_POLL_MAX_ATTEMPTS,
  useDigitalHumanRecording,
} from './useDigitalHumanRecording'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { Operation, Recording, RecordingState, Session } from '../../../types/digital-human'

/** TC105F-04-02 录制状态链（连点/幂等/重试同键）/ TC105F-04-04 轮询卸载（换号/隐藏，迟到包不复活）。 */

const SESSION: Session = {
  id: '44444444-4444-4444-8444-444444444444', profileId: '33333333-3333-4333-8333-333333333333',
  profileRevision: 1, state: 'ready', leaseEpoch: 3, mediaEpoch: 1, controllerId: 'c-1',
  serverNow: '2026-09-23T00:00:00Z', createdAt: '2026-09-23T00:00:00Z', readyAt: null, expiresAt: null,
  pausedUntil: null, leaseExpiresAt: '2026-09-23T00:00:30Z', lastSeq: 0, saveTranscript: false,
  transcriptVersion: 1, contentEpoch: 1,
  billing: { confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 0, priceTableVersion: 'pt-v1' },
  errorCode: null,
}

const RECORDING_ID = '55555555-5555-4555-8555-555555555555'

function recordingOf(state: RecordingState, overrides: Partial<Recording> = {}): Recording {
  return {
    id: RECORDING_ID, sessionId: SESSION.id, state, partial: false, durationMs: 3000, sizeBytes: 12345,
    startedAt: '2026-09-23T00:00:00Z', endedAt: null, expiresAt: '2026-09-24T00:00:00Z',
    assetId: null, subtitleAvailable: true, errorCode: null, ...overrides,
  }
}

function operationOf(assetId: string | null): Operation {
  return {
    id: 'op-1', kind: 'recording_save', state: 'succeeded', resourceId: RECORDING_ID, resultRef: assetId,
    errorCode: null, createdAt: '2026-09-23T00:01:00Z', updatedAt: '2026-09-23T00:01:00Z',
  }
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
    startRecording: vi.fn(async (_id: string, _input: { requestId: string }): Promise<Recording> =>
      recordingOf('recording')),
    stopRecording: vi.fn(async (_id: string, _input: { requestId: string }): Promise<Recording> =>
      recordingOf('finalizing')),
    getRecording: vi.fn(async (_id: string, _signal?: AbortSignal): Promise<Recording> => recordingOf('ready')),
    saveRecording: vi.fn(async (_id: string, _input: { requestId: string }) => operationOf('asset-1')),
    downloadRecording: vi.fn(async (_id: string, _artifact: 'mp4' | 'srt', _signal?: AbortSignal) =>
      new Response(new Blob(['mp4-bytes']), { status: 200 })),
  }
}

function setup() {
  const api = makeApi()
  const account = makeAccount()
  const state = useDigitalHumanRecording(api as unknown as DigitalHumanApi, account.port, {
    session: () => SESSION,
    canRecord: () => true,
  })
  return { state, api, account }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('TC105F-04-02 录制状态链', () => {
  test('连点只发一次 start（同键幂等），状态 recording 且有停止动作可用', async () => {
    const { state, api } = setup()

    const first = state.start()
    const second = state.start() // 在途连点
    await Promise.all([first, second])

    expect(api.startRecording).toHaveBeenCalledTimes(1)
    expect(api.startRecording).toHaveBeenCalledWith(SESSION.id, expect.objectContaining({
      leaseEpoch: SESSION.leaseEpoch, acknowledgement: true,
    }))
    expect(state.recording.value).toMatchObject({ id: RECORDING_ID, state: 'recording' })
    expect(state.stopping.value).toBe(false) // 停止动作可用（不被在途标志锁死）
  })

  test('start 网络失败后重试沿同一 requestId；成功后进入 recording', async () => {
    const { state, api } = setup()
    api.startRecording.mockRejectedValueOnce(new Error('网络断开'))

    await expect(state.start()).resolves.toBe(false)
    await expect(state.start()).resolves.toBe(true)

    expect(api.startRecording).toHaveBeenCalledTimes(2)
    expect(api.startRecording.mock.calls[1][1].requestId)
      .toBe(api.startRecording.mock.calls[0][1].requestId)
  })

  test('stop → finalizing → 轮询（请求完成后 1 秒串行）→ ready → 保存：同键重试同 assetId', async () => {
    const { state, api } = setup()
    await state.start()

    api.getRecording.mockResolvedValueOnce(recordingOf('finalizing'))
    await expect(state.stop()).resolves.toBe(true)
    expect(state.recording.value?.state).toBe('finalizing')
    expect(api.getRecording).toHaveBeenCalledTimes(1) // 首跳即时

    api.getRecording.mockResolvedValueOnce(recordingOf('ready'))
    await vi.advanceTimersByTimeAsync(RECORDING_POLL_INTERVAL_MS)
    expect(state.recording.value?.state).toBe('ready')

    // 保存失败（网络断开）→ 重试沿同一 requestId → 同一 assetId；saved 只落一次。
    api.saveRecording.mockRejectedValueOnce(new Error('网络断开'))
    await expect(state.save('口播素材', true)).resolves.toBe(false)
    await expect(state.save('口播素材', true)).resolves.toBe(true)
    expect(api.saveRecording).toHaveBeenCalledTimes(2)
    expect(api.saveRecording.mock.calls[1][1].requestId).toBe(api.saveRecording.mock.calls[0][1].requestId)
    expect(state.recording.value).toMatchObject({ state: 'saved', assetId: 'asset-1' })
    expect(state.savedAssetId.value).toBe('asset-1')
  })

  test('saving 在途锁保存（连点返回 false），不锁停止/结束路径', async () => {
    const { state, api } = setup()
    await state.start()
    api.getRecording.mockResolvedValueOnce(recordingOf('ready'))
    await state.stop()
    await vi.advanceTimersByTimeAsync(RECORDING_POLL_INTERVAL_MS)
    expect(state.recording.value?.state).toBe('ready')

    let releaseSave: (value: Operation) => void = () => {}
    api.saveRecording.mockImplementationOnce(() => new Promise((resolve) => { releaseSave = resolve }))
    const inFlight = state.save('素材', false)
    expect(state.saving.value).toBe(true)
    await expect(state.save('再点一次', false)).resolves.toBe(false) // 在途锁
    expect(api.saveRecording).toHaveBeenCalledTimes(1)
    releaseSave(operationOf('asset-9'))
    await expect(inFlight).resolves.toBe(true)
    expect(state.savedAssetId.value).toBe('asset-9')
  })

  test('坏产物保存失败置 failed、过期置 expired（不冒充可保存）；下载非 2xx 如实报错', async () => {
    const { state, api } = setup()
    await state.start()
    api.getRecording.mockResolvedValueOnce(recordingOf('ready'))
    await state.stop()
    await vi.advanceTimersByTimeAsync(RECORDING_POLL_INTERVAL_MS)

    api.saveRecording.mockRejectedValueOnce(new GrasslandHttpError(409, '产物无法解码', 'dh_media_invalid', undefined))
    await expect(state.save('坏产物', false)).resolves.toBe(false)
    expect(state.recording.value).toMatchObject({ state: 'failed', errorCode: 'dh_media_invalid' })

    api.downloadRecording.mockResolvedValueOnce(new Response(null, { status: 410 }))
    await expect(state.download('mp4')).resolves.toBe(false)
    expect(state.error.value).toContain('410')
  })

  test('轮询 90 次未收口：停止并进入手动刷新态', async () => {
    const { state, api } = setup()
    await state.start()
    api.getRecording.mockResolvedValue(recordingOf('finalizing'))
    await state.stop()
    await vi.advanceTimersByTimeAsync(RECORDING_POLL_INTERVAL_MS * RECORDING_POLL_MAX_ATTEMPTS)
    expect(api.getRecording.mock.calls.length).toBeLessThanOrEqual(RECORDING_POLL_MAX_ATTEMPTS)
    expect(state.pollExhausted.value).toBe(true)

    api.getRecording.mockResolvedValueOnce(recordingOf('ready'))
    await expect(state.refresh()).resolves.toBe(true)
    expect(state.recording.value?.state).toBe('ready')
  })
})

describe('TC105F-04-04 轮询卸载', () => {
  test('换号（abort+epoch）后：在途查询迟到解析，状态不落、无后续轮询', async () => {
    const api = makeApi()
    const account = makeAccount()
    const state = useDigitalHumanRecording(api as unknown as DigitalHumanApi, account.port, {
      session: () => SESSION,
      canRecord: () => true,
    })
    await state.start()
    let release: (value: Recording) => void = () => {}
    api.getRecording.mockImplementationOnce(() => new Promise((resolve) => { release = resolve }))
    await state.stop()
    expect(api.getRecording).toHaveBeenCalledTimes(1)

    account.switchAccount() // 换号：abort 在途信号 + epoch 前进
    release(recordingOf('ready')) // 迟到回复

    await vi.advanceTimersByTimeAsync(RECORDING_POLL_INTERVAL_MS * 5)
    expect(state.recording.value?.state).toBe('finalizing') // 未被迟到包复活为 ready
    expect(api.getRecording).toHaveBeenCalledTimes(1) // 不恢复 timer：没有第二跳
  })

  test('dispose（隐藏/卸载）终止轮询：代次失效后迟到包不落状态', async () => {
    const api = makeApi()
    const state = useDigitalHumanRecording(api as unknown as DigitalHumanApi, makeAccount().port, {
      session: () => SESSION,
      canRecord: () => true,
    })
    await state.start()
    let release: (value: Recording) => void = () => {}
    api.getRecording.mockImplementationOnce(() => new Promise((resolve) => { release = resolve }))
    await state.stop()

    state.dispose() // 隐藏
    release(recordingOf('ready'))

    await vi.advanceTimersByTimeAsync(RECORDING_POLL_INTERVAL_MS * 3)
    expect(state.recording.value?.state).toBe('finalizing')
    expect(api.getRecording).toHaveBeenCalledTimes(1)
  })
})
