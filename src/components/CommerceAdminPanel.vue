<template>
  <section class="commerce-admin">
    <header>
      <div><h3>消费订单与核销监控</h3><p>查看支付、退款、核销分账卡点；Sandbox 流水也会进入财务双录账本。</p></div>
      <button type="button" :disabled="commerce.loading.value" @click="load">刷新</button>
    </header>
    <div class="filters">
      <select v-model="status" @change="load">
        <option value="">全部状态</option><option value="pending_payment">支付处理中</option>
        <option value="paid">待核销</option><option value="redeeming">分账中</option>
        <option value="redeemed">已核销</option><option value="refund_pending">退款中</option>
        <option value="refunded">已退款</option>
      </select>
      <span>共 {{ orders.length }} 笔</span>
    </div>
    <p v-if="commerce.error.value" class="error-msg">{{ commerce.error.value }}</p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>订单/套餐</th><th>消费者</th><th>组织/门店</th><th>金额</th><th>状态</th><th>支付/核销时间</th><th>异常</th></tr></thead>
        <tbody>
          <tr v-for="order in orders" :key="order.id">
            <td><strong>{{ order.packageTitle }}</strong><code>{{ short(order.id) }} · v{{ order.packageVersion }}</code></td>
            <td><code>{{ short(order.consumerAccountId) }}</code></td>
            <td><code>{{ short(order.organizationId) }}</code><small>{{ order.storeId ? short(order.storeId) : '组织级' }}</small></td>
            <td>¥{{ (order.priceCents / 100).toFixed(2) }}<small>推 {{ money(order.recommenderAmountCents) }} / 商 {{ money(order.merchantAmountCents) }} / 平 {{ money(order.platformFeeCents) }}</small></td>
            <td><span :class="['status', order.status]">{{ statusLabel(order.status) }}</span></td>
            <td><small>支付 {{ format(order.paidAt) }}</small><small>核销 {{ format(order.redeemedAt) }}</small></td>
            <td :class="{ problem: order.lastError }">{{ order.lastError || '—' }}</td>
          </tr>
          <tr v-if="orders.length === 0"><td colspan="7" class="empty">暂无订单</td></tr>
        </tbody>
      </table>
    </div>
    <div class="section-head">
      <div><h4>核销与分账流水</h4><p>单独展示核销处理中和已核销订单，便于定位分账重试。</p></div>
      <span>共 {{ redemptions.length }} 笔</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>订单/套餐</th><th>门店</th><th>核销状态</th><th>核销时间</th><th>分账结果</th></tr></thead>
        <tbody>
          <tr v-for="order in redemptions" :key="order.id">
            <td><strong>{{ order.packageTitle }}</strong><code>{{ short(order.id) }} · v{{ order.packageVersion }}</code></td>
            <td><code>{{ order.storeId ? short(order.storeId) : short(order.organizationId) }}</code></td>
            <td><span :class="['status', order.status]">{{ statusLabel(order.status) }}</span></td>
            <td>{{ format(order.redeemedAt) }}</td>
            <td :class="{ problem: order.lastError }">{{ order.lastError || (order.status === 'redeemed' ? '已完成三方分账' : '分账处理中') }}</td>
          </tr>
          <tr v-if="redemptions.length === 0"><td colspan="5" class="empty">暂无核销流水</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useCommerce } from '../composables/useCommerce'
import type { ConsumerOrder } from '../types/commerce'
const commerce = useCommerce()
const orders = ref<ConsumerOrder[]>([])
const redemptions = ref<ConsumerOrder[]>([])
const status = ref('')
onMounted(load)
async function load(): Promise<void> {
  const [orderValues, redemptionValues] = await Promise.all([
    commerce.listAdminOrders(status.value || undefined),
    commerce.listAdminRedemptions(),
  ])
  if (orderValues) orders.value = orderValues
  if (redemptionValues) redemptions.value = redemptionValues
}
function short(value: string): string { return value.length > 12 ? `${value.slice(0, 8)}…` : value }
function money(cents: number): string { return `¥${(cents / 100).toFixed(2)}` }
function format(value?: string): string { return value ? new Date(value).toLocaleString() : '—' }
function statusLabel(value: ConsumerOrder['status']): string { return ({ pending_payment: '支付处理中', paid: '待核销', redeeming: '分账中', redeemed: '已核销', refund_pending: '退款中', refunded: '已退款', payment_failed: '支付失败', cancelled: '已取消' })[value] }
</script>

<style scoped>
.commerce-admin { display: grid; gap: 12px; }.commerce-admin > header, .filters, .section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.commerce-admin h3, .commerce-admin h4, .commerce-admin p { margin: 0; }.commerce-admin header p, .section-head p { font-size: 13px; opacity: .7; }
button, select { min-height: 36px; padding: 7px 10px; border: 1px solid var(--color-border); border-radius: 7px; background: var(--color-surface); color: var(--color-text); }.table-wrap { overflow: auto; border: 1px solid var(--color-border); border-radius: 10px; }table { width: 100%; border-collapse: collapse; font-size: 12px; }th, td { padding: 10px; border-bottom: 1px solid var(--color-border); text-align: left; vertical-align: top; }td code, td small { display: block; margin-top: 4px; opacity: .68; }.status { display: inline-flex; padding: 3px 7px; border-radius: 999px; background: color-mix(in srgb, var(--color-accent) 12%, transparent); }.status.redeeming, .status.refund_pending, .status.pending_payment { color: #a05b00; }.status.redeemed { color: var(--color-success); }.problem, .error-msg { color: var(--color-danger); }.empty { text-align: center; opacity: .65; }
</style>
