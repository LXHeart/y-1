/**
 * 通知中心类型（草场 Slice 12）。对应 identity-service `NotificationController @ /api/me/notifications`。
 *
 * 后端约定（不要在前端「补全」这些语义）：
 * - `body` **刻意不含对方账号**，只描述发生了什么；标的（taskId / disputeId / 金额）在 `payload` 里，渲染由前端负责。
 * - `linkPath` 是后端模板写死的字符串（`/me/engagements` 等）。本应用没有 vue-router，故它是**意图键**而非 URL，
 *   由 {@link NOTIFICATION_LINK_TARGETS} 映射到应用内落点。
 * - 列表是 `created_at DESC` keyset 分页：翻页要把上一页末条的 `nextBefore` + `nextBeforeId` 一起回传，
 *   缺一个后端 400。`nextBefore` 为 null = 已到末页。
 */

/** 通知分类。与后端 `NotificationCategory.dbValue()` 一一对应。 */
export type NotificationCategory =
  | 'invitation'
  | 'permission'
  | 'engagement'
  | 'dispute'
  | 'wallet'

/** 后端 payload 是自由 jsonb（各模板只放自己那几个键），故用宽类型 + 取值时逐键判型。 */
export type NotificationPayload = Record<string, string | number | boolean | null>

export interface Notification {
  id: string
  category: NotificationCategory
  /** 领域事件类型（如 `DeliverableSubmitted`），用于分类之外的细粒度文案/图标。 */
  eventType: string
  title: string
  body: string
  /** null = 该通知无落点（如「邀请已被撤销」）。 */
  linkPath: string | null
  read: boolean
  payload: NotificationPayload
  createdAt: string | null
}

/** 一页通知 + 随页返回的未读数（省一次 unread-count 请求）。 */
export interface NotificationPage {
  items: Notification[]
  unreadCount: number
  /** 下一页游标（ISO-8601）；null = 已到末页。必须与 {@link nextBeforeId} 成对回传。 */
  nextBefore: string | null
  nextBeforeId: string | null
}

/** 列表查询参数。 */
export interface NotificationQuery {
  unreadOnly?: boolean
  limit?: number
  before?: string | null
  beforeId?: string | null
}

/**
 * 应用内落点：把后端的 `linkPath` 映射成「切到哪个视图 + 滚到哪个锚点」。
 *
 * 本应用无路由（`main.ts` 只 `createApp(App).mount`），导航是 `App.vue` 的 `currentView` 标签 +
 * `GrasslandWorkbench` 内的商家/推荐官切换。故落点只到「视图 + 卡片锚点」这一层，**刻意不自动切换角色视角**——
 * `switchSide()` 会重置组织/任务/争议选择，替用户切视角等于清掉他手上的活。
 */
export interface NotificationLinkTarget {
  /** 目标视图键。当前所有通知都落在草场工作台。 */
  view: 'grassland'
  /** 目标卡片的 DOM id（见 `GrasslandWorkbench.vue`）。 */
  anchor: string
  /** Dedicated task invitations may request the recommender workbench side. */
  side?: 'merchant' | 'recommender'
  /** Marketplace task carried by the notification payload; never parsed from linkPath text. */
  taskId?: string
  /** Trust dispute carried by an ops shortcut or notification payload. */
  disputeId?: string
}

export const NOTIFICATION_LINK_TARGETS: Record<string, NotificationLinkTarget> = {
  // 任务书 #49 邀请流下线：存量通知（老邀请事件）的 linkPath 兜底落到组织区——
  // 邀请入口已不存在，成员管理在 gl-organizations。
  '/me/invitations': { view: 'grassland', anchor: 'gl-organizations' },
  '/me/organizations': { view: 'grassland', anchor: 'gl-organizations' },
  '/me/engagements': { view: 'grassland', anchor: 'gl-engagements' },
  '/me/disputes': { view: 'grassland', anchor: 'gl-disputes' },
  '/me/wallet': { view: 'grassland', anchor: 'gl-wallet' },
}

/** 分类中文名 + 渲染顺序（面板分组按此顺序，不按后端返回顺序）。 */
export const NOTIFICATION_CATEGORY_ORDER: NotificationCategory[] = [
  'invitation',
  'permission',
  'engagement',
  'dispute',
  'wallet',
]

export const NOTIFICATION_CATEGORY_LABEL: Record<NotificationCategory, string> = {
  invitation: '组织邀请',
  permission: '权限审核',
  engagement: '履约',
  dispute: '争议',
  wallet: '钱包',
}
