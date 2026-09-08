<script setup lang="ts">
import { inject } from 'vue'
import { WORKBENCH_ENGAGEMENTS_CTX } from '../workbench-keys'
import { MY_TASK_FILTERS, MY_TASK_LIMIT_OPTIONS, type MyTaskFilterId } from '../composables/useWorkbenchMyTasks'
import { platformDisplayLabel } from '../../../config/ai-platform-capabilities'
import { formatYuan } from '../../../lib/money'
import EngagementNextAction from './EngagementNextAction.vue'
import EngagementExitActions from './EngagementExitActions.vue'

/**
 * 推荐官「我的任务」面板（任务书 #91 W5 自 GrasslandWorkbench.vue 模板整段迁入，纯搬运）。
 * D-03：经 WORKBENCH_ENGAGEMENTS_CTX 注入六域实例与关键 refs；v-show 由父层挂在本组件标签上。
 */
const ctx = inject(WORKBENCH_ENGAGEMENTS_CTX)!

const { grassland, router, myTaskBadge } = ctx
const { deferredDisputeRequestId } = ctx.disputes
const { openTaskDetail, confirmWithdrawMyApplication } = ctx.drawer
const { openMyTaskCreation } = ctx.navigation
const { taskContextLoadingAppId } = ctx.engagements
const {
  items: myTaskItems, loading: myTasksLoading, filter: myTaskFilter, limit: myTaskLimit,
  page: myTaskPage, hasMore: myTaskHasMore,
  setFilter: setMyTaskFilter, setLimit: setMyTaskLimit,
  loadPrev: loadMyTasksPrev, loadNext: loadMyTasksNext,
  groupedItems, settlements, load: reloadMyTasks,
} = ctx.myTasks
</script>

<template>
  <section class="gl-zone" aria-label="我的任务">
    <div class="gl-zone-head">
      <h3 class="gl-zone-title">我的任务</h3>
      <p class="gl-zone-note">报名、履约与完成记录——按状态筛选我的全部任务</p>
      <!-- 2026-09-04 反馈 5：审判看板撤出工作台后，当事方争议的常驻入口 -->
      <button type="button" class="gl-zone-action" @click="router.push('/me/disputes')">我的争议 →</button>
    </div>
    <div class="gl-zone-body">
      <article id="gl-engagements" class="gl-tile gl-tile-wide">
        <h3>我的任务</h3>
        <p class="gl-hint">对已接受的履约，如商家未按约定处理，可在任务详情弹窗开启争议——结算将被暂停直至审判终局。异议须在核实结果公布后 48 小时内提出。</p>
        <!-- deferred 客服案升格等待提示（升格后自动跳案件详情页） -->
        <p v-if="deferredDisputeRequestId" class="gl-hint" data-testid="deferred-dispute-status">
          异议已记录，客服案终局后自动开普通争议；系统将自动进入七官审判流程。
        </p>

        <div class="gl-row">
          <label>状态
            <select
              :value="myTaskFilter"
              aria-label="我的任务状态筛选"
              name="my-task-filter"
              :disabled="myTasksLoading"
              @change="setMyTaskFilter(($event.target as HTMLSelectElement).value as MyTaskFilterId)"
            >
              <option v-for="f in MY_TASK_FILTERS" :key="f.id" :value="f.id">{{ f.label }}</option>
            </select>
          </label>
        </div>

        <p v-if="myTaskItems.length === 0 && !myTasksLoading" class="gl-empty">
          {{ myTaskFilter === 'all' ? '还没有报名过任务——去任务大厅看看吧' : '该筛选下暂无任务' }}
        </p>
        <div v-else class="gl-my-tasks-table">
        <table class="gl-table">
          <thead><tr><th>任务</th><th>门店</th><th>平台</th><th>赏金</th><th>状态</th><th>下一步与截止</th><th>操作</th></tr></thead>
          <tbody v-for="group in groupedItems" :key="group.label">
            <tr class="gl-group-heading"><th colspan="7" scope="rowgroup">{{ group.label }} · {{ group.items.length }}</th></tr>
            <tr v-for="row in group.items" :key="row.applicationId">
              <td>
                <button type="button" class="gl-link" aria-haspopup="dialog"
                        @click="openTaskDetail(row.taskId, { from: 'my-tasks' })">{{ row.taskTitle || '未命名任务' }}</button>
              </td>
              <td>{{ [row.storeName, row.city].filter(Boolean).join(' · ') || '—' }}</td>
              <!-- 卡 C/D：平台列中文映射，与大厅同源 -->
              <td>{{ platformDisplayLabel(row.platform) || '—' }}</td>
              <td class="gl-num">{{ row.bountyCents ? formatYuan(row.bountyCents) : '—' }}</td>
              <td><span class="badge" :class="myTaskBadge(row).cls">{{ myTaskBadge(row).label }}</span></td>
              <td><EngagementNextAction :state="settlements[row.applicationId]" /></td>
              <td>
                <!-- 任务书 #97：协商退出动作区（发起/撤回/确认/拒绝，服务端动作契约驱动） -->
                <EngagementExitActions
                  :client="grassland" :task-id="row.taskId" :application-id="row.applicationId"
                  :settlement="settlements[row.applicationId]" :application-status="row.applicationStatus"
                  @refresh="reloadMyTasks(false)"
                />
                <!-- pending → 取消报名（口径同大厅）；accepted 未结算 → 开始创作；其余 → 详情
                     （终态不可重报——V2 UNIQUE 阻断，操作列只给详情） -->
                <button
                  v-if="settlements[row.applicationId]?.allowedActions.includes('withdraw')"
                  type="button"
                  :disabled="grassland.loading.value"
                  @click="confirmWithdrawMyApplication(row)"
                >取消报名</button>
                <button
                  v-else-if="settlements[row.applicationId]?.allowedActions.includes('submit') && ['delivery', 'revision'].includes(settlements[row.applicationId]?.nextActionGroup || '')"
                  type="button"
                  :disabled="grassland.loading.value || Boolean(taskContextLoadingAppId)"
                  @click="openMyTaskCreation(row)"
                >
                  {{ taskContextLoadingAppId === row.applicationId ? '加载上下文…' : '开始创作' }}
                </button>
                <button v-else type="button" @click="openTaskDetail(row.taskId, { from: 'my-tasks' })">{{ row.commercePackageId ? '查看推广' : row.applicationStatus === 'reconsent' ? '确认新条款' : '详情' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
        </div>

        <nav v-if="myTaskItems.length > 0" class="gl-row gl-feed-pager" aria-label="我的任务分页">
          <button type="button" :disabled="myTasksLoading || myTaskPage === 0" @click="loadMyTasksPrev()">上一页</button>
          <span class="gl-feed-page">第 {{ myTaskPage + 1 }} 页</span>
          <button type="button" :disabled="myTasksLoading || !myTaskHasMore" @click="loadMyTasksNext()">下一页</button>
          <label class="gl-feed-limit">每页
            <select
              :value="myTaskLimit"
              aria-label="每页条数"
              name="my-task-limit"
              :disabled="myTasksLoading"
              @change="setMyTaskLimit(Number(($event.target as HTMLSelectElement).value))"
            >
              <option v-for="option in MY_TASK_LIMIT_OPTIONS" :key="option" :value="option">{{ option }} 条</option>
            </select>
          </label>
        </nav>
      </article>
    </div>
  </section>
</template>

<style scoped>
/* 垄眉右端的主操作（「我的争议」）：标题/说明占左，按钮靠右（自 SFC 随迁，W5）。
   gl-feed-pager/gl-feed-page/gl-feed-limit 在 SFC 作用域本无规则（样式在 RecommenderTaskHall
   scoped 内，不穿透），随迁为空即渲染不变。 */
.gl-zone-action { margin-left: auto; }
.gl-my-tasks-table { width: 100%; overflow-x: auto; }
.gl-my-tasks-table .gl-table { min-width: 48rem; }
.gl-my-tasks-table button { white-space: nowrap; }
.gl-group-heading { color: var(--color-text-secondary); background: var(--surface-furrow); }
</style>
