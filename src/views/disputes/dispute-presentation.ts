/**
 * 争议域纯展示工具（任务书 #103 C103-12：纯标签/格式从视图拆出，列表与详情共用）。
 * 只做无状态格式化——不读 store、不发请求、不持有组件状态。
 */
import type { AdjudicationSnapshot, DisputeChannel, DisputeCase, DisputeStatus } from '../../types/grassland/dispute'

/**
 * 证据项长度上限——与服务端 Trust `OpenDisputeRequest.EvidenceItem.validate` 同源：
 * contentRef 非空且 ≤10,000（Java 字符串长度单位）、caption ≤500、kind ∈ text/screenshot/link。
 * 前端不做静默截断：超限即提示并阻止提交（边界用例 9,999/10,000/10,001 与 499/500/501）。
 */
export const EVIDENCE_CONTENT_MAX = 10_000
export const EVIDENCE_CAPTION_MAX = 500

export const disputeStatusLabels: Record<DisputeStatus, string> = {
  open: '受理中',
  evidence: '举证质证期',
  voting: '评审中',
  decided: '已裁决',
  appealed: '上诉中',
  final: '已终局',
}

export const disputeChannelLabels: Record<DisputeChannel, string> = {
  court: '小法庭',
  cs_direct: '客服直裁',
}

/** 列表徽标色（复用全局 .badge 修饰类）。 */
export const disputeStatusBadges: Record<DisputeStatus, string> = {
  open: 'badge-info', evidence: 'badge-info', voting: 'badge-info',
  decided: 'badge-success', appealed: 'badge-warning', final: 'badge-neutral',
}

/** 统一时间展示（zh-CN 两位数日期+时分）；空值给确定占位，不产生 NaN/Invalid Date。 */
export function formatDisputeDate(dateString: string | null): string {
  if (!dateString) return '-'
  const date = new Date(dateString)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** 截止倒计时文案；空值返回空串（调用方决定是否展示）。 */
export function disputeTimeRemaining(deadline: string | null): string {
  if (!deadline) return ''
  const end = new Date(deadline)
  if (Number.isNaN(end.getTime())) return ''
  const hoursRemaining = Math.max(0, Math.floor((end.getTime() - Date.now()) / (1000 * 60 * 60)))
  if (hoursRemaining <= 0) return '已截止'
  if (hoursRemaining < 24) return `剩余 ${hoursRemaining} 小时`
  return `剩余 ${Math.floor(hoursRemaining / 24)} 天`
}

/** 列表卡截止提示：质证期剩余 / 客服 SLA 剩余。 */
export function disputeDeadlineText(dispute: DisputeCase): string {
  if (dispute.status === 'evidence' && dispute.evidenceDeadline) {
    const remaining = disputeTimeRemaining(dispute.evidenceDeadline)
    if (!remaining) return ''
    if (remaining === '已截止') return '质证期已结束'
    return `质证期${remaining}`
  }
  if (dispute.channel === 'cs_direct' && dispute.csDueAt && dispute.status !== 'final') {
    const remaining = disputeTimeRemaining(dispute.csDueAt)
    if (!remaining) return ''
    if (remaining === '已截止') return '已超客服 SLA'
    return `客服处理${remaining}`
  }
  return ''
}

/** 详情页时间线项（数据驱动渲染；文案与原手写五段一致）。 */
export interface DisputeTimelineItem {
  key: string
  label: string
  active: boolean
  time?: string
  remaining?: string
  detail?: string
}

export function buildDisputeTimeline(dispute: DisputeCase, adjudication: AdjudicationSnapshot | null): DisputeTimelineItem[] {
  return [
    {
      key: 'evidence',
      label: '举证质证期',
      active: dispute.status === 'open' || dispute.status === 'evidence',
      time: dispute.evidenceDeadline ? formatDisputeDate(dispute.evidenceDeadline) : undefined,
      remaining: dispute.evidenceDeadline ? disputeTimeRemaining(dispute.evidenceDeadline) : undefined,
    },
    {
      key: 'voting',
      label: '评审中',
      active: dispute.status === 'voting',
      detail: adjudication ? `面板 ${adjudication.panel.size} 人，已投票 ${adjudication.panel.voted} 人` : undefined,
    },
    {
      key: 'decided',
      label: '已裁决',
      active: dispute.status === 'decided',
      time: dispute.decidedAt ? formatDisputeDate(dispute.decidedAt) : undefined,
    },
    { key: 'appealed', label: '上诉中', active: dispute.status === 'appealed' },
    {
      key: 'final',
      label: '已终局',
      active: dispute.status === 'final',
      detail: dispute.finalDecision ? (dispute.finalDecision === 'for_merchant' ? '商家胜诉' : '推荐官胜诉') : undefined,
    },
  ]
}

/** 详情页计票条段（宽度百分比；panelSize=0 时给 0% 不产生 NaN）。 */
export interface DisputeVoteSegment {
  key: string
  label: string
  count: number
  cls: string
  width: string
}

export function buildDisputeVoteSegments(adjudication: AdjudicationSnapshot): DisputeVoteSegment[] {
  const total = adjudication.tallies.panelSize
  const pct = (count: number) => total > 0 ? `${Math.round((count / total) * 100)}%` : '0%'
  const base = [
    { key: 'merchant', label: '支持商家', count: adjudication.tallies.forMerchant, cls: 'vote-merchant' },
    { key: 'recommender', label: '支持推荐官', count: adjudication.tallies.forRecommender, cls: 'vote-recommender' },
    { key: 'abstain', label: '弃权', count: adjudication.tallies.abstain, cls: 'vote-abstain' },
  ]
  return base.map((segment) => ({ ...segment, width: pct(segment.count) }))
}
