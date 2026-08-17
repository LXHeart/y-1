import { request } from './grassland-http'
import type { AiOrgBudget, UpdateAiOrgBudgetInput } from '../types/grassland'

/** 组织管理员的 AI 全局预算读写 API。 */
export function useAiOrgBudget() {
  const getBudget = (organizationId: string) =>
    request<AiOrgBudget>(`/api/ai/organizations/${encodeURIComponent(organizationId)}/budget`)

  const saveBudget = (organizationId: string, input: UpdateAiOrgBudgetInput) =>
    request<AiOrgBudget>(`/api/ai/organizations/${encodeURIComponent(organizationId)}/budget`, {
      method: 'PUT',
      body: JSON.stringify(input),
    })

  return { getBudget, saveBudget }
}
