import { getCurrentScope, onScopeDispose, ref } from 'vue'
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

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: init.body
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
      : init.headers || {},
  })

  const raw = await response.text()
  let body: Envelope<T> | null = null
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
export function useCreationDraft() {
  const draft = ref<CreationDraft | null>(null)
  const drafts = ref<CreationDraft[]>([])
  const loading = ref(false)
  const error = ref('')
  const autosaveState = ref<AutosaveState>('idle')
  const lastSavedAt = ref<string>('')

  let timer: ReturnType<typeof setTimeout> | null = null
  let pendingPatch: Partial<SaveDraftInput> = {}
  let saveInFlight: Promise<boolean> | null = null
  let activeDraftEpoch = 0
  let navigationEpoch = 0

  function clearTimer(): void {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  async function loadDrafts(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const data = await request<{ items: CreationDraft[] }>('/api/creation-drafts')
      drafts.value = data.items ?? []
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '草稿列表加载失败'
    } finally {
      loading.value = false
    }
  }

  function selectDraft(next: CreationDraft | null): void {
    draft.value = next
    activeDraftEpoch += 1
  }

  function mergePendingPatch(saved: CreationDraft): CreationDraft {
    return {
      ...saved,
      ...(pendingPatch.title !== undefined ? { title: pendingPatch.title } : {}),
      ...(pendingPatch.topic !== undefined ? { topic: pendingPatch.topic } : {}),
      ...(pendingPatch.articleTitle !== undefined ? { articleTitle: pendingPatch.articleTitle } : {}),
      ...(pendingPatch.outline !== undefined ? { outline: pendingPatch.outline } : {}),
      ...(pendingPatch.content !== undefined ? { content: pendingPatch.content } : {}),
      ...(pendingPatch.platform !== undefined ? { platform: pendingPatch.platform } : {}),
      ...(pendingPatch.contentForm !== undefined ? { contentForm: pendingPatch.contentForm } : {}),
      ...(pendingPatch.status !== undefined ? { status: pendingPatch.status } : {}),
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

  async function createDraft(input: CreateDraftInput): Promise<CreationDraft | null> {
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
      drafts.value = [data, ...drafts.value]
      if (requestEpoch !== navigationEpoch) return data
      if (!await flush() || requestEpoch !== navigationEpoch) return null
      selectDraft(data)
      autosaveState.value = 'idle'
      return data
    } catch (err: unknown) {
      if (requestEpoch !== navigationEpoch) return null
      error.value = err instanceof Error ? err.message : '草稿创建失败'
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
          topic: patch.topic ?? current.topic,
          articleTitle: patch.articleTitle ?? current.articleTitle,
          outline: patch.outline ?? current.outline,
          content: patch.content ?? current.content,
          platform: patch.platform ?? current.platform,
          contentForm: patch.contentForm ?? current.contentForm,
          status: patch.status ?? current.status,
        } satisfies SaveDraftInput),
      })
      const optimisticDraft = isCurrentDraft() ? mergePendingPatch(saved) : saved
      drafts.value = drafts.value.map((item) => item.id === saved.id ? optimisticDraft : item)
      if (isCurrentDraft()) {
        draft.value = optimisticDraft
        autosaveState.value = Object.keys(pendingPatch).length ? 'pending' : 'saved'
        lastSavedAt.value = saved.updatedAt
      }
      return true
    } catch (err: unknown) {
      const status = (err as { status?: number }).status
      if (status === 409) {
        if (isCurrentDraft()) {
          autosaveState.value = 'conflict'
          error.value = '草稿已被其他设备修改，请重新载入后合并'
        }
      } else {
        // 较新的编辑覆盖本轮失败的旧字段，保证恢复队列时不倒退用户输入。
        pendingPatch = { ...patch, ...pendingPatch }
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
    if (!draft.value || autosaveState.value === 'conflict') return
    pendingPatch = { ...pendingPatch, ...patch }
    autosaveState.value = 'pending'
    clearTimer()
    timer = setTimeout(() => { void flush() }, AUTOSAVE_DELAY_MS)
  }

  /** 冲突后重载服务端版本，丢弃本地未保存改动（由 UI 明确告知用户）。 */
  async function reloadForConflict(): Promise<CreationDraft | null> {
    const current = draft.value
    if (!current) return null
    clearTimer()
    pendingPatch = {}
    const reloaded = await openDraft(current.id)
    if (reloaded) {
      autosaveState.value = 'idle'
      error.value = ''
    }
    return reloaded
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
  if (getCurrentScope()) {
    onScopeDispose(() => { clearTimer() })
  }

  return {
    draft, drafts, loading, error, autosaveState, lastSavedAt,
    loadDrafts, openDraft, createDraft, queueSave, flush, reloadForConflict, removeDraft,
  }
}
