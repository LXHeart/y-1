import type { RunFn } from './grassland-http'
import { request } from './grassland-http'
import type {
  DisputeAudit,
  EvidenceAccessAudit,
  EvidenceAccessAuditQuery,
  IdentityAdminAudit,
  TaskReviewAudit,
} from '../types/grassland'

/** 跨服务审计读取。响应在 UI 层归一化，服务端仍各自拥有权威审计表。 */
export function useGrasslandAudit(run: RunFn) {
  const listAdminUserAudit = (accountId: string) =>
    run(() => request<IdentityAdminAudit[]>(
      `/api/admin/users/${encodeURIComponent(accountId)}/audit`))

  const listTaskReviewAudit = (taskId: string, limit = 100) =>
    run(() => request<TaskReviewAudit[]>(
      `/api/admin/tasks/${encodeURIComponent(taskId)}/review/history?limit=${limit}`))

  const listDisputeAudit = (disputeId: string) =>
    run(() => request<DisputeAudit[]>(
      `/api/trust/disputes/${encodeURIComponent(disputeId)}/audit`))

  const listEvidenceAccessAudit = (input: EvidenceAccessAuditQuery = {}) => {
    const qs = new URLSearchParams()
    if (input.disputeId) qs.set('disputeId', input.disputeId)
    if (input.evidenceId) qs.set('evidenceId', input.evidenceId)
    if (input.viewerAccountId) qs.set('viewerAccountId', input.viewerAccountId)
    if (input.viewerRole) qs.set('viewerRole', input.viewerRole)
    if (input.from) qs.set('from', input.from)
    if (input.to) qs.set('to', input.to)
    qs.set('limit', String(input.limit ?? 100))
    return run(() => request<EvidenceAccessAudit[]>(
      `/api/admin/trust/evidence-access-audits?${qs}`))
  }

  return {
    listAdminUserAudit,
    listTaskReviewAudit,
    listDisputeAudit,
    listEvidenceAccessAudit,
  }
}
