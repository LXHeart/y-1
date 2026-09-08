import { computed, getCurrentInstance, getCurrentScope, onScopeDispose, ref, watch } from 'vue'
import { useAccountSessionStore } from '../stores/account-session'
import type { AccountSessionPort } from '../stores/account-session'
import { creationDraftFingerprint } from '../lib/creation-draft-json'
import { fetchApi } from './grassland-http'
import type {
  AutosaveState,
  CreateDraftInput,
  CreationDraft,
  SaveDraftInput,
} from '../types/creation-assistant'

const AUTOSAVE_DELAY_MS = 1500

interface Envelope<T> {
  success: boolean
  data?: T
  error?: string
}

/**
 * 草稿端点的非 2xx 语义刻意只透出 JSON 信封里的 error（网关 HTML/纯文本一律回退状态码文案），
 * 与共享 `request` 的 readError（会透出原始文本）不同；且 409 判定要求错误携带 status——
 * `useCreationDraft.test.ts` 已锁定该契约，故只复用 fetchApi 做传输层统一。
 */
async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchApi(url, init)

  const raw = await response.text()
  let body: Envelope<T> | null
  try {
    body = raw ? JSON.parse(raw) as Envelope<T> : null
  } catch {
    body = null
  }

  if (!response.ok) {
    // 409 的文案要能被上层识别成冲突而不是普通失败，所以状态码随 Error 一起带出去。
    const error = new Error(body?.error || `请求失败（${response.status}）`) as Error & { status?: number }
    error.status = response.status
    throw error
  }
  if (!body?.success) {
    throw new Error(body?.error || '请求失败')
  }
  return body.data as T
}

/**
 * 创作草稿（§4.9.7）：CRUD + debounce 自动保存。
 *
 * **乐观锁语义**：后端每次 save 都 version+1 并落旧版快照，`expectedVersion` 不匹配返 409。
 * 所以本地必须用服务端回传的 version 覆盖，不能自增猜测——猜错会让后续每次保存都 409。
 * 409 时进 `conflict` 状态并停止自动重试（继续重试会一直撞同一个版本），由用户决定重载还是覆盖。
 */
export function useCreationDraft(options: { autosaveDelayMs?: number; persistent?: boolean; account?: AccountSessionPort } = {}) {
  const draft = ref<CreationDraft | null>(null)
  const drafts = ref<CreationDraft[]>([])
  const loading = ref(false)
  const error = ref('')
  const autosaveState = ref<AutosaveState>('idle')
  const lastSavedAt = ref<string>('')
  const remoteDraft = ref<CreationDraft | null>(null)
  const readonly = computed(() => {
    const version = draft.value?.workspace?.schemaVersion
    return version !== undefined && version !== 1
  })

  let timer: ReturnType<typeof setTimeout> | null = null
  let pendingPatch: Partial<SaveDraftInput> = {}
  let saveInFlight: Promise<boolean> | null = null
  let activeDraftEpoch = 0
  let navigationEpoch = 0
  let listEpoch = 0
  let createInFlight: Promise<CreationDraft | null> | null = null
  let createAttempt: CreateDraftInput | null = null
  let savedSnapshot: CreationDraft | null = null
  const pinia = getCurrentInstance()?.appContext.config.globalProperties.$pinia
  const account = options.account ?? (pinia ? useAccountSessionStore(pinia) : null)

  function reset(): void {
    clearTimer()
    navigationEpoch += 1
    listEpoch += 1
    activeDraftEpoch += 1
    pendingPatch = {}
    draft.value = null
    savedSnapshot = null
    remoteDraft.value = null
    drafts.value = []
    createAttempt = null
    autosaveState.value = 'idle'
    error.value = ''
    loading.value = false
  }

  if (account) watch(() => account.capture().epoch, reset, { flush: 'sync' })

  function clearTimer(): void {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  async function loadDrafts(): Promise<void> {
    const epoch = ++listEpoch
    loading.value = true
    error.value = ''
    try {
      const data = await request<{ items: CreationDraft[] }>('/api/creation-drafts')
      if (epoch !== listEpoch) return
      drafts.value = data.items ?? []
    } catch (err: unknown) {
      if (epoch !== listEpoch) return
      error.value = err instanceof Error ? err.message : '草稿列表加载失败'
    } finally {
      if (epoch === listEpoch) loading.value = false
    }
  }

  function selectDraft(next: CreationDraft | null): void {
    draft.value = next
    savedSnapshot = next
    activeDraftEpoch += 1
  }

  function mergePendingPatch(saved: CreationDraft): CreationDraft {
    return {
      ...saved,
      ...(pendingPatch.title !== undefined ? { title: pendingPatch.title } : {}),
      ...(pendingPatch.topic !== undefined ? { topic: pendingPatch.topic ?? undefined } : {}),
      ...(pendingPatch.articleTitle !== undefined ? { articleTitle: pendingPatch.articleTitle ?? undefined } : {}),
      ...(pendingPatch.outline !== undefined ? { outline: pendingPatch.outline ?? undefined } : {}),
      ...(pendingPatch.content !== undefined ? { content: pendingPatch.content ?? undefined } : {}),
      ...(pendingPatch.platform !== undefined ? { platform: pendingPatch.platform ?? undefined } : {}),
      ...(pendingPatch.contentForm !== undefined ? { contentForm: pendingPatch.contentForm ?? undefined } : {}),
      ...(pendingPatch.contentMode !== undefined ? { contentMode: pendingPatch.contentMode } : {}),
      ...(pendingPatch.questionText !== undefined ? { questionText: pendingPatch.questionText ?? undefined } : {}),
      ...(pendingPatch.questionRef !== undefined ? { questionRef: pendingPatch.questionRef ?? undefined } : {}),
      ...(pendingPatch.status !== undefined ? { status: pendingPatch.status } : {}),
      ...(pendingPatch.capability !== undefined ? { capability: pendingPatch.capability } : {}),
      ...(pendingPatch.workspace !== undefined ? { workspace: pendingPatch.workspace } : {}),
      ...(pendingPatch.resultAssetIds !== undefined ? { resultAssetIds: pendingPatch.resultAssetIds } : {}),
      ...(pendingPatch.runIds !== undefined ? { runIds: pendingPatch.runIds } : {}),
    }
  }

  async function openDraft(id: string): Promise<CreationDraft | null> {
    const requestEpoch = ++navigationEpoch
    loading.value = true
    if (!await flush() || requestEpoch !== navigationEpoch) {
      if (requestEpoch === navigationEpoch) loading.value = false
      return null
    }
    error.value = ''
    try {
      const data = await request<CreationDraft>(`/api/creation-drafts/${id}`)
      if (requestEpoch !== navigationEpoch) return null
      if (!await flush() || requestEpoch !== navigationEpoch) return null
      selectDraft(data)
      autosaveState.value = 'idle'
      return data
    } catch (err: unknown) {
      if (requestEpoch !== navigationEpoch) return null
      error.value = err instanceof Error ? err.message : '草稿加载失败'
      return null
    } finally {
      if (requestEpoch === navigationEpoch) loading.value = false
    }
  }

  function createDraft(input: CreateDraftInput): Promise<CreationDraft | null> {
    if (createInFlight) return createInFlight
    createAttempt ??= { ...input, requestId: input.requestId ?? crypto.randomUUID() }
    const operation = createOnce(createAttempt)
    createInFlight = operation.finally(() => { createInFlight = null })
    return createInFlight
  }

  async function createOnce(input: CreateDraftInput): Promise<CreationDraft | null> {
    const requestEpoch = ++navigationEpoch
    loading.value = true
    if (!await flush() || requestEpoch !== navigationEpoch) {
      if (requestEpoch === navigationEpoch) loading.value = false
      return null
    }
    error.value = ''
    try {
      const data = await request<CreationDraft>('/api/creation-drafts', {
        method: 'POST',
        body: JSON.stringify(input),
      })
      if (requestEpoch !== navigationEpoch) return null
      drafts.value = [data, ...drafts.value]
      if (!await flush() || requestEpoch !== navigationEpoch) return null
      selectDraft(data)
      createAttempt = null
      autosaveState.value = 'idle'
      return data
    } catch (err: unknown) {
      if (requestEpoch !== navigationEpoch) return null
      error.value = err instanceof Error ? err.message : '草稿创建失败'
      autosaveState.value = 'error'
      return null
    } finally {
      if (requestEpoch === navigationEpoch) loading.value = false
    }
  }

  async function savePatch(
    current: CreationDraft,
    patch: Partial<SaveDraftInput>,
    draftEpoch: number,
  ): Promise<boolean> {
    const isCurrentDraft = () =>
      activeDraftEpoch === draftEpoch && draft.value?.id === current.id

    autosaveState.value = 'saving'
    try {
      const saved = await request<CreationDraft>(`/api/creation-drafts/${current.id}`, {
        method: 'PUT',
        body: JSON.stringify({
          expectedVersion: current.version,
          // 未变更字段回传当前值：后端 save 是整行覆盖，只发 patch 会把其余字段清空。
          title: patch.title ?? current.title,
          topic: patch.topic !== undefined ? patch.topic : current.topic,
          articleTitle: patch.articleTitle !== undefined ? patch.articleTitle : current.articleTitle,
          outline: patch.outline !== undefined ? patch.outline : current.outline,
          content: patch.content !== undefined ? patch.content : current.content,
          platform: patch.platform !== undefined ? patch.platform : current.platform,
          contentForm: patch.contentForm !== undefined ? patch.contentForm : current.contentForm,
          contentMode: patch.contentMode ?? current.contentMode ?? 'article',
          questionText: patch.questionText !== undefined ? patch.questionText : current.questionText,
          questionRef: patch.questionRef !== undefined ? patch.questionRef : current.questionRef,
          status: patch.status ?? current.status,
          capability: patch.capability ?? current.capability,
          workspace: patch.workspace ?? current.workspace,
          resultAssetIds: patch.resultAssetIds ?? current.resultAssetIds,
          runIds: patch.runIds ?? current.runIds,
        } satisfies SaveDraftInput),
      })
      if (!isCurrentDraft()) return false
      savedSnapshot = saved
      const optimisticDraft = mergePendingPatch(saved)
      drafts.value = drafts.value.map((item) => item.id === saved.id ? optimisticDraft : item)
      if (isCurrentDraft()) {
        draft.value = optimisticDraft
        autosaveState.value = Object.keys(pendingPatch).length ? 'pending' : 'saved'
        lastSavedAt.value = saved.updatedAt
      }
      return true
    } catch (err: unknown) {
      if (!isCurrentDraft()) return false
      pendingPatch = { ...patch, ...pendingPatch }
      const status = (err as { status?: number }).status
      if (status === 409) {
        if (isCurrentDraft()) {
          autosaveState.value = 'conflict'
          error.value = '草稿已被其他设备修改，请重新载入后合并'
        }
      } else {
        // 较新的编辑覆盖本轮失败的旧字段，保证恢复队列时不倒退用户输入。
        if (isCurrentDraft()) {
          autosaveState.value = 'error'
          error.value = err instanceof Error ? err.message : '自动保存失败'
        }
      }
      return false
    }
  }

  function startPendingSave(): Promise<boolean> | null {
    if (saveInFlight) return saveInFlight
    const current = draft.value
    if (!current || Object.keys(pendingPatch).length === 0) return null

    pendingPatch = Object.fromEntries(Object.entries(pendingPatch).filter(([key, value]) =>
      creationDraftFingerprint(value ?? null) !== creationDraftFingerprint(savedSnapshot?.[key as keyof CreationDraft] ?? null)))
    if (!Object.keys(pendingPatch).length) {
      autosaveState.value = 'saved'
      return null
    }

    const patch = pendingPatch
    pendingPatch = {}
    const draftEpoch = activeDraftEpoch
    const operation = savePatch(current, patch, draftEpoch)
    saveInFlight = operation.finally(() => {
      saveInFlight = null
    })
    return saveInFlight
  }

  /** 立即串行排空累积改动。返回是否全部保存成功；冲突走 conflict 状态而非抛错。 */
  async function flush(): Promise<boolean> {
    clearTimer()
    if (autosaveState.value === 'conflict' || readonly.value) return false
    while (true) {
      const operation = startPendingSave()
      if (operation === null) return true
      if (!await operation) return false
    }
  }

  /**
   * 记录改动并安排 debounce 保存。冲突态下不再排程——先让用户 reload 解决。
   */
  function queueSave(patch: Partial<SaveDraftInput>): void {
    if (!draft.value || readonly.value) return
    pendingPatch = { ...pendingPatch, ...patch }
    draft.value = mergePendingPatch(draft.value)
    if (autosaveState.value === 'conflict') return
    autosaveState.value = 'pending'
    clearTimer()
    timer = setTimeout(() => { void flush() }, options.autosaveDelayMs ?? AUTOSAVE_DELAY_MS)
  }

  /** 冲突后重载服务端版本，丢弃本地未保存改动（由 UI 明确告知用户）。 */
  async function reloadForConflict(): Promise<CreationDraft | null> {
    const current = draft.value
    if (!current) return null
    const reloaded = await readConflict()
    if (reloaded) {
      clearTimer()
      pendingPatch = {}
      selectDraft(reloaded)
      remoteDraft.value = null
      autosaveState.value = 'idle'
      error.value = ''
    }
    return reloaded
  }

  async function readConflict(): Promise<CreationDraft | null> {
    const id = draft.value?.id
    const epoch = activeDraftEpoch
    if (!id) return null
    try {
      const fresh = await request<CreationDraft>(`/api/creation-drafts/${id}`)
      if (epoch !== activeDraftEpoch || draft.value?.id !== id) return null
      remoteDraft.value = fresh
      return fresh
    } catch (err) {
      if (epoch === activeDraftEpoch) error.value = err instanceof Error ? err.message : '最新版本读取失败'
      return null
    }
  }

  async function keepLocalForConflict(): Promise<boolean> {
    const fresh = remoteDraft.value ?? await readConflict()
    if (!fresh || fresh.id !== draft.value?.id) return false
    savedSnapshot = fresh
    draft.value = mergePendingPatch(fresh)
    remoteDraft.value = null
    autosaveState.value = 'pending'
    error.value = ''
    return flush()
  }

  function adopt(next: CreationDraft): void {
    clearTimer()
    navigationEpoch += 1
    pendingPatch = {}
    selectDraft(next)
    remoteDraft.value = null
    autosaveState.value = 'idle'
  }

  async function removeDraft(id: string): Promise<boolean> {
    const requestEpoch = ++navigationEpoch
    loading.value = true
    if (!await flush() || requestEpoch !== navigationEpoch) {
      if (requestEpoch === navigationEpoch) loading.value = false
      return false
    }
    try {
      await request<{ deleted: boolean }>(`/api/creation-drafts/${id}`, { method: 'DELETE' })
      drafts.value = drafts.value.filter((item) => item.id !== id)
      if (requestEpoch === navigationEpoch && draft.value?.id === id) {
        selectDraft(null)
        clearTimer()
        pendingPatch = {}
        autosaveState.value = 'idle'
      }
      return true
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '草稿删除失败'
      return false
    } finally {
      if (requestEpoch === navigationEpoch) loading.value = false
    }
  }

  // 组件内使用时挂 scope 清理；scope 外调用（裸用/测试）Vue 会 warn，故先判有无 scope。
  if (getCurrentScope() && !options.persistent) {
    onScopeDispose(() => { clearTimer() })
  }

  return {
    draft, drafts, loading, error, autosaveState, lastSavedAt, remoteDraft, readonly,
    loadDrafts, openDraft, createDraft, queueSave, flush, reloadForConflict, removeDraft,
    readConflict, keepLocalForConflict, adopt, reset,
  }
}
