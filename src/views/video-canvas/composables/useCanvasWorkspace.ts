import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { fetchApi } from '../../../composables/grassland-http'
import { projectAsDraft, useCreationDraftSessions } from '../../../lib/creation-draft-session'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { CreationWorkspacePayload } from '../../../types/creation'
import type { VideoCanvasLayout, WorkspaceBindingResult } from '../../../types/video-canvas'

/**
 * 画布工作区会话（任务书 #100 C100-04）：绑定（API-07）+ 轻量布局的共享自动保存。
 *
 * <p>
 * 布局只写 inputs.videoCanvas，通过 {@link useCreationDraftSessions} 与快速模式共用同一
 * 草稿会话（版本与写队列单例）；其余 inputs（video 等）原样透传不覆写。
 * 账号 epoch 切换即失效——在途绑定响应与排队布局不串账号。
 */
export interface UseCanvasWorkspaceOptions {
  /** 收集当前轻量布局（视口/坐标/活动分支）；调用方保证数值已按画布界钳制。 */
  collectLayout: () => VideoCanvasLayout
  /** 应用恢复的布局（未知 schema 时调用方负责只读降级）；null/undefined 表示旧草稿无布局。 */
  applyLayout: (layout: unknown) => void
}

const LAYOUT_SAVE_DELAY_MS = 800

export function useCanvasWorkspace(options: UseCanvasWorkspaceOptions) {
  const account = useAccountSessionStore()
  const getDraftSession = useCreationDraftSessions()
  const session = shallowRef(getDraftSession())
  const binding = shallowRef<WorkspaceBindingResult | null>(null)
  const bindingError = ref('')
  const bindingPending = ref(false)

  const draftId = computed(() => binding.value?.project.id ?? session.value.draft.value?.id ?? '')
  const saveState = computed(() => session.value.autosaveState.value)
  const readonly = computed(() => session.value.readonly.value)

  /** 账号 epoch：切换即作废绑定与在途响应（A→B→A 的慢响应不串号）。 */
  let revision = 0
  watch(() => account.epoch, () => {
    revision += 1
    binding.value = null
    bindingError.value = ''
    bindingPending.value = false
  }, { flush: 'sync' })

  /** 绑定幂等键：同账号同分镜复用同一 operationId（响应丢失重试不换关联）。 */
  function operationIdFor(storyboardId: string): string {
    const key = `video-canvas-bind:${account.epoch ?? 0}:${storyboardId}`
    const existing = sessionStorage.getItem(key)
    if (existing) return existing
    const generated = crypto.randomUUID()
    sessionStorage.setItem(key, generated)
    return generated
  }

  async function fetchDraftVersion(draftId: string): Promise<number | null> {
    const response = await fetchApi(`/api/creation-drafts/${encodeURIComponent(draftId)}`)
    if (!response.ok) return null
    const body = await response.json() as { success: boolean; data?: { version?: number } }
    return typeof body.data?.version === 'number' ? body.data.version : null
  }

  /**
   * 绑定分镜工作区：旧 storyboard-only 深链不带 draft（服务端唯一补关联）；
   * draft 入口带 expectedDraftVersion（会话已知版本优先，冷入口先取当前版本）。
   *
   * 并发重入串行化（C100-08 e2e 实测）：挂载绑定与账号 epoch 解析后的重入绑定可能并发
   * 争抢同一分镜的首次关联（双方各带不同 operationId → 后到者 409「已被其他会话关联」）。
   * 排队等待而非并发发起；前一次成功后当前 key 已绑定则直接复用其结果。
   */
  let pendingBind: Promise<boolean> | null = null
  async function bind(key: { storyboard: string; draft: string | null }): Promise<boolean> {
    if (pendingBind) {
      await pendingBind.catch(() => undefined)
      // 前一绑定若已落到同一分镜，直接视为成功（URL draft 回填由调用方 syncDraft 收口）
      if (binding.value && !bindingError.value) return true
    }
    const run = doBind(key)
    pendingBind = run
    try {
      return await run
    } finally {
      if (pendingBind === run) pendingBind = null
    }
  }

  async function doBind(key: { storyboard: string; draft: string | null }): Promise<boolean> {
    const epoch = revision
    bindingError.value = ''
    bindingPending.value = true
    try {
      let draftParam: { draftId: string; expectedDraftVersion: number } | null = null
      if (key.draft) {
        const known = getDraftSession(key.draft).draft.value
        const version = known?.id === key.draft && typeof known.version === 'number'
          ? known.version
          : await fetchDraftVersion(key.draft)
        if (version == null) throw new Error('草稿不存在或已删除')
        draftParam = { draftId: key.draft, expectedDraftVersion: version }
      }
      const response = await fetchApi(
        `/api/video-production/storyboards/${encodeURIComponent(key.storyboard)}/workspace`, {
          method: 'POST',
          body: JSON.stringify({
            operationId: operationIdFor(key.storyboard),
            ...(draftParam ?? {}),
          }),
        })
      const body = await response.json() as { success: boolean; data?: WorkspaceBindingResult; error?: string }
      if (!response.ok || !body.success || !body.data) {
        throw new Error(body.error || '画布工作区绑定失败')
      }
      if (epoch !== revision) return false
      binding.value = body.data
      const nextSession = getDraftSession(body.data.project.id)
      // 快速模式会话已持有同草稿的本地态时不覆盖，只在冷会话采纳服务端项目
      if (nextSession.draft.value?.id !== body.data.project.id) {
        nextSession.adopt(projectAsDraft(body.data.project))
      }
      session.value = nextSession
      options.applyLayout((body.data.project.workspace?.inputs as Record<string, unknown> | undefined)?.videoCanvas)
      return true
    } catch (err) {
      if (epoch === revision) {
        bindingError.value = err instanceof Error ? err.message : '画布工作区绑定失败'
      }
      return false
    } finally {
      if (epoch === revision) bindingPending.value = false
    }
  }

  let saveTimer: ReturnType<typeof setTimeout> | null = null

  function clearTimer(): void {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = null
  }

  /** 布局变更排队保存（拖拽落位/键盘移动/分支切换触发；debounce 与草稿自动保存同频）。 */
  function queueLayoutSave(): void {
    if (!session.value.draft.value || readonly.value) return
    clearTimer()
    saveTimer = setTimeout(() => { void flushLayout() }, LAYOUT_SAVE_DELAY_MS)
  }

  /** 立即排空布局保存；切模式/卸载前调用。失败返回 false（调用方停留并提示）。 */
  async function flushLayout(): Promise<boolean> {
    clearTimer()
    const current = session.value.draft.value
    if (!current) return true
    const previousWorkspace = (current.workspace ?? {}) as CreationWorkspacePayload
    const workspace: CreationWorkspacePayload = {
      ...previousWorkspace,
      schemaVersion: 1,
      capability: previousWorkspace.capability ?? 'video',
      inputs: {
        ...(previousWorkspace.inputs ?? {}),
        videoCanvas: options.collectLayout(),
      },
    }
    session.value.queueSave({ workspace })
    return session.value.flush()
  }

  onScopeDispose(() => {
    clearTimer()
    void flushLayout()
  })

  return {
    binding, bindingError, bindingPending, draftId, saveState, readonly,
    bind, queueLayoutSave, flushLayout,
  }
}

/** 布局恢复的窄类型读取（未知形态由调用方降级；这里只做安全的字段抽取）。 */
export function readCanvasLayout(raw: unknown): VideoCanvasLayout | null {
  if (!raw || typeof raw !== 'object') return null
  const candidate = raw as Partial<VideoCanvasLayout>
  if (candidate.schemaVersion !== 1) return null
  return candidate as VideoCanvasLayout
}
