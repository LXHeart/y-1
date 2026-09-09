<template>
  <!-- 任务书 #77 卡 A：任务详情弹窗——大厅与「我的任务」两处复用（单实现，D1）。
       详情 + 门店公开资料 + 品牌资料 + 门店媒体画廊 + 报名/取消报名/举报全部收进弹窗；
       GlModal 已 Teleport 到 body，插槽内容包一层 .gl-field 恢复田垄作用域（D9）。 -->
  <GlModal :title="effectiveTask?.title ?? '任务详情'" wide scroll @close="$emit('close')">
    <div class="gl-field task-detail-modal-body" data-testid="task-detail-modal">
      <p v-if="detailLoading && !effectiveTask" class="gl-empty">任务详情加载中…</p>
      <p v-else-if="!effectiveTask" class="gl-empty">任务不存在或已下架</p>
      <template v-else>
        <TaskDetailCard
          :task="effectiveTask"
          :my-application="effectiveApplication"
          :loading="loading"
          :wallet-balance-cents="walletBalanceCents"
          embedded
        />
        <!-- 任务书 #98 C98-04：商家信用摘要（读时派生、样本不足中性文案、无硬门槛）。 -->
        <p
          v-if="effectiveTask.merchantCredit"
          class="gl-hint"
          data-testid="merchant-credit"
        >商家信用：{{ merchantCreditNote }}</p>
        <TaskTermsPreview :task-id="effectiveTask.id" :version="effectiveTask.version" />

        <!-- 任务书 #24：门店公开详情（只读白名单）。原大厅 zone 挂载随 #77 卡 A 迁入弹窗。 -->
        <StorePublicProfilePanel
          :store-id="effectiveTask.storeId ?? null"
          :profile="storeProfile"
          :loading="storeProfileLoading"
          :error="storeProfileError"
        />

        <!-- 缺口清偿之六：品牌公开资料（#32 D9 公开消费）——组件自取。 -->
        <BrandPublicProfilePanel :organization-id="effectiveTask.organizationId ?? null" />

        <!-- 任务书 #42：门店公开媒体画廊（按需拉取，URL 过期 onerror 重拉一次）——组件自取。 -->
        <StoreMediaGallery :store-id="effectiveTask.storeId ?? null" />

        <!-- 任务书 #77 卡 D：推荐官侧 accepted 的履约动作（提交凭证/商家评分/争议流）
             由「我的任务」挂载点注入；大厅挂载不提供本插槽即不渲染。 -->
        <p v-if="grassland.error.value" class="gl-hint" role="alert">{{ grassland.error.value }}</p>
        <label v-if="applicationStatus === 'reconsent'" class="gl-row">
          <input v-model="termsAccepted" type="checkbox" />我已阅读并同意当前任务的新条款
        </label>
        <template v-if="applicationStatus === 'accepted'">
          <RecommenderShareCard v-if="effectiveTask.commercePackageId" :task-id="effectiveTask.id" />
          <template v-else>
            <p class="gl-hint" data-testid="application-settlement">{{ settlementLabel(settlement) }}</p>
            <slot name="accepted-actions" :task="effectiveTask" :application="effectiveApplication" :settlement="settlement" />
          </template>
        </template>
      </template>
    </div>

    <template #actions>
      <div v-if="effectiveTask" class="gl-field task-detail-actions">
        <!-- 报名：口径沿用 TaskDetailCard 三态（报名已截止/已报名/报名）+ 终态不可重报
             （V2 全表 UNIQUE 阻断重报——#77 卡 C：终态不可再诱导点「报名」）。 -->
        <button
          v-if="showApply && applicationStatus !== 'accepted' && applicationStatus !== 'reconsent'"
          type="button"
          class="gl-btn-primary"
          :disabled="loading || detailLoading || applyDisabled"
          @click="$emit('apply', effectiveTask.id)"
        >{{ applyDisabled ? applyDisabledLabel : '报名' }}</button>
        <!-- 卡 D3：pending 态取消报名——确认与撤销在父级（警示文案由父级 confirm 弹出） -->
        <button v-if="applicationStatus === 'pending' || applicationStatus === 'reconsent'" type="button" :disabled="loading || detailLoading"
                @click="$emit('withdraw', effectiveApplication!)">取消报名</button>
        <button v-else-if="applicationStatus === 'reserving'" type="button" disabled>处理中</button>
        <button
          v-else-if="applicationStatus === 'accepted' && !effectiveTask.commercePackageId && !settlement?.confirmedAt"
          type="button"
          class="gl-btn-primary"
          :disabled="loading"
          @click="$emit('start-creation', { task: effectiveTask, application: effectiveApplication })"
        >开始创作</button>
        <button v-if="applicationStatus === 'reconsent'" type="button" class="gl-btn-primary" :disabled="loading || detailLoading || !canReconsent" @click="reconsent">确认新条款</button>
        <slot v-if="!effectiveTask.commercePackageId" name="actions-extra" :task="effectiveTask" :application="effectiveApplication" />
        <button type="button" title="刷新任务状态" aria-label="刷新任务状态" :disabled="detailLoading" @click="load"><RefreshCw :size="16" /></button>
        <button type="button" @click="$emit('report', effectiveTask)">举报该任务</button>
      </div>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RefreshCw } from '@lucide/vue'
import RecommenderShareCard from '../../../components/RecommenderShareCard.vue'
import { useAccountSessionStore } from '../../../stores/account-session'
import { useWorkbenchApplicationDetail } from '../composables/useWorkbenchApplicationDetail'
import { settlementLabel } from '../composables/useWorkbenchSettlement'
import GlModal from '../../../components/GlModal.vue'
import TaskDetailCard from './TaskDetailCard.vue'
import TaskTermsPreview from './TaskTermsPreview.vue'
import StorePublicProfilePanel from './StorePublicProfilePanel.vue'
import BrandPublicProfilePanel from './BrandPublicProfilePanel.vue'
import StoreMediaGallery from './StoreMediaGallery.vue'
import { useGrassland } from '../../../composables/useGrassland'
import type { MyApplication, StorePublicProfile, Task } from '../../../types/grassland'

const props = withDefaults(defineProps<{
  /** 已知任务详情（大厅 feed 行内即有）；null 时按 taskId 拉取（我的任务列表只有投影行）。 */
  task: Task | null
  /** 当前查看的任务 id——弹窗开合由父级以此控制（非空 = 开）。 */
  taskId: string
  /** 当前推荐官在此任务上的最近一条报名；null = 未报名过。 */
  myApplication: MyApplication | null
  loading: boolean
  /** 推荐官钱包余额（分，任务书 #22 软检查）；null/缺省 = 未加载，不做余额提示。 */
  walletBalanceCents?: number | null
  /** 我的任务挂载点传 false——列表行本身就是报名，弹窗不再提供「报名」入口（卡 D）。 */
  showApply?: boolean
}>(), { walletBalanceCents: null, showApply: true })

defineEmits<{
  close: []
  apply: [taskId: string]
  /** pending 态取消报名——父级 window.confirm 警示「撤销后不可重新报名该任务」后调 withdraw。 */
  withdraw: [application: MyApplication]
  /** accepted → 开始创作：整包（task + application）抛给父级走既有 getTaskContext 快照链。 */
  'start-creation': [payload: { task: Task; application: MyApplication | null }]
  /** 场景化举报——整只 task 抛给父级开举报弹窗。 */
  report: [task: Task]
}>()

const grassland = useGrassland()
const session = useAccountSessionStore()
const {
  task: effectiveTask, application: effectiveApplication, settlement, loading: detailLoading,
  termsAccepted, canReconsent, load, reconsent,
} = useWorkbenchApplicationDetail(grassland, props)

/** 任务书 #98 C98-04：商家信用摘要文案（样本不足显示中性文案，不展示标签）。 */
const merchantCreditNote = computed(() => {
  const credit = effectiveTask.value?.merchantCredit
  if (!credit) return ''
  if (credit.insufficientSamples || !credit.label) {
    return `合作样本不足（${credit.sampleCount ?? 0} 次），暂不展示信用标签`
  }
  return `${credit.label}（近 ${credit.sampleCount ?? 0} 次合作，口径 ${credit.policyVersion ?? ''}）`
})

// ---------- 门店公开资料（原 useWorkbenchEngagements.loadStorePublicProfile 随面板迁入弹窗） ----------
const storeProfile = ref<StorePublicProfile | null>(null)
const storeProfileLoading = ref(false)
const storeProfileError = ref('')

watch(() => effectiveTask.value?.storeId ?? null, async (storeId, _previous, onCleanup) => {
  const ticket = session.capture()
  let active = true
  onCleanup(() => { active = false })
  storeProfile.value = null
  storeProfileError.value = ''
  storeProfileLoading.value = false
  if (!storeId) return
  storeProfileLoading.value = true
  try {
    const profile = await grassland.getStorePublicProfile(storeId)
    // 快速切换任务时丢弃过期响应
    if (!active || !session.isCurrent(ticket)) return
    storeProfile.value = profile
    if (!profile) storeProfileError.value = '该门店暂无公开资料'
  } finally {
    if (active && session.isCurrent(ticket)) storeProfileLoading.value = false
  }
}, { immediate: true })

// ---------- 报名动作三态（口径 = TaskDetailCard:53-58 + 卡 C 终态阻断重报） ----------
const applicationStatus = computed(() => effectiveApplication.value?.applicationStatus ?? null)

const TERMINAL_STATUSES: ReadonlySet<string> = new Set(['rejected', 'withdrawn', 'refunded', 'cancelled'])

const deadlinePassed = computed(() =>
  Boolean(effectiveTask.value?.applicationDeadline
    && new Date(effectiveTask.value.applicationDeadline).getTime() < Date.now()))

const activeApplication = computed(() =>
  applicationStatus.value === 'pending' || applicationStatus.value === 'reserving'
  || applicationStatus.value === 'accepted' || applicationStatus.value === 'reconsent')

const applyDisabled = computed(() =>
  activeApplication.value || deadlinePassed.value || effectiveTask.value?.status !== 'published'
  || (applicationStatus.value != null && TERMINAL_STATUSES.has(applicationStatus.value)))

const applyDisabledLabel = computed(() => {
  if (deadlinePassed.value) return '报名已截止'
  if (applicationStatus.value && TERMINAL_STATUSES.has(applicationStatus.value)) return '不可重新报名'
  return '已报名'
})
</script>

<style scoped>
.task-detail-modal-body { display: flex; flex-direction: column; gap: var(--space-md); }
.task-detail-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: var(--space-sm); width: 100%; padding-top: var(--space-md); }
.task-detail-actions button { flex: 0 0 auto; min-height: 40px; white-space: nowrap; }
</style>
