/**
 * 跨页面共享组件统一导出
 *
 * UI 基础组件 — components/ui/
 * 业务共享组件 — components/shared/
 */

// ── UI 基础组件 ──────────────────────────────────
export { default as AppButton } from './ui/AppButton.vue'
export { default as AppModal } from './ui/AppModal.vue'
export { default as AppCard } from './ui/AppCard.vue'
export { default as AppTabs } from './ui/AppTabs.vue'
export { default as AppSelect } from './ui/AppSelect.vue'
export { default as AppInput } from './ui/AppInput.vue'
export { default as AppTooltip } from './ui/AppTooltip.vue'
export { default as AppDropdown } from './ui/AppDropdown.vue'

// ── 业务共享组件 ─────────────────────────────────
export { default as LoadingState } from './shared/LoadingState.vue'
export { default as EmptyState } from './shared/EmptyState.vue'

// ── 类型导出 ─────────────────────────────────────
export type { AppTabItem } from './ui/AppTabs.vue'
export type { AppSelectOption } from './ui/AppSelect.vue'
export type { AppDropdownItem } from './ui/AppDropdown.vue'
