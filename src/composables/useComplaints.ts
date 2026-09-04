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

/**
 * 原因选项按举报对象过滤（任务书 #74 D6 值域定死）：场景化举报弹窗按锁定对象取值，
 * 个人设置弹窗的兜底表单按所选对象联动——单一映射表两处消费，避免误选。
 * 后端 V49 CHECK 不限制 target×reason 组合，这里只是产品层降噪，老数据与治理台零影响。
 */
export const COMPLAINT_REASON_OPTIONS: Record<ComplaintTargetType, readonly ComplaintReason[]> = {
  task: ['spam', 'fraud', 'inappropriate_content', 'other'],
  submission: ['rights_infringement', 'inappropriate_content', 'fraud', 'spam', 'other'],
  user: ['fraud', 'inappropriate_content', 'spam', 'other'],
  content: ['rights_infringement', 'inappropriate_content', 'spam', 'other'],
  order: ['fraud', 'other'],
  other: ['spam', 'fraud', 'inappropriate_content', 'rights_infringement', 'other'],
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
