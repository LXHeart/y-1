/**
 * 数字人角色/目录/试听/预检域 composable（任务书 #105E C105E-02 / K02、K03、K08.1、K13.6）。
 *
 * - 取数：目录（API01）+ 本人角色列表（API02）；目录失败保留已有角色（只置错误，不清数据）。
 * - 保存：API03/05；requestId「同 draft 未变的失败重试复用原键，draft 变更换新键」（K04）；
 *   版本冲突（409 dh_version_conflict）保留 draft 并置 conflict 标记，不偷偷覆盖。
 * - 试听：API27→28→29 固定句（服务端文案，客户端不接受任意 text）；换试听/换号停止前一个、
 *   撤销旧 objectURL；每次 ticket+generation 双查，迟到回调不播放；429 显示服务端提示不循环请求。
 * - 预检：API07（controllerId=本页内存随机 UUID，刷新即新 id，K03/K13.4）。
 */
import { computed, onScopeDispose, ref, type Ref } from 'vue'
import type {
  Catalog, CatalogView, InputMode, Preflight, Profile, ProfileInput, Tone,
} from '../../../types/digital-human'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { isPersonalCatalog } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort } from '../../../stores/account-session'

export type PreviewState = 'idle' | 'requesting' | 'ready' | 'failed'

/** K02 字段界：name 1～40、persona 1～4000、greeting 0～200（Unicode 码点，先 trim）。 */
export const PROFILE_LIMITS = { name: 40, persona: 4000, greeting: 200 } as const

export function codePoints(text: string): number {
  return [...text].length
}

const TONES: readonly Tone[] = ['natural', 'professional', 'friendly']

export function emptyDraft(catalogVersion: number): ProfileInput {
  return { name: '', persona: '', greeting: '', tone: 'natural', avatarId: '', voiceId: '', catalogVersion }
}

export function draftOf(profile: Profile): ProfileInput {
  return {
    name: profile.name,
    persona: profile.persona,
    greeting: profile.greeting,
    tone: profile.tone,
    avatarId: profile.avatarId,
    voiceId: profile.voiceId,
    catalogVersion: profile.catalogVersion,
  }
}

/** 客户端校验（服务端仍是权威；此处只挡明显越界并贴字段提示）。返回字段 → 错误文案。 */
export function validateProfileInput(input: ProfileInput): Record<string, string> {
  const errors: Record<string, string> = {}
  const name = input.name.trim()
  if (codePoints(name) < 1) errors.name = '请填写角色名称。'
  else if (codePoints(name) > PROFILE_LIMITS.name) {
    errors.name = `角色名称不能超过 ${PROFILE_LIMITS.name} 个字符（当前 ${codePoints(name)}）。`
  }
  const persona = input.persona.trim()
  if (codePoints(persona) < 1) errors.persona = '请填写人设描述。'
  else if (codePoints(persona) > PROFILE_LIMITS.persona) {
    errors.persona = `人设不能超过 ${PROFILE_LIMITS.persona} 个字符（当前 ${codePoints(persona)}）。`
  }
  if (codePoints(input.greeting.trim()) > PROFILE_LIMITS.greeting) {
    errors.greeting = `开场白不能超过 ${PROFILE_LIMITS.greeting} 个字符。`
  }
  if (!TONES.includes(input.tone)) errors.tone = '请选择有效的语气。'
  if (!input.avatarId) errors.avatarId = '请选择一个形象。'
  if (!input.voiceId) errors.voiceId = '请选择一个音色。'
  return errors
}

/** 同 payload（含 expectedVersion）判定：失败重试复用原 requestId 的前提。 */
function sameSavePayload(
  left: { input: ProfileInput; expectedVersion: number | null },
  right: { input: ProfileInput; expectedVersion: number | null },
): boolean {
  return left.expectedVersion === right.expectedVersion && JSON.stringify(left.input) === JSON.stringify(right.input)
}

const PREVIEW_POLL_INTERVAL_MS = 500
const PREVIEW_POLL_DEADLINE_MS = 20_000

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return '操作失败，请稍后重试。'
}

export function useDigitalHumanProfiles(api: DigitalHumanApi, account: AccountSessionPort): {
  /** 本页 controllerId（K03：组件内存随机 UUID，刷新换新；E-03 会话接管沿用）。 */
  controllerId: string
  catalog: Ref<CatalogView | null>
  catalogLoading: Ref<boolean>
  catalogError: Ref<unknown>
  profiles: Ref<Profile[]>
  profilesError: Ref<unknown>
  /** 目录内可用（approved）后端；无后端与无角色是两种空态。 */
  approvedBackendIds: Ref<string[]>
  load: () => Promise<void>
  draft: Ref<ProfileInput>
  /** 编辑目标与期望版本（null=新建）；版本冲突后保留不动。 */
  editing: Ref<Profile | null>
  saving: Ref<boolean>
  saveError: Ref<string | null>
  versionConflict: Ref<boolean>
  save: (input: ProfileInput) => Promise<boolean>
  editProfile: (profile: Profile | null) => void
  previewState: Ref<PreviewState>
  previewUrl: Ref<string | null>
  previewError: Ref<string | null>
  previewVoiceId: Ref<string | null>
  preview: (voiceId: string, catalogVersion: number) => Promise<void>
  stopPreview: () => void
  preflight: (mode: InputMode) => Promise<Preflight | null>
  preflightError: Ref<string | null>
} {
  const controllerId = crypto.randomUUID()

  const catalog = ref<CatalogView | null>(null)
  const catalogLoading = ref(true)
  const catalogError = ref<unknown>(null)
  const profiles = ref<Profile[]>([])
  const profilesError = ref<unknown>(null)

  const draft = ref<ProfileInput>(emptyDraft(0))
  const editing = ref<Profile | null>(null)
  const saving = ref(false)
  const saveError = ref<string | null>(null)
  const versionConflict = ref(false)

  const previewState = ref<PreviewState>('idle')
  const previewUrl = ref<string | null>(null)
  const previewError = ref<string | null>(null)
  const previewVoiceId = ref<string | null>(null)

  const preflightError = ref<string | null>(null)

  /** 失败未确认的保存请求（原键复用；draft 变更后作废）。 */
  let retryableSave: { requestId: string; input: ProfileInput; expectedVersion: number | null } | null = null

  const approvedBackendIds = computed(() => {
    const view = catalog.value
    if (view == null || !isPersonalCatalog(view)) return []
    return view.backends.filter((backend) => backend.state === 'approved').map((backend) => backend.id)
  })

  async function load(): Promise<void> {
    const ticket = account.capture()
    catalogLoading.value = true
    catalogError.value = null
    profilesError.value = null
    try {
      const view = await api.getCatalog(ticket.signal)
      if (!account.isCurrent(ticket)) return
      catalog.value = view
    } catch (error) {
      // 目录失败：保留已有角色/目录数据，只置错误（不把 503 当空角色）。
      if (account.isCurrent(ticket)) catalogError.value = error
    } finally {
      if (account.isCurrent(ticket)) catalogLoading.value = false
    }
    if (!account.isCurrent(ticket) || catalog.value == null || !isPersonalCatalog(catalog.value)) return
    try {
      const page = await api.listProfiles({}, ticket.signal)
      if (!account.isCurrent(ticket)) return
      profiles.value = page.items
    } catch (error) {
      if (account.isCurrent(ticket)) profilesError.value = error
    }
  }

  function editProfile(profile: Profile | null): void {
    editing.value = profile
    versionConflict.value = false
    saveError.value = null
    if (profile) {
      draft.value = draftOf(profile)
    } else {
      const view = catalog.value
      const catalogVersion = view && isPersonalCatalog(view) ? view.version : 0
      draft.value = emptyDraft(catalogVersion)
    }
  }

  async function save(input: ProfileInput): Promise<boolean> {
    const errors = validateProfileInput(input)
    if (Object.keys(errors).length > 0) {
      saveError.value = Object.values(errors)[0]
      return false
    }
    const ticket = account.capture()
    const expectedVersion = editing.value?.version ?? null
    // 同 payload 失败重试复用原 requestId（K04：丢首次响应重发同键取得原结果，不生成新 key）。
    const reusable = retryableSave != null && sameSavePayload(retryableSave, { input, expectedVersion })
    const requestId = reusable ? retryableSave!.requestId : crypto.randomUUID()
    retryableSave = { requestId, input, expectedVersion }
    saving.value = true
    saveError.value = null
    versionConflict.value = false
    try {
      const saved = expectedVersion == null
        ? await api.createProfile({ ...input, requestId })
        : await api.updateProfile(editing.value!.id, { ...input, expectedVersion, requestId })
      if (!account.isCurrent(ticket)) return false
      retryableSave = null
      editing.value = saved
      draft.value = draftOf(saved)
      const index = profiles.value.findIndex((item) => item.id === saved.id)
      if (index >= 0) profiles.value.splice(index, 1, saved)
      else profiles.value.unshift(saved)
      return true
    } catch (error) {
      if (account.isCurrent(ticket)) {
        saveError.value = messageOf(error)
        if (error instanceof GrasslandHttpError && error.code === 'dh_version_conflict') {
          // 保留本地 draft 并提示重新载入；不偷偷覆盖（K01/K04）。
          versionConflict.value = true
          retryableSave = null
        }
      }
      return false
    } finally {
      if (account.isCurrent(ticket)) saving.value = false
    }
  }

  function stopPreview(): void {
    if (previewUrl.value != null) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
    previewVoiceId.value = null
    if (previewState.value !== 'requesting') previewState.value = 'idle'
    previewError.value = null
  }

  async function preview(voiceId: string, catalogVersion: number): Promise<void> {
    // 换试听先停前一个：撤 objectURL（旧 audio 失去源自然停止）。
    stopPreview()
    const ticket = account.capture()
    previewState.value = 'requesting'
    previewVoiceId.value = voiceId
    try {
      const created = await api.createVoicePreview({ requestId: crypto.randomUUID(), voiceId, catalogVersion })
      if (!account.isCurrent(ticket)) return
      let state = created.state
      const deadline = Date.now() + PREVIEW_POLL_DEADLINE_MS
      while (state === 'processing' && Date.now() < deadline) {
        await sleep(PREVIEW_POLL_INTERVAL_MS)
        if (!account.isCurrent(ticket)) return
        state = (await api.getVoicePreview(created.id, ticket.signal)).state
      }
      if (!account.isCurrent(ticket)) return
      if (state !== 'ready') throw new Error('试听仍在准备中，请稍后在列表中重试。')
      const response = await api.getVoicePreviewAudio(created.id, ticket.signal)
      if (!account.isCurrent(ticket)) return
      const blob = await response.blob()
      // 最后一道闸：blob 到手后换号 → 不创建 URL（无泄漏可谈）。
      if (!account.isCurrent(ticket)) return
      const url = URL.createObjectURL(blob)
      if (!account.isCurrent(ticket)) {
        URL.revokeObjectURL(url)
        return
      }
      previewUrl.value = url
      previewState.value = 'ready'
    } catch (error) {
      if (!account.isCurrent(ticket)) return
      previewState.value = 'failed'
      // 429 等服务端文案直接上屏；不循环请求、不自动重试。
      previewError.value = messageOf(error)
    }
  }

  async function preflight(mode: InputMode): Promise<Preflight | null> {
    const profile = editing.value
    if (!profile) {
      preflightError.value = '请先保存角色配置。'
      return null
    }
    const ticket = account.capture()
    preflightError.value = null
    try {
      return await api.createPreflight({
        profileId: profile.id,
        profileVersion: profile.version,
        inputMode: mode,
        controllerId,
      })
    } catch (error) {
      if (account.isCurrent(ticket)) preflightError.value = messageOf(error)
      return null
    }
  }

  onScopeDispose(stopPreview)

  return {
    controllerId,
    catalog, catalogLoading, catalogError, profiles, profilesError, approvedBackendIds,
    load,
    draft, editing, saving, saveError, versionConflict, save, editProfile,
    previewState, previewUrl, previewError, previewVoiceId, preview, stopPreview,
    preflight, preflightError,
  }
}

/** 目录能力过滤辅助（表单/选择器共用）：可用 = enabled 且与 approved 后端兼容。 */
export function compatibleVoiceIds(catalogView: Catalog | null, approvedBackendIds: string[]): string[] {
  if (!catalogView) return []
  return catalogView.voices
    .filter((voice) => voice.enabled && voice.compatibleBackendIds.some((id) => approvedBackendIds.includes(id)))
    .map((voice) => voice.id)
}
