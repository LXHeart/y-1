<script setup lang="ts">
import { computed } from 'vue'
import type {
  MerchantProfileStatus,
  OrgBrandSummary,
  OrgKybSummary,
  OrgPermissionSummary,
  OrgTeamSummary,
} from '../../../types/grassland'

/**
 * 商家主体概览——「商家主体与门店」屏的默认落点。
 *
 * 四张状态卡（认证 / 额度 / 品牌 / 成员）各自回答「这项齐了吗、缺什么、去哪补」。
 * 数据来自四张子卡向上冒泡的摘要（见 types/grassland/org-summary.ts），
 * 本组件不发请求；点卡片 = 切到对应分节。
 *
 * 摘要为 null（子卡还没加载完）时卡片呈现「—」而不是 0，
 * 避免把「还不知道」显示成「没有」。
 */

/** 分节 id，与父组件的 rail 一致。 */
export type OrgSection = 'overview' | 'team' | 'brand' | 'kyb' | 'permission'

const props = defineProps<{
  kyb: OrgKybSummary | null
  permission: OrgPermissionSummary | null
  team: OrgTeamSummary | null
  brand: OrgBrandSummary | null
}>()

const emit = defineEmits<{ open: [section: OrgSection] }>()

type Tone = 'ok' | 'warn' | 'bad' | 'muted'

interface OverviewCard {
  key: string
  label: string
  /** 主值：一眼看的结论。 */
  value: string
  tone: Tone
  /** 副行：缺什么 / 下一步；无则不渲染。 */
  hint: string | null
  section: OrgSection
}

const KYB_LABEL: Record<MerchantProfileStatus, string> = {
  draft: '资料草稿',
  pending: '待审核',
  under_review: '审核中',
  approved: '已通过',
  rejected: '已驳回',
}

const kybCard = computed<OverviewCard>(() => {
  const kyb = props.kyb
  if (!kyb) return { key: 'kyb', label: '认证', value: '—', tone: 'muted', hint: null, section: 'kyb' }

  const status = kyb.merchantStatus
  if (status === 'approved') {
    return {
      key: 'kyb', label: '认证', value: '已通过', tone: 'ok', section: 'kyb',
      // 认证过了但没收款账户，结算时才发现就晚了——提前提示。
      hint: kyb.approvedWithdrawalCount === 0 ? '还没有已通过的收款账户' : null,
    }
  }
  if (status === 'rejected') {
    return { key: 'kyb', label: '认证', value: '已驳回', tone: 'bad', hint: '按审核意见补正后重新提交', section: 'kyb' }
  }
  if (status === 'pending' || status === 'under_review') {
    return { key: 'kyb', label: '认证', value: KYB_LABEL[status], tone: 'warn', hint: '平台审核中，无需操作', section: 'kyb' }
  }
  return {
    key: 'kyb', label: '认证', value: status ? KYB_LABEL[status] : '未提交',
    tone: 'muted', hint: '完成认证后才能发布资金型任务', section: 'kyb',
  }
})

const permissionCard = computed<OverviewCard>(() => {
  const permission = props.permission
  if (!permission) return { key: 'permission', label: '额度', value: '—', tone: 'muted', hint: null, section: 'permission' }

  const pendingHint = permission.hasPendingRequest ? '升级申请审核中' : null
  // 用量不可见（非绑定 org，marketplace 403）：只报上限，不臆造余量。
  if (permission.remainingActiveTasks === null) {
    return {
      key: 'permission', label: '额度',
      value: permission.maxActiveTasks > 0 ? `上限 ${permission.maxActiveTasks}` : '不可交易',
      tone: permission.maxActiveTasks > 0 ? 'muted' : 'warn',
      hint: pendingHint ?? '已用量仅对绑定的商家身份可见', section: 'permission',
    }
  }
  const remaining = permission.remainingActiveTasks
  return {
    key: 'permission', label: '额度',
    value: `活跃余 ${remaining} / ${permission.maxActiveTasks}`,
    tone: remaining === 0 ? 'warn' : 'ok',
    hint: pendingHint ?? (remaining === 0 ? '额度用尽，发布会被拒' : null),
    section: 'permission',
  }
})

/** 品牌完整度按四项计数（品牌名 / Logo / 简介 / 经营分类），全齐才算完成。 */
const brandCard = computed<OverviewCard>(() => {
  const brand = props.brand
  if (!brand) return { key: 'brand', label: '品牌', value: '—', tone: 'muted', hint: null, section: 'brand' }

  const missing: string[] = []
  if (!brand.hasBrandName) missing.push('品牌名')
  if (!brand.hasLogo) missing.push('Logo')
  if (!brand.hasDescription) missing.push('简介')
  if (!brand.hasIndustry) missing.push('经营分类')

  const filled = 4 - missing.length
  return {
    key: 'brand', label: '品牌',
    value: missing.length === 0 ? '已完善' : `${filled} / 4`,
    tone: missing.length === 0 ? 'ok' : 'muted',
    hint: missing.length === 0 ? null : `缺 ${missing.join('、')}`,
    section: 'brand',
  }
})

const teamCard = computed<OverviewCard>(() => {
  const team = props.team
  if (!team) return { key: 'team', label: '成员与门店', value: '—', tone: 'muted', hint: null, section: 'team' }

  // 待办：待审核成员（挡着人登录）。邀请待办已随邀请流下线（任务书 #49）。
  const pending: string[] = []
  if (team.pendingReviewCount > 0) pending.push(`${team.pendingReviewCount} 人待审核`)

  return {
    key: 'team', label: '成员与门店',
    value: `${team.memberCount} 人 · ${team.storeCount} 店`,
    tone: team.pendingReviewCount > 0 ? 'warn' : 'ok',
    hint: pending.length > 0 ? pending.join('，') : null,
    section: 'team',
  }
})

const cards = computed<OverviewCard[]>(() => [
  kybCard.value, permissionCard.value, brandCard.value, teamCard.value,
])
</script>

<template>
  <div class="org-overview">
    <button
      v-for="card in cards" :key="card.key"
      type="button" class="org-ov-card" :data-tone="card.tone"
      @click="emit('open', card.section)"
    >
      <span class="org-ov-label">{{ card.label }}</span>
      <span class="org-ov-value">{{ card.value }}</span>
      <span v-if="card.hint" class="org-ov-hint">{{ card.hint }}</span>
    </button>
  </div>
</template>

<style scoped>
/**
 * 2×2 为主的网格：四张卡挤成一行时高度只有左栏的一半，右侧留大片空白。
 * 320px 下限让常规桌面宽度落到两列，与五项左栏的高度大致齐平；窄屏自然退单列。
 */
.org-overview {
  display: grid; gap: var(--space-sm);
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr));
}

.org-ov-card {
  display: flex; flex-direction: column; gap: 2px; min-width: 0;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border); border-radius: var(--radius-md);
  background: var(--surface-furrow); text-align: left; cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out),
    background var(--duration-fast) var(--ease-out);
}
.org-ov-card:hover { border-color: var(--color-border-hover); background: var(--color-surface-highlight); }

.org-ov-label {
  font-size: var(--text-xs); font-weight: 600; letter-spacing: 0.04em;
  color: var(--color-text-muted);
}
.org-ov-value {
  font-size: var(--text-base); font-weight: 700; color: var(--color-text);
  font-variant-numeric: tabular-nums;
}
.org-ov-hint { font-size: var(--text-xs); color: var(--color-text-muted); line-height: 1.5; }

/* 语义色只落在主值上——整卡染色会让四张卡互相抢注意力 */
.org-ov-card[data-tone="ok"] .org-ov-value { color: var(--color-success); }
.org-ov-card[data-tone="warn"] .org-ov-value { color: var(--color-warning); }
.org-ov-card[data-tone="bad"] .org-ov-value { color: var(--color-danger); }
.org-ov-card[data-tone="warn"] .org-ov-hint,
.org-ov-card[data-tone="bad"] .org-ov-hint { color: var(--color-text-secondary); }
</style>
