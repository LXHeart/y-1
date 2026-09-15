import { computed, ref, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { useAuth } from '../../../composables/useAuth'
import { GrasslandHttpError, fetchApi, request } from '../../../composables/grassland-http'
import { useAccountSessionStore, type AccountTicket } from '../../../stores/account-session'
import type { AdjudicationSnapshot, DisputeCase } from '../../../types/grassland/dispute'

/**
 * 争议详情读取代次（任务书 #103 C103-11 / §4.4、§6.7）。
 *
 * 状态机：`auth_pending → anonymous | loading_case → ready | forbidden | not_found | error`。
 * - 身份恢复中等待；确认匿名后进入 anonymous（视图走既有登录/returnTo，不跳首页）；
 * - 切换案件 ID、账号 epoch、退出页面（失活）时**同步**清掉旧案件数据、旧审判快照与错误，
 *   新案加载完且票据仍有效才进入 ready；
 * - 案件与 adjudication 以相同 caseId 上下文加载，旧请求的 success/error/finally 一律
 *   过「票据 + 请求序号 + 激活代次」闸后才可写回；
 * - 403=无权限（清私有案情）、404=不存在/不可用、401=匿名态、503/网络=本案可重试；
 * - captureAction 只在 ready 且路由目标一致时产出不可变写上下文（C103-12 消费）。
 */
export type DisputeCaseSessionState =
  | 'auth_pending'
  | 'anonymous'
  | 'loading_case'
  | 'ready'
  | 'forbidden'
  | 'not_found'
  | 'error'

/** 需要附带审判快照的状态（与既有视图判定一致：voting 起才有面板事实）。 */
const ADJUDICATION_STATUSES = ['voting', 'decided', 'appealed', 'final']

/** 争议 id 为服务端 UUID（trust 侧 randomUUID）；空白/非 UUID 属非法目标，不发私有请求。 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isPlausibleDisputeId(raw: string | null | undefined): boolean {
  return typeof raw === 'string' && UUID_PATTERN.test(raw.trim())
}

/** §4.4 写入上下文：`{accountId, epoch, disputeId, activationGeneration}` 的不可变捕获。 */
export interface DisputeActionContext {
  readonly accountId: string
  readonly epoch: number
  readonly disputeId: string
  readonly activationGeneration: number
  /** 提交前/回包后再校验：任一维度漂移（切案/换号/失活）即失效。 */
  isCurrent: () => boolean
}

export interface DisputeCaseSession {
  readonly state: Ref<DisputeCaseSessionState>
  /** 当前绑定的路由案件 ID（去空白后的目标；空/非法为 null）。 */
  readonly caseId: ComputedRef<string | null>
  readonly dispute: Ref<DisputeCase | null>
  readonly adjudication: Ref<AdjudicationSnapshot | null>
  readonly error: Ref<string>
  /** 当前激活代次：每次 activate/deactivate 递增。 */
  readonly activationGeneration: Ref<number>
  /** 只在 ready 且 route/loaded ID 一致时产出不可变写上下文；否则 null。 */
  captureAction: () => DisputeActionContext | null
  /** 手动重读当前案（错误态「本案重试」/写后核实；等待/匿名/失活态为安全空操作）。 */
  refresh: () => Promise<void>
  /** 组件挂载/重激活时调用（幂等）；重激活同案也会重新读取验证。 */
  activate: () => void
  /** 组件失活/卸载时调用：清私有案情并作废在途请求。 */
  deactivate: () => void
}

export function useDisputeCaseSession(options: {
  /** 路由案件 ID 读取器（默认无路由目标，纯单元环境使用）。 */
  routeCaseId?: () => string | null
}): DisputeCaseSession {
  const auth = useAuth()
  const accountSession = useAccountSessionStore()
  const routeCaseId = options.routeCaseId ?? (() => null)

  const state = ref<DisputeCaseSessionState>('auth_pending')
  const dispute = ref<DisputeCase | null>(null)
  const adjudication = ref<AdjudicationSnapshot | null>(null)
  const error = ref('')
  const activationGeneration = ref(0)
  const caseId = computed(() => {
    const raw = routeCaseId()
    if (typeof raw !== 'string') return null
    const trimmed = raw.trim()
    return trimmed === '' ? null : trimmed
  })

  let active = false
  /** 统一代次：目标（案件/账号/epoch）或激活变化 +1，作废一切在途闭包。 */
  let generation = 0
  /** 请求序号：同代次内只有最新一次读取可写。 */
  let readToken = 0
  let abortController: AbortController | null = null

  function makeGuard(captured: { token: number; gen: number; ticket: AccountTicket }) {
    return () =>
      captured.token === readToken && captured.gen === generation && accountSession.isCurrent(captured.ticket)
  }

  function invalidate(): void {
    generation += 1
    readToken += 1
    abortController?.abort()
    abortController = null
    dispute.value = null
    adjudication.value = null
    error.value = ''
  }

  async function load(): Promise<void> {
    const targetId = caseId.value
    if (!targetId || !isPlausibleDisputeId(targetId)) return
    const ticket = accountSession.capture()
    if (!ticket.accountId) return
    const guard = makeGuard({ token: ++readToken, gen: generation, ticket })
    abortController = new AbortController()
    const signal = abortController.signal
    state.value = 'loading_case'
    error.value = ''
    dispute.value = null
    adjudication.value = null
    try {
      // 案件与审判快照在同一 caseId 上下文（同代次+同票据+同请求序号）下加载。
      const data = await request<DisputeCase>(
        `/api/trust/disputes/${encodeURIComponent(targetId)}`,
        { signal },
      )
      if (!guard()) return
      dispute.value = data
      state.value = 'ready'
      if (ADJUDICATION_STATUSES.includes(data.status)) {
        try {
          // 快照端点带脱敏证据与访问审计，正常 200；403（非本轮面板/非当事方）静默忽略。
          const res = await fetchApi(`/api/trust/disputes/${encodeURIComponent(targetId)}/adjudication`, { signal })
          if (!guard()) return
          if (res.ok) {
            const body = await res.json().catch(() => null) as { success?: boolean; data?: AdjudicationSnapshot } | null
            if (!guard()) return
            if (body?.success === true && body.data) adjudication.value = body.data
          }
        } catch {
          // 审判快照失败不掩盖已就绪的案件事实（主体已在 ready）。
        }
      }
    } catch (caught: unknown) {
      if (!guard()) return // 旧请求的失败也不得显示
      if (caught instanceof GrasslandHttpError) {
        if (caught.status === 401) {
          state.value = 'anonymous'
          return
        }
        if (caught.status === 403) {
          // 无权限：清空私有案情并显示无权限（不跳列表页掩盖）。
          state.value = 'forbidden'
          return
        }
        if (caught.status === 404) {
          state.value = 'not_found'
          return
        }
      }
      state.value = 'error'
      error.value = caught instanceof Error ? caught.message : '加载失败'
    }
  }

  /** 同步 watch：换目标（账号/epoch/案件 ID/激活代次）立即清旧数据并重裁决。 */
  watch(
    () => ({
      authReady: auth.loaded.value && !auth.loading.value,
      accountId: accountSession.ownerAccountId,
      epoch: accountSession.epoch,
      caseId: caseId.value,
      activation: activationGeneration.value,
      // currentUser 以对象身份参与：401 后同 id 重新登录（新对象、epoch 不变）也要重读，
      // 否则会卡在 anonymous 态；资料更新触发的一次重读无害（正确性优先）。
      authUser: auth.currentUser.value,
    }),
    (target) => {
      invalidate()
      if (!target.authReady) {
        state.value = 'auth_pending'
        return
      }
      if (!target.accountId) {
        state.value = 'anonymous'
        return
      }
      if (!active) return // 失活缓存：私有案情已清，不发请求
      if (!target.caseId || !isPlausibleDisputeId(target.caseId)) {
        // 空/非法案件 ID：按目标不可用呈现，绝不发私有请求（E01）。
        state.value = 'not_found'
        return
      }
      void load()
    },
    { flush: 'sync', immediate: true },
  )

  function activate(): void {
    if (active) return // onMounted+onActivated 双触发幂等：不重复叠请求
    active = true
    activationGeneration.value += 1
  }

  function deactivate(): void {
    if (!active) return
    active = false
    activationGeneration.value += 1
  }

  function refresh(): Promise<void> {
    if (!active || !auth.loaded.value || auth.loading.value) return Promise.resolve()
    if (!accountSession.ownerAccountId) return Promise.resolve()
    if (!caseId.value || !isPlausibleDisputeId(caseId.value)) return Promise.resolve()
    return load()
  }

  function captureAction(): DisputeActionContext | null {
    if (state.value !== 'ready') return null
    const targetId = caseId.value
    const loaded = dispute.value
    if (!targetId || !loaded || loaded.id !== targetId) return null
    const accountId = accountSession.ownerAccountId
    if (!accountId) return null
    const ticket = accountSession.capture()
    if (!accountSession.isCurrent(ticket) || ticket.accountId !== accountId) return null
    const capturedGeneration = activationGeneration.value
    return {
      accountId,
      epoch: ticket.epoch,
      disputeId: targetId,
      activationGeneration: capturedGeneration,
      isCurrent: () =>
        accountSession.ownerAccountId === accountId
          && accountSession.epoch === ticket.epoch
          && caseId.value === targetId
          && activationGeneration.value === capturedGeneration
          && dispute.value?.id === targetId
          && state.value === 'ready',
    }
  }

  return {
    state,
    caseId,
    dispute,
    adjudication,
    error,
    activationGeneration,
    captureAction,
    refresh,
    activate,
    deactivate,
  }
}
