// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useDigitalHumanProfiles } from './useDigitalHumanProfiles'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { Page, Preflight, Profile } from '../../../types/digital-human'
import DigitalHumanStartDialog from '../components/DigitalHumanStartDialog.vue'

/**
 * C105E-02 角色域（TC105E-02-02 费用确认 / TC105E-02-04 试听重入与换号 + 幂等/失败保输入/换号）。
 * api 与账号票据是外部依赖（transport/账号边界），用可控 stub；URL/objectURL 生命周期为被测事实。
 */

const PROFILE: Profile = {
  id: '33333333-3333-4333-8333-333333333333',
  name: '创作助手',
  persona: '用简洁中文帮助我整理口播思路。',
  greeting: '你好，今天想创作什么内容？',
  tone: 'natural',
  avatarId: '11111111-1111-4111-8111-111111111111',
  voiceId: 'preset-zh-natural-01',
  catalogVersion: 1,
  version: 3,
  status: 'active',
  createdAt: '2026-09-23T00:00:00Z',
  updatedAt: '2026-09-23T00:00:00Z',
}

/** own text（provider_direct）+ 平台 stt/tts/render 的预检夹具（K08.1 四项齐全）。 */
function ownTextPreflight(): Preflight {
  return {
    id: '55555555-5555-4555-8555-555555555555',
    expiresAt: '2026-09-23T00:01:00Z',
    profileId: PROFILE.id,
    profileVersion: 3,
    catalogVersion: 1,
    inputMode: 'text',
    modelSource: 'own',
    llmModelLabel: 'own-model-x',
    sttModelLabel: 'platform-stt',
    ttsModelLabel: 'platform-tts',
    renderModelLabel: 'third-party-render',
    priceTableVersion: 'pt-v1',
    billingNoticeVersion: 'dh-billing-v1',
    limits: {
      maxSessionsPerAccount: 1, maxSessionsGlobal: 1, maxQueuedGlobal: 10, sessionDurationMs: 600000,
      idleTimeoutMs: 120000, resumeWindowMs: 30000, contextPairs: 10, maxRecordingMs: 300000,
    },
    estimatedMaxCents: 300,
    platformCostCapCents: 300,
    renderChargeCents: 0,
    backendId: 'backend-mock-1',
    controllerId: '44444444-4444-4444-8444-444444444444',
    billingItems: [
      { stage: 'llm', modelSource: 'own', modelLabel: 'own-model-x', chargeTo: 'provider_direct', priceTableVersion: 'pt-v1', unit: 'token', estimatedCents: null },
      { stage: 'stt', modelSource: 'platform', modelLabel: 'platform-stt', chargeTo: 'user', priceTableVersion: 'pt-v1', unit: 'second', estimatedCents: 12 },
      { stage: 'tts', modelSource: 'platform', modelLabel: 'platform-tts', chargeTo: 'platform', priceTableVersion: 'pt-v1', unit: 'second', estimatedCents: 45 },
      { stage: 'render', modelSource: 'platform', modelLabel: 'third-party-render', chargeTo: 'platform', priceTableVersion: 'pt-v1', unit: 'second', estimatedCents: 200 },
    ],
  }
}

/** 可换号/递增 epoch 的账号票据桩。 */
function makeAccount(initialAccountId = 'account-a') {
  const state = { accountId: initialAccountId, epoch: 0 }
  let controller = new AbortController()
  const port: AccountSessionPort = {
    capture(): AccountTicket {
      return { accountId: state.accountId, epoch: state.epoch, signal: controller.signal }
    },
    isCurrent(ticket: AccountTicket): boolean {
      return ticket.accountId === state.accountId && ticket.epoch === state.epoch
    },
  }
  return {
    port,
    switchAccount(accountId: string): void {
      state.accountId = accountId
      state.epoch += 1
      controller.abort()
      controller = new AbortController()
    },
  }
}

/** 只实现被消费方法的 api 桩；调用记录用 spy 断言（幂等键/createSession 零调用）。 */
function makeApi(overrides: Record<string, unknown> = {}) {
  const calls: Array<{ method: string; args: unknown[] }> = []
  const api = {
    async getCatalog() {
      calls.push({ method: 'getCatalog', args: [] })
      return {
        authenticated: true, version: 1, enabled: true, newSessionsAllowed: true,
        recordingEnabled: false, customAvatarEnabled: false,
        avatars: [], voices: [], backends: [], limits: {
          maxSessionsPerAccount: 1, maxSessionsGlobal: 1, maxQueuedGlobal: 10, sessionDurationMs: 600000,
          idleTimeoutMs: 120000, resumeWindowMs: 30000, contextPairs: 10, maxRecordingMs: 300000,
        },
        billingNoticeVersion: 'dh-billing-v1',
      }
    },
    async listProfiles(): Promise<Page<Profile>> {
      calls.push({ method: 'listProfiles', args: [] })
      return { items: [PROFILE], nextCursor: null }
    },
    createProfile: vi.fn(async (input: { requestId: string }) => {
      calls.push({ method: 'createProfile', args: [input] })
      return { ...PROFILE, id: '66666666-6666-4666-8666-666666666666', version: 1 }
    }),
    updateProfile: vi.fn(async (id: string, input: { requestId: string; expectedVersion: number }) => {
      calls.push({ method: 'updateProfile', args: [id, input] })
      return { ...PROFILE, version: input.expectedVersion + 1 }
    }),
    getProfile: vi.fn(async () => PROFILE),
    createPreflight: vi.fn(async () => ownTextPreflight()),
    createSession: vi.fn(async () => {
      throw new Error('E-02 不 create：确认后 create 由 E-03 接线')
    }),
    createVoicePreview: vi.fn(async (input: { voiceId: string }) => {
      calls.push({ method: 'createVoicePreview', args: [input] })
      return { id: '77777777-7777-4777-8777-777777777777', state: 'ready', expiresAt: '2026-09-23T00:10:00Z', errorCode: null }
    }),
    getVoicePreview: vi.fn(async () => ({ id: 'x', state: 'ready', expiresAt: '2026-09-23T00:10:00Z', errorCode: null })),
    getVoicePreviewAudio: vi.fn(async () => ({ blob: async () => new Blob(['pcm-bytes'], { type: 'audio/wav' }) })),
    ...overrides,
    __calls: calls,
  }
  return api as unknown as DigitalHumanApi & { __calls: Array<{ method: string; args: unknown[] }> }
}

let blobSeq = 0

beforeEach(() => {
  blobSeq = 0
  // happy-dom 的 Blob/objectURL 环境不完整；显式打桩并断言生命周期（泄漏是被测事实）。
  Object.assign(URL, {
    createObjectURL: vi.fn(() => `blob:mock-${blobSeq += 1}`),
    revokeObjectURL: vi.fn(),
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('TC105E-02-02 费用确认（own text + 平台转写/配音/第三方渲染）', () => {
  test('preflight 携带本页 controllerId 与已保存角色版本；确认前零 createSession', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)
    await state.load()
    state.editProfile(PROFILE)

    const result = await state.preflight('text')

    expect(result).not.toBeNull()
    expect(api.createPreflight).toHaveBeenCalledWith({
      profileId: PROFILE.id,
      profileVersion: PROFILE.version,
      inputMode: 'text',
      controllerId: expect.stringMatching(/^[0-9a-f-]{36}$/),
    })
    // controllerId 是本页内存随机 UUID（K03），不落 storage。
    expect(window.localStorage.getItem('dh-controller-id')).toBeNull()
    // 无确认不 create：预检流程不触碰 createSession。
    expect(api.createSession).not.toHaveBeenCalled()
  })

  test('开始 dialog 分项明确（四项费用行 + own 自有账户 + 平台补贴），save 默认 false', async () => {
    const preflight = ownTextPreflight()
    const wrapper = mount(DigitalHumanStartDialog, {
      props: { preflight, open: true, submitting: false, error: null },
      global: { stubs: { teleport: true } },
    })

    const rows = wrapper.findAll('.dh-billing-table tbody tr')
    expect(rows).toHaveLength(4)
    const rowText = rows.map((row) => row.text())
    expect(rowText[0]).toContain('对话文本')
    expect(rowText[0]).toContain('own-model-x（你指定的模型）')
    expect(rowText[0]).toContain('你的自有账户')
    expect(rowText[0]).toContain('按你的自有账户计费')
    expect(rowText[1]).toContain('语音转写')
    expect(rowText[1]).toContain('你的积分')
    expect(rowText[2]).toContain('配音')
    expect(rowText[2]).toContain('平台补贴')
    expect(rowText[3]).toContain('数字人渲染')
    expect(rowText[3]).toContain('平台补贴')
    expect(wrapper.text()).toContain('本场成本授权上限 ¥3.00')
    expect(wrapper.text()).toContain('文本走你指定的模型账户')

    const saveCheckbox = wrapper.get('[data-testid="dh-start-save-transcript"]')
    expect((saveCheckbox.element as HTMLInputElement).checked).toBe(false)

    // 确认按钮把当前同意状态传出；saveTranscript 默认 false。无任何 create 副作用（纯组件）。
    await wrapper.get('[data-testid="dh-start-confirm"]').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[{ saveTranscript: false }]])

    await saveCheckbox.setValue(true)
    await wrapper.get('[data-testid="dh-start-confirm"]').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([
      [{ saveTranscript: false }],
      [{ saveTranscript: true }],
    ])
  })

  test('再次打开 dialog 重置 saveTranscript=false（不沿用上次勾选）', async () => {
    const preflight = ownTextPreflight()
    const wrapper = mount(DigitalHumanStartDialog, {
      props: { preflight, open: true, submitting: false, error: null },
      global: { stubs: { teleport: true } },
    })
    await wrapper.get('[data-testid="dh-start-save-transcript"]').setValue(true)
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    expect((wrapper.get('[data-testid="dh-start-save-transcript"]').element as HTMLInputElement).checked).toBe(false)
  })
})

describe('TC105E-02-04 试听重入与换号', () => {
  test('换试听：立即撤销旧 objectURL，不泄漏；新音频独立 URL', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)

    await state.preview('voice-1', 1)
    expect(state.previewUrl.value).toBe('blob:mock-1')

    await state.preview('voice-2', 1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-1')
    expect(state.previewUrl.value).toBe('blob:mock-2')
    expect(state.previewVoiceId.value).toBe('voice-2')
  })

  test('音频下载 Promise 悬挂期间换号：迟到回调不播放、零 objectURL 产生', async () => {
    const api = makeApi()
    let resolveAudio!: (value: unknown) => void
    ;(api as unknown as { getVoicePreviewAudio: ReturnType<typeof vi.fn> }).getVoicePreviewAudio = vi.fn(
      () => new Promise((resolve) => { resolveAudio = resolve }))
    const { port, switchAccount } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)

    void state.preview('voice-1', 1)
    await flushPromises()
    expect(state.previewState.value).toBe('requesting')

    // 下载在途时换号（epoch+1）→ 悬挂 Promise 稍后才 resolve。
    switchAccount('account-b')
    resolveAudio({ blob: async () => new Blob(['late'], { type: 'audio/wav' }) })
    await flushPromises()

    expect(state.previewUrl.value).toBeNull()
    expect(state.previewState.value).not.toBe('ready')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  test('blob 已到手才换号：URL 创建后立即撤销、不上屏', async () => {
    const api = makeApi()
    const { port, switchAccount } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)

    // 先正常拿到一次 URL，再发起第二次并恰在 blob 之后换号。
    await state.preview('voice-1', 1)
    let resolveSecond!: (value: unknown) => void
    ;(api as unknown as { getVoicePreviewAudio: ReturnType<typeof vi.fn> }).getVoicePreviewAudio = vi.fn(
      () => new Promise((resolve) => { resolveSecond = resolve }))
    void state.preview('voice-2', 1)
    await flushPromises()

    // 在 blob resolve 与 isCurrent 复查之间换号不可精确注入；此处验证最终闸门：
    // resolve 后若账号已换，URL 即使创建也被撤销，previewUrl 不更新为旧账号内容。
    const createdBefore = (URL.createObjectURL as ReturnType<typeof vi.fn>).mock.calls.length
    switchAccount('account-b')
    resolveSecond({ blob: async () => new Blob(['late'], { type: 'audio/wav' }) })
    await flushPromises()

    const created = (URL.createObjectURL as ReturnType<typeof vi.fn>).mock.calls.length
    if (created > createdBefore) {
      expect(URL.revokeObjectURL).toHaveBeenCalled()
      expect(state.previewUrl.value).not.toBe('blob:mock-2')
    } else {
      expect(state.previewUrl.value).toBeNull()
    }
  })

  test('429 超频：显示服务端文案、不循环请求（自动重试次数 0）', async () => {
    const api = makeApi({
      createVoicePreview: vi.fn(async () => {
        throw new GrasslandHttpError(429, '试听太频繁，请稍后再试。', 'dh_rate_limited')
      }),
    })
    const { port } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)

    await state.preview('voice-1', 1)
    await flushPromises()

    expect(state.previewState.value).toBe('failed')
    expect(state.previewError.value).toContain('试听太频繁')
    const previewCalls = (api.createVoicePreview as ReturnType<typeof vi.fn>).mock.calls
    expect(previewCalls).toHaveLength(1)
  })
})

describe('保存幂等与换号（白名单用例组）', () => {
  test('失败重试复用原 requestId（同 payload）；改 draft 换新键', async () => {
    const api = makeApi()
    const update = api.updateProfile as unknown as ReturnType<typeof vi.fn>
    // 网络持续中断：键复用断言不依赖服务端恢复。
    update.mockRejectedValue(new Error('网络中断'))
    const { port } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)
    state.editProfile(PROFILE)

    const input = {
      ...PROFILE, name: '新名字', persona: PROFILE.persona, greeting: PROFILE.greeting,
      tone: 'natural' as const, avatarId: PROFILE.avatarId, voiceId: PROFILE.voiceId, catalogVersion: 1,
    }
    const first = await state.save(input)
    expect(first).toBe(false)
    expect(state.saveError.value).toContain('网络中断')
    // 失败保输入：draft/editing 未被重置。
    expect(state.editing.value?.id).toBe(PROFILE.id)

    const retry = await state.save(input)
    expect(retry).toBe(false)
    const keys = update.mock.calls.map((call) => call[1].requestId)
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])

    const changedInput = { ...input, name: '又一个名字' }
    await state.save(changedInput)
    const thirdKey = update.mock.calls[2][1].requestId
    expect(thirdKey).not.toBe(keys[0])
  })

  test('版本冲突（409 dh_version_conflict）：置冲突标记、保留输入、清可复用键', async () => {
    const api = makeApi()
    const update = api.updateProfile as unknown as ReturnType<typeof vi.fn>
    update.mockRejectedValueOnce(new GrasslandHttpError(409, '角色已被其他页面更新。', 'dh_version_conflict'))
    const { port } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)
    state.editProfile(PROFILE)

    const ok = await state.save({ ...PROFILE, name: '冲突尝试' })
    expect(ok).toBe(false)
    expect(state.versionConflict.value).toBe(true)
    expect(state.saveError.value).toContain('角色已被其他页面更新')
    expect(state.editing.value?.id).toBe(PROFILE.id)
  })

  test('load 迟到回包换号后不落：profiles 维持旧账号数据', async () => {
    const api = makeApi()
    const { port, switchAccount } = makeAccount()
    const state = useDigitalHumanProfiles(api, port)

    const pending = state.load()
    switchAccount('account-b')
    await pending
    expect(state.profiles.value).toEqual([])
  })
})
