import { getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
import { getActivePinia } from 'pinia'
import type { useGrassland } from './useGrassland'
import { useStudioActivity } from '../lib/creation-studio-http'
import { useAccountSessionStore } from '../stores/account-session'
import type { EngagementExitFunds } from '../types/grassland/task'

/**
 * 退出资金态查询 composable（任务书 #103 C103-04 → #104 C104-05 重构，§3 D04）。
 *
 * - 目标以 tuple 捕获（不拼/拆分隔符）；同目标 refresh 在途只返回同一 Promise，不重叠请求。
 * - 请求完成后 5,000ms 的单次 setTimeout（无 setInterval）；仅 pending/processing/retry_wait
 *   三个进行态续排，succeeded/needs_review/空结果停止（null 不推断成功）。
 * - 轮询只在活动且有目标时存在：hidden 与 KeepAlive deactivated 正交记录（复用
 *   useStudioActivity：显示页面不能激活仍处于 deactivated 的组件）；重新活动立即查一次。
 * - disposed 一经置 true 永不恢复；pause/换目标/换账号递增请求代次并 abort 只读请求、
 *   清 timer——迟到回包在代次/活动/目标/账号票据四重校验后才能写 state。
 * - 失败保留已有资金快照、显示错误并停止自动轮询（手动刷新恢复）；abort 不显示业务错误
 *   （只读取消；取消不冒充写取消，不撤销后端资金操作）。
 * - 空目标：资金/错误/loading 全清并停止一切计时与请求。
 */
interface FundsTarget {
  taskId: string
  applicationId: string
}

const POLL_INTERVAL_MS = 5000
const ACTIVE_STATES = new Set(['pending', 'processing', 'retry_wait'])

export function useEngagementExitFunds(client: ReturnType<typeof useGrassland>) {
  const funds = ref<EngagementExitFunds | null>(null) as Ref<EngagementExitFunds | null>
  const loading = ref(false)
  const error = ref('')

  const session = getActivePinia() ? useAccountSessionStore() : null
  let disposed = false
  let requestGeneration = 0
  let currentTarget: FundsTarget | null = null
  let pendingPromise: Promise<EngagementExitFunds | null> | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let controller: AbortController | null = null

  function clearTimer(): void {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  /** 暂停：代次+1、abort 在途只读、清 timer 与 loading；保留资金快照与错误供恢复参考。 */
  function pause(clearSnapshot: boolean): void {
    requestGeneration += 1
    controller?.abort()
    controller = null
    clearTimer()
    pendingPromise = null
    loading.value = false
    if (clearSnapshot) {
      funds.value = null
      error.value = ''
    }
  }

  function sameTarget(left: FundsTarget | null, right: FundsTarget | null): boolean {
    return left?.taskId === right?.taskId && left?.applicationId === right?.applicationId
  }

  /** state 写入四重闸：代次、活动、目标、账号票据（§3 D04）。 */
  function canApply(generation: number, target: FundsTarget, ticket: ReturnType<
    NonNullable<typeof session>['capture']> | null): boolean {
    if (disposed || generation !== requestGeneration) return false
    if (!sameTarget(target, currentTarget)) return false
    if (!activity.isActive()) return false
    return ticket === null || session === null || session.isCurrent(ticket)
  }

  const activity = useStudioActivity(
    () => pause(false),
    () => {
      // 重新活动立即查询一次；是否续排由响应状态决定（三进行态才继续）。
      if (!disposed && currentTarget) void refresh()
    },
  )

  async function refresh(): Promise<EngagementExitFunds | null> {
    const target = currentTarget
    if (!target || disposed) return funds.value
    if (pendingPromise) return pendingPromise
    const generation = requestGeneration
    const ticket = session ? session.capture() : null
    const localController = new AbortController()
    controller = localController
    loading.value = true
    error.value = ''
    const holder: { promise: Promise<EngagementExitFunds | null> | null } = { promise: null }
    holder.promise = (async () => {
      try {
        const result = await client.fetchEngagementExitFunds(target.taskId, target.applicationId,
          localController.signal)
        if (!canApply(generation, target, ticket)) return funds.value
        loading.value = false
        if (result === null || result === undefined) {
          error.value = client.error.value || '资金状态查询失败'
          return funds.value
        }
        funds.value = result.operationId ? result : null
        scheduleIfNeeded()
        return funds.value
      } catch (caught: unknown) {
        // run 约定不抛；防御路径与失败同语义（不中断调用方）。
        if (canApply(generation, target, ticket)) {
          loading.value = false
          error.value = caught instanceof Error && caught.message ? caught.message : '资金状态查询失败'
        }
        return funds.value
      } finally {
        if (pendingPromise === holder.promise) pendingPromise = null
      }
    })()
    pendingPromise = holder.promise
    return holder.promise
  }

  /** 成功响应后按进行态续排一次 5s timeout；终态/空态/非活动不排。 */
  function scheduleIfNeeded(): void {
    clearTimer()
    if (disposed || !activity.isActive() || !currentTarget) return
    const state = funds.value?.state
    if (state && ACTIVE_STATES.has(state)) {
      pollTimer = setTimeout(() => {
        pollTimer = null
        void refresh()
      }, POLL_INTERVAL_MS)
    }
  }

  /** 激活目标：切对象先废弃旧请求（代次+abort）；空 key 停止并清空资金/错误/loading。 */
  function target(taskId: string | null | undefined, applicationId: string | null | undefined): void {
    const next: FundsTarget | null = taskId && applicationId
      ? { taskId: String(taskId), applicationId: String(applicationId) }
      : null
    if (sameTarget(next, currentTarget)) return
    pause(next === null)
    currentTarget = next
    if (!next) return
    void refresh()
  }

  // 换号：旧票全部失效，资金快照属于旧账号不可信——清快照并停一切（新查询由新目标的 target() 驱动）。
  if (session && getCurrentScope()) {
    const stopEpochWatch = watch(() => session.epoch, () => {
      if (disposed) return
      pause(true)
    }, { flush: 'sync' })
    onScopeDispose(stopEpochWatch)
  }

  // 作用域销毁：disposed 不可逆；useStudioActivity 的 dispose 回调同样触发 pause。
  if (getCurrentScope()) {
    onScopeDispose(() => {
      disposed = true
      pause(false)
    })
  }

  return { funds, loading, error, refresh, target, watch }
}
