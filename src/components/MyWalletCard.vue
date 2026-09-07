<script setup lang="ts">
import { computed } from 'vue'
import { RefreshCw, Download } from '@lucide/vue'
import { useGrassland } from '../composables/useGrassland'
import type { WalletEntry, WalletEntryType } from '../types/grassland'
import { formatYuan } from '../lib/money'
import { useWorkbenchWallet } from '../views/grassland/composables/useWorkbenchWallet'

/**
 * 「我的收益」——推荐官钱包（余额 / 流水 / 提现）。
 *
 * 这是资金链此前缺的**收款侧出口**：商家的钱在 reserve 时被扣、capture 时才分账进这个钱包。
 * 结算前看不到钱是正常的（还在托管中），UI 需要说清楚，否则会被当成「钱丢了」。
 */

const grassland = useGrassland()
const { wallet, notice, withdrawYuan, groups, pendingWithdrawal, canWithdraw, refresh, withdraw } = useWorkbenchWallet(grassland)

const ENTRY_LABEL: Record<WalletEntryType, string> = {
  task_payout: '任务结算入账',
  commerce_commission: '到店推荐佣金',
  withdrawal: '提现',
  clawback: '争议冲正扣回',
  freebie_reserve: '霸王餐押金预付',
  freebie_refund: '霸王餐押金返还',
  judge_commission: '审判现金佣金',
}

const balanceYuan = computed(() =>
  wallet.value ? (wallet.value.balanceCents / 100).toFixed(2) : '—')

function entryLabel(entry: WalletEntry): string {
  return ENTRY_LABEL[entry.entryType] || entry.entryType
}

/** amountCents 已带符号，直接格式化即可，别再自己补负号。 */
function signedYuan(cents: number): string {
  const sign = cents > 0 ? '+' : ''
  return `${sign}${(cents / 100).toFixed(2)}`
}

function exportWallet(format: 'csv' | 'xlsx'): void {
  window.location.assign(`/api/finance/wallets/me/export?format=${format}`)
}

</script>

<template>
  <article class="wal">
    <header class="wal-head">
      <h3>我的收益</h3>
      <div class="wal-actions">
        <button type="button" class="wal-quiet" title="刷新收益" aria-label="刷新收益" :disabled="grassland.loading.value" @click="refresh"><RefreshCw :size="16" /></button>
        <button type="button" class="wal-quiet" @click="exportWallet('csv')"><Download :size="16" /> CSV</button>
        <button type="button" class="wal-quiet" @click="exportWallet('xlsx')"><Download :size="16" /> Excel</button>
      </div>
    </header>

    <p v-if="grassland.error.value" class="wal-alert wal-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="wal-alert wal-ok">{{ notice }}</p>

    <p class="wal-balance">可提现余额 <strong>¥{{ balanceYuan }}</strong></p>
    <dl class="wal-groups" data-testid="wallet-groups">
      <div><dt>托管押金</dt><dd>{{ groups ? formatYuan(groups.deposit) : '待核对' }}</dd></div>
      <div><dt>待结算赏金（托管额）</dt><dd>{{ groups ? formatYuan(groups.pending) : '待核对' }}</dd></div>
      <div><dt>暂扣资金</dt><dd>{{ groups ? formatYuan(groups.held) : '待核对' }}</dd></div>
      <div><dt>提现处理中</dt><dd>{{ wallet?.withdrawingCents != null ? formatYuan(wallet.withdrawingCents) : '待核对' }}</dd></div>
    </dl>

    <div class="wal-row">
      <label>提现 ¥<input v-model.number="withdrawYuan" type="number" min="0" step="0.01" :disabled="pendingWithdrawal != null" /></label>
      <button type="button" :disabled="grassland.loading.value || !canWithdraw" @click="withdraw">{{ pendingWithdrawal ? '重试本次提现' : '提现' }}</button>
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
.wal { display: flex; flex-direction: column; gap: var(--space-md); min-width: 0; }
.wal-head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.wal-actions { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.wal-head h3 { margin: 0; font-size: var(--text-base); }
.wal h4 { margin: var(--space-sm) 0 0; font-size: var(--text-sm); }
.wal-alert { margin: 0; padding: var(--space-sm) var(--space-md); border-radius: var(--radius-sm); font-size: var(--text-sm); }
.wal-groups { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 160px), 1fr)); gap: var(--space-md); margin: 0; }
.wal-groups dt { color: var(--color-text-muted); font-size: var(--text-xs); }
.wal-groups dd { margin: var(--space-xs) 0 0; font-size: var(--text-base); font-variant-numeric: tabular-nums; }
.wal-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.wal-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.wal-balance { margin: 0; font-size: var(--text-sm); }
.wal-balance strong { font-size: var(--text-lg); }
.wal-row { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.wal-table { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.wal-table th, .wal-table td { text-align: left; padding: var(--space-sm); border-bottom: 1px solid var(--color-border); }
.wal-in { color: var(--color-success); }
.wal-out { color: var(--color-danger); }
.wal-fee { font-size: var(--text-xs); color: var(--color-text-muted); margin-left: var(--space-sm); }
.wal-hint { margin: 0; font-size: var(--text-xs); color: var(--color-text-muted); }
input { padding: var(--space-sm) var(--space-md); border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-sm); width: 110px; }
button { display: inline-flex; align-items: center; gap: var(--space-xs); padding: var(--space-sm) var(--space-md); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-sm); }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.wal-quiet { font-size: var(--text-xs); padding: var(--space-xs) var(--space-md); }
</style>
