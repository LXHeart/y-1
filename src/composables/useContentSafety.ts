/**
 * 内容安全（任务书 #34 / ADR-D16 D7/D9）：消费各生成流 result 帧的 `safety` 块，
 * 以及「重新检查」手动复查端点（`POST /api/content-safety/check`）。
 *
 * 词库服务端独占（D9）——前端只持有 findings 渲染；findings 是 advisory（D6，不阻断）。
 */

import { fetchApi } from './grassland-http'
import type { SafetyFinding, SafetyReport } from '../types/content-safety'

export type { ContentSafetyStreamFrame, SafetyFinding, SafetyReport } from '../types/content-safety'

/** 从 SSE payload JSON 解析 safety 块（非 safety 帧返回 null）。 */
export function parseSafetyFrame(payload: unknown): SafetyReport | null {
  if (!payload || typeof payload !== 'object') return null
  const safety = (payload as Record<string, unknown>).safety as Partial<SafetyReport> | undefined
  if (!safety || typeof safety !== 'object') return null
  return {
    findings: Array.isArray(safety.findings)
      ? safety.findings.filter((finding): finding is SafetyFinding => Boolean(
        finding && typeof finding === 'object'
        && typeof (finding as SafetyFinding).category === 'string'
        && typeof (finding as SafetyFinding).severity === 'string',
      ))
      : [],
    lexiconVersion: String(safety.lexiconVersion ?? ''),
    deepCheck: safety.deepCheck === true,
    appliedOverlays: Array.isArray(safety.appliedOverlays)
      ? safety.appliedOverlays.filter((item): item is string => typeof item === 'string')
      : [],
  }
}

/** 手动复查（编辑后的文本）：返回最新 findings；失败返回 null（UI 显示原状态）。 */
export async function recheckSafety(text: string): Promise<SafetyReport | null> {
  try {
    // 该端点的响应不带 success 信封（只认 2xx + data.safety），故走 fetchApi 保留原契约。
    const response = await fetchApi('/api/content-safety/check', {
      method: 'POST',
      body: JSON.stringify({ text }),
    })
    if (!response.ok) return null
    const body = (await response.json()) as { data?: { safety?: SafetyReport } }
    return parseSafetyFrame({ safety: body.data?.safety })
  } catch {
    return null
  }
}
