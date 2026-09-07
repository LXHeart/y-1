import type { ApplicationSettlement } from '../../../types/grassland'

export function settlementLabel(state?: ApplicationSettlement | null): string {
  if (!state) return '状态加载中'
  if (state.settlementStatus === 'settled') return '已结算'
  if (state.settlementStatus === 'held') {
    return `结算暂扣：${state.holdReason === 'open_dispute' ? '争议处理中' : state.holdReason || '等待处理'}`
  }
  if (state.confirmedAt) {
    return state.settlementEligibleAt
      ? `已确认，预计 ${new Date(state.settlementEligibleAt).toLocaleString('zh-CN', { hour12: false })} 到账`
      : '已确认，待结算'
  }
  return state.settlementStatus === 'not_confirmed' ? '尚未确认履约' : state.settlementStatus
}
