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
          :my-application="myApplication"
          :loading="loading"
          :wallet-balance-cents="walletBalanceCents"
          embedded
        />

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
        <slot name="accepted-actions" :task="effectiveTask" :application="myApplication" />
      </template>
    </div>

    <template #actions>
      <template v-if="effectiveTask">
        <!-- 报名：口径沿用 TaskDetailCard 三态（报名已截止/已报名/报名）+ 终态不可重报
             （V2 全表 UNIQUE 阻断重报——#77 卡 C：终态不可再诱导点「报名」）。 -->
        <button
          v-if="showApply"
          type="button"
          class="gl-btn-primary"
          :disabled="loading || applyDisabled"
          @click="$emit('apply', effectiveTask.id)"
        >{{ applyDisabled ? applyDisabledLabel : '报名' }}</button>
        <!-- 卡 D3：pending 态取消报名——确认与撤销在父级（警示文案由父级 confirm 弹出） -->
        <button v-if="applicationStatus === 'pending'" type="button" :disabled="loading"
                @click="$emit('withdraw', myApplication!)">取消报名</button>
        <button v-else-if="applicationStatus === 'reserving'" type="button" disabled>处理中</button>
        <button
          v-else-if="applicationStatus === 'accepted'"
          type="button"
          class="gl-btn-primary"
          :disabled="loading"
          @click="$emit('start-creation', { task: effectiveTask, application: myApplication })"
        >开始创作</button>
        <slot name="actions-extra" :task="effectiveTask" :application="myApplication" />
        <button type="button" @click="$emit('report', effectiveTask)">举报该任务</button>
        <span v-if="showApply && applyDisabled && myApplication" class="gl-hint">每个任务同时只保留一条有效报名</span>
      </template>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import TaskDetailCard from './TaskDetailCard.vue'
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

/** 大厅传入 feed 行即有详情；我的任务传投影行（task=null）——此处按 taskId 补拉。 */
const fetchedTask = ref<Task | null>(null)
const detailLoading = ref(false)
const effectiveTask = computed(() => props.task ?? fetchedTask.value)

// 任务快照过期容忍：先展示后端最新详情，失败保留弹窗空态（与 hall 旧行为「找不到就不渲染」不同，
// 弹窗已有明确的空态文案，不再静默）。
watch(() => [props.taskId, props.task] as const, async ([taskId, knownTask]) => {
  fetchedTask.value = null
  if (!taskId || knownTask) return
  detailLoading.value = true
  const task = await grassland.getTask(taskId)
  detailLoading.value = false
  // 弹窗已切到别的任务/已关闭：丢弃过期响应
  if (props.taskId !== taskId) return
  fetchedTask.value = task
}, { immediate: true })

// ---------- 门店公开资料（原 useWorkbenchEngagements.loadStorePublicProfile 随面板迁入弹窗） ----------
const storeProfile = ref<StorePublicProfile | null>(null)
const storeProfileLoading = ref(false)
const storeProfileError = ref('')

watch(() => effectiveTask.value?.storeId ?? null, async (storeId) => {
  storeProfile.value = null
  storeProfileError.value = ''
  if (!storeId) return
  storeProfileLoading.value = true
  try {
    const profile = await grassland.getStorePublicProfile(storeId)
    // 快速切换任务时丢弃过期响应
    if ((effectiveTask.value?.storeId ?? null) !== storeId) return
    storeProfile.value = profile
    if (!profile) storeProfileError.value = '该门店暂无公开资料'
  } finally {
    if ((effectiveTask.value?.storeId ?? null) === storeId) storeProfileLoading.value = false
  }
}, { immediate: true })

// ---------- 报名动作三态（口径 = TaskDetailCard:53-58 + 卡 C 终态阻断重报） ----------
const applicationStatus = computed(() => props.myApplication?.applicationStatus ?? null)

const TERMINAL_STATUSES: ReadonlySet<string> = new Set(['rejected', 'withdrawn', 'refunded'])

const deadlinePassed = computed(() =>
  Boolean(effectiveTask.value?.applicationDeadline
    && new Date(effectiveTask.value.applicationDeadline).getTime() < Date.now()))

const activeApplication = computed(() =>
  applicationStatus.value === 'pending' || applicationStatus.value === 'reserving'
  || applicationStatus.value === 'accepted')

const applyDisabled = computed(() =>
  activeApplication.value || deadlinePassed.value
  || (applicationStatus.value != null && TERMINAL_STATUSES.has(applicationStatus.value)))

const applyDisabledLabel = computed(() => {
  if (deadlinePassed.value) return '报名已截止'
  if (applicationStatus.value && TERMINAL_STATUSES.has(applicationStatus.value)) return '不可重新报名'
  return '已报名'
})
</script>

<style scoped>
.task-detail-modal-body { display: flex; flex-direction: column; gap: var(--space-md); }
</style>
