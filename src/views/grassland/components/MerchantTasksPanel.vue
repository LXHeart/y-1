<script setup lang="ts">
import { computed, defineAsyncComponent, inject, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, FileText } from '@lucide/vue'
import GlModal from '../../../components/GlModal.vue'
import TaskTermsPreview from './TaskTermsPreview.vue'
import CommissionLadderSummary from './CommissionLadderSummary.vue'
import TaskApplicantsPanel from './TaskApplicantsPanel.vue'
import { TASK_STAGES } from '../workbench-tabs'
import { WORKBENCH_TASKS_CTX } from '../workbench-keys'
import { formatYuan } from '../../../lib/money'
import { AI_PLATFORM_DEFINITIONS, normalizePlatformId } from '../../../config/ai-platform-capabilities'
import type { Task, TaskStatus } from '../../../types/grassland'

// 任务书 #91 W4 自 GrasslandWorkbench.vue 迁入；异步分包语义保持（defineAsyncComponent 随迁）。
const MerchantTaskForm = defineAsyncComponent(() => import('./MerchantTaskForm.vue'))

/**
 * 商家「任务与报名」面板（任务书 #91 W4 自 GrasslandWorkbench.vue 模板整段迁入；
 * W5 起选中展开块由子面板 TaskApplicantsPanel 承载）。D-03：经 WORKBENCH_TASKS_CTX
 * 注入六域实例与关键 refs（不走 20+ props 钻透，子组件禁止自行 useGrassland()）。
 *
 * 2026-09-10 反馈 1/2/4：任务列表加状态分类签（五态 + 全部，带计数）与行内分页；
 * 任务行重排为「标题行 / 进度元信息行 / 操作行」——报名数、名额、待筛选、截止时间
 * 直接可读，有人报名与没人报名的任务一眼可分。
 */
const ctx = inject(WORKBENCH_TASKS_CTX)!

const { grassland, router } = ctx
const { stores, activeOrgId, selectedStoreId, canPublishBounty } = ctx.session
const previewTask = ref<Task | null>(null)
watch(activeOrgId, () => {
  previewTask.value = null
  statusFilter.value = 'all'
  taskPage.value = 0
}, { flush: 'sync' })
const {
  tasks, selectedTaskId, taskSummary,
  publishDraft,
  isRejectedDraft, taskStatusLabel, toggleSelectTask,
} = ctx.engagements
const {
  taskFormNotice, taskFormOpen, taskForm, editingDraft, revisingTask,
  updateCommissionLadder, handleTaskFormUpdate, handleTaskFormStoreChange,
  openNewTaskForm, openEditDraft, openEditPublished, cancelTaskForm, goToPermissionUpgrade,
  publishTaskFromDrawer, saveDraftFromDrawer, confirmCancelTask, confirmCloseTask,
} = ctx.drawer

// ---------- 任务状态分类签（反馈 2）+ 行内分页（反馈 1） ----------

type TaskStatusFilter = 'all' | TaskStatus
const TASK_STATUS_FILTERS: readonly { id: TaskStatusFilter; label: string }[] = [
  { id: 'all', label: '全部' },
  { id: 'draft', label: '草稿' },
  { id: 'pending_review', label: '待审核' },
  { id: 'published', label: '招募中' },
  { id: 'closed', label: '已关闭' },
  { id: 'cancelled', label: '已取消' },
]
/** 每页条数：任务行重排后行高增加，8 条约一屏半，翻页粒度不至于太细。 */
const TASK_PAGE_SIZE = 8

const statusFilter = ref<TaskStatusFilter>('all')
const taskPage = ref(0)

const statusCounts = computed<Record<TaskStatusFilter, number>>(() => {
  const counts: Record<TaskStatusFilter, number> = {
    all: tasks.value.length, draft: 0, pending_review: 0, published: 0, closed: 0, cancelled: 0,
  }
  for (const task of tasks.value) counts[task.status] += 1
  return counts
})
const filteredTasks = computed(() => (statusFilter.value === 'all'
  ? tasks.value
  : tasks.value.filter((task) => task.status === statusFilter.value)))
const taskPageCount = computed(() => Math.max(1, Math.ceil(filteredTasks.value.length / TASK_PAGE_SIZE)))
const pagedTasks = computed(() => filteredTasks.value
  .slice(taskPage.value * TASK_PAGE_SIZE, (taskPage.value + 1) * TASK_PAGE_SIZE))
/** 列表收缩（取消任务/切分类）后当前页越界时回落末页。 */
watch([filteredTasks, taskPageCount], () => {
  if (taskPage.value >= taskPageCount.value) taskPage.value = taskPageCount.value - 1
})

function setStatusFilter(id: TaskStatusFilter): void {
  if (statusFilter.value === id) return
  statusFilter.value = id
  taskPage.value = 0
}

function turnTaskPage(delta: number): void {
  const next = taskPage.value + delta
  if (next < 0 || next >= taskPageCount.value) return
  taskPage.value = next
}

// ---------- 任务行可读化（反馈 4）：平台/内容形式、报名进度、截止时间 ----------

/** 平台显示名与 MerchantTaskForm 同源（config/ai-platform-capabilities，B 站按 PRD 口径显示）。 */
const PLATFORM_LABELS: Readonly<Record<string, string>> = Object.fromEntries(
  AI_PLATFORM_DEFINITIONS.map((platform) => [platform.id, platform.id === 'bilibili' ? 'B站' : platform.label]),
)
const CONTENT_FORM_LABELS: Readonly<Record<string, string>> = { image: '图文种草', video: '视频种草', interaction: '互动' }

function platformText(task: Task): string {
  if (!task.platform) return ''
  // 归一失败（存量自由文本平台）时原样展示，不猜测映射
  const normalized = normalizePlatformId(task.platform)
  const platformLabel = (normalized != null ? PLATFORM_LABELS[normalized] : undefined) ?? task.platform
  const formLabel = task.contentForm ? CONTENT_FORM_LABELS[task.contentForm] : ''
  return formLabel ? `${platformLabel} · ${formLabel}` : platformLabel
}

/** 报名进度摘要：报名总数 / 名额 / 待筛选 / 已接受（progress 缺省时整行不渲染）。 */
function progressText(task: Task): string {
  const progress = task.progress
  if (!progress) return ''
  const parts = [`报名 ${progress.totalApplications}`]
  if (task.maxSlots != null) parts.push(`名额 ${task.maxSlots}`)
  if (progress.acceptedApplicationCount > 0) parts.push(`已接受 ${progress.acceptedApplicationCount}`)
  return parts.join(' · ')
}

function formatTaskDate(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

/** 任务状态 → 刻度当前段：closed 停在履约（招募关闭、确认结算进行中）；cancelled 由模板加 dead 态。 */
function taskStageIndex(task: Task): number {
  switch (task.status) {
    case 'draft': return 0
    case 'pending_review': return 1
    case 'published': return 2
    case 'closed': return 3
    default: return 2
  }
}

/**
 * 已报名成功人数（accepted + reserving，reserving 是资金预留中的在途态）。
 * 有人报名成功后任务不可再修订（PRD §2.3）；前端禁用「编辑」按钮 + 行内原因，
 * 后端 revise 端点另有 409 守卫兜底（API 直调也堵死）。列表行没带 progress 时取 0——
 * 容许编辑，提交时由后端拦下。
 */
function acceptedApplicationCount(task: Task): number {
  return task.progress?.acceptedApplicationCount ?? 0
}
</script>

<template>
  <section class="gl-zone" aria-label="发布与撮合">
    <div class="gl-zone-head">
      <h3 class="gl-zone-title">发布与撮合</h3>
      <p class="gl-zone-note">任务沿 草稿 → 审核 → 招募 → 履约 → 结算 的生长线推进</p>
      <!-- 发布是偶发动作：入口收进垄眉，表单在抽屉里填（页签主体让给任务与报名列表） -->
      <button type="button" class="gl-btn-primary gl-zone-action" :disabled="!activeOrgId || grassland.loading.value" @click="openNewTaskForm()">发布新任务</button>
      <!-- 2026-09-04 反馈 5：商家侧争议（含「拒绝并转客服」生成的客服案）在 /me/disputes 查看 -->
      <button type="button" class="gl-zone-action" @click="router.push('/me/disputes')">我的争议 →</button>
    </div>
    <div class="gl-zone-body">
      <MerchantTaskForm
        :open="taskFormOpen"
        :form="taskForm"
        :editing-draft="editingDraft"
        :revising-task="revisingTask"
        :stores="stores"
        :selected-store-id="selectedStoreId"
        :active-org-id="activeOrgId"
        :can-publish-bounty="canPublishBounty"
        :loading="grassland.loading.value"
        :notice="taskFormNotice"
        @update:field="handleTaskFormUpdate"
        @update:commission-ladder="updateCommissionLadder"
        @change-store="handleTaskFormStoreChange"
        @publish="publishTaskFromDrawer"
        @save-draft="saveDraftFromDrawer"
        @close="cancelTaskForm"
        @go-upgrade-permission="goToPermissionUpgrade"
      />

      <article id="gl-engagements" class="gl-tile gl-tile-wide">
        <h3>任务与报名</h3>
        <div class="gl-row" aria-label="商家待办">
          <span class="badge badge-info">待筛选 {{ taskSummary.pending }}</span>
          <span class="badge badge-neutral">待验收 {{ taskSummary.review }}</span>
          <span class="badge badge-neutral">待结算 {{ taskSummary.settling }}</span>
        </div>

        <!-- 状态分类签（反馈 2）：五态 + 全部，带计数；切分类回到第一页 -->
        <nav v-if="tasks.length > 0" class="gl-chips" aria-label="任务状态筛选">
          <button
            v-for="filter in TASK_STATUS_FILTERS"
            :key="filter.id"
            type="button"
            class="gl-chip"
            :class="{ 'gl-chip-active': statusFilter === filter.id }"
            :aria-pressed="statusFilter === filter.id"
            @click="setStatusFilter(filter.id)"
          >{{ filter.label }}<span class="gl-chip-count">{{ statusCounts[filter.id] }}</span></button>
        </nav>

        <p v-if="tasks.length === 0" class="gl-empty">暂无任务</p>
        <p v-else-if="filteredTasks.length === 0" class="gl-empty">该分类下暂无任务</p>
        <template v-else>
          <ul class="gl-list">
            <li v-for="t in pagedTasks" :key="t.id" :class="{ 'task-row--active': selectedTaskId === t.id }">
              <!-- 标题行：标题 + 状态/金额/门槛徽标 -->
              <div class="task-row-main">
                <button
                  type="button"
                  class="gl-link task-row-title"
                  :class="{ active: selectedTaskId === t.id }"
                  :aria-expanded="selectedTaskId === t.id"
                  @click="toggleSelectTask(t.id)"
                >
                  {{ t.title }}
                </button>
                <!-- 任务书 #53：被驳回退回的草稿标「已驳回·待修改」（danger），其余按状态取 neutral -->
                <span class="badge" :class="isRejectedDraft(t) ? 'badge-danger' : 'badge-neutral'">{{
                  isRejectedDraft(t) ? '已驳回·待修改' : taskStatusLabel(t.status)
                }}</span>
                <span v-if="t.bountyCents" class="badge badge-success gl-num">{{ formatYuan(t.bountyCents) }}</span>
                <!-- 任务书 #25：阶梯任务在状态/赏金标签旁展示 compact 档位摘要（赏金 = 最高档预留） -->
                <CommissionLadderSummary v-if="t.requirements?.commissionLadder" :ladder="t.requirements.commissionLadder" compact />
                <span v-if="t.minRecommenderLevel > 1" class="badge badge-neutral">Lv{{ t.minRecommenderLevel }}+</span>
                <span v-if="t.autoAcceptMinLevel" class="badge badge-info">Lv{{ t.autoAcceptMinLevel }}+ 自动通过中</span>
              </div>
              <!-- 元信息行（反馈 4）：平台/形式 · 门店 · 报名进度 · 待筛选 · 截止时间 + 生长刻度 -->
              <div class="task-row-meta">
                <span v-if="platformText(t)">{{ platformText(t) }}</span>
                <span v-if="t.storeId">{{ stores.find((s) => s.id === t.storeId)?.name || '门店任务' }}</span>
                <span v-if="progressText(t)">{{ progressText(t) }}</span>
                <span
                  v-if="t.status === 'published' && (t.progress?.pendingApplications ?? 0) > 0"
                  class="badge badge-warning"
                >待筛选 {{ t.progress!.pendingApplications }}</span>
                <span v-if="t.applicationDeadline">报名截止 {{ formatTaskDate(t.applicationDeadline) }}</span>
                <!-- 生长刻度：五段状态轨，当前段高亮；cancelled 整轨转 danger -->
                <div
                  class="gl-growth" :class="{ dead: t.status === 'cancelled' }" role="img"
                  :aria-label="`生长进度：第 ${taskStageIndex(t) + 1}/5 段（${TASK_STAGES[taskStageIndex(t)]}）${t.status === 'cancelled' ? '，任务已取消' : ''}`"
                >
                  <span
                    v-for="(stageLabel, i) in TASK_STAGES" :key="stageLabel" class="gl-growth-seg"
                    :class="[`s${i}`, { done: i < taskStageIndex(t), now: i === taskStageIndex(t) }]"
                  />
                </div>
              </div>
              <!-- 任务书 #53：被驳回的草稿在行下展示驳回原因（重新提交/通过后后端置 null，不再显示） -->
              <p v-if="isRejectedDraft(t)" class="gl-hint gl-reject-hint">
                驳回原因：{{ t.lastRejectedNote || '平台未填写原因' }}
              </p>
              <div class="task-row-actions">
                <button type="button" @click="previewTask = t"><FileText :size="16" />合作条款</button>
                <!-- 草稿：编辑 / 提交审核 / 取消 -->
                <template v-if="t.status === 'draft'">
                  <button type="button" :disabled="grassland.loading.value" @click="openEditDraft(t)">编辑</button>
                  <button type="button" :disabled="grassland.loading.value" @click="publishDraft(t)">提交审核</button>
                  <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消</button>
                </template>
                <!-- 待审核：平台内容审核中，仅可取消（编辑需先驳回或取消重建） -->
                <template v-else-if="t.status === 'pending_review'">
                  <span class="gl-hint">平台审核中</span>
                  <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消</button>
                </template>
                <!-- 已发布：编辑出新版本（有人报名成功即禁用，PRD §2.3）/ 关闭报名 / 取消 -->
                <template v-else-if="t.status === 'published'">
                  <button
                    type="button"
                    :disabled="grassland.loading.value || acceptedApplicationCount(t) > 0"
                    @click="openEditPublished(t)"
                  >编辑</button>
                  <span v-if="acceptedApplicationCount(t) > 0" class="gl-hint gl-reject-hint">
                    已有 {{ acceptedApplicationCount(t) }} 名推荐官报名成功，任务不可再修改
                  </span>
                  <!-- 反馈 5：关闭报名先经应用内确认弹窗（原先无任何确认） -->
                  <button type="button" :disabled="grassland.loading.value" @click="confirmCloseTask(t)">关闭报名</button>
                  <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消任务</button>
                </template>
              </div>
            </li>
          </ul>

          <!-- 行内分页（反馈 1）：前端分页——任务按五态全量拉取后本地切片 -->
          <div class="gl-pager" aria-label="任务分页">
            <button type="button" title="上一页" aria-label="上一页任务" :disabled="taskPage === 0" @click="turnTaskPage(-1)"><ChevronLeft :size="16" /></button>
            <span class="gl-num">第 {{ taskPage + 1 }} / {{ taskPageCount }} 页 · 共 {{ filteredTasks.length }} 条</span>
            <button type="button" title="下一页" aria-label="下一页任务" :disabled="taskPage >= taskPageCount - 1" @click="turnTaskPage(1)"><ChevronRight :size="16" /></button>
          </div>
        </template>

        <!-- 任务书 #91 W5：选中展开块（推荐列表/筛选/批量条/申请表/评分面板）抽出为
             TaskApplicantsPanel（同一 WORKBENCH_TASKS_CTX）；v-if 语义原样挂组件标签。 -->
        <TaskApplicantsPanel v-if="selectedTaskId" />
      </article>
    </div>
    <GlModal v-if="previewTask" title="合作条款预览" wide scroll @close="previewTask = null">
      <div class="gl-field"><TaskTermsPreview :task-id="previewTask.id" :version="previewTask.version" /></div>
      <template #actions><button type="button" @click="previewTask = null">关闭</button></template>
    </GlModal>
  </section>
</template>

<style scoped>
/* 垄眉右端的主操作（「发布新任务」）：标题/说明占左，按钮靠右（自 SFC 随迁，W4） */
.gl-zone-action { margin-left: auto; }

/* ---------- 任务列表（反馈 4 重排）：每行三段——标题 / 元信息 / 操作 ---------- */
.gl-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-sm); }
.gl-list li {
  display: flex; flex-direction: column; gap: var(--space-xs);
  min-width: 0; padding: var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md);
  background: var(--surface-furrow);
}
/* 选中（展开报名块）的任务行：边框转品牌紫，定位「当前在看哪条」 */
.gl-list li.task-row--active { border-color: color-mix(in srgb, var(--color-accent) 55%, transparent); }
.task-row-main { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; min-width: 0; }
.task-row-title { font-weight: var(--weight-label); }
.task-row-meta {
  display: flex; align-items: center; gap: var(--space-xs) var(--space-sm); flex-wrap: wrap;
  font-size: var(--text-xs); color: var(--color-text-muted); font-variant-numeric: tabular-nums;
}
.task-row-meta .gl-growth { margin-left: auto; }
/* 移动端：元信息行放不下生长刻度时不再硬推右侧 */
@media (max-width: 767px) { .task-row-meta .gl-growth { margin-left: 0; } }
.task-row-actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
/* 任务书 #53：被驳回草稿的行下原因提示 */
.gl-reject-hint { margin: 0; font-size: var(--text-xs); color: var(--color-text-muted); }

/* 生长刻度：五段轨（草稿/审核/招募/履约/结算），已完成=段色半透、当前=段色实心；
   段色映射状态 token：中性/警示/信息/强调/成功——结构即状态机，不新增色相 */
.gl-growth { display: inline-flex; align-items: center; gap: 3px; }
.gl-growth-seg { width: 28px; height: var(--workflow-track-height); border-radius: var(--radius-pill); background: var(--color-border); }
.gl-growth-seg.s0.done { background: color-mix(in srgb, var(--color-text-secondary) 55%, transparent); }
.gl-growth-seg.s0.now { background: var(--color-text-secondary); }
.gl-growth-seg.s1.done { background: color-mix(in srgb, var(--color-warning) 55%, transparent); }
.gl-growth-seg.s1.now { background: var(--color-warning); }
.gl-growth-seg.s2.done { background: color-mix(in srgb, var(--color-info) 55%, transparent); }
.gl-growth-seg.s2.now { background: var(--color-info); }
.gl-growth-seg.s3.done { background: color-mix(in srgb, var(--color-accent) 55%, transparent); }
.gl-growth-seg.s3.now { background: var(--color-accent-2); }
.gl-growth-seg.s4.done { background: color-mix(in srgb, var(--color-success) 55%, transparent); }
.gl-growth-seg.s4.now { background: var(--color-success); }
.gl-growth.dead .gl-growth-seg { background: color-mix(in srgb, var(--color-danger) 40%, transparent); }

/* W5：展开块/筛选/批量/评分样式已随选中展开块迁入 TaskApplicantsPanel.vue。 */
</style>
