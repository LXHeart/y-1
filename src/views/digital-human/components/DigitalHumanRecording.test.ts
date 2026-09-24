// @vitest-environment happy-dom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, test, vi } from 'vitest'
import DigitalHumanAvatarUpload from './DigitalHumanAvatarUpload.vue'
import DigitalHumanRecording from './DigitalHumanRecording.vue'
import { AVATAR_MAX_BYTES, useDigitalHumanAvatar } from '../composables/useDigitalHumanAvatar'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { request, putToPresignedUrl } from '../../../composables/grassland-http'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { AvatarItem, Recording, RecordingState } from '../../../types/digital-human'

/**
 * C105F-04 UI（TC105F-04-01 上传处理：合法/超限/无授权、未确认不提交、失败保配置；
 * TC105F-04-03 partial/失败/提交中显示 + 开始确认文案）。组件纯 props/emits，编排层在
 * Workbench；上传编排用真实 useDigitalHumanAvatar + 只 mock 最外层 HTTP（grassland-http）。
 */

vi.mock('../../../composables/grassland-http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../composables/grassland-http')>()
  return { ...actual, request: vi.fn(), putToPresignedUrl: vi.fn() }
})

const RECORDING_ID = '55555555-5555-4555-8555-555555555555'
const SESSION_ID = '44444444-4444-4444-8444-444444444444'

function recordingOf(state: RecordingState, overrides: Partial<Recording> = {}): Recording {
  return {
    id: RECORDING_ID, sessionId: SESSION_ID, state, partial: false, durationMs: 3000, sizeBytes: 12345,
    startedAt: '2026-09-23T00:00:00Z', endedAt: null, expiresAt: '2026-09-24T00:00:00Z',
    assetId: null, subtitleAvailable: true, errorCode: null, ...overrides,
  }
}

function makeAccount(): AccountSessionPort {
  const state = { accountId: 'account-a', epoch: 0 }
  const controller = new AbortController()
  return {
    capture: (): AccountTicket => ({ accountId: state.accountId, epoch: state.epoch, signal: controller.signal }),
    isCurrent: (ticket) => ticket.accountId === state.accountId && ticket.epoch === state.epoch,
  }
}

let router: ReturnType<typeof createRouter> | null = null

async function mountRecording(props: Record<string, unknown>): Promise<VueWrapper> {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'create', component: { template: '<div />' } },
      { path: '/digital-human', name: 'digital-human', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  return mount(DigitalHumanRecording, {
    props: {
      recording: null, supported: true, operable: true,
      starting: false, stopping: false, saving: false, downloading: false,
      pollExhausted: false, savedAssetId: null, error: null,
      ...props,
    },
    global: { plugins: [router], stubs: { teleport: true } },
  })
}

function mountUpload(stage: 'idle' | 'uploading' | 'processing' | 'ready' | 'failed', error: string | null = null): VueWrapper {
  return mount(DigitalHumanAvatarUpload, {
    props: { stage, error, rightsVersion: 'dh-avatar-v1' },
  })
}

function pickFile(wrapper: VueWrapper, file: File): void {
  const input = wrapper.find<HTMLInputElement>('[data-testid="dh-avatar-file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  input.trigger('change')
}

const LEGAL_IMAGE = new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10])], 'me.jpg', { type: 'image/jpeg' })

afterEach(() => {
  router = null
  vi.mocked(request).mockReset()
  vi.mocked(putToPresignedUrl).mockReset()
  vi.useRealTimers()
})

// ---------- TC105F-04-01 上传处理 ----------

describe('TC105F-04-01 上传处理', () => {
  test('无授权勾选不提交；选文件+勾选后才 emit submit（不默认勾选）', async () => {
    const wrapper = mountUpload('idle')

    // 只选文件、未勾选授权：提交按钮可点但不 emit。
    pickFile(wrapper, LEGAL_IMAGE)
    expect((wrapper.find('[data-testid="dh-avatar-rights"]').element as HTMLInputElement).checked).toBe(false)
    wrapper.find('[data-testid="dh-avatar-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()

    await wrapper.find('[data-testid="dh-avatar-rights"]').setValue(true)
    wrapper.find('[data-testid="dh-avatar-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('submit')).toHaveLength(1)
    expect(wrapper.emitted('submit')![0]).toEqual([LEGAL_IMAGE])
  })

  test('超限（>10MiB）与非 JPG/PNG：本地拒绝，不触达上传接口', async () => {
    const avatarApi = {
      createAvatar: vi.fn(),
      getAvatar: vi.fn(),
      deleteAvatar: vi.fn(),
    }
    const state = useDigitalHumanAvatar(avatarApi as unknown as DigitalHumanApi, makeAccount())

    const oversize = new File([new Uint8Array(AVATAR_MAX_BYTES + 1)], 'big.png', { type: 'image/png' })
    await expect(state.upload(oversize)).resolves.toBe(false)
    expect(state.error.value).toContain('10MiB')
    expect(state.stage.value).toBe('idle')

    const wrongType = new File([new Uint8Array(8)], 'me.webp', { type: 'image/webp' })
    await expect(state.upload(wrongType)).resolves.toBe(false)
    expect(state.error.value).toContain('JPG / PNG')
    expect(vi.mocked(request)).not.toHaveBeenCalled()
    expect(avatarApi.createAvatar).not.toHaveBeenCalled()
  })

  test('合法图片：三步上传（ticket→PUT→confirm）→ API30 受理 → 轮询 API31 到 ready', async () => {
    vi.useFakeTimers()
    const avatarApi = {
      createAvatar: vi.fn(async () => ({ id: 'avatar-1', state: 'processing' })),
      getAvatar: vi.fn(async () => ({ id: 'avatar-1', state: 'ready' }) as unknown as AvatarItem),
      deleteAvatar: vi.fn(),
    }
    vi.mocked(request)
      .mockResolvedValueOnce({ id: 'media-1', objectKey: 'k', uploadUrl: 'https://put', method: 'PUT', headers: {} })
      .mockResolvedValueOnce({ id: 'media-1' })
    vi.mocked(putToPresignedUrl).mockResolvedValueOnce(undefined)

    const state = useDigitalHumanAvatar(avatarApi as unknown as DigitalHumanApi, makeAccount())
    const promise = state.upload(LEGAL_IMAGE)
    await vi.advanceTimersByTimeAsync(0)
    await expect(promise).resolves.toBe(true)

    expect(vi.mocked(request)).toHaveBeenNthCalledWith(1, '/api/media/upload-tickets', expect.objectContaining({
      method: 'POST',
    }))
    expect(vi.mocked(putToPresignedUrl)).toHaveBeenCalledTimes(1)
    expect(avatarApi.createAvatar).toHaveBeenCalledWith(expect.objectContaining({
      mediaId: 'media-1', rightsAccepted: true, rightsVersion: 'dh-avatar-v1',
    }))
    expect(state.stage.value).toBe('ready')
    expect(state.avatar.value).toMatchObject({ id: 'avatar-1', state: 'ready' })
  })

  test('上传失败（网络断开）：stage failed 且组件保留文件与授权（可重试）', async () => {
    const wrapper = mountUpload('failed', '上传失败，请稍后重试。')

    pickFile(wrapper, LEGAL_IMAGE)
    await wrapper.find('[data-testid="dh-avatar-rights"]').setValue(true)

    // failed 态不忙：提交按钮可重试；文件与勾选仍在（不静默重置）。
    expect((wrapper.find('[data-testid="dh-avatar-file"]').element as HTMLInputElement).files?.length).toBe(1)
    expect((wrapper.find('[data-testid="dh-avatar-rights"]').element as HTMLInputElement).checked).toBe(true)
    const button = wrapper.find('[data-testid="dh-avatar-submit"]').element as HTMLButtonElement
    expect(button.disabled).toBe(false)
    expect(wrapper.find('[data-testid="dh-avatar-error"]').text()).toContain('上传失败')
  })
})

// ---------- TC105F-04-03 partial/失败/提交中与开始确认 ----------

describe('TC105F-04-03 partial/失败显示', () => {
  test('ready+partial：明确提示部分录制（不冒充完整视频），可保存可下载', async () => {
    const wrapper = await mountRecording({ recording: recordingOf('ready', { partial: true }) })

    const badge = wrapper.find('[data-testid="dh-recording-partial"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('部分录制')
    expect(badge.text()).toContain('非完整视频')
    expect(wrapper.find('[data-testid="dh-recording-save"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dh-recording-download-mp4"]').exists()).toBe(true)
  })

  test('failed：不冒充可保存——无保存表单，错误码如实展示', async () => {
    const wrapper = await mountRecording({
      recording: recordingOf('failed', { errorCode: 'dh_recording_overflow' }),
    })

    expect(wrapper.find('[data-testid="dh-recording-failed"]').text()).toContain('dh_recording_overflow')
    expect(wrapper.find('[data-testid="dh-recording-save"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dh-recording-download-mp4"]').exists()).toBe(false)
  })

  test('expired：提示不可保存或下载', async () => {
    const wrapper = await mountRecording({ recording: recordingOf('expired') })
    expect(wrapper.find('[data-testid="dh-recording-failed"]').text()).toContain('已过期')
    expect(wrapper.find('[data-testid="dh-recording-save"]').exists()).toBe(false)
  })

  test('提交中：保存按钮锁死并显示保存中…；能力未开与确认文案', async () => {
    const submitting = await mountRecording({
      recording: recordingOf('ready'), saving: true,
    })
    const saveButton = submitting.find('[data-testid="dh-recording-save"]')
    expect(saveButton.text()).toContain('保存中')
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)

    // 开始确认：文案明确只录数字人输出；确认后 emit start。
    const fresh = await mountRecording({ recording: null })
    await fresh.find('[data-testid="dh-recording-start"]').trigger('click')
    const confirmText = fresh.find('[data-testid="dh-recording-confirm"]').text()
    expect(confirmText).toContain('只录制数字人输出')
    expect(confirmText).toContain('不采集你的摄像头或麦克风')
    await fresh.find('[data-testid="dh-recording-confirm-start"]').trigger('click')
    expect(fresh.emitted('start')).toHaveLength(1)

    // 能力未开放：不可操作（按钮不呈现为可操作）且如实说明。
    const unsupported = await mountRecording({ recording: null, supported: false })
    expect(unsupported.find('[data-testid="dh-recording-unsupported"]').text()).toContain('未开放')
    expect(unsupported.find('[data-testid="dh-recording-start"]').exists()).toBe(false)
  })

  test('saved：展示 assetId 与素材库入口，不自动新建任务', async () => {
    const wrapper = await mountRecording({
      recording: recordingOf('saved', { assetId: 'asset-1' }), savedAssetId: 'asset-1',
    })
    const saved = wrapper.find('[data-testid="dh-recording-saved"]')
    expect(saved.text()).toContain('asset-1')
    expect(saved.find('[data-testid="dh-recording-library-link"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dh-recording-save"]').exists()).toBe(false) // 不重复保存
  })
})
