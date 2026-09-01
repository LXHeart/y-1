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

/**
 * 手动复查（编辑后的文本）：返回最新 findings；失败返回 null（UI 显示原状态）。
 * 任务书 #63 卡3：platform/contentForm 随请求带上——后端早已接收并落指纹，
 * 「未知平台」缺陷的根因就在前端没传。
 */
export async function recheckSafety(
  text: string,
  platform?: string,
  contentForm?: string,
): Promise<SafetyReport | null> {
  try {
    // 该端点的响应不带 success 信封（只认 2xx + data.safety），故走 fetchApi 保留原契约。
    const response = await fetchApi('/api/content-safety/check', {
      method: 'POST',
      body: JSON.stringify({ text, platform, contentForm }),
    })
    if (!response.ok) return null
    const body = (await response.json()) as { data?: { safety?: SafetyReport } }
    return parseSafetyFrame({ safety: body.data?.safety })
  } catch {
    return null
  }
}

/** 修复请求载荷（任务书 #63 契约速查）：findings 1..20 条由调用方保证。 */
export interface FixSafetyPayload {
  text: string
  findings: Array<{ category: string; match: string; advice: string }>
  platform?: string
  contentForm?: string
  genre?: string
  style?: string
}

/**
 * 内容安全修复（任务书 #63 卡2/卡3）：消费修复 SSE——progress 帧忽略、result 帧取全文；
 * 非 2xx（401/400/503 未配置模型等）以 error JSON 先于流，取 error 字段抛出。
 */
export async function fixSafety(payload: FixSafetyPayload): Promise<string> {
  const response = await fetchApi('/api/content-safety/fix', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    let message = '修复失败，请稍后再试'
    try {
      const body = await response.json() as { error?: string }
      if (body.error) message = body.error
    } catch {
      // 非 JSON 错误体，保留兜底文案
    }
    throw new Error(message)
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('修复响应为空，请稍后再试')
  const decoder = new TextDecoder()
  let buffer = ''
  let fixed = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (!line.startsWith('data: ')) continue
      const payloadText = line.slice(6).trim()
      if (payloadText === '[DONE]') return fixed
      try {
        const frame = JSON.parse(payloadText) as { type?: string; text?: string }
        if (frame.type === 'result' && typeof frame.text === 'string') fixed = frame.text
      } catch {
        // 忽略不可解析帧（与创作流消费器同姿态）
      }
    }
  }
  if (!fixed) throw new Error('修复未返回内容，请稍后再试')
  return fixed
}
