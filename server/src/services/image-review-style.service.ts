import { providerFetch } from '../lib/fetch.js'
import { logger } from '../lib/logger.js'
import type { ResolvedProviderConfig } from './providers/types.js'
import { loadUserSettingsRecord, saveUserSettingsRecord } from './user-settings.service.js'

interface ImageReviewSnapshot {
  review: string
  title?: string
  tags?: string[]
}

interface StylePreferencesData {
  preferences: string[]
  updatedAt: string
}

function extractTextFromChatCompletion(response: unknown): string {
  if (typeof response !== 'object' || response === null) {
    throw new Error('LLM 返回了无效响应')
  }

  const record = response as Record<string, unknown>
  const choices = record.choices

  if (!Array.isArray(choices) || choices.length === 0) {
    throw new Error('LLM 返回了空结果')
  }

  const firstChoice = choices[0] as Record<string, unknown> | undefined
  const message = firstChoice?.message as Record<string, unknown> | undefined

  if (typeof message?.content !== 'string' || !message.content.trim()) {
    throw new Error('LLM 返回了空内容')
  }

  return message.content.trim()
}

function buildStyleSummaryPrompt(original: ImageReviewSnapshot, edited: ImageReviewSnapshot): string {
  return `你是一个写作风格分析助手。用户修改了 AI 生成的评价文案。请对比"修改前"和"修改后"两个版本，总结用户偏好的写作风格差异。

只输出风格偏好规则，每条一行，不要编号，不要解释，不要输出其他内容。

规则示例：
- 偏好短句，不用长复合句
- 用口语化表达，如"挺好""还行"
- 不使用 emoji
- 喜欢强调包装和分量细节

修改前：
${JSON.stringify(original, null, 2)}

修改后：
${JSON.stringify(edited, null, 2)}`
}

export async function loadImageReviewStylePreferences(userId: string): Promise<string[]> {
  const record = await loadUserSettingsRecord(userId, 'image-review-style')
  if (!record || typeof record !== 'object') {
    return []
  }

  const data = record as StylePreferencesData
  if (!Array.isArray(data.preferences)) {
    return []
  }

  return data.preferences.filter((p): p is string => typeof p === 'string' && p.trim().length > 0)
}

export async function saveImageReviewStyleFromEdits(
  userId: string,
  original: ImageReviewSnapshot,
  edited: ImageReviewSnapshot,
  config: ResolvedProviderConfig,
): Promise<string[]> {
  if (JSON.stringify(original) === JSON.stringify(edited)) {
    return loadImageReviewStylePreferences(userId)
  }

  const baseUrl = config.baseUrl.replace(/\/$/u, '')
  const endpoint = `${baseUrl}/chat/completions`

  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (config.apiKey) {
    headers.Authorization = `Bearer ${config.apiKey}`
  }

  const prompt = buildStyleSummaryPrompt(original, edited)

  logger.info({ userId }, 'Summarizing image review style edits')

  const response = await providerFetch(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      model: config.model || 'qwen3.5-flash',
      messages: [{ role: 'user', content: prompt }],
    }),
  })

  if (!response.ok) {
    const errorText = await response.text()
    logger.error({ status: response.status, errorTextLength: errorText.length }, 'Style summary LLM call failed')
    throw new Error(`风格总结失败（${response.status}）`)
  }

  const responseBody = await response.json() as unknown
  const summaryText = extractTextFromChatCompletion(responseBody)

  const newRules = summaryText
    .split('\n')
    .map((line: string) => line.replace(/^[-•*\d.)\s]+/u, '').trim())
    .filter((line: string) => line.length > 0)

  const existingPreferences = await loadImageReviewStylePreferences(userId)
  const merged = [...existingPreferences]
  for (const rule of newRules) {
    if (merged.length >= 100) break
    if (!merged.some((existing) => existing === rule)) {
      merged.push(rule)
    }
  }

  const data: StylePreferencesData = {
    preferences: merged,
    updatedAt: new Date().toISOString(),
  }

  await saveUserSettingsRecord(userId, 'image-review-style', data)

  logger.info({ userId, ruleCount: merged.length, newRules: newRules.length }, 'Saved image review style preferences')

  return merged
}

export function buildStylePreferenceAppendix(preferences: string[]): string {
  if (preferences.length === 0) return ''
  return `\n\n用户个人风格偏好（请在生成中体现这些偏好）：\n${preferences.map((p) => `- ${p}`).join('\n')}`
}

export async function saveImageReviewStylePreferences(userId: string, preferences: string[]): Promise<string[]> {
  await saveUserSettingsRecord(userId, 'image-review-style', {
    preferences,
    updatedAt: new Date().toISOString(),
  })
  logger.info({ userId, preferenceCount: preferences.length }, 'Updated image review style preferences')
  return preferences
}

export async function optimizeStylePreferences(
  preferences: string[],
  config: ResolvedProviderConfig,
): Promise<string[]> {
  const baseUrl = config.baseUrl.replace(/\/$/u, '')
  const endpoint = `${baseUrl}/chat/completions`

  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (config.apiKey) {
    headers.Authorization = `Bearer ${config.apiKey}`
  }

  const prompt = `你是一个写作风格偏好整理助手。下面是一组风格偏好规则列表，其中有些规则含义相近或重复。请你合并含义相近的规则，保留更准确、更具体的表述，去掉冗余。

要求：
1. 只输出合并后的规则列表，每条一行
2. 不要编号，不要解释，不要输出其他内容
3. 合并时优先保留更具体的描述
4. 如果某条规则是其他规则的特殊情况，合并到更通用的那条中

风格偏好规则：
${preferences.map((p, i) => `${i + 1}. ${p}`).join('\n')}`

  logger.info({ inputCount: preferences.length }, 'Optimizing style preferences via LLM')

  const response = await providerFetch(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      model: config.model || 'qwen3.5-flash',
      messages: [{ role: 'user', content: prompt }],
    }),
  })

  if (!response.ok) {
    const errorText = await response.text()
    logger.error({ status: response.status, errorTextLength: errorText.length }, 'Style optimize LLM call failed')
    throw new Error(`风格偏好优化失败（${response.status}）`)
  }

  const responseBody = await response.json() as unknown
  const summaryText = extractTextFromChatCompletion(responseBody)

  return summaryText
    .split('\n')
    .map((line: string) => line.replace(/^[-•*\d.)\s]+/u, '').trim())
    .filter((line: string) => line.length > 0)
    .slice(0, 100)
}
