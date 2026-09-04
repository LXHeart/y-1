<template>
  <div class="share-card">
    <h3>我的推广链接</h3>
    <p class="gl-hint">
      接下商家的「套餐推广」任务后，这里会出现对应的专属链接——消费者经你的链接下单并到店核销，
      冷静期后佣金自动入账你的钱包。
    </p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="!loading && !error && promotions.length === 0" class="gl-empty">
      还没有进行中的套餐推广任务——先到「任务大厅」找一个「套餐推广」任务报名吧。
    </p>

    <ul v-if="promotions.length" class="promotion-list">
      <li v-for="item in promotions" :key="item.taskId" class="promotion-item">
        <div class="promotion-copy">
          <strong>{{ item.packageTitle }}</strong>
          <span>
            {{ formatYuan(item.priceCents) }} · 佣金 {{ commissionLabel(item) }}
            <template v-if="item.taskStatus !== 'published'"> · 任务已{{ taskStatusLabel(item.taskStatus) }}</template>
          </span>
          <span class="promotion-stats" data-testid="promotion-stats">
            下单 {{ item.stats.orderCount }} · 已核销 {{ item.stats.redeemedCount }}
            · 待结算 <span class="gl-num">{{ formatYuan(item.stats.pendingSettleCents) }}</span>
            · 已入账 <span class="gl-num">{{ formatYuan(item.stats.settledCents) }}</span>
          </span>
        </div>
        <button
          type="button"
          :disabled="loading"
          data-testid="promotion-generate"
          @click="generate(item)"
        >生成推广链接</button>

        <div v-if="activeUrlByTask[item.taskId]" class="share-result">
          <div class="copy-row">
            <input :value="activeUrlByTask[item.taskId]" readonly />
            <button type="button" @click="copy(item.taskId)">{{ copiedTaskId === item.taskId ? '已复制' : '复制链接' }}</button>
          </div>
          <img v-if="qrByTask[item.taskId]" class="share-qr" :src="qrByTask[item.taskId]" alt="推荐官专属购买二维码" />
          <p class="gl-hint">消费者扫码或打开链接 → 套餐已带出、归因已锁定，直接下单。</p>
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import QRCode from 'qrcode'
import { useCommerce } from '../composables/useCommerce'
import type { RecommenderPromotion } from '../composables/useCommerce'
import { useAuth } from '../composables/useAuth'
import { formatYuan } from '../lib/money'

/**
 * 任务书 #75 卡 B7：「我的推广」列表卡（原手输套餐 ID 自由分销已下线——D4 纯任务化，
 * 归因资格=持有进行中推广任务的 accepted 报名，链接由这里按任务维一生成）。
 */
const commerce = useCommerce()
const { currentUser } = useAuth()

const promotions = ref<RecommenderPromotion[]>([])
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
  if (status === 'closed') return '截止'
  if (status === 'cancelled') return '取消'
  return status
}

async function generate(item: RecommenderPromotion): Promise<void> {
  if (loading.value) return
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
    activeUrlByTask.value = { ...activeUrlByTask.value, [item.taskId]: url.toString() }
    qrByTask.value = { ...qrByTask.value, [item.taskId]: await QRCode.toDataURL(url.toString(), { width: 220, margin: 1 }) }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '推广链接生成失败'
  } finally {
    loading.value = false
  }
}

async function copy(taskId: string): Promise<void> {
  await navigator.clipboard.writeText(activeUrlByTask.value[taskId] || '')
  copiedTaskId.value = taskId
}

onMounted(async () => {
  loading.value = true
  try {
    promotions.value = (await commerce.listMyPromotions()) ?? []
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '推广任务加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* 任务书 #75：推广任务列表卡（token 取根 DESIGN.md 体系，同 MerchantCommerceCard 先例）。 */
.share-card { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); }
.share-card h3 { margin: 0; font-size: var(--text-base); }
.promotion-list { list-style: none; display: grid; gap: 10px; margin: 0; padding: 0; }
.promotion-item { display: grid; gap: 8px; padding: 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.promotion-copy { display: grid; gap: 4px; font-size: var(--text-sm); }
.promotion-copy strong { font-size: var(--text-sm); }
.promotion-stats { font-size: var(--text-sm); color: var(--color-text-secondary); }
.share-result { display: grid; gap: 8px; }
.copy-row { display: flex; align-items: center; gap: 8px; }
.copy-row input { flex: 1; }
.share-qr { width: 180px; height: 180px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
input, button { min-height: 36px; padding: 7px 9px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text); }
button { cursor: pointer; }
.form-error { margin: 0; color: var(--color-danger); font-size: var(--text-sm); }
</style>
