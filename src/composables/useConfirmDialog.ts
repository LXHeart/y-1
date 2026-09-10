import { ref } from 'vue'

/**
 * 应用内确认弹窗（2026-09-10 商家工作台反馈 5）：替换工作台域的 window.confirm——
 * 原生 confirm 框与页面视觉完全脱节（系统字体、无主题、无危险态），且是同步阻塞。
 *
 * 机制：模块级单例请求槽 + Promise 结算。调用方 `await confirmDialog({...})` 拿布尔；
 * 渲染层只挂一份 GlConfirmDialog（GrasslandWorkbench 根），确认/取消各结清一次。
 * 同时只允许一个确认请求：新请求进来时上一个未决请求按「取消」结清（防叠窗）。
 */
export interface ConfirmDialogOptions {
  title: string
  /** 正文必须写明对象与后果（不可逆操作不得只写「确定吗」，DESIGN.md 危险动作规则）。 */
  message: string
  /** 确认按钮动词文案（如「确认取消」）；缺省「确认」。 */
  confirmLabel?: string
  cancelLabel?: string
  /** 危险动作：确认钮转 danger 实色（取消任务/批量拒绝等不可逆操作）。 */
  danger?: boolean
}

interface ConfirmDialogRequest {
  title: string
  message: string
  confirmLabel: string
  cancelLabel: string
  danger: boolean
  resolve: (ok: boolean) => void
}

/** 模块级单例：同一时刻至多一个确认请求。 */
const request = ref<ConfirmDialogRequest | null>(null)

/** 发起确认：resolve(true)=确认，resolve(false)=取消/关闭/Esc/被新请求顶替。 */
export function confirmDialog(options: ConfirmDialogOptions): Promise<boolean> {
  request.value?.resolve(false)
  return new Promise((resolve) => {
    request.value = {
      title: options.title,
      message: options.message,
      confirmLabel: options.confirmLabel ?? '确认',
      cancelLabel: options.cancelLabel ?? '取消',
      danger: options.danger ?? false,
      resolve,
    }
  })
}

/** 渲染组件（GlConfirmDialog）专用：读当前请求并结清。 */
export function useConfirmDialog() {
  return {
    request,
    settle(ok: boolean): void {
      const current = request.value
      if (!current) return
      request.value = null
      current.resolve(ok)
    },
  }
}
