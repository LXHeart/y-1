<template>
  <!-- 2026-09-04 用户反馈：推荐官侧任务详情卡——此前选中任务只联动门店/品牌/媒体
       三块面板，任务本体没有详情块，点标题「打不开详情」。此卡补齐任务详情，并承载从
       操作栏迁入的场景化举报入口（反馈 2）与报名状态标识（反馈 4）。 -->
  <article v-if="task" class="gl-task-detail" :class="{ 'gl-tile gl-tile-wide': !embedded }" data-testid="task-detail-card">
    <header class="gl-task-detail-head">
      <h4 class="gl-task-detail-title">{{ task.title }}</h4>
      <!-- 任务书 #77 卡 A：详情收进弹窗后由弹窗 × 关闭，内嵌模式不再渲染「收起」。 -->
      <button v-if="!embedded" type="button" class="gl-task-detail-collapse" :aria-label="`收起任务 ${task.title} 的详情`"
              @click="$emit('close')">收起</button>
    </header>

    <div class="gl-row gl-task-detail-badges">
      <span v-if="platformLabel" class="badge badge-neutral">{{ platformLabel }}</span>
      <span class="badge badge-neutral">{{ contentFormLabel }}</span>
      <span v-if="myApplication" class="badge" :class="applicationBadgeClass">{{ applicationStatusLabel }}</span>
      <span v-else class="badge badge-success">未报名</span>
      <span v-if="task.minRecommenderLevel > 1" class="badge badge-neutral">Lv{{ task.minRecommenderLevel }}+</span>
      <span v-if="task.autoAcceptMinLevel" class="badge badge-info">Lv{{ task.autoAcceptMinLevel }}+ 自动通过</span>
    </div>

    <dl class="gl-task-detail-meta">
      <div><dt>门店</dt><dd>{{ task.store ? [task.store.storeName, task.store.city].filter(Boolean).join(' · ') || '—' : '—' }}</dd></div>
      <div>
        <dt>赏金</dt>
        <dd class="gl-num">
          <CommissionLadderSummary v-if="task.requirements?.commissionLadder" :ladder="task.requirements.commissionLadder" />
          <template v-else-if="task.freebieDepositCents">霸王餐（押金 {{ formatYuan(task.freebieDepositCents) }}，达标全额返还）</template>
          <template v-else>{{ task.bountyCents ? formatYuan(task.bountyCents) : '无' }}</template>
        </dd>
      </div>
      <div><dt>名额</dt><dd>{{ task.maxSlots ? `${task.maxSlots} 人` : '不限' }}</dd></div>
      <div><dt>报名截止</dt><dd>{{ task.applicationDeadline ? new Date(task.applicationDeadline).toLocaleString() : '不限' }}</dd></div>
      <div v-if="task.distanceKm != null"><dt>距离</dt><dd class="gl-num">{{ task.distanceKm.toFixed(1) }} km</dd></div>
      <div v-if="myApplication?.appliedAt"><dt>报名时间</dt><dd>{{ new Date(myApplication.appliedAt).toLocaleString() }}</dd></div>
    </dl>

    <p v-if="freebieShortage" class="gl-hint gl-freebie-warn">
      押金超过钱包余额 <span class="gl-num">{{ formatYuan(walletBalanceCents!) }}</span>，被接受时会因余额不足退回
    </p>

    <p v-if="task.description" class="gl-task-detail-desc">{{ task.description }}</p>

    <template v-if="requirementBlocks.length > 0">
      <div v-for="block in requirementBlocks" :key="block.label" class="gl-task-detail-req">
        <h5>{{ block.label }}</h5>
        <ul>
          <li v-for="(item, index) in block.items" :key="index">{{ item }}</li>
        </ul>
      </div>
    </template>

    <!-- 任务书 #77 卡 A：动作按钮迁弹窗 footer——内嵌模式不再渲染行内动作（弹窗的 actions 槽
         按本组件同一口径渲染报名/举报）。独立卡片模式（2026-09-04 反馈 1/2）保持原样。 -->
    <div v-if="!embedded" class="gl-row gl-task-detail-actions">
      <button type="button" class="gl-btn-primary" :disabled="loading || applyDisabled" @click="$emit('apply', task.id)">
        {{ applyDisabled ? applyDisabledLabel : '报名' }}
      </button>
      <!-- 任务书 #74 场景化举报：从大厅操作栏迁入详情卡（2026-09-04 反馈 2——低频治理动作不占行内操作位） -->
      <button type="button" @click="$emit('report', task)">举报该任务</button>
      <span v-if="applyDisabled && myApplication" class="gl-hint">每个任务同时只保留一条有效报名</span>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import CommissionLadderSummary from './CommissionLadderSummary.vue'
import type { MyApplication, Task } from '../../../types/grassland'
import { formatYuan } from '../../../lib/money'
import { getPlatform, normalizePlatformId } from '../../../config/ai-platform-capabilities'

const props = withDefaults(defineProps<{
  task: Task
  /** 当前推荐官在此任务上的最近一条报名；null = 未报名过。 */
  myApplication: MyApplication | null
  loading: boolean
  /** 推荐官钱包余额（分，任务书 #22 软检查）；null = 未加载，不做余额提示。 */
  walletBalanceCents?: number | null
  /** 任务书 #77 卡 A：内嵌进 TaskDetailModal——去磁贴外框/「收起」/行内动作（动作迁弹窗 footer）。 */
  embedded?: boolean
}>(), { embedded: false })

defineEmits<{
  apply: [taskId: string]
  /** 场景化举报——整只 task 抛给父级开举报弹窗。 */
  report: [task: Task]
  close: []
}>()

const PLATFORM_CONTENT_FORM_LABELS: Readonly<Record<string, string>> = {
  image: '图文种草',
  video: '视频种草',
  article: '文章',
  interaction: '点赞互动',
}

const APPLICATION_STATUS_LABELS: Readonly<Record<string, string>> = {
  pending: '已报名 · 待商家处理',
  reserving: '已报名 · 佣金预留中',
  accepted: '已报名 · 履约中',
  rejected: '曾报名 · 未通过',
  withdrawn: '曾报名 · 已撤销',
  refunded: '曾报名 · 已退款',
}

const platformLabel = computed(() => {
  if (!props.task.platform) return ''
  const platformId = normalizePlatformId(props.task.platform)
  return (platformId && getPlatform(platformId)?.label) ?? props.task.platform
})

const contentFormLabel = computed(() =>
  PLATFORM_CONTENT_FORM_LABELS[props.task.contentForm ?? ''] ?? props.task.contentForm ?? '—')

const myApplicationStatus = computed(() => props.myApplication?.applicationStatus ?? null)

const applicationStatusLabel = computed(() =>
  (myApplicationStatus.value && APPLICATION_STATUS_LABELS[myApplicationStatus.value]) || '已报名')

/** 占用态用 accent 突出、历史态回中性灰——「能不能再报名」一眼可辨。 */
const applicationBadgeClass = computed(() => (
  myApplicationStatus.value === 'pending' || myApplicationStatus.value === 'reserving' ? 'badge-info'
    : myApplicationStatus.value === 'accepted' ? 'badge-success'
      : 'badge-neutral'
))

/** 报名截止即不可再报名（后端同规则拒绝，前端先给出不可点状态）。 */
const deadlinePassed = computed(() =>
  Boolean(props.task.applicationDeadline && new Date(props.task.applicationDeadline).getTime() < Date.now()))

const activeApplication = computed(() => (
  myApplicationStatus.value === 'pending'
  || myApplicationStatus.value === 'reserving'
  || myApplicationStatus.value === 'accepted'))

const applyDisabled = computed(() => activeApplication.value || deadlinePassed.value)

const applyDisabledLabel = computed(() => (deadlinePassed.value ? '报名已截止' : '已报名'))

const freebieShortage = computed(() => (
  Boolean(props.task.freebieDepositCents
    && props.walletBalanceCents != null
    && props.task.freebieDepositCents > props.walletBalanceCents)))

/** 要求块的呈现次序：商家说明 → 必含 → 禁止 → 指标 → 凭证 → 发布窗口 → 互动配置。空块不渲染。 */
const requirementBlocks = computed(() => {
  const requirements = props.task.requirements
  const blocks: Array<{ label: string; items: string[] }> = []
  if (requirements?.productServiceInfo) {
    blocks.push({ label: '产品与商家说明', items: [requirements.productServiceInfo] })
  }
  if (requirements?.mustInclude?.length) blocks.push({ label: '内容须包含', items: requirements.mustInclude })
  if (requirements?.forbiddenContent?.length) blocks.push({ label: '禁止出现', items: requirements.forbiddenContent })
  if (requirements?.metricRequirements?.length) blocks.push({ label: '指标要求', items: requirements.metricRequirements })
  if (requirements?.evidenceRequirements?.length) blocks.push({ label: '凭证要求', items: requirements.evidenceRequirements })
  const start = requirements?.publishStartAt
  const end = requirements?.publishEndAt
  if (start || end) {
    blocks.push({
      label: '发布窗口',
      items: [`${start ? new Date(start).toLocaleString() : '不限'} 起 · ${end ? new Date(end).toLocaleString() : '不限'} 止`],
    })
  }
  if (requirements?.interaction?.targetUrl) {
    const actionLabels: Record<string, string> = {
      like: '点赞', favorite: '收藏', follow: '关注', comment: '评论',
    }
    blocks.push({
      label: '互动要求',
      items: [`在 ${requirements.interaction.targetUrl} 完成${actionLabels[requirements.interaction.actionType] ?? requirements.interaction.actionType}`],
    })
  }
  return blocks
})
</script>

<style scoped>
.gl-task-detail { display: flex; flex-direction: column; gap: var(--space-sm); }
.gl-task-detail-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.gl-task-detail-title { margin: 0; font-size: var(--text-base); font-weight: 700; letter-spacing: -0.01em; }
.gl-task-detail-collapse { font-size: var(--text-xs); padding: 2px 10px; }
.gl-task-detail-badges { margin: 0; }
.gl-task-detail-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr)); gap: var(--space-sm); margin: 0; }
.gl-task-detail-meta div { display: flex; flex-direction: column; gap: 2px; }
.gl-task-detail-meta dt { font-size: var(--text-xs); color: var(--color-text-muted); }
.gl-task-detail-meta dd { margin: 0; font-size: var(--text-sm); font-weight: 500; }
.gl-task-detail-desc { margin: 0; font-size: var(--text-sm); line-height: 1.6; color: var(--color-text-secondary); white-space: pre-line; }
.gl-task-detail-req h5 { margin: var(--space-xs) 0 2px; font-size: var(--text-xs); font-weight: 600; color: var(--color-text-muted); }
.gl-task-detail-req ul { margin: 0; padding-left: 18px; display: grid; gap: 2px; font-size: var(--text-sm); color: var(--color-text-secondary); }
.gl-task-detail-actions { padding-top: var(--space-xs); border-top: 1px solid var(--color-border); }
.gl-freebie-warn { color: var(--color-danger); font-size: 12px; }
</style>
