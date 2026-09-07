import { computed, ref, watch } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { Wallet } from '../../../types/grassland'
import { yuanToCents } from '../../../lib/money'

export function useWorkbenchWallet(grassland: ReturnType<typeof useGrassland>) {
  const session = useAccountSessionStore()
  const wallet = ref<Wallet | null>(null)
  const notice = ref('')
  const withdrawYuan = ref(0)
  const pendingWithdrawal = ref<{ amountCents: number; operationId: string } | null>(null)
  const withdrawalBusy = ref(false)
  const groups = ref<{ deposit: number; pending: number; held: number } | null>(null)
  let refreshSequence = 0

  const canWithdraw = computed(() => !withdrawalBusy.value && (pendingWithdrawal.value != null
    || (!!wallet.value && Number.isFinite(withdrawYuan.value) && withdrawYuan.value > 0
      && yuanToCents(withdrawYuan.value) <= wallet.value.balanceCents)))

  async function refresh(): Promise<void> {
    const ticket = session.capture()
    const sequence = ++refreshSequence
    const current = () => session.isCurrent(ticket) && sequence === refreshSequence
    const data = await grassland.getMyWallet()
    if (!current() || !data) return
    wallet.value = data
    groups.value = null
    if (!data.positions) return
    const positions = await Promise.all(data.positions.map(async (position) => ({
      position, state: await grassland.getApplicationSettlement(position.engagementRef),
    })))
    if (!current() || positions.some(({ state }) => !state)) return
    const totals = { deposit: 0, pending: 0, held: 0 }
    for (const { position, state } of positions) {
      if (state?.settlementStatus === 'held') totals.held += position.amountCents
      else if (position.kind === 'deposit') totals.deposit += position.amountCents
      else totals.pending += position.amountCents
    }
    groups.value = totals
  }

  async function withdraw(): Promise<void> {
    if (!canWithdraw.value) return
    const ticket = session.capture()
    const operation = pendingWithdrawal.value ?? {
      amountCents: yuanToCents(withdrawYuan.value), operationId: `withdraw:${crypto.randomUUID()}`,
    }
    pendingWithdrawal.value = operation
    withdrawalBusy.value = true
    notice.value = ''
    try {
      const updated = await grassland.withdrawFromWallet(operation.amountCents, operation.operationId)
      if (!session.isCurrent(ticket)) return
      if (!updated) {
        notice.value = '提现结果尚未确认，请重试查询本次提现'
        return
      }
      wallet.value = updated
      pendingWithdrawal.value = null
      withdrawYuan.value = 0
      notice.value = `已提现 ¥${(operation.amountCents / 100).toFixed(2)}（sandbox，未接真实支付通道）`
    } finally {
      if (session.isCurrent(ticket)) withdrawalBusy.value = false
    }
  }

  watch(() => session.epoch, () => {
    refreshSequence += 1
    wallet.value = null
    groups.value = null
    notice.value = ''
    withdrawYuan.value = 0
    pendingWithdrawal.value = null
    withdrawalBusy.value = false
    if (session.ownerAccountId) void refresh()
  }, { immediate: true, flush: 'sync' })

  return { wallet, notice, withdrawYuan, groups, pendingWithdrawal, withdrawalBusy, canWithdraw, refresh, withdraw }
}
