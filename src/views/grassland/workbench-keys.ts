import type { InjectionKey, Ref } from 'vue'
import type { useGrassland } from '../../composables/useGrassland'
import type { ComplaintTargetType } from '../../composables/useComplaints'
import type {
  MyApplication,
  OrgBrandSummary,
  OrgKybSummary,
  OrgPermissionSummary,
  OrgTeamSummary,
} from '../../types/grassland'
import type { useWorkbenchEngagements } from './composables/useWorkbenchEngagements'
import type { useWorkbenchSession } from './composables/useWorkbenchSession'
import type { useTaskFormDrawer } from './composables/useTaskFormDrawer'
import type { useWorkbenchNavigation } from './composables/useWorkbenchNavigation'
import type { useWorkbenchMyTasks } from './composables/useWorkbenchMyTasks'
import type { useWorkbenchDisputes } from './composables/useWorkbenchDisputes'
import type { OrgSection } from './components/OrgOverviewGrid.vue'
import type { FinanceSection, SubTabId } from './workbench-tabs'

/**
 * 工作台面板 provide/inject 上下文键（任务书 #91 W4，D-03：面板注入六域实例与关键 refs，
 * 不走 20+ props 钻透；**子组件禁止自行 useGrassland()**——非单例，error/loading 会分叉）。
 *
 * v1.1（2026-09-07 主程拍板，W5 增补）：org/finance 页签同步面板化（MerchantOrgPanel /
 * MerchantFinancePanel），session 升格为整实例注入，并新增 subTab/orgSection/financeSection
 * （SFC 持有的分节 refs——drawer 与 navigation composable 的既有 deps，不动）与四张摘要 refs。
 */

/**
 * 商家侧面板（MerchantTasksPanel，W4；TaskApplicantsPanel/MerchantOrgPanel/MerchantFinancePanel）
 * 注入上下文。SFC 在 setup 中 provide 整包；面板按需解构成平铺绑定，模板表达式与迁出前逐字符一致。
 */
export interface WorkbenchTasksContext {
  grassland: ReturnType<typeof useGrassland>
  router: { push: (to: string) => unknown }
  session: ReturnType<typeof useWorkbenchSession>
  engagements: ReturnType<typeof useWorkbenchEngagements>
  drawer: ReturnType<typeof useTaskFormDrawer>
  openAcceptedTaskCreation: ReturnType<typeof useWorkbenchNavigation>['openAcceptedTaskCreation']
  openComplaint: (target: { targetType: ComplaintTargetType; targetId: string; targetSummary: string }) => void
  subTab: Ref<SubTabId>
  orgSection: Ref<OrgSection>
  financeSection: Ref<FinanceSection>
  summaries: {
    team: Ref<OrgTeamSummary | null>
    brand: Ref<OrgBrandSummary | null>
    kyb: Ref<OrgKybSummary | null>
    permission: Ref<OrgPermissionSummary | null>
  }
}
export const WORKBENCH_TASKS_CTX: InjectionKey<WorkbenchTasksContext> = Symbol('workbench-tasks-ctx')

/**
 * 推荐官「我的任务」面板（RecommenderEngagementsPanel，W5）注入上下文。
 */
export interface WorkbenchEngagementsContext {
  grassland: ReturnType<typeof useGrassland>
  router: { push: (to: string) => unknown }
  myTasks: ReturnType<typeof useWorkbenchMyTasks>
  disputes: ReturnType<typeof useWorkbenchDisputes>
  drawer: ReturnType<typeof useTaskFormDrawer>
  navigation: ReturnType<typeof useWorkbenchNavigation>
  engagements: ReturnType<typeof useWorkbenchEngagements>
  myTaskBadge: (row: MyApplication) => { label: string; cls: string }
}
export const WORKBENCH_ENGAGEMENTS_CTX: InjectionKey<WorkbenchEngagementsContext> = Symbol('workbench-engagements-ctx')
