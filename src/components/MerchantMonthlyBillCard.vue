<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import MonthPicker from './MonthPicker.vue'
import { currentMonth } from '../lib/month'
import { formatSignedCents, formatYuan } from '../lib/money'
import type { MerchantMonthlyBill } from '../types/grassland'

/**
 * 商家月度账单（任务书 #29+#30 #30）：月份选择 → flows 明细表 + 平台费/托管净变动汇总。
 *
 * <p>org-scoped：orgId 由 props 传入（工作台 activeOrgId），跨 org 后端 404。
 * flows 各科目 label 直接用后端返回的中文（D4「映射只放一处」），金额列带符号格式化（D8）。
 * 不变式：Σ flows == netEscrowDeltaCents（后端保证，前端如实展示）。
 */
const props = defineProps<{ organizationId: string }>()

const grassland = useGrassland()

const month = ref(currentMonth())
const bill = ref<MerchantMonthlyBill | null>(null)

// 防御性投影：测试 stub / 部分响应可能缺字段，统一兜底，避免模板读到 undefined。
const flows = computed(() => bill.value?.flows ?? [])
const platformFeeCents = computed(() => bill.value?.platformFeeCents ?? 0)
const netEscrowDeltaCents = computed(() => bill.value?.netEscrowDeltaCents ?? 0)

async function load(): Promise<void> {
  if (!props.organizationId || !month.value) return
  bill.value = null
  const data = await grassland.getMonthlyBill(props.organizationId, month.value)
  if (data) bill.value = data
}

function exportBill(format: 'csv' | 'xlsx'): void {
  if (!props.organizationId || !month.value) return
  const path = `/api/finance/organizations/${encodeURIComponent(props.organizationId)}/monthly-bill/export`
  window.location.assign(`${path}?month=${encodeURIComponent(month.value)}&format=${format}`)
}

watch(() => [props.organizationId, month.value], () => { void load() }, { immediate: true })
</script>

<template>
  <article class="mmb">
    <header class="mmb-head">
      <h3>月度账单</h3>
      <div class="mmb-actions">
        <MonthPicker v-model="month" />
        <button type="button" :disabled="!organizationId || !month" @click="exportBill('csv')">导出 CSV</button>
        <button type="button" :disabled="!organizationId || !month" @click="exportBill('xlsx')">导出 Excel</button>
      </div>
    </header>

    <p v-if="grassland.error.value" class="mmb-alert" role="alert">{{ grassland.error.value }}</p>

    <template v-if="bill">
      <div class="mmb-summary">
        <div class="mmb-stat">
          <span class="mmb-stat-label">托管净变动</span>
          <strong :class="netEscrowDeltaCents < 0 ? 'mmb-out' : 'mmb-in'">
            {{ formatSignedCents(netEscrowDeltaCents) }} 元
          </strong>
        </div>
        <div class="mmb-stat">
          <span class="mmb-stat-label">平台费</span>
          <strong>{{ formatYuan(platformFeeCents) }}</strong>
        </div>
      </div>

      <p v-if="flows.length === 0" class="mmb-hint">该月无资金流水。</p>
      <table v-else class="mmb-table">
        <thead><tr><th>科目</th><th>金额</th></tr></thead>
        <tbody>
          <tr v-for="flow in flows" :key="flow.type">
            <td>{{ flow.label }}</td>
            <td :class="flow.amountCents < 0 ? 'mmb-out' : 'mmb-in'">
              {{ formatSignedCents(flow.amountCents) }}
            </td>
          </tr>
        </tbody>
      </table>

      <p class="mmb-hint">托管净变动 = 充值/释放/冲正等入账 − 预留等出账；结算（赏金支出）不动托管余额，平台费单列。</p>
    </template>
  </article>
</template>

<style scoped>
.mmb { display: flex; flex-direction: column; gap: 10px; }
.mmb-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mmb-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mmb-actions button { min-height: 32px; padding: 0 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text); cursor: pointer; }
.mmb-head h3 { margin: 0; font-size: 15px; }
.mmb-alert { margin: 0; padding: 7px 11px; border-radius: var(--radius-sm); font-size: 13px;
  background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.mmb-summary { display: flex; gap: 20px; flex-wrap: wrap; }
.mmb-stat { display: flex; flex-direction: column; gap: 2px; }
.mmb-stat-label { font-size: 12px; opacity: 0.62; }
.mmb-stat strong { font-size: 17px; }
.mmb-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.mmb-table th, .mmb-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.mmb-in { color: var(--color-success); }
.mmb-out { color: var(--color-danger); }
.mmb-hint { margin: 0; font-size: 12px; opacity: 0.62; }
</style>
