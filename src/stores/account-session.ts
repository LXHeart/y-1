import { onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth'

/**
 * 账号会话票据（任务书 #79 C79-01，D79-01/D79-02）：
 *
 * 私有状态的每次异步读写都以「账号 + epoch」双重判定归属——只比 accountId 防不住
 * A→B→A（第三个 A 会误认第一个 A 的旧票有效），epoch 在每次账号变化时单调递增，
 * 使所有旧票（含同 id 旧票）一律失效。signal 只用于中止旧账号的**只读**请求（取消是
 * 优化，不承担正确性——正确性由提交前的 isCurrent 检查保证）。
 */
export interface AccountTicket {
  readonly accountId: string | null
  readonly epoch: number
  readonly signal: AbortSignal
}

/** 消费方（设置/积分/身份/工作台等私有域）与账号会话的唯一对接面。 */
export interface AccountSessionPort {
  capture(): AccountTicket
  isCurrent(ticket: AccountTicket): boolean
}

/**
 * 账号 id 归一化（§5.1）：只有非空白 string 才是有效账号；null/缺字段/空白/非 string
 * 一律视为匿名，不得成为有效 owner。
 */
export function normalizeAccountId(raw: unknown): string | null {
  return typeof raw === 'string' && raw.trim() !== '' ? raw : null
}

/**
 * 每个 Pinia 实例一个账号会话 store：watch auth.currentUser.id（flush: sync），
 * 账号变化时先递增 epoch、abort 旧只读请求、切换 controller，再让消费方启动新加载
 * （消费方的 sync watch 排在本 watcher 之后，天然读到新 epoch）。
 *
 * epoch 从 0 起单调递增的内存计数器（不以 Date.now 做相等判定，固定时钟下照常递增）；
 * 不持久化到任何 storage。
 */
export const useAccountSessionStore = defineStore('account-session', () => {
  const auth = useAuthStore()
  const ownerAccountId = ref<string | null>(null)
  const epoch = ref(0)
  let controller = new AbortController()

  function capture(): AccountTicket {
    return { accountId: ownerAccountId.value, epoch: epoch.value, signal: controller.signal }
  }

  function isCurrent(ticket: AccountTicket): boolean {
    return ticket.accountId === ownerAccountId.value && ticket.epoch === epoch.value
  }

  const stopWatch = watch(
    () => normalizeAccountId(auth.currentUser?.id),
    (nextAccountId) => {
      // 同 id 的普通资料更新（换昵称/邮箱）不增 epoch、不失效现有票。
      if (nextAccountId === ownerAccountId.value) return
      ownerAccountId.value = nextAccountId
      epoch.value += 1
      controller.abort()
      controller = new AbortController()
    },
    // sync：auth.currentUser 一变，任何后续 capture() 立即拿到新 epoch，
    // 不给「已是 B、票还是 A」留窗口。immediate：store 晚于登录创建（整页加载）时
    // 也能对已就位的账号起算。
    { flush: 'sync', immediate: true },
  )

  // store 生命周期归 Pinia：作用域释放（disposePinia/应用卸载）时停表并废掉当前票。
  onScopeDispose(() => {
    stopWatch()
    controller.abort()
  })

  return { ownerAccountId, epoch, capture, isCurrent }
})
