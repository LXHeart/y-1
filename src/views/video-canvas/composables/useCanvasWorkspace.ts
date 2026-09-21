import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { fetchApi } from '../../../composables/grassland-http'
import { projectAsDraft, useCreationDraftSessions } from '../../../lib/creation-draft-session'
import { readAccountKey, registerAccountKey } from '../../../lib/account-private-cache'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { VideoCanvasLayout, WorkspaceBindingResult } from '../../../types/video-canvas'

/**
 * 项目绑定与共享草稿会话；旧 inputs.videoCanvas 仅用于首次读取兼容。
 *
 * <p>
 * 独立文档负责布局保存；共享草稿会话只承载内容和交付版本。
 * 账号 epoch 切换即失效——在途绑定响应与排队布局不串账号。
 */
export interface UseCanvasWorkspaceOptions {
  /** 收集当前轻量布局（视口/坐标/活动分支）；调用方保证数值已按画布界钳制。 */
  collectLayout: () => VideoCanvasLayout
  /** 应用恢复的布局（未知 schema 时调用方负责只读降级）；null/undefined 表示旧草稿无布局。 */
  applyLayout: (layout: unknown) => void
}


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

  let revision = 0
  let activeKey = ''
  let pendingBind: { key: string; run: Promise<boolean> } | null = null
  function reset(): void {
    revision += 1
    activeKey = ''
    pendingBind = null
    binding.value = null
    bindingError.value = ''
    bindingPending.value = false
    session.value = getDraftSession()
  }
  watch(() => account.epoch, reset, { flush: 'sync' })

  /** 绑定幂等键：同账号同分镜复用同一 operationId（响应丢失重试不换关联）。 */
  function requestKey(key: { storyboard: string; draft: string | null }): string {
    return `video-canvas-bind:${account.ownerAccountId ?? ''}:${account.epoch}:${key.storyboard}:${key.draft ?? ''}`
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
  async function bind(key: { storyboard: string; draft: string | null }): Promise<boolean> {
    if (binding.value?.storyboardId === key.storyboard
      && (!key.draft || key.draft === binding.value.project.id)) return true
    const nextKey = requestKey(key)
    if (pendingBind?.key === nextKey) return pendingBind.run
    if (activeKey !== nextKey) { reset(); activeKey = nextKey }
    const run = doBind(key, nextKey)
    pendingBind = { key: nextKey, run }
    try {
      return await run
    } finally {
      if (pendingBind?.run === run) pendingBind = null
    }
  }

  async function doBind(key: { storyboard: string; draft: string | null }, storageKey: string): Promise<boolean> {
    const epoch = revision
    bindingError.value = ''
    bindingPending.value = true
    try {
      // C104-04：恢复读取走登记簿入口——代次校验/旧值回收/v1 迁移都在那里，
      // 失效只读不复活旧缓存；同键重试继续复用登记过的 operationId。
      let payload = readAccountKey(account.ownerAccountId, 'session', storageKey)
      let draftParam: { draftId: string; expectedDraftVersion: number } | null = null
      if (!payload && key.draft) {
        const known = getDraftSession(key.draft).draft.value
        const version = known?.id === key.draft && typeof known.version === 'number'
          ? known.version
          : await fetchDraftVersion(key.draft)
        if (version == null) throw new Error('草稿不存在或已删除')
        draftParam = { draftId: key.draft, expectedDraftVersion: version }
      }
      if (epoch !== revision) return false
      if (!payload) {
        payload = JSON.stringify({ operationId: crypto.randomUUID(), ...(draftParam ?? {}) })
        sessionStorage.setItem(storageKey, payload)
        // 任务书 #103 C103-10 → #104 C104-04：绑定暂存键登记 owner（键本身含账号+epoch）；
        // 登记失败/代次失效时登记簿会回收刚写的值，内存态 payload 继续本次绑定。
        registerAccountKey(account.ownerAccountId, 'session', storageKey)
      }
      const response = await fetchApi(
        `/api/video-production/storyboards/${encodeURIComponent(key.storyboard)}/workspace`, {
          method: 'POST',
          body: payload,
        })
      const body = await response.json() as { success: boolean; data?: WorkspaceBindingResult; error?: string }
      if (!response.ok || !body.success || !body.data) {
        throw new Error(body.error || '画布工作区绑定失败')
      }
      sessionStorage.removeItem(storageKey)
      if (epoch !== revision) return false
      if (body.data.storyboardId !== key.storyboard || (key.draft && body.data.project.id !== key.draft))
        throw new Error('项目关联不匹配，请从最近项目重新进入')
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

  // Existing callers may keep these names; the only writer is now the independent document queue.
  let layoutWriter: { queue: () => void; flush: () => Promise<boolean> } | null = null
  function setLayoutWriter(writer: NonNullable<typeof layoutWriter>): void { layoutWriter = writer }
  function queueLayoutSave(): void { layoutWriter?.queue() }
  async function flushLayout(): Promise<boolean> { return layoutWriter ? layoutWriter.flush() : true }

  onScopeDispose(() => {
    reset()
  })

  return {
    binding, bindingError, bindingPending, draftId, saveState, readonly,
    bind, queueLayoutSave, flushLayout, setLayoutWriter, reset,
    flush: () => session.value.flush(),
  }
}

/** 布局恢复的窄类型读取（未知形态由调用方降级；这里只做安全的字段抽取）。 */
export function readCanvasLayout(raw: unknown): VideoCanvasLayout | null {
  if (!raw || typeof raw !== 'object') return null
  const candidate = raw as Partial<VideoCanvasLayout>
  if (candidate.schemaVersion !== 1) return null
  return candidate as VideoCanvasLayout
}
