/**
 * 知乎专属创作台（zhihu-studio）共享契约。
 *
 * 架构对位 xhs-studio（已验证模式，test-artifacts/zhihu-studio/plan.md）：
 * 四步为「本地用户步」——current 只被用户动作（go）与终态跳转（completed→publish）
 * 更新；引擎 stage 推进不自动切换 UI 步。并行组件经 useZhihuStudioContext()
 * 只读消费，禁止回写共享 refs。
 *
 * 与 xhs 的两点差异（方案 §2 新建①）：
 * - 步映射含 images：知乎不跳配图（imagesStageSkipped 恒 false，回答/文章都走
 *   既有 ArticleImageSlots 配图链），images/completed → publish。
 * - 内容形态（回答/文章）是横切状态：由顶栏 seg 切换（confirm 语义沿用
 *   ZhihuModeToggle），切换清空产物后回 pick 步。
 */
import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { useArticleCreation } from '../../composables/useArticleCreation'
import type { useArticleWorkspace } from '../article/composables/useArticleWorkspace'
import type { useArticleFormatRule } from '../article/composables/useArticleFormatRule'
import type { useZhihuStudioTuning } from './composables/useZhihuStudioTuning'

/** 四步流程：选题 → 创作（候选/稿纸正文/话题/合规）→ 校对 → 配图发布。 */
export type ZhihuStudioStep = 'pick' | 'generate' | 'proof' | 'publish'

/** 内容形态：回答挂在问题下，文章独立成篇（engine.contentMode 的视图口径）。 */
export type ZhihuContentMode = 'answer' | 'article'

/** 步骤状态机契约（useZhihuStudioSteps 返回形）。 */
export interface ZhihuStudioStepsApi {
  /** 用户当前查看的步（本地用户步，不随引擎 stage 自动推进）。 */
  current: Ref<ZhihuStudioStep>
  /** 最深可见步，按数据派生（titles⇒generate；content⇒proof；completed⇒publish）。 */
  reached: ComputedRef<ZhihuStudioStep>
  /** 超过 reached 的步返回 false（步骤条渲染 disabled）。go('pick') 恒允许。 */
  canGo: (step: ZhihuStudioStep) => boolean
  /** 切步；内部完成引擎协同（enterCheck / check→content 回编辑工位）与 ?step= URL 同步。 */
  go: (step: ZhihuStudioStep) => void
}

/** provide/inject 的视图上下文（引擎 + 持久化 + 步骤 + 派生助手）。 */
export interface ZhihuStudioContext {
  engine: ReturnType<typeof useArticleCreation>
  /** 含 deliveryValue/updateDelivery/flush/draftId/draftVersion/saveState 等。 */
  autosave: ReturnType<typeof useArticleWorkspace>
  /** formatRule/formatRuleSummary/formatIssues/titleOverLimit。 */
  format: ReturnType<typeof useArticleFormatRule>
  steps: ZhihuStudioStepsApi
  tuning: ReturnType<typeof useZhihuStudioTuning>
  /** 回答模式视图口径（engine.contentMode 直读；切换经 requestContentMode 带 confirm）。 */
  answerMode: ComputedRef<boolean>
  /** 切内容形态：有产物先 confirm（两套 prompt 产物不可混用），确认后清空回 pick 步。 */
  requestContentMode: (mode: ZhihuContentMode) => void
  /** flush 成功且 draftId 存在 → draftVersion；否则 false（DeliveryPanel before-export）。 */
  beforeExport: () => Promise<number | false>
  platformLocked: ComputedRef<boolean>
  fromCreationCenter: ComputedRef<boolean>
  toast: Ref<string>
  notify: (message: string) => void
  goCreationCenter: () => void
  /** 确认后 reset({keepPlatform:true}) + autosave.startNew（清空回选题步）。 */
  resetSession: () => Promise<void>
}

export const ZHIHU_STUDIO_CONTEXT_KEY: InjectionKey<ZhihuStudioContext> = Symbol('zhihu-studio-context')

/** 四步固定文案（回答/文章两形态共用步骤骨架；首步文案按形态分叉在 StepsBar 内派生）。 */
export const ZHIHU_STUDIO_STEPS: ReadonlyArray<{ key: ZhihuStudioStep; label: string }> = [
  { key: 'pick', label: '选题' },
  { key: 'generate', label: '创作' },
  { key: 'proof', label: '校对' },
  { key: 'publish', label: '发布' },
]
