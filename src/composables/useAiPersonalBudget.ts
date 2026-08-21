import { request } from './grassland-http'
import type { AiOrgBudget, UpdateAiOrgBudgetInput } from '../types/grassland'

/** 个人 AI 预算（GL-P3-AI-001）：响应契约与组织版一致（AiOrgBudget）。 */
export function useAiPersonalBudget() {
  const getBudget = () =>
    request<AiOrgBudget>('/api/ai/me/budget')

  const saveBudget = (input: UpdateAiOrgBudgetInput) =>
    request<AiOrgBudget>('/api/ai/me/budget', {
      method: 'PUT',
      body: JSON.stringify(input),
    })

  return { getBudget, saveBudget }
}
