<script setup lang="ts">
import { inject } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from '@lucide/vue'
import RecommenderRecommendations from './RecommenderRecommendations.vue'
import RecommenderReputationBadge from '../../../components/RecommenderReputationBadge.vue'
import EngagementRatingPanel from '../../../components/EngagementRatingPanel.vue'
import EngagementSubmissionPanel from '../../../components/EngagementSubmissionPanel.vue'
import { WORKBENCH_TASKS_CTX } from '../workbench-keys'
import { formatYuan } from '../../../lib/money'

/**
 * 选中任务的「推荐官排序与报名」展开块（任务书 #91 W5 自 MerchantTasksPanel 再抽出，纯搬运；
 * 原 GrasslandWorkbench.vue 选中展开位语义不变）。D-03：与父面板共用 WORKBENCH_TASKS_CTX
 * （SFC provide 的同一份六域实例），父层以 v-if="selectedTaskId" 挂本组件标签。
 */
const ctx = inject(WORKBENCH_TASKS_CTX)!

const { grassland, openAcceptedTaskCreation, openComplaint } = ctx
const {
  selectedTaskId, selectedTask, applications,
  filteredApplications, pendingFilteredApplications, allPendingSelected, batchButtonsDisabled, selectedAppIds,
  levelFilter, rateFilterPct,
  taskContextLoadingAppId,
  contestReasons, confirmedMetricInputs,
  applicantReputation, applicantProfile,
  recommendations, recommendationsLoading, invitingAccountId, confirmedAppIds,
  clearSelectedTask, loadRecommendations, inviteRecommended,
  statusLabel, toggleSelectAll, toggleSelectApp, batchAccept,
  contest, selectedCommissionLadder, confirmedMetricResult, previewCommissionCents, confirm,
  accept, reject,
  applicationPage, applicationsHasMore, applicationsLoading, loadApplicationPage,
  canAct, refreshSettlement, settlementStatusLabel, endPromotionAction,
} = ctx.engagements
const { confirmBatchReject } = ctx.drawer
</script>

<template>
  <div class="gl-apps">
    <!-- 展开块头部：给一个明确的「收起」出口——selectTask 此前只设不清，块一旦展开永远开着 -->
    <div class="gl-apps-head">
      <span class="gl-apps-caption">「{{ selectedTask?.title ?? '' }}」的推荐官排序与报名</span>
      <button
        type="button"
        class="gl-apps-collapse"
        :aria-label="`收起 ${selectedTask?.title ?? ''} 的推荐官排序与报名列表`"
        @click="clearSelectedTask"
      >收起</button>
    </div>
    <RecommenderRecommendations
      v-if="selectedTask?.status === 'published'"
      :items="recommendations?.items || []"
      :eligible-count="recommendations?.eligibleCount || 0"
      :scoring-version="recommendations?.scoringVersion || 'deterministic-v1'"
      :loading="recommendationsLoading"
      :inviting-account-id="invitingAccountId"
      @refresh="loadRecommendations()"
      @invite="inviteRecommended"
    />
    <h4>报名列表</h4>
    <div class="gl-row" aria-label="报名分页">
      <button type="button" title="上一页" aria-label="上一页报名" :disabled="applicationsLoading || applicationPage === 0" @click="loadApplicationPage(applicationPage - 1)"><ChevronLeft :size="16" /></button>
      <span>第 {{ applicationPage + 1 }} 页</span>
      <button type="button" title="下一页" aria-label="下一页报名" :disabled="applicationsLoading || !applicationsHasMore" @click="loadApplicationPage(applicationPage + 1)"><ChevronRight :size="16" /></button>
      <button type="button" title="刷新报名" aria-label="刷新报名" :disabled="applicationsLoading" @click="loadApplicationPage(applicationPage)"><RefreshCw :size="16" /></button>
      <button v-if="selectedTask?.commercePackageId" type="button" :disabled="grassland.loading.value" @click="endPromotionAction(selectedTask)">结束推广</button>
    </div>
    <p v-if="applicationsLoading" class="gl-hint" role="status">正在加载报名与结算状态</p>
    <p v-if="applications.length === 0" class="gl-empty">该任务暂无报名</p>
    <template v-else>
      <!-- 筛选：等级 ≥ / 完成率 ≥（前端对全量报名筛选，后端无搜人入口） -->
      <div class="gl-filter">
        <label>本页等级 ≥
          <select v-model="levelFilter">
            <option value="">不限</option>
            <option v-for="lv in ['Lv2','Lv3','Lv4']" :key="lv" :value="lv">{{ lv }}</option>
          </select>
        </label>
        <label>本页完成率 ≥
          <select v-model.number="rateFilterPct">
            <option :value="0">不限</option>
            <option v-for="p in [60,70,80,90]" :key="p" :value="p">{{ p }}%</option>
          </select>
        </label>
      </div>

      <p v-if="filteredApplications.length === 0" class="gl-empty">无符合筛选条件的报名</p>
      <template v-else>
        <!-- 任务书 #27：批量操作栏 -->
        <div class="gl-batch-bar">
          <label class="gl-batch-select-all">
            <input type="checkbox" aria-label="全选待处理报名" :checked="allPendingSelected" @change="toggleSelectAll" />
            全选待处理（{{ pendingFilteredApplications.length }}）
          </label>
          <button type="button" :disabled="batchButtonsDisabled" @click="batchAccept">批量接受</button>
          <button type="button" :disabled="batchButtonsDisabled" @click="confirmBatchReject">批量拒绝</button>
          <span v-if="selectedAppIds.size > 0" class="gl-hint">已选 {{ selectedAppIds.size }} 条</span>
        </div>

        <div class="gl-applications-table">
        <table class="gl-table">
          <thead><tr><th class="gl-th-check"><input type="checkbox" aria-label="全选待处理报名" :checked="allPendingSelected" @change="toggleSelectAll" /></th><th>推荐官</th><th>等级 / 声誉</th><th>状态</th><th>操作</th><th>结果</th></tr></thead>
          <tbody>
            <tr v-for="(a, index) in filteredApplications" :key="a.id">
              <td>
                <input v-if="a.status === 'pending'" type="checkbox" :aria-label="`选择第 ${index + 1} 行报名`" :checked="selectedAppIds.has(a.id)" @change="toggleSelectApp(a.id)" />
              </td>
              <td><code>{{ a.recommenderAccountId.slice(0, 8) }}…</code></td>
              <td>
                <RecommenderReputationBadge
                  compact
                  :reputation="applicantReputation[a.recommenderAccountId] || null"
                  :profile="applicantProfile[a.recommenderAccountId] || null"
                />
              </td>
              <td>{{ statusLabel(a.status) }}</td>
              <td class="gl-actions">
                <button v-if="canAct(a, 'accept')" type="button" :disabled="grassland.loading.value" @click="accept(a)">接受</button>
                <button v-if="canAct(a, 'reject')" type="button" :disabled="grassland.loading.value" @click="reject(a)">拒绝</button>
                <template v-if="a.status === 'accepted' && !selectedTask?.commercePackageId">
                  <button type="button" :disabled="Boolean(taskContextLoadingAppId)" @click="openAcceptedTaskCreation(a, selectedTask)">
                    {{ taskContextLoadingAppId === a.id ? '加载上下文…' : '围绕任务创作' }}
                  </button>
                  <!-- 任务书 #25：阶梯任务确认履约须申报实际指标，实时预览预计结算 -->
                  <template v-if="canAct(a, 'confirm_after_submission') && selectedCommissionLadder()">
                    <input
                      v-model="confirmedMetricInputs[a.id]"
                      type="number"
                      min="0"
                      step="1"
                      class="gl-metric-input"
                      :aria-label="`第 ${index + 1} 行实际指标（${selectedCommissionLadder()?.metricKey ?? ''}）`"
                      placeholder="实际指标"
                    />
                    <span class="gl-hint">预计结算 <span class="gl-num">{{ formatYuan(previewCommissionCents(a.id)) }}</span></span>
                    <span v-if="confirmedMetricResult(a.id).error" class="gl-hint gl-metric-error">
                      {{ confirmedMetricResult(a.id).error }}
                    </span>
                  </template>
                  <button
                    v-if="canAct(a, 'confirm_after_submission')"
                    type="button"
                    :disabled="grassland.loading.value
                      || (selectedCommissionLadder() != null && confirmedMetricResult(a.id).error != null)"
                    @click="confirm(a)"
                  >确认履约</button>
                  <input
                    v-if="canAct(a, 'contest')"
                    v-model="contestReasons[a.id]"
                    class="gl-contest-reason"
                    :aria-label="`第 ${index + 1} 行拒绝理由`"
                    placeholder="拒绝理由（系统核实通过后转客服）"
                  />
                  <button
                    v-if="canAct(a, 'contest')"
                    type="button"
                    :disabled="grassland.loading.value || !contestReasons[a.id]?.trim()"
                    @click="contest(a)"
                  >拒绝并转客服</button>
                </template>
                <!-- 任务书 #74：场景化举报——对象是这名推荐官（user），全状态行可见 -->
                <button
                  type="button"
                  :aria-label="`举报推荐官 ${a.recommenderAccountId.slice(0, 8)}`"
                  @click="openComplaint({
                    targetType: 'user',
                    targetId: a.recommenderAccountId,
                    targetSummary: `推荐官 ${a.recommenderAccountId.slice(0, 8)}…`,
                  })"
                >举报</button>
              </td>
              <td class="gl-outcome gl-num">
                <span v-if="selectedTask?.commercePackageId && a.status === 'accepted'">套餐推广中，佣金按订单结算</span>
                <span v-else>{{ settlementStatusLabel(a) }}</span>
                <button type="button" title="刷新结算状态" :aria-label="`刷新第 ${index + 1} 行结算状态`" :disabled="grassland.loading.value" @click="refreshSettlement(a)"><RefreshCw :size="16" /></button>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
      </template>

      <!-- 交付物 + 评分：确认履约前必须有一份待核验的（后端 409 守卫）；评分须先确认履约。 -->
      <template v-for="a in applications" :key="`sub-${a.id}`">
        <div v-if="a.status === 'accepted' && !selectedTask?.commercePackageId" class="gl-sub-block">
          <h5>
            履约交付物 · <code>{{ a.recommenderAccountId.slice(0, 8) }}…</code>
            <!-- 任务书 #74：场景化举报——对象是这份交付物（submission=applicationId） -->
            <button
              type="button"
              :aria-label="`举报履约交付物 ${a.id.slice(0, 8)}`"
              @click="openComplaint({
                targetType: 'submission',
                targetId: a.id,
                targetSummary: `任务「${selectedTask?.title ?? ''}」的履约交付物（${a.recommenderAccountId.slice(0, 8)}…）`,
              })"
            >举报</button>
          </h5>
          <EngagementSubmissionPanel
            :task-id="selectedTaskId" :application-id="a.id" role="merchant"
            :task-content-form="selectedTask?.contentForm ?? null"
            :interaction-action-type="selectedTask?.requirements?.interaction?.actionType ?? null"
          />
          <EngagementRatingPanel
            :task-id="selectedTaskId" :application-id="a.id" role="merchant"
            :can-rate="confirmedAppIds.has(a.id)"
          />
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.gl-applications-table { max-width: 100%; overflow-x: auto; }
.gl-applications-table .gl-table { min-width: 56rem; }
.gl-applications-table button { white-space: nowrap; }
.gl-outcome { font-size: var(--text-xs); color: var(--color-text-secondary); white-space: normal; overflow-wrap: anywhere; }
.gl-contest-reason, .gl-metric-input {
  min-height: 30px; padding: 4px var(--space-xs);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-xs);
}
.gl-contest-reason { min-width: 210px; flex: 1; }
.gl-metric-input { width: 110px; }
.gl-metric-error { color: var(--color-danger); white-space: nowrap; }

/* ---------- 筛选 / 批量 ---------- */
.gl-filter { display: flex; gap: var(--space-md); align-items: center; flex-wrap: wrap; font-size: var(--text-sm); }
.gl-filter label { display: flex; align-items: center; gap: 6px; color: var(--color-text-secondary); }
.gl-filter select {
  min-height: 30px; padding: 4px var(--space-xs);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-sm);
}
.gl-batch-bar { display: flex; gap: var(--space-xs); align-items: center; flex-wrap: wrap; padding: var(--space-xs) 0; font-size: var(--text-sm); }
.gl-batch-select-all { display: flex; align-items: center; gap: 6px; font-size: var(--text-sm); cursor: pointer; }
.gl-th-check { width: 32px; }

.gl-sub-block { margin-top: var(--space-sm); }
.gl-sub-block h5 { margin: 0; font-size: var(--text-xs); font-weight: 600; color: var(--color-text-muted); letter-spacing: 0.04em; }

/* 展开块头部：选中任务的排序+报名块给出明确「收起」出口——此前一旦展开永远开着 */
.gl-apps-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.gl-apps-caption { font-size: var(--text-sm); font-weight: 600; color: var(--color-text-secondary); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
