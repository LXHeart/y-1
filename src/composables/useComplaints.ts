import { request } from './grassland-http'

/** 用户举报/投诉工单（PRD §11.8）： targetType/reason 取值与 marketplace V49 CHECK 一一对应。 */
export type ComplaintTargetType = 'task' | 'submission' | 'content' | 'order' | 'user' | 'other'
export type ComplaintReason = 'spam' | 'fraud' | 'inappropriate_content' | 'rights_infringement' | 'other'
export type ComplaintStatus = 'open' | 'processing' | 'resolved' | 'dismissed'

export interface UserComplaint {
  id: string
  targetType: ComplaintTargetType
  targetId: string | null
  reason: ComplaintReason
  description: string
  status: ComplaintStatus
  resolutionNote: string | null
  createdAt: string | null
  handledAt: string | null
}

export const COMPLAINT_TARGET_LABELS: Record<ComplaintTargetType, string> = {
  task: '任务', submission: '履约交付物', content: '内容', order: '订单', user: '用户', other: '其他',
}

export const COMPLAINT_REASON_LABELS: Record<ComplaintReason, string> = {
  spam: '垃圾信息', fraud: '涉嫌欺诈', inappropriate_content: '违规内容',
  rights_infringement: '侵权', other: '其他',
}

export const COMPLAINT_STATUS_LABELS: Record<ComplaintStatus, string> = {
  open: '待受理', processing: '处理中', resolved: '已办结', dismissed: '不成立',
}

export interface SubmitComplaintInput {
  targetType: ComplaintTargetType
  targetId?: string
  reason: ComplaintReason
  description: string
}

export function useComplaints() {
  const submitComplaint = (input: SubmitComplaintInput) =>
    request<UserComplaint>('/api/complaints', {
      method: 'POST',
      body: JSON.stringify({
        targetType: input.targetType,
        targetId: input.targetId?.trim() || undefined,
        reason: input.reason,
        description: input.description.trim(),
      }),
    })

  const listMyComplaints = () =>
    request<{ items: UserComplaint[] }>('/api/complaints/mine')

  return { submitComplaint, listMyComplaints }
}
