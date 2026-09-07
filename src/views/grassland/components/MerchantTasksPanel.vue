<script setup lang="ts">
import { defineAsyncComponent, inject } from 'vue'
import CommissionLadderSummary from './CommissionLadderSummary.vue'
import TaskApplicantsPanel from './TaskApplicantsPanel.vue'
import { TASK_STAGES } from '../workbench-tabs'
import { WORKBENCH_TASKS_CTX } from '../workbench-keys'
import { formatYuan } from '../../../lib/money'
import type { Task } from '../../../types/grassland'

// 任务书 #91 W4 自 GrasslandWorkbench.vue 迁入；异步分包语义保持（defineAsyncComponent 随迁）。
const MerchantTaskForm = defineAsyncComponent(() => import('./MerchantTaskForm.vue'))

/**
 * 商家「任务与报名」面板（任务书 #91 W4 自 GrasslandWorkbench.vue 模板整段迁入，纯搬运；
 * W5 起选中展开块由子面板 TaskApplicantsPanel 承载）。D-03：经 WORKBENCH_TASKS_CTX
 * 注入六域实例与关键 refs（不走 20+ props 钻透，子组件禁止自行 useGrassland()），
 * 模板表达式与迁出前逐字符一致；v-show 由父层挂在本组件标签上。
 */
const ctx = inject(WORKBENCH_TASKS_CTX)!

const { grassland, router } = ctx
const { stores, activeOrgId, selectedStoreId, canPublishBounty } = ctx.session
const {
  tasks, selectedTaskId, taskSummary,
  publishDraft, closeTaskAction,
  isRejectedDraft, taskStatusLabel, toggleSelectTask,
} = ctx.engagements
const {
  taskFormNotice, taskFormOpen, taskForm, editingDraft, revisingTask,
  updateCommissionLadder, handleTaskFormUpdate, handleTaskFormStoreChange,
  openNewTaskForm, openEditDraft, openEditPublished, cancelTaskForm, goToPermissionUpgrade,
  publishTaskFromDrawer, saveDraftFromDrawer, confirmCancelTask,
} = ctx.drawer

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
        <p v-if="tasks.length === 0" class="gl-empty">暂无任务</p>
        <ul class="gl-list">
          <li v-for="t in tasks" :key="t.id">
            <div class="gl-task-main">
              <button
                type="button"
                class="gl-link"
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
              <span v-if="t.storeId" class="badge badge-neutral">{{ stores.find((s) => s.id === t.storeId)?.name || '门店任务' }}</span>
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
            <div class="gl-task-actions">
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
                <button type="button" :disabled="grassland.loading.value" @click="closeTaskAction(t)">关闭报名</button>
                <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消任务</button>
              </template>
            </div>
          </li>
        </ul>

        <!-- 任务书 #91 W5：选中展开块（推荐列表/筛选/批量条/申请表/评分面板）抽出为
             TaskApplicantsPanel（同一 WORKBENCH_TASKS_CTX）；v-if 语义原样挂组件标签。 -->
        <TaskApplicantsPanel v-if="selectedTaskId" />
      </article>
    </div>
  </section>
</template>

<style scoped>
/* 垄眉右端的主操作（「发布新任务」）：标题/说明占左，按钮靠右（自 SFC 随迁，W4） */
.gl-zone-action { margin-left: auto; }

/* ---------- 任务列表 + 生长刻度 ---------- */
.gl-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-sm); }
.gl-list li {
  display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap;
  min-width: 0; padding: var(--space-sm); border-radius: var(--radius-md);
  background: var(--surface-furrow);
}
.gl-task-main { display: flex; align-items: center; gap: var(--space-xs); flex: 1 1 240px; min-width: 0; flex-wrap: wrap; }
.gl-task-actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
/* 任务书 #53：被驳回草稿的行下原因提示——muted 小字独占整行（li 是 wrap flex，不占满会挤进操作区） */
.gl-reject-hint { flex-basis: 100%; margin: 0; font-size: var(--text-xs); color: var(--color-text-muted); }
/* 生长刻度：五段轨（草稿/审核/招募/履约/结算），已完成=段色半透、当前=段色实心；
   段色映射状态 token：中性/警示/信息/强调/成功——结构即状态机，不新增色相 */
.gl-growth { display: inline-flex; align-items: center; gap: 3px; }
.gl-growth-seg { width: 18px; height: 3px; border-radius: var(--radius-pill); background: color-mix(in srgb, var(--color-text-muted) 30%, transparent); }
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
