import type { OrgSection } from './components/OrgOverviewGrid.vue'

/**
 * 工作台子页签 / 二级分节 / 生长刻度常量（任务书 #91 W4 自 GrasslandWorkbench.vue
 * 纯数据外移；注释与值原样保留）。
 */

// 工作台子页签（任务书 #73 收敛）：两侧各留纯业务垄；账号级内容（主页与分享/账号与合规）
// 收进共享「个人设置」弹窗（两侧头部同一入口，组件 PersonalSettingsModal），页签不再承载。
// v-show 常驻 DOM（锚点滚动与既有断言不破坏）
// 任务书 #78 卡 A（D2）：`ai` 页签整体迁入 AI 创作中心「AI 与治理」板块，商家侧剩三签；
// `?wtab=ai` 深链因不在 activeTabs 自动回落 tasks。
export type SubTabId = 'tasks' | 'org' | 'finance' | 'hall' | 'engagements' | 'earnings'
export interface SubTab { id: SubTabId; label: string }
export const MERCHANT_TABS: readonly SubTab[] = [
  { id: 'tasks', label: '任务与报名' },
  { id: 'org', label: '商家主体与门店' },
  { id: 'finance', label: '资金与经营' },
]
export const RECOMMENDER_TABS: readonly SubTab[] = [
  { id: 'hall', label: '任务大厅' },
  // 任务书 #77 卡 D：「我的履约」改造为「我的任务」（全量 my-applications + 四态筛选）；
  // 用户拍板不新增页签，subTab id `engagements` 保留（URL ?wtab= 深链与锚点映射不破坏）。
  { id: 'engagements', label: '我的任务' },
  { id: 'earnings', label: '收益与结算' },
]

/**
 * 「商家主体与门店」页签内的二级分节。
 *
 * 原先这一屏是 5 张全宽卡竖着堆（子组件合计 2600+ 行），认证状态与额度余量埋在第 2、
 * 第 5 张卡内部要滚屏才看到。改为「身份条 + 概览 + 左侧竖栏分节」：常驻页眉答「我是谁、
 * 现在什么状态」，概览答「哪项缺、去哪补」，五个域各自独立成节，首屏高度从五卡叠加降到一节。
 */
export const ORG_SECTIONS: readonly { id: OrgSection; label: string }[] = [
  { id: 'overview', label: '概览' },
  { id: 'team', label: '成员与门店' },
  { id: 'brand', label: '品牌资料' },
  { id: 'kyb', label: '认证资料' },
  { id: 'permission', label: '权限与额度' },
]

/**
 * 「资金与经营」页签内的二级分节（任务书 #78 卡 J，镜像 ORG_SECTIONS 左栏模式）：
 * 原先四块全宽卡竖着堆（钱包/账单/套餐/经营分析），经营分析埋在最底要滚屏才看到。
 */
export type FinanceSection = 'account' | 'bill' | 'commerce' | 'analytics'
export const FINANCE_SECTIONS: readonly { id: FinanceSection; label: string }[] = [
  { id: 'account', label: '资金账户' },
  { id: 'bill', label: '月度账单' },
  { id: 'commerce', label: '到店套餐与核销' },
  { id: 'analytics', label: '经营分析' },
]

/** 生长刻度：任务生命周期五段（草稿 → 审核 → 招募 → 履约 → 结算）。 */
export const TASK_STAGES = ['草稿', '审核', '招募', '履约', '结算'] as const
