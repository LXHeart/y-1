import { readonly, ref } from 'vue'
import { studioRequest as request, StudioHttpError, useStudioActivity, useStudioGuard } from '../../../lib/creation-studio-http'
import { GrasslandHttpError } from '../../../composables/grassland-http'

/**
 * 任务书 #101 C101-22：公众号草稿同步客户端与状态（API101-26~31）。
 * 提交绑定不可变快照（draftVersion 冻结在服务端 payload——本地继续编辑不影响在途同步）；
 * unknown 只读核实（候选/明确 media_id），绝不自动重发；轮询带 epoch 迟到响应守卫。
 */

export type WechatDraftSyncState =
  | 'preparing' | 'uploading' | 'submitting' | 'verifying'
  | 'succeeded' | 'failed' | 'unknown' | 'cancelled'

export interface WechatDraftSync {
  id: string
  requestId: string
  accountId: string
  draftId: string
  draftVersion: number
  state: WechatDraftSyncState
  externalDraftMediaId: string | null
  payloadHash: string
  version: number
  createdAt: string
  verifiedAt: string | null
  error: { code: string; message: string } | null
}

export interface WechatDraftCandidate {
  externalDraftMediaId: string
  title: string
  updatedAt: string | null
  contentMatches: boolean
}

export const ACTIVE_SYNC_STATES: WechatDraftSyncState[] = ['preparing', 'uploading', 'submitting', 'verifying']

export function isSyncActive(state: WechatDraftSyncState): boolean {
  return ACTIVE_SYNC_STATES.includes(state)
}

export interface CreateDraftSyncInput {
  requestId: string
  accountId: string
  expectedAccountVersion: number
  draftId: string
  draftVersion: number
  exportId: string
  author?: string
  contentSourceUrl?: string
  needOpenComment: 0 | 1
  onlyFansCanComment: 0 | 1
}

export async function createDraftSync(input: CreateDraftSyncInput): Promise<WechatDraftSync> {
  return request<WechatDraftSync>('/api/creation-channels/wechat/draft-syncs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export async function readDraftSync(id: string): Promise<WechatDraftSync> {
  return request<WechatDraftSync>(`/api/creation-channels/wechat/draft-syncs/${encodeURIComponent(id)}`,
    { method: 'GET' })
}

export async function listDraftSyncs(draftId: string): Promise<{ items: WechatDraftSync[]; nextCursor: string | null }> {
  return request(`/api/creation-channels/wechat/draft-syncs?draftId=${encodeURIComponent(draftId)}`, { method: 'GET' })
}

export async function cancelDraftSync(id: string, expectedVersion: number, requestId: string): Promise<WechatDraftSync> {
  return request<WechatDraftSync>(
    `/api/creation-channels/wechat/draft-syncs/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId, expectedVersion }),
    })
}

export async function reconcileDraftSync(id: string, expectedVersion: number, externalDraftMediaId: string,
  requestId: string): Promise<WechatDraftSync> {
  return request<WechatDraftSync>(
    `/api/creation-channels/wechat/draft-syncs/${encodeURIComponent(id)}/reconcile`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId, expectedVersion, externalDraftMediaId }),
    })
}

export interface DraftSyncCandidates {
  items: WechatDraftCandidate[]
  searchedCount: number
  hasMore: boolean
}

export async function fetchDraftSyncCandidates(id: string): Promise<DraftSyncCandidates> {
  return request<DraftSyncCandidates>(
    `/api/creation-channels/wechat/draft-syncs/${encodeURIComponent(id)}/candidates`, { method: 'GET' })
}

/** 同步状态中文（成功只说「已存入草稿箱」——不存在「已发布」与公开链接文案）。 */
export function syncStateLabel(state: WechatDraftSyncState): string {
  switch (state) {
    case 'preparing': return '准备中'
    case 'uploading': return '图片上传中'
    case 'submitting': return '写入草稿箱中'
    case 'verifying': return '回读核实中'
    case 'succeeded': return '已存入草稿箱'
    case 'failed': return '失败'
    case 'unknown': return '结果未知'
    case 'cancelled': return '已取消'
  }
}

export function syncActionError(error: unknown, fallback: string): { message: string; versionConflict: boolean } {
  if (error instanceof GrasslandHttpError || error instanceof StudioHttpError) {
    if (error.status === 409 && error.code === 'STUDIO_VERSION_CONFLICT')
      return { message: '同步记录已被其他操作更新，已刷新，请重试', versionConflict: true }
    return { message: error.message || fallback, versionConflict: false }
  }
  return { message: error instanceof Error && error.message ? error.message : fallback, versionConflict: false }
}

/** 默认 4s（服务端总时限 10min 对应 ~150 次）；测试可注入更短间隔。 */
const DEFAULT_POLL_INTERVAL_MS = 4000
const POLL_MAX_ATTEMPTS = 150

/**
 * 同步状态机：提交后保留 syncId；轮询推进到终态；刷新/重开经 refresh() 从服务端读回同任务。
 * 迟到响应/任务切换经 epoch 丢弃；轮询到 active 终止条件即停（无泄漏定时器）。
 */
export function useWechatDraftSync(draftId: () => string | undefined,
  options: { pollIntervalMs?: number } = {}) {
  const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS
  const current = ref<WechatDraftSync | null>(null)
  const history = ref<WechatDraftSync[]>([])
  const loadError = ref('')
  let epoch = 0
  let pollAttempts = 0
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let active = true
  const guard = useStudioGuard(draftId)
  guard.onInvalidate(reset)
  function pause(): void { active = false; epoch += 1; stopPolling() }
  function resume(): void { active = true; if (current.value) void refresh() }
  const activity = useStudioActivity(pause, resume)

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function schedulePoll(): void {
    stopPolling()
    if (!active || !activity.isActive() || !current.value || !isSyncActive(current.value.state)) return
    if (pollAttempts >= POLL_MAX_ATTEMPTS) { loadError.value = '同步耗时较长，请手动刷新状态'; return }
    pollTimer = setTimeout(() => { void pollOnce() }, pollIntervalMs)
  }

  async function pollOnce(): Promise<void> {
    const sync = current.value
    if (!sync || !isSyncActive(sync.state)) return
    const guard = epoch
    try {
      const next = await readDraftSync(sync.id)
      if (guard !== epoch) return
      if (next.draftId !== draftId() || next.id !== sync.id || next.version < sync.version) return
      current.value = next
      history.value = history.value.map(item => item.id === next.id ? next : item)
    } catch (failure) {
      if (guard !== epoch) return
      loadError.value = syncActionError(failure, '同步状态暂时无法更新，请手动刷新').message
      stopPolling()
      return
    }
    pollAttempts += 1
    schedulePoll()
  }

  /** 提交后接管：保留 syncId 并开始轮询；双击提交由调用方 busy 态拦截。 */
  function track(sync: WechatDraftSync): void {
    if (sync.draftId !== draftId()) return
    epoch += 1
    current.value = sync
    history.value = [sync, ...history.value.filter(item => item.id !== sync.id)]
    pollAttempts = 0
    schedulePoll()
  }

  /** 刷新/重开恢复：读回该草稿最近同步（同任务同一 syncId）。 */
  async function refresh(): Promise<void> {
    if (!activity.isActive()) return
    const guard = ++epoch
    const id = draftId()
    stopPolling()
    if (!id) return
    try {
      const page = await listDraftSyncs(id)
      if (guard !== epoch) return
      history.value = page.items ?? []
      loadError.value = ''
      const latest = page.items[0] ?? null
      if (latest) {
          current.value = page.items.find(item => item.id === current.value?.id) ?? latest
          if (isSyncActive(current.value.state)) {
            pollAttempts = 0
            schedulePoll()
          }
      } else current.value = null
    } catch (error) {
      if (guard !== epoch) return
      loadError.value = syncActionError(error, '同步记录加载失败').message
    }
  }

  /** 账号切换/卸载：停止轮询并丢弃在途状态（TC101-108 请求与 owner 隔离）。 */
  function reset(): void {
    epoch += 1
    stopPolling()
    current.value = null
    history.value = []
    loadError.value = ''
  }

  return {
    current: readonly(current),
    history: readonly(history),
    loadError: readonly(loadError),
    track,
    refresh,
    reset,
    /** 测试探针。 */
    isPolling: () => pollTimer !== null,
  }
}
