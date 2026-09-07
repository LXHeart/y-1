<template>
  <div class="share-card">
    <h3>我的推广链接</h3>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="!loading && !error && promotions.length === 0" class="gl-empty">
      暂无可推广的套餐。
    </p>

    <ul v-if="promotions.length" class="promotion-list">
      <li v-for="item in visiblePromotions" :key="item.taskId" class="promotion-item">
        <div class="promotion-copy">
          <strong>{{ item.packageTitle }}</strong>
          <span>
            {{ formatYuan(item.priceCents) }} · 佣金 {{ commissionLabel(item) }}
            <template v-if="item.promotionEnded"> · 推广已结束</template>
            <template v-if="item.taskStatus !== 'published'"> · {{ taskStatusLabel(item.taskStatus) }}</template>
          </span>
          <span class="promotion-stats" data-testid="promotion-stats">
            下单 {{ item.stats.orderCount }} · 已核销 {{ item.stats.redeemedCount }}
            · 待结算 <span class="gl-num">{{ formatYuan(item.stats.pendingSettleCents) }}</span>
            · 已入账 <span class="gl-num">{{ formatYuan(item.stats.settledCents) }}</span>
          </span>
        </div>
        <button
          type="button"
          :disabled="loading || item.taskStatus === 'cancelled' || item.promotionEnded === true"
          data-testid="promotion-generate"
          @click="generate(item)"
        >生成推广链接</button>

        <div v-if="activeUrlByTask[item.taskId]" class="share-result">
          <div class="copy-row">
            <input :value="activeUrlByTask[item.taskId]" aria-label="推广链接" readonly />
            <button type="button" @click="copy(item.taskId)">{{ copiedTaskId === item.taskId ? '已复制' : '复制链接' }}</button>
          </div>
          <img v-if="qrByTask[item.taskId]" class="share-qr" :src="qrByTask[item.taskId]" alt="推荐官专属购买二维码" />
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { useCommerce } from '../composables/useCommerce'
import type { RecommenderPromotion } from '../composables/useCommerce'
import { useAuth } from '../composables/useAuth'
import { formatYuan } from '../lib/money'
import { useAccountSessionStore } from '../stores/account-session'

/**
 * 任务书 #75 卡 B7：「我的推广」列表卡（原手输套餐 ID 自由分销已下线——D4 纯任务化，
 * 归因资格=持有进行中推广任务的 accepted 报名，链接由这里按任务维一生成）。
 */
const commerce = useCommerce()
const { currentUser } = useAuth()
const props = defineProps<{ taskId?: string }>()
const session = useAccountSessionStore()

const promotions = ref<RecommenderPromotion[]>([])
const visiblePromotions = computed(() => props.taskId
  ? promotions.value.filter((item) => item.taskId === props.taskId) : promotions.value)
const activeUrlByTask = ref<Record<string, string>>({})
const qrByTask = ref<Record<string, string>>({})
const copiedTaskId = ref('')
const loading = ref(false)
const error = ref('')

function commissionLabel(item: RecommenderPromotion): string {
  return item.commission.form === 'fixed' && item.commission.fixedCents != null
    ? `${formatYuan(item.commission.fixedCents)} / 单`
    : `${Math.round(item.commission.shareBps / 100)}% / 单`
}

function taskStatusLabel(status: string): string {
  if (status === 'closed') return '招募已关闭'
  if (status === 'cancelled') return '任务已取消'
  return status
}

async function generate(item: RecommenderPromotion): Promise<void> {
  if (loading.value) return
  const ticket = session.capture()
  loading.value = true
  error.value = ''
  try {
    if (!currentUser.value?.id) throw new Error('登录后才能生成专属归因链接')
    // 链接固定挂根路径：DefaultLayout 的 ?view=commerce 兜底只认 ['/', '', '/ai-center', '/home']，
    // 若按当前 pathname（推荐官多在 /grassland 生成）会拼出 /grassland?view=commerce——
    // 落到工作台而非购买页，归因链断（本地冒烟实锤）。
    const url = new URL(window.location.origin + '/')
    url.searchParams.set('view', 'commerce')
    url.searchParams.set('package', item.packageId)
    url.searchParams.set('recommender', currentUser.value.id)
    const qr = await QRCode.toDataURL(url.toString(), { width: 220, margin: 1 })
    if (!session.isCurrent(ticket)) return
    activeUrlByTask.value = { ...activeUrlByTask.value, [item.taskId]: url.toString() }
    qrByTask.value = { ...qrByTask.value, [item.taskId]: qr }
  } catch (cause) {
    if (!session.isCurrent(ticket)) return
    error.value = cause instanceof Error ? cause.message : '推广链接生成失败'
  } finally {
    if (session.isCurrent(ticket)) loading.value = false
  }
}

async function copy(taskId: string): Promise<void> {
  const ticket = session.capture()
  try {
    await navigator.clipboard.writeText(activeUrlByTask.value[taskId] || '')
    if (session.isCurrent(ticket)) copiedTaskId.value = taskId
  } catch {
    if (session.isCurrent(ticket)) error.value = '复制失败，请重试'
  }
}

watch(() => session.epoch, async () => {
  const ticket = session.capture()
  promotions.value = []
  activeUrlByTask.value = {}
  qrByTask.value = {}
  copiedTaskId.value = ''
  error.value = ''
  loading.value = true
  try {
    const items = await commerce.listMyPromotions()
    if (!session.isCurrent(ticket)) return
    promotions.value = items ?? []
    error.value = commerce.error.value
  } catch (cause) {
    if (!session.isCurrent(ticket)) return
    error.value = cause instanceof Error ? cause.message : '推广任务加载失败'
  } finally {
    if (session.isCurrent(ticket)) loading.value = false
  }
}, { immediate: true })
</script>

<style scoped>
/* 任务书 #75：推广任务列表卡（token 取根 DESIGN.md 体系，同 MerchantCommerceCard 先例）。 */
.share-card { display: grid; gap: var(--space-md); min-width: 0; }
.share-card h3 { margin: 0; font-size: var(--text-base); }
.promotion-list { list-style: none; display: grid; gap: var(--space-md); margin: 0; padding: 0; min-width: 0; }
.promotion-item { display: grid; gap: var(--space-sm); padding: var(--space-md) 0; border-top: 1px solid var(--color-border); min-width: 0; }
.promotion-copy { display: grid; gap: var(--space-xs); font-size: var(--text-sm); overflow-wrap: anywhere; }
.promotion-copy strong { font-size: var(--text-sm); }
.promotion-stats { font-size: var(--text-sm); color: var(--color-text-secondary); }
.share-result { display: grid; gap: var(--space-sm); min-width: 0; }
.copy-row { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.copy-row input { flex: 1; min-width: 0; }
.share-qr { width: 180px; height: 180px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
input, button { min-height: 40px; padding: var(--space-sm) var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text); }
button { cursor: pointer; }
.form-error { margin: 0; color: var(--color-danger); font-size: var(--text-sm); }
</style>
