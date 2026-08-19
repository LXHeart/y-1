<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import MonthPicker from './MonthPicker.vue'
import { currentMonth } from '../lib/month'
import { formatCents, formatSignedCents } from '../lib/money'
import type { WalletStatistics } from '../types/grassland'

/**
 * 推荐官收入统计（任务书 #29+#30 #29）：月份区间 → 月度汇总表（任务佣金/到店佣金/提现/冲正/毛/抽成/净）
 * + 按任务明细表（任务标题 ← join my-applications、金额、次数、最近时间）。
 *
 * <p>私有数据（D7）：本卡片只出现在推荐官自己的视图，不进公开主页。
 * 金额列直接用后端带符号的 cents，毛/抽成/净三列由 formatCents 统一格式化（D8）。
 */
const grassland = useGrassland()
const { currentUser } = useAuth()

const from = ref(currentMonth())
const to = ref(currentMonth())
const stats = ref<WalletStatistics | null>(null)
/** engagementRef(=applicationId) → 任务标题，供 byEngagement join（D3：不在 finance 反查 marketplace）。 */
const titleByEngagement = ref<Record<string, string>>({})

const months = computed(() => stats.value?.months ?? [])
const byEngagement = computed(() => stats.value?.byEngagement ?? [])

function engagementTitle(ref: string): string {
  return titleByEngagement.value[ref] || `订单/任务 ${ref.slice(0, 8)}…`
}

async function loadTitleMap(): Promise<void> {
  // 翻页收集 my-applications 建标题映射；给 200 条上限，覆盖绝大多数推荐官的活跃任务量。
  const map: Record<string, string> = {}
  let cursor: string | undefined
  let collected = 0
  for (let page = 0; page < 4 && collected < 200; page += 1) {
    const result = await grassland.listMyApplications(undefined, cursor, 50)
    if (!result) break
    for (const item of result.items ?? []) {
      map[item.applicationId] = item.taskTitle || '（无标题任务）'
      collected += 1
    }
    if (!result.hasMore || !result.nextCursor) break
    cursor = result.nextCursor
  }
  titleByEngagement.value = map
}

async function load(): Promise<void> {
  if (!from.value || !to.value) return
  stats.value = null
  const data = await grassland.getWalletStatistics(from.value, to.value)
  if (data) stats.value = data
}

watch(() => currentUser.value?.id, (accountId) => {
  stats.value = null
  if (accountId) {
    loadTitleMap()
    load()
  }
}, { immediate: true })

watch([from, to], () => {
  if (currentUser.value?.id) load()
})
</script>

<template>
  <article class="ris">
    <header class="ris-head">
      <h3>收入统计</h3>
      <MonthPicker range :max-months="12" v-model:from="from" v-model:to="to" />
    </header>

    <p v-if="grassland.error.value" class="ris-alert" role="alert">{{ grassland.error.value }}</p>

    <h4>按月汇总</h4>
    <p v-if="months.length === 0" class="ris-hint">该区间暂无流水。结算完成后收入才会出现在这里。</p>
    <table v-else class="ris-table">
      <thead>
        <tr>
          <th>月份</th><th>任务佣金</th><th>到店佣金</th><th>提现</th><th>冲正</th>
          <th>毛额</th><th>平台抽成</th><th>净额</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="m in months" :key="m.month">
          <td>{{ m.month }}</td>
          <td>{{ formatCents(m.taskPayoutCents) }}</td>
          <td>{{ formatCents(m.commerceCommissionCents) }}</td>
          <td>{{ formatSignedCents(m.withdrawalCents) }}</td>
          <td>{{ formatSignedCents(m.clawbackCents) }}</td>
          <td>{{ formatCents(m.grossCents) }}</td>
          <td>{{ formatCents(m.feeCents) }}</td>
          <td :class="m.netCents < 0 ? 'ris-out' : 'ris-in'">{{ formatSignedCents(m.netCents) }}</td>
        </tr>
      </tbody>
    </table>

    <h4>按任务明细</h4>
    <p v-if="byEngagement.length === 0" class="ris-hint">该区间暂无按任务的入账。</p>
    <table v-else class="ris-table">
      <thead><tr><th>任务</th><th>结算金额</th><th>平台费</th><th>次数</th><th>最近时间</th></tr></thead>
      <tbody>
        <tr v-for="e in byEngagement" :key="e.engagementRef">
          <td>{{ engagementTitle(e.engagementRef) }}</td>
          <td>{{ formatCents(e.payoutCents) }}</td>
          <td>{{ formatCents(e.feeCents) }}</td>
          <td>{{ e.count }}</td>
          <td>{{ e.lastAt ? e.lastAt.slice(0, 10) : '—' }}</td>
        </tr>
      </tbody>
    </table>

    <p class="ris-hint">毛额 = 任务佣金 + 到店佣金（含平台费）；净额 = 全部流水带符号求和（含提现/冲正）。</p>
  </article>
</template>

<style scoped>
.ris { display: flex; flex-direction: column; gap: 10px; }
.ris-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.ris-head h3 { margin: 0; font-size: 15px; }
.ris h4 { margin: 6px 0 0; font-size: 13px; }
.ris-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px;
  background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.ris-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.ris-table th, .ris-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.ris-in { color: var(--color-success); }
.ris-out { color: var(--color-danger); }
.ris-hint { margin: 0; font-size: 12px; opacity: 0.62; }
</style>
