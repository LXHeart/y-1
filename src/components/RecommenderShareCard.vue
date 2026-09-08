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
        >{{ activeLink(item.taskId) ? '重新获取链接' : '生成推广链接' }}</button>

        <div v-if="linkFor(item.taskId)" class="share-result" data-testid="share-link">
          <div class="link-meta">
            <span class="badge" :class="linkStatusClass(item.taskId)">{{ linkStatusLabel(item.taskId) }}</span>
            <span class="link-expiry">{{ linkExpiryNote(item.taskId) }}</span>
          </div>
          <div class="copy-row">
            <input :value="absoluteUrl(item.taskId)" aria-label="推广链接" readonly data-testid="promotion-link-input" />
            <button v-if="activeLink(item.taskId)" type="button" @click="copy(item.taskId)">
              {{ copiedTaskId === item.taskId ? '已复制' : '复制链接' }}
            </button>
          </div>
          <img v-if="qrByTask[item.taskId]" class="share-qr" :src="qrByTask[item.taskId]" alt="推荐官专属购买二维码" />
          <button
            v-if="activeLink(item.taskId)"
            type="button"
            class="link-end"
            :disabled="loading"
            data-testid="promotion-link-end"
            @click="endLink(item)"
          >失效此链接</button>
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
import type { ReferralLink } from '../types/commerce'
import { useAuth } from '../composables/useAuth'
import { formatYuan } from '../lib/money'
import { useAccountSessionStore } from '../stores/account-session'

/**
 * 任务书 #98 C98-01：「我的推广」链接卡。链接改为服务端发放的不透明 rlid（D98-01）——
 * 前端不再拼裸账号 ID；一键生成（幂等）/复制/二维码沿用 #75 卡 B7 交互位，新增本人失效与
 * 状态/失效原因展示（active/ended/expired）。
 */
const commerce = useCommerce()
const { currentUser } = useAuth()
const props = defineProps<{ taskId?: string }>()
const session = useAccountSessionStore()

const promotions = ref<RecommenderPromotion[]>([])
const visiblePromotions = computed(() => props.taskId
  ? promotions.value.filter((item) => item.taskId === props.taskId) : promotions.value)
const linksByTask = ref<Record<string, ReferralLink>>({})
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

function linkFor(taskId: string): ReferralLink | undefined {
  return linksByTask.value[taskId]
}

function activeLink(taskId: string): ReferralLink | undefined {
  const link = linkFor(taskId)
  return link && link.status === 'active' ? link : undefined
}

/** 相对 url → 绝对地址（服务端只发站内路径，origin 由运行环境决定）。 */
function absoluteUrl(taskId: string): string {
  const link = linkFor(taskId)
  if (!link) return ''
  return new URL(link.url, window.location.origin).toString()
}

function linkStatusLabel(taskId: string): string {
  const link = linkFor(taskId)
  if (!link) return ''
  if (link.status === 'ended') return '已终止'
  if (link.status === 'expired') return '已过期'
  return '生效中'
}

function linkExpiryNote(taskId: string): string {
  const link = linkFor(taskId)
  if (!link) return ''
  if (link.status === 'active') return `有效期至 ${formatDate(link.expiresAt)}`
  if (link.status === 'expired') return `已于 ${formatDate(link.expiresAt)} 过期，可重新生成`
  return link.endedReason === 'manual' ? '本人终止，可重新生成' : '推广已结束'
}

function linkStatusClass(taskId: string): string {
  const link = linkFor(taskId)
  if (!link) return ''
  if (link.status === 'active') return 'badge-active'
  return 'badge-muted'
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN')
}

async function generate(item: RecommenderPromotion): Promise<void> {
  if (loading.value) return
  const ticket = session.capture()
  loading.value = true
  error.value = ''
  try {
    if (!currentUser.value?.id) throw new Error('登录后才能生成专属归因链接')
    // 服务端发放不透明 rlid（幂等：同任务返回现行 active 链接）；url 为站内相对路径。
    const link = await commerce.issuePromotionLink(item.taskId)
    if (!session.isCurrent(ticket) || !link) return
    linksByTask.value = { ...linksByTask.value, [item.taskId]: link }
    const qr = await QRCode.toDataURL(absoluteUrl(item.taskId), { width: 220, margin: 1 })
    if (!session.isCurrent(ticket)) return
    qrByTask.value = { ...qrByTask.value, [item.taskId]: qr }
    if (link.status !== 'active') {
      error.value = `链接当前为「${linkStatusLabel(item.taskId)}」，可重新生成`
    }
  } catch (cause) {
    if (!session.isCurrent(ticket)) return
    error.value = cause instanceof Error ? cause.message : '推广链接生成失败'
  } finally {
    if (session.isCurrent(ticket)) loading.value = false
  }
}

async function endLink(item: RecommenderPromotion): Promise<void> {
  const link = activeLink(item.taskId)
  if (!link || loading.value) return
  const ticket = session.capture()
  loading.value = true
  error.value = ''
  try {
    const ended = await commerce.endReferralLink(link.referralLinkId)
    if (!session.isCurrent(ticket) || !ended) return
    linksByTask.value = { ...linksByTask.value, [item.taskId]: ended }
    qrByTask.value = { ...qrByTask.value, [item.taskId]: '' }
  } catch (cause) {
    if (!session.isCurrent(ticket)) return
    error.value = cause instanceof Error ? cause.message : '链接失效操作失败'
  } finally {
    if (session.isCurrent(ticket)) loading.value = false
  }
}

async function copy(taskId: string): Promise<void> {
  const ticket = session.capture()
  try {
    await navigator.clipboard.writeText(absoluteUrl(taskId) || '')
    if (!session.isCurrent(ticket)) return
    copiedTaskId.value = taskId
  } catch {
    if (session.isCurrent(ticket)) error.value = '复制失败，请重试'
  }
}

watch(() => session.epoch, async () => {
  const ticket = session.capture()
  promotions.value = []
  linksByTask.value = {}
  qrByTask.value = {}
  copiedTaskId.value = ''
  error.value = ''
  loading.value = true
  try {
    const [items, links] = await Promise.all([commerce.listMyPromotions(), commerce.listMyReferralLinks()])
    if (!session.isCurrent(ticket)) return
    promotions.value = items ?? []
    // 我的链接按任务归并（列表 created_at DESC，首个即该任务最新链接）。
    const merged: Record<string, ReferralLink> = {}
    for (const link of links ?? []) {
      if (!merged[link.taskId]) merged[link.taskId] = link
    }
    linksByTask.value = merged
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
/* 任务书 #98 C98-01：推广链接卡（token 取根 DESIGN.md 体系，同 #75 卡 B7 先例）。 */
.share-card { display: grid; gap: var(--space-md); min-width: 0; }
.share-card h3 { margin: 0; font-size: var(--text-base); }
.promotion-list { list-style: none; display: grid; gap: var(--space-md); margin: 0; padding: 0; min-width: 0; }
.promotion-item { display: grid; gap: var(--space-sm); padding: var(--space-md) 0; border-top: 1px solid var(--color-border); min-width: 0; }
.promotion-copy { display: grid; gap: var(--space-xs); font-size: var(--text-sm); overflow-wrap: anywhere; }
.promotion-copy strong { font-size: var(--text-sm); }
.promotion-stats { font-size: var(--text-sm); color: var(--color-text-secondary); }
.share-result { display: grid; gap: var(--space-sm); min-width: 0; }
.link-meta { display: flex; align-items: center; gap: var(--space-sm); font-size: var(--text-sm); color: var(--color-text-secondary); flex-wrap: wrap; }
.link-expiry { color: var(--color-text-secondary); }
.copy-row { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.copy-row input { flex: 1; min-width: 0; }
.share-qr { width: 180px; height: 180px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.link-end { justify-self: start; }
input, button { min-height: 40px; padding: var(--space-sm) var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text); }
button { cursor: pointer; }
.form-error { margin: 0; color: var(--color-danger); font-size: var(--text-sm); }
</style>
