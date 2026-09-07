<script setup lang="ts">
import { computed, inject, onActivated, onUnmounted, ref } from 'vue'
import OpsPagination from '../components/OpsPagination.vue'
import { ADMIN_BADGE_BRIDGE } from '../adminTabs'
import { useGrassland } from '../../../composables/useGrassland'
import { request } from '../../../composables/grassland-http'
import type { Task } from '../../../types/grassland'
import { formatDateTime } from '../admin-format'

defineOptions({ name: 'AdminReviewTasksPanel' })

/**
 * 任务审核面板（任务书 #91 A3 自 AdminView.vue 内联分支整段迁出，纯搬运）。
 * fetch=onActivated（D-04）：首次挂载即拉（B-2 声明差异——原深链首屏空、需再点一次），
 * 之后每次激活重拉（等价原 onActivate）；KeepAlive 常驻下「已激活再点击」不重拉（B-3）。
 * 徽标（待审数）经 ADMIN_BADGE_BRIDGE 注册回 AdminView 导航。
 */
const grassland = useGrassland()
const badgeBridge = inject(ADMIN_BADGE_BRIDGE)

/** 任务审核三态筛选项（后端 status 参数 + 行状态徽标 + 空态文案）。 */
const REVIEW_STATUS_OPTIONS = [
  { value: 'pending_review', label: '待审核', badge: 'badge-warning', empty: '暂无待审核任务' },
  { value: 'published', label: '已通过', badge: 'badge-success', empty: '暂无已通过任务' },
  { value: 'rejected', label: '已驳回', badge: 'badge-danger', empty: '暂无已驳回任务' },
] as const
type ReviewStatusFilter = typeof REVIEW_STATUS_OPTIONS[number]['value']

const reviewTasks = ref<Task[]>([])
const taskSearch = ref('')
const taskReviewLoading = ref(false)
const taskReviewError = ref('')
const taskReviewNotes = ref<Record<string, string>>({})
const reviewStatus = ref<ReviewStatusFilter>('pending_review')
const taskLimit = ref(10)
const taskOffset = ref(0)
const taskTotal = ref(0)
const reviewingTaskId = ref<string | null>(null)
let listRequestVersion = 0
const reviewStatusOption = computed(() =>
  REVIEW_STATUS_OPTIONS.find((option) => option.value === reviewStatus.value) ?? REVIEW_STATUS_OPTIONS[0])

/** 任务审核统计条（进页签才请求，不进 onMounted）。 */
interface ReviewStats {
  pending: number
  overdue: number
  approvedLast24Hours: number
  rejectedLast24Hours: number
}
const reviewStats = ref<ReviewStats | null>(null)

async function loadReviewTasks(): Promise<void> {
  const version = ++listRequestVersion
  taskReviewLoading.value = true
  taskReviewError.value = ''
  const result = await grassland.listReviewTasks({
    status: reviewStatus.value,
    q: taskSearch.value || undefined,
    limit: taskLimit.value,
    offset: taskOffset.value,
  })
  if (version !== listRequestVersion) return
  if (result) {
    reviewTasks.value = [...result.items]
    taskTotal.value = result.total
  } else {
    taskReviewError.value = grassland.error.value || '任务审核列表加载失败'
  }
  taskReviewLoading.value = false
}

async function loadReviewStats(): Promise<void> {
  try {
    reviewStats.value = await request<ReviewStats>('/api/admin/tasks/review/stats')
  } catch {
    reviewStats.value = null
  }
}

/** 切三态：offset 归零重载（当前状态重复点击不重发请求）。 */
function setReviewStatus(status: ReviewStatusFilter): void {
  if (reviewStatus.value === status) return
  reviewStatus.value = status
  taskOffset.value = 0
  void loadReviewTasks()
}

function submitTaskSearch(): void {
  taskOffset.value = 0
  void loadReviewTasks()
}

function changeTaskPage(offset: number): void {
  taskOffset.value = offset
  void loadReviewTasks()
}

/** 切每页条数：limit 生效 + offset 归零重载。 */
function changeTaskLimit(limit: number): void {
  taskLimit.value = limit
  taskOffset.value = 0
  void loadReviewTasks()
}

async function reviewTask(task: Task, decision: 'approve' | 'reject'): Promise<void> {
  if (reviewingTaskId.value) return
  const note = (taskReviewNotes.value[task.id] || '').trim()
  if (decision === 'reject' && !note) {
    taskReviewError.value = '驳回任务必须填写原因'
    return
  }
  taskReviewError.value = ''
  reviewingTaskId.value = task.id
  try {
    const result = decision === 'approve'
      ? await grassland.approveTaskReview(task.id, task.version)
      : await grassland.rejectTaskReview(task.id, task.version, note)
    if (result) {
      await Promise.all([loadReviewTasks(), loadReviewStats()])
      delete taskReviewNotes.value[task.id]
    } else {
      taskReviewError.value = grassland.error.value || '审核失败'
    }
  } finally {
    reviewingTaskId.value = null
  }
}

badgeBridge?.register('tasks', () => reviewStats.value?.pending ?? 0)
onUnmounted(() => badgeBridge?.unregister('tasks'))

onActivated(() => {
  void loadReviewTasks()
  void loadReviewStats()
})
</script>

<template>
  <div class="panel-toolbar">
    <div><h3>任务审核</h3><p>全审政策：所有任务提交后需审核通过才在大厅上架</p></div>
    <form class="search-toolbar" @submit.prevent="submitTaskSearch">
      <input v-model="taskSearch" type="search" maxlength="100" placeholder="搜索任务标题或描述">
      <button class="refresh-btn" type="submit" :disabled="taskReviewLoading">搜索</button>
    </form>
  </div>
  <div class="review-status-bar">
    <div class="status-pill-group" aria-label="任务审核状态筛选">
      <button v-for="option in REVIEW_STATUS_OPTIONS" :key="option.value" type="button"
        class="status-pill" :class="{ active: reviewStatus === option.value }"
        :aria-pressed="reviewStatus === option.value" @click="setReviewStatus(option.value)">
        {{ option.label }}
      </button>
    </div>
    <div v-if="reviewStats" class="review-stats" aria-label="审核统计">
      <span class="badge badge-warning">待审 <span class="gl-num">{{ reviewStats.pending }}</span></span>
      <span class="badge badge-danger">超时 <span class="gl-num">{{ reviewStats.overdue }}</span></span>
      <span class="badge badge-success">24h 通过 <span class="gl-num">{{ reviewStats.approvedLast24Hours }}</span></span>
      <span class="badge badge-danger">24h 驳回 <span class="gl-num">{{ reviewStats.rejectedLast24Hours }}</span></span>
    </div>
  </div>
  <p v-if="taskReviewError" class="error-msg" role="alert">{{ taskReviewError }}</p>
  <div v-if="taskReviewLoading" class="loading-state">加载中...</div>
  <template v-else>
    <div class="table-card">
      <div class="table-scroll">
      <table class="user-table kyb-table">
        <thead><tr><th>标题</th><th>类型</th><th>平台</th><th>赏金</th><th>组织</th><th>状态</th><th>驳回原因</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="t in reviewTasks" :key="t.id">
            <td>{{ t.title }}</td>
            <!-- 任务书 #75 卡 D4：套餐推广任务类型标识（佣金来自套餐版本快照，任务侧无赏金）。 -->
            <td>
              <span v-if="t.commercePackageId" class="badge badge-success" title="末次点击单归因，改绑为人工纠错">套餐推广</span>
              <span v-else class="type-tag">—</span>
            </td>
            <td><span class="type-tag">{{ t.platform || '—' }}</span></td>
            <td class="td-balance">{{ t.bountyCents ? '¥' + (t.bountyCents / 100).toFixed(2) : (t.commercePackageId ? '套餐分佣' : '—') }}</td>
            <td class="id-cell" :title="t.organizationId">{{ t.organizationId }}</td>
            <td><span class="badge" :class="reviewStatusOption.badge">{{ reviewStatusOption.label }}</span></td>
            <td>
              <input v-if="reviewStatus === 'pending_review'" v-model="taskReviewNotes[t.id]" class="field-input" type="text" maxlength="500" placeholder="驳回原因（驳回必填）" />
              <div v-else-if="reviewStatus === 'rejected'" class="review-note-history">
                <span>{{ t.lastReviewNote || '—' }}</span>
                <span class="td-time">{{ formatDateTime(t.lastReviewedAt ?? null) }}</span>
              </div>
              <span v-else>—</span>
            </td>
            <td class="review-actions">
              <template v-if="reviewStatus === 'pending_review'">
                <button class="approve-btn" type="button" :disabled="reviewingTaskId !== null" @click="reviewTask(t, 'approve')">{{ reviewingTaskId === t.id ? '提交中...' : '通过' }}</button>
                <button class="reject-btn" type="button" :disabled="reviewingTaskId !== null" @click="reviewTask(t, 'reject')">驳回</button>
              </template>
              <!-- 已通过/已驳回视图不再给操作入口（决策 F：终态再审后端 409，前端不暴露入口） -->
              <span v-else>—</span>
            </td>
          </tr>
          <tr v-if="reviewTasks.length === 0"><td colspan="8" class="td-empty">{{ reviewStatusOption.empty }}</td></tr>
        </tbody>
      </table>
      </div>
    </div>
    <OpsPagination :total="taskTotal" :limit="taskLimit" :offset="taskOffset"
      @change="changeTaskPage" @change-limit="changeTaskLimit" />
  </template>
</template>

<style scoped src="../admin-shared.css"></style>

<style scoped>
/* 任务审核三态筛选：DESIGN.md nav-pill-group + category-tab 范式——
   pill 容器（surface-muted 底 + pill 圆角 + 6px 内边距）内嵌胶囊页签（激活=画布底 + 阴影）。 */
.review-status-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-md); flex-wrap: wrap; }
.status-pill-group { display: inline-flex; gap: 2px; padding: 6px; background: var(--surface-muted); border: 1px solid var(--color-border); border-radius: var(--radius-pill); }
.status-pill {
  padding: 8px 14px;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}
.status-pill:hover { color: var(--color-text-secondary); }
.status-pill.active { background: var(--color-surface); color: var(--color-text); font-weight: 600; box-shadow: var(--shadow-card); }
.review-stats { display: inline-flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; }
.review-note-history { display: grid; gap: 2px; font-size: 0.8rem; }
</style>
