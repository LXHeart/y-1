import type { Component, InjectionKey } from 'vue'
import BgmTracksAdminPanel from '../../components/BgmTracksAdminPanel.vue'
import HomepageHotConfigPanel from '../../components/HomepageHotConfigPanel.vue'
import PermissionReviewPanel from '../../components/PermissionReviewPanel.vue'
import CommerceAdminPanel from '../../components/CommerceAdminPanel.vue'
import JudgeAdminPanel from '../../components/JudgeAdminPanel.vue'
import ReputationAdminPanel from '../../components/ReputationAdminPanel.vue'
import RiskAdminPanel from '../../components/RiskAdminPanel.vue'
import CreditsPackagesPanel from '../../components/CreditsPackagesPanel.vue'
import BusinessAnalyticsPanel from '../../components/BusinessAnalyticsPanel.vue'
import UnifiedAuditPanel from '../../components/UnifiedAuditPanel.vue'
import CreationSkillsAdminPanel from './components/CreationSkillsAdminPanel.vue'
import HumanizeSkillsAdminPanel from './components/HumanizeSkillsAdminPanel.vue'
import VideoTaskMonitorPanel from './components/VideoTaskMonitorPanel.vue'
import PublicAssetsAdminPanel from './components/PublicAssetsAdminPanel.vue'
import OrganizationRenameAdminPanel from './components/OrganizationRenameAdminPanel.vue'
import OrganizationPrefixAdminPanel from './components/OrganizationPrefixAdminPanel.vue'
import StoreMediaModerationAdminPanel from './components/StoreMediaModerationAdminPanel.vue'
import AdminUsersPanel from './tabs/AdminUsersPanel.vue'
import AdminKybPanel from './tabs/AdminKybPanel.vue'
import AdminReviewTasksPanel from './tabs/AdminReviewTasksPanel.vue'
import AdminFinancePanel from './tabs/AdminFinancePanel.vue'
import AdminRecommendersPanel from './tabs/AdminRecommendersPanel.vue'
import AdminAiModelsPanel from './tabs/AdminAiModelsPanel.vue'
import OpsCommercePanel from './tabs/OpsCommercePanel.vue'

/**
 * 治理台页签 registry 纯数据化（任务书 #91 A1，自 AdminView.vue 迁出；D-05：纯静态数据 +
 * 静态 import，无闭包 → 无 TDZ 约束）。badge/onActivate 字段已删除——AdminView 持本地
 * BADGE_SOURCES/ACTIVATION_HOOKS 映射（行为与原闭包逐点等价），A2–A4 随面板化逐项迁走。
 *
 * 任务书 #78 卡 D（D4）：页签单源 TAB_REGISTRY。旧 TAB_ROLES 表与 activeSection 联合类型由
 * registry 派生。页签可见角色集（任务书 #72 卡C D4 口径保留）：未列 roles 的页签默认
 * platform_admin 专属；users=三查看角色（platform_admin/customer_service/risk——content_reviewer
 * 无 /api/admin/users 读取权限，维持既有不可见）；公共素材/门店媒体/账号前缀=content_reviewer
 * 既有可见集合原样保留。
 *
 * 任务书 #95 D95-01（前端镜像后端现状，服务端 requireRole 零改动）：财务对账=platform_admin/finance
 * （镜像 LedgerAdminController requireRole(FINANCE)）；推荐官认证=platform_admin/merchant_reviewer/
 * content_reviewer（镜像 RecommenderVerificationController requireRole(MERCHANT_REVIEWER,
 * CONTENT_REVIEWER)，content_reviewer 补见认证页签）；风险调查=platform_admin/risk（镜像 RiskController）。
 */

export const ADMIN_TAB_KEYS = [
  'kyb', 'org-renames', 'recommenders', 'tasks', 'judges', 'permission-review', 'store-media', 'public-assets',
  'users', 'org-prefix', 'reputation',
  'finance', 'credits-packages', 'commerce', 'ops-commerce', 'analytics',
  'ai-models', 'creation-skills', 'humanize-skills', 'bgm-library', 'homepage-hot', 'video-monitor',
  'risk', 'audit',
] as const

export type AdminSection = typeof ADMIN_TAB_KEYS[number]

/** 五组定案（任务书 #78 卡 D 决策表）。组可见性 = 组内页签角色并集（visibleGroups 派生）。 */
export const ADMIN_GROUPS = [
  { id: 'review', label: '审核队列' },
  { id: 'users-org', label: '用户与主体' },
  { id: 'finance', label: '交易与财务' },
  { id: 'content-ai', label: '内容与 AI' },
  { id: 'risk-audit', label: '风控与审计' },
] as const
export type AdminGroupId = typeof ADMIN_GROUPS[number]['id']

export interface AdminTabDef {
  key: AdminSection
  label: string
  group: AdminGroupId
  /** 未列 = platform_admin 专属（DEFAULT_TAB_ROLES）。 */
  roles?: readonly string[]
  /** 单组件页签的渲染体；users/kyb/recommenders/tasks/finance/ai-models 六个内联复合面板不设。 */
  component?: Component
  componentProps?: Record<string, unknown>
}

/**
 * TAB_REGISTRY 单源（任务书 #78 卡 D）：23 页签的 key/显示名/分组/角色/渲染组件。
 * 顺序即组内显示顺序。
 */
export const TAB_REGISTRY: readonly AdminTabDef[] = [
  // ---- 审核队列 review ----
  // 任务书 #91 A2：users/kyb 内联复合页面板化（tabs/AdminUsersPanel/AdminKybPanel），走统一渲染器。
  { key: 'kyb', label: 'KYB 审核', group: 'review', component: AdminKybPanel },
  { key: 'org-renames', label: '主体更名', group: 'review', component: OrganizationRenameAdminPanel },
  {
    key: 'recommenders', label: '推荐官认证', group: 'review',
    roles: ['platform_admin', 'merchant_reviewer', 'content_reviewer'], component: AdminRecommendersPanel,
  },
  { key: 'tasks', label: '任务审核', group: 'review', component: AdminReviewTasksPanel },
  { key: 'judges', label: '审判官准入', group: 'review', component: JudgeAdminPanel },
  // 2026-09-04：平台侧商家权限升级审核队列（原用户端工作台底部挂载，迁治理台归口）
  { key: 'permission-review', label: '权限审核', group: 'review', component: PermissionReviewPanel },
  {
    key: 'store-media', label: '门店媒体', group: 'review',
    roles: ['platform_admin', 'content_reviewer'], component: StoreMediaModerationAdminPanel,
  },
  {
    key: 'public-assets', label: '公共素材', group: 'review',
    roles: ['platform_admin', 'content_reviewer'], component: PublicAssetsAdminPanel,
  },
  // ---- 用户与主体 users-org ----
  { key: 'users', label: '用户管理', group: 'users-org', roles: ['platform_admin', 'customer_service', 'risk'], component: AdminUsersPanel },
  // 任务书 #51：成员账号前缀改名（商家侧入口已下线，这里是全平台唯一入口）
  {
    key: 'org-prefix', label: '账号前缀', group: 'users-org',
    roles: ['platform_admin', 'content_reviewer'], component: OrganizationPrefixAdminPanel,
  },
  { key: 'reputation', label: '等级与权益', group: 'users-org', component: ReputationAdminPanel },
  // ---- 交易与财务 finance ----
  {
    key: 'finance', label: '财务对账', group: 'finance',
    roles: ['platform_admin', 'finance'], component: AdminFinancePanel,
  },
  { key: 'credits-packages', label: '积分套餐', group: 'finance', component: CreditsPackagesPanel },
  { key: 'commerce', label: '订单核销', group: 'finance', component: CommerceAdminPanel },
  {
    key: 'ops-commerce', label: '经营看板', group: 'finance',
    roles: ['platform_admin', 'customer_service', 'finance', 'risk'], component: OpsCommercePanel,
  },
  { key: 'analytics', label: '经营分析', group: 'finance', component: BusinessAnalyticsPanel,
    componentProps: { admin: true } },
  // ---- 内容与 AI content-ai ----
  { key: 'ai-models', label: 'AI 模型', group: 'content-ai', component: AdminAiModelsPanel },
  { key: 'creation-skills', label: '创作风格', group: 'content-ai', component: CreationSkillsAdminPanel },
  { key: 'humanize-skills', label: '去AI味', group: 'content-ai', component: HumanizeSkillsAdminPanel },
  { key: 'bgm-library', label: 'BGM 曲库', group: 'content-ai', component: BgmTracksAdminPanel },
  { key: 'homepage-hot', label: '首页热点', group: 'content-ai', component: HomepageHotConfigPanel },
  { key: 'video-monitor', label: '视频任务', group: 'content-ai', component: VideoTaskMonitorPanel },
  // ---- 风控与审计 risk-audit ----
  {
    key: 'risk', label: '风险调查', group: 'risk-audit',
    roles: ['platform_admin', 'risk'], component: RiskAdminPanel,
  },
  { key: 'audit', label: '统一审计', group: 'risk-audit', component: UnifiedAuditPanel },
]

export const DEFAULT_TAB_ROLES: readonly string[] = ['platform_admin']
/** 页签可见角色集由 registry 派生（任务书 #78 卡 D）。 */
export const TAB_ROLES: Partial<Record<AdminSection, readonly string[]>> = Object.fromEntries(
  TAB_REGISTRY.filter((tab) => tab.roles).map((tab) => [tab.key, tab.roles!]),
)

// 卡 D：registry 键覆盖校验——漏登记/键名拼错启动即报错，而非静默落空面板（旧 v-else 兜底的根治）。
{
  const registered = new Set(TAB_REGISTRY.map((tab) => tab.key))
  const missing = ADMIN_TAB_KEYS.filter((key) => !registered.has(key))
  if (missing.length > 0) {
    throw new Error(`TAB_REGISTRY 缺少页签登记: ${missing.join(', ')}`)
  }
}

/**
 * 任务书 #91 A2（D-04/D-05）：面板 → AdminView 的徽标控制器注册桥。
 * 面板 setup 期注册 `() => total.value` 徽标源（渲染期求值——AdminView 的 render effect
 * 会穿透闭包追踪面板内的 reactive ref，等价原父级 refs 常驻语义）；卸载时注销。
 */
export interface AdminBadgeBridge {
  register: (key: AdminSection, source: () => string | number) => void
  unregister: (key: AdminSection) => void
}
export const ADMIN_BADGE_BRIDGE: InjectionKey<AdminBadgeBridge> = Symbol('admin-badge-bridge')
