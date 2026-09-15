import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useAuth } from '../../../composables/useAuth'
import { GrasslandHttpError, request } from '../../../composables/grassland-http'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { DisputeCase } from '../../../types/grassland/dispute'

/**
 * 争议列表读取代次（任务书 #103 C103-11 / §4.4）。
 *
 * 状态机：`auth_pending → anonymous | loading → ready | error`。
 * - 身份恢复中（auth.loaded=false 或 auth.loading=true）等待，不发任何私有请求；
 * - 确认匿名后进入 anonymous（视图走既有登录引导），不得跳首页掩盖；
 * - 目标变化（账号、epoch、激活代次）时同步清空私有列表并作废在途请求，
 *   旧的 success/error/finally 一律不得写回（票 + 请求序号 + 激活代次三重闸）；
 * - 同账号同代次内加载去重（KeepAlive 的 onMounted+onActivated 双触发、重复刷新）。
 */
export type DisputeListSessionState = 'auth_pending' | 'anonymous' | 'loading' | 'ready' | 'error'

export interface DisputeListSession {
  readonly state: Ref<DisputeListSessionState>
  readonly items: Ref<DisputeCase[]>
  readonly error: Ref<string>
  /** 当前激活代次：每次 activate/deactivate 递增，进入写上下文与失效判定。 */
  readonly activationGeneration: Ref<number>
  /** 组件挂载/重激活时调用（幂等：同目标重复调用不叠请求）。 */
  activate: () => void
  /** 组件失活/卸载时调用：清私有数据并作废在途请求。 */
  deactivate: () => void
  /** 手动重读当前账号列表（错误态「重试」按钮；匿名/等待态为安全空操作）。 */
  refresh: () => void
}

export function useDisputeListSession(): DisputeListSession {
  const auth = useAuth()
  const accountSession = useAccountSessionStore()

  const state = ref<DisputeListSessionState>('auth_pending')
  const items = ref<DisputeCase[]>([])
  const error = ref('')
  const activationGeneration = ref(0)

  let active = false
  /** 统一代次：任何目标/激活变化 +1，在途闭包捕获后比对，作废迟到更新。 */
  let generation = 0
  /** 请求序号：同代次内只有最新一次请求可写（受控逆序）。 */
  let readToken = 0
  /** 在途请求的代次键：同键去重（「当前账号内加载去重」）。 */
  let inFlightKey: string | null = null
  let abortController: AbortController | null = null

  /** 写回闸：票据（账号+epoch）+ 请求序号 + 读取代次全部一致才允许更新。 */
  function makeGuard(captured: { token: number; gen: number; ticket: ReturnType<typeof accountSession.capture> }) {
    return () =>
      captured.token === readToken && captured.gen === generation && accountSession.isCurrent(captured.ticket)
  }

  function invalidate(): void {
    generation += 1
    readToken += 1
    abortController?.abort()
    abortController = null
    items.value = []
    error.value = ''
  }

  async function load(): Promise<void> {
    const ticket = accountSession.capture()
    if (!ticket.accountId) return
    const key = `${ticket.accountId}|${ticket.epoch}|${generation}`
    if (inFlightKey === key) return // 同账号同代次去重：不重复叠请求
    inFlightKey = key
    const guard = makeGuard({ token: ++readToken, gen: generation, ticket })
    abortController = new AbortController()
    state.value = 'loading'
    error.value = ''
    items.value = []
    try {
      const data = await request<{ items: DisputeCase[] }>('/api/trust/disputes/me', {
        signal: abortController.signal,
      })
      if (!guard()) return
      items.value = Array.isArray(data.items) ? data.items : []
      state.value = 'ready'
    } catch (caught: unknown) {
      if (!guard()) return // 旧请求的失败也不得显示
      if (caught instanceof GrasslandHttpError && caught.status === 401) {
        // 服务端会话失效：回到匿名态走既有登录引导，不跳首页。
        state.value = 'anonymous'
        return
      }
      state.value = 'error'
      error.value = caught instanceof Error ? caught.message : '加载失败'
    } finally {
      if (guard()) inFlightKey = null
    }
  }

  /** 同步 watch：账号、epoch、身份恢复、激活代次任一变化立即重裁决目标。 */
  watch(
    () => ({
      authReady: auth.loaded.value && !auth.loading.value,
      accountId: accountSession.ownerAccountId,
      epoch: accountSession.epoch,
      activation: activationGeneration.value,
      // currentUser 以对象身份参与：401 后同 id 重新登录（新对象、epoch 不变）也要重读。
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
      if (!active) return // 失活缓存组件：数据已清，不发起读取
      void load()
    },
    { flush: 'sync', immediate: true },
  )

  function activate(): void {
    if (active) return
    active = true
    activationGeneration.value += 1
  }

  function deactivate(): void {
    if (!active) return
    active = false
    activationGeneration.value += 1
  }

  function refresh(): void {
    if (!active || !auth.loaded.value || auth.loading.value) return
    if (!accountSession.ownerAccountId) return
    void load()
  }

  return {
    state,
    items,
    error,
    activationGeneration,
    activate,
    deactivate,
    refresh,
  }
}
