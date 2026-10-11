/**
 * 小红书专属创作台（xhs-studio）共享契约（阶段 0 一次冻结，方案 §4.0/§4.1）。
 *
 * 四步为「本地用户步」（方案 §4.1【F3】）：current 只被用户动作（go）与终态跳转
 * （completed→publish）更新；引擎 stage 推进不自动切换 UI 步。并行组件经
 * useXhsStudioContext() 只读消费，禁止回写共享 refs。
 */
import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { useArticleCreation } from '../../composables/useArticleCreation'
import type { useCardSeries } from '../../composables/useCardSeries'
import type { useArticleWorkspace } from '../article/composables/useArticleWorkspace'
import type { useArticleFormatRule } from '../article/composables/useArticleFormatRule'
import type { useXhsStudioTuning } from './composables/useXhsStudioTuning'

/** 四步流程：选题 → 生成（标题/正文/话题/配图/合规）→ 校对 → 发布导出。 */
export type XhsStudioStep = 'pick' | 'generate' | 'proof' | 'publish'

/** 步骤状态机契约（useXhsStudioSteps 返回形）。 */
export interface XhsStudioStepsApi {
  /** 用户当前查看的步（本地用户步，不随引擎 stage 自动推进）。 */
  current: Ref<XhsStudioStep>
  /** 最深可见步，按数据派生（titles⇒generate；content⇒proof；completed⇒publish）。 */
  reached: ComputedRef<XhsStudioStep>
  /** 超过 reached 的步返回 false（步骤条渲染 disabled）。go('pick') 恒允许。 */
  canGo: (step: XhsStudioStep) => boolean
  /** 切步；内部完成引擎协同（enterCheck / check→content 回编辑工位）与 ?step= URL 同步。 */
  go: (step: XhsStudioStep) => void
}

/** provide/inject 的视图上下文（引擎 + 持久化 + 步骤 + 派生助手）。 */
export interface XhsStudioContext {
  engine: ReturnType<typeof useArticleCreation>
  cards: ReturnType<typeof useCardSeries>
  /** 含 deliveryValue/updateDelivery/flush/draftId/draftVersion/saveState 等。 */
  autosave: ReturnType<typeof useArticleWorkspace>
  /** formatRule/formatRuleSummary/formatIssues/titleOverLimit。 */
  format: ReturnType<typeof useArticleFormatRule>
  steps: XhsStudioStepsApi
  tuning: ReturnType<typeof useXhsStudioTuning>
  /** 【F6】flush 成功且 draftId 存在 → draftVersion；否则 false（DeliveryPanel before-export）。 */
  beforeExport: () => Promise<number | false>
  platformLocked: ComputedRef<boolean>
  fromCreationCenter: ComputedRef<boolean>
  toast: Ref<string>
  notify: (message: string) => void
  goCreationCenter: () => void
  /** 确认后 reset({keepPlatform:true}) + autosave.startNew（清空回选题步）。 */
  resetSession: () => Promise<void>
}

export const XHS_STUDIO_CONTEXT_KEY: InjectionKey<XhsStudioContext> = Symbol('xhs-studio-context')

/** 四步固定文案（视图与步骤条共享；冻结物，不在组件内重复定义）。 */
export const XHS_STUDIO_STEPS: ReadonlyArray<{ key: XhsStudioStep; label: string }> = [
  { key: 'pick', label: '选题' },
  { key: 'generate', label: '创作' },
  { key: 'proof', label: '校对' },
  { key: 'publish', label: '发布' },
]
