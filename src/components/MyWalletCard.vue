<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import type { Wallet, WalletEntry, WalletEntryType } from '../types/grassland'

/**
 * 「我的收益」——推荐官钱包（余额 / 流水 / 提现）。
 *
 * 这是资金链此前缺的**收款侧出口**：商家的钱在 reserve 时被扣、capture 时才分账进这个钱包。
 * 结算前看不到钱是正常的（还在托管中），UI 需要说清楚，否则会被当成「钱丢了」。
 */

const grassland = useGrassland()
const { currentUser } = useAuth()

const wallet = ref<Wallet | null>(null)
const notice = ref('')
const withdrawYuan = ref(0)

const ENTRY_LABEL: Record<WalletEntryType, string> = {
  task_payout: '任务结算入账',
  withdrawal: '提现',
  clawback: '争议冲正扣回',
}

const balanceYuan = computed(() =>
  wallet.value ? (wallet.value.balanceCents / 100).toFixed(2) : '—')

const canWithdraw = computed(() =>
  !!wallet.value && wallet.value.balanceCents > 0
  && withdrawYuan.value > 0 && withdrawYuan.value * 100 <= wallet.value.balanceCents)

function entryLabel(entry: WalletEntry): string {
  return ENTRY_LABEL[entry.entryType] || entry.entryType
}

/** amountCents 已带符号，直接格式化即可，别再自己补负号。 */
function signedYuan(cents: number): string {
  const sign = cents > 0 ? '+' : ''
  return `${sign}${(cents / 100).toFixed(2)}`
}

async function refresh(): Promise<void> {
  const data = await grassland.getMyWallet()
  if (data) wallet.value = data
}

watch(() => currentUser.value?.id, (accountId) => {
  wallet.value = null
  notice.value = ''
  if (accountId) refresh()
}, { immediate: true })

async function withdraw(): Promise<void> {
  if (!canWithdraw.value) return
  notice.value = ''
  const updated = await grassland.withdrawFromWallet(Math.round(withdrawYuan.value * 100))
  if (!updated) return   // 余额不足等 409 由 error 条呈现
  const amount = withdrawYuan.value.toFixed(2)
  wallet.value = updated
  withdrawYuan.value = 0
  notice.value = `已提现 ¥${amount}（sandbox，未接真实支付通道）`
}
</script>

<template>
  <article class="wal">
    <header class="wal-head">
      <h3>我的收益</h3>
      <button type="button" class="wal-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="wal-alert wal-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="wal-alert wal-ok">{{ notice }}</p>

    <p class="wal-balance">可提现余额 <strong>¥{{ balanceYuan }}</strong></p>

    <div class="wal-row">
      <label>提现 ¥<input v-model.number="withdrawYuan" type="number" min="0" step="1" /></label>
      <button type="button" :disabled="grassland.loading.value || !canWithdraw" @click="withdraw">提现</button>
    </div>

    <h4>流水</h4>
    <p v-if="!wallet || wallet.entries.length === 0" class="wal-hint">
      暂无流水。商家确认履约、结算完成后，赏金才会打入这里——在那之前钱在平台托管中。
    </p>
    <table v-else class="wal-table">
      <thead><tr><th>类型</th><th>金额</th><th>时间</th></tr></thead>
      <tbody>
        <tr v-for="e in wallet.entries" :key="e.id">
          <td>
            {{ entryLabel(e) }}
            <span v-if="e.feeCents > 0" class="wal-fee">平台服务费 ¥{{ (e.feeCents / 100).toFixed(2) }}</span>
          </td>
          <td :class="e.amountCents < 0 ? 'wal-out' : 'wal-in'">{{ signedYuan(e.amountCents) }}</td>
          <td>{{ e.createdAt ? e.createdAt.slice(0, 19).replace('T', ' ') : '—' }}</td>
        </tr>
      </tbody>
    </table>

    <p class="wal-hint">提现为 sandbox 行为：立即从余额扣除并记流水，尚未对接真实支付通道。</p>
  </article>
</template>

<style scoped>
.wal { display: flex; flex-direction: column; gap: 10px; }
.wal-head { display: flex; justify-content: space-between; align-items: center; }
.wal-head h3 { margin: 0; font-size: 15px; }
.wal h4 { margin: 6px 0 0; font-size: 13px; }
.wal-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.wal-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.wal-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.wal-balance { margin: 0; font-size: 14px; }
.wal-balance strong { font-size: 20px; }
.wal-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wal-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.wal-table th, .wal-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.wal-in { color: var(--color-success); }
.wal-out { color: var(--color-danger); }
.wal-fee { font-size: 11px; opacity: 0.62; margin-left: 6px; }
.wal-hint { margin: 0; font-size: 12px; opacity: 0.62; }
input { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; width: 110px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.wal-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
