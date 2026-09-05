/**
 * 唯一身份 bootstrap 协调器（任务书 #79 C79-03，D79-03）。
 *
 * 身份 I/O 只有 `ensureAccountIdentity` 一个协调入口：布局（账号 watch）与工作台
 * （initForAccount）等待**同一份 pending/快照**——同 epoch 的身份列表/门店 scope
 * 各请求一次（§6.6）；不同账号（或同账号换代，A→B→A）绝不共享 promise 或快照。
 *
 * pending/快照状态放在 Pinia store（每实例一份）：不跨 Pinia 复用账号数据，
 * 一个消费方卸载也不影响其他消费方仍在等待的同账号 bootstrap（生命周期归 Pinia）。
 */
import { shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { useAccountSessionStore } from '../stores/account-session'
import { useActiveIdentity, type AccountIdentitySnapshot } from './useActiveIdentity'
import type { useGrassland } from './useGrassland'

/** §6 契约：唯一身份 I/O 协调入口的签名。 */
export type EnsureAccountIdentity = (
  grassland: ReturnType<typeof useGrassland>,
) => Promise<AccountIdentitySnapshot | null>

interface BootstrapState {
  pendingKey: string
  pending: Promise<AccountIdentitySnapshot | null> | null
  snapshotKey: string
  snapshot: AccountIdentitySnapshot | null
}

const useAccountBootstrapStore = defineStore('account-bootstrap', () => {
  // shallowRef：快照保持原始引用（消费方 toBe 判定同一份），promise 不被深代理
  const state = shallowRef<BootstrapState>({ pendingKey: '', pending: null, snapshotKey: '', snapshot: null })

  function setPending(key: string, promise: Promise<AccountIdentitySnapshot | null>): void {
    state.value = { ...state.value, pendingKey: key, pending: promise }
  }

  function clearPending(key: string): void {
    if (state.value.pendingKey !== key) return
    state.value = { ...state.value, pendingKey: '', pending: null }
  }

  function setSnapshot(key: string, snapshot: AccountIdentitySnapshot): void {
    state.value = { ...state.value, snapshotKey: key, snapshot }
  }

  return { state, setPending, clearPending, setSnapshot }
})

/**
 * 确保当前账号的身份快照就绪：
 * - 匿名（无有效账号）不 bootstrap，直接返回 null（E01）；
 * - 同账号同 epoch：并发共用 pending、完成后共用快照（E03/§6.6）；
 * - 账号或代次变化：pending/快照一律不复用（E11），失败可显式重试（E04）。
 */
export const ensureAccountIdentity: EnsureAccountIdentity = async (grassland) => {
  const session = useAccountSessionStore()
  const store = useAccountBootstrapStore()
  const ticket = session.capture()
  if (!ticket.accountId) return null

  const key = `${ticket.accountId}#${ticket.epoch}`
  const current = store.state
  if (current.snapshotKey === key && current.snapshot !== null) return current.snapshot
  if (current.pendingKey === key && current.pending !== null) return current.pending

  const attempt = (async () => {
    try {
      const snapshot = await useActiveIdentity().loadAccountIdentity(grassland)
      // 快照只供当前 owner：装载期间换号则丢弃（E11）
      if (snapshot !== null && session.isCurrent(ticket)) store.setSnapshot(key, snapshot)
      return snapshot
    } finally {
      store.clearPending(key)
    }
  })()
  store.setPending(key, attempt)
  return attempt
}
