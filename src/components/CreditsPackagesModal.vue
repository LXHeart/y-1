<script setup lang="ts">
import { ref, watch } from 'vue'
import { formatPrice, useCreditsPackages } from '../composables/useCreditsPackages'
import { useAccountSessionStore } from '../stores/account-session'

/**
 * 积分与套餐弹窗（AI 套餐 v1）：余额 + active SKU 卡片 + 购买记录。
 * 购买走 Sandbox 支付即时生效；成功后 emit('balance-refreshed', balance) 供徽标刷新。
 *
 * 任务书 #82 C82-04：弹窗常驻挂载（v-if 只控制内容），换号必须清确认态/成功提示；
 * 购买回调验票——A 的迟到购买结果不得触发 B 的成功文案、余额刷新或订单重拉。
 */

const props = defineProps<{
  open: boolean
  balance: number
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'balance-refreshed', balance: number): void
}>()

const session = useAccountSessionStore()

const {
  packages, orders, loading, purchasing, error,
  loadPackages, purchase, loadOrders,
} = useCreditsPackages()

const successMessage = ref('')
const confirmingId = ref('')

watch(() => props.open, (open) => {
  if (!open) return
  successMessage.value = ''
  confirmingId.value = ''
  void loadPackages().then(() => void loadOrders())
}, { immediate: true })

// 账号变化：清确认态与成功提示（订单/错误/购买中由 composable 的 owner watch 清理）
watch(() => session.ownerAccountId, () => {
  successMessage.value = ''
  confirmingId.value = ''
}, { flush: 'sync' })

async function confirmPurchase(packageId: string): Promise<void> {
  const ticket = session.capture()
  successMessage.value = ''
  const balance = await purchase(packageId)
  // 旧票不续发（任务书 #82 C82-04）：迟到的购买结果不触发 B 的成功提示/余额刷新/订单重拉
  if (!session.isCurrent(ticket)) return
  confirmingId.value = ''
  if (balance !== null) {
    successMessage.value = `购买成功，当前余额 ${balance} 积分`
    emit('balance-refreshed', balance)
    void loadOrders()
  }
}
</script>

<template>
  <div v-if="open" class="credits-modal-overlay" data-test="credits-modal" @click.self="emit('close')">
    <div class="credits-modal" role="dialog" aria-label="积分与套餐">
      <header class="credits-modal-head">
        <h3>积分与套餐</h3>
        <p class="balance-line">当前余额 <strong>{{ balance }}</strong> 积分（1 积分 = 1 次 AI 调用）</p>
        <button type="button" class="close-btn" aria-label="关闭积分弹窗" @click="emit('close')">×</button>
      </header>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="successMessage" class="success" role="status">{{ successMessage }}</p>

      <section aria-label="积分套餐">
        <h4>积分套餐</h4>
        <p v-if="loading && !packages.length" class="muted">加载中…</p>
        <p v-else-if="!packages.length" class="muted">暂无可购买的套餐</p>
        <ul v-else class="package-list">
          <li v-for="pkg in packages" :key="pkg.id" class="package-card">
            <div class="package-info">
              <span class="package-name">{{ pkg.name }}</span>
              <span v-if="pkg.description" class="package-desc">{{ pkg.description }}</span>
              <span class="package-credits">{{ pkg.creditsAmount }} 积分</span>
            </div>
            <div class="package-actions">
              <span class="package-price">¥{{ formatPrice(pkg.priceCents) }}</span>
              <p class="refund-hint">购买后暂不支持自助退款</p>
              <template v-if="confirmingId === pkg.id">
                <button type="button" class="primary" :disabled="purchasing"
                  @click="confirmPurchase(pkg.id)">
                  {{ purchasing ? '支付中…' : `确认支付 ¥${formatPrice(pkg.priceCents)}` }}
                </button>
                <button type="button" class="secondary" :disabled="purchasing" @click="confirmingId = ''">取消</button>
              </template>
              <button v-else type="button" class="primary" :data-test="`buy-${pkg.id}`"
                :disabled="purchasing" @click="confirmingId = pkg.id">购买</button>
            </div>
          </li>
        </ul>
      </section>

      <section aria-label="购买记录">
        <h4>购买记录</h4>
        <p v-if="!orders.length" class="muted">暂无购买记录</p>
        <ul v-else class="order-list">
          <li v-for="order in orders" :key="order.id" class="order-item">
            <span>¥{{ formatPrice(order.priceCents) }} → {{ order.creditsAmount }} 积分</span>
            <span class="order-status" :class="{ paid: order.status === 'paid' }">{{ order.status }}</span>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.credits-modal-overlay {
  position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, 0.45); padding: 20px;
}
.credits-modal {
  position: relative; width: min(560px, 100%); max-height: 84vh; overflow-y: auto;
  background: var(--surface-card); border: 1px solid var(--color-border);
  border-radius: var(--radius-xl); padding: 22px; display: grid; gap: 16px;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.22);
}
.credits-modal-head h3 { margin: 0; font-size: 1.2rem; color: var(--color-text); }
.balance-line { margin: 6px 0 0; color: var(--color-text-muted); font-size: 0.88rem; }
.close-btn {
  position: absolute; top: 14px; right: 14px; width: 30px; height: 30px; border: none;
  border-radius: var(--radius-md); background: none; font-size: 1.2rem; color: var(--color-text-muted); cursor: pointer;
}
section h4 { margin: 0 0 8px; font-size: 0.96rem; color: var(--color-text); }
.muted { margin: 0; color: var(--color-text-muted); font-size: 0.88rem; }
.error { margin: 0; color: var(--color-danger); font-size: 0.9rem; }
.success { margin: 0; color: var(--color-success); font-size: 0.9rem; }
.package-list, .order-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.package-card {
  display: flex; justify-content: space-between; gap: 12px; padding: 14px;
  border: 1px solid var(--color-border); border-radius: var(--radius-lg);
}
.package-info { display: grid; gap: 4px; }
.package-name { font-weight: 600; color: var(--color-text); }
.package-desc, .refund-hint { color: var(--color-text-muted); font-size: 0.8rem; }
.package-credits { color: var(--color-success); font-weight: 600; font-size: 0.92rem; }
.package-actions { display: grid; gap: 4px; justify-items: end; text-align: right; }
.package-price { font-weight: 700; color: var(--color-text); }
.primary, .secondary {
  padding: 7px 14px; border-radius: var(--radius-md); font: inherit; cursor: pointer; border: 1px solid transparent;
}
.primary { background: var(--color-success); color: var(--color-on-accent); }
.primary:disabled { opacity: 0.55; cursor: not-allowed; }
.secondary { background: none; border-color: var(--color-border); color: var(--color-text); }
.order-item {
  display: flex; justify-content: space-between; padding: 8px 12px; border-radius: var(--radius-md);
  background: var(--surface-muted); font-size: 0.88rem; color: var(--color-text);
}
.order-status { color: var(--color-text-muted); }
.order-status.paid { color: var(--color-success); font-weight: 600; }
</style>
