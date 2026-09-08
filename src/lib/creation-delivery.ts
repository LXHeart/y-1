import type { CreationDeclarations, CreationDeliveryContract } from '../types/creation'

export function creationDeclarations(value?: CreationDeclarations): CreationDeclarations {
  return Object.fromEntries(['aiGenerated', 'commercial', 'original'].map(key => {
    const state = value?.[key as keyof CreationDeclarations]
    return [key, state === 'confirmed' || state === 'not-applicable' ? state : 'pending']
  }))
}

export interface DeliveryReadinessCheck {
  key: string
  label: string
  state: 'ready' | 'missing'
}

export interface DeliveryReadinessOptions {
  platform?: string
  /** complete-content 意图下媒体为必需项；text-only/script-only 不检查。 */
  mediaExpected?: boolean
}

/** 平台 → 交付字段要求（总方案 §8.6 / 任务书2 §3）。 */
const TOPIC_PLATFORMS = new Set(['xiaohongshu', 'douyin'])
const SUMMARY_PLATFORMS = new Set(['wechat-official', 'wechat'])
const SHARE_COPY_PLATFORMS = new Set(['wechat-channels', 'moments'])

/**
 * 交付就绪检查：缺项明确列出，不因「生成过」就视为完整（§7.4）。
 * completed 与发布包 readiness、公开发布是三个独立判断——本函数只覆盖第二个。
 */
export function deliveryReadiness(delivery: Partial<CreationDeliveryContract> | undefined,
  options: DeliveryReadinessOptions = {}): DeliveryReadinessCheck[] {
  const value = delivery ?? {}
  const platform = options.platform ?? value.platform ?? ''
  const checks: DeliveryReadinessCheck[] = []
  const push = (key: string, label: string, present: boolean) =>
    checks.push({ key, label, state: present ? 'ready' : 'missing' })

  push('titleOrOpening', platform === 'zhihu' ? '标题或回答开头' : '标题',
    Boolean((value.titleOrOpening ?? '').trim()))
  push('bodyOrDescription', '正文或描述', Boolean((value.bodyOrDescription ?? '').trim()))
  if (TOPIC_PLATFORMS.has(platform)) {
    push('topics', '话题标签', (value.topics ?? []).filter(topic => topic.trim()).length > 0)
  }
  if (SUMMARY_PLATFORMS.has(platform)) {
    push('summary', '摘要', Boolean((value.summary ?? '').trim()))
  }
  if (SHARE_COPY_PLATFORMS.has(platform)) {
    push('shareCopy', '分享配文', Boolean((value.shareCopy ?? '').trim()))
  }
  if (options.mediaExpected) {
    push('media', '选定媒体', (value.mediaRefs ?? []).length > 0)
  }
  const normalized = creationDeclarations(value.declarations)
  push('declarations', '声明状态', ['aiGenerated', 'commercial', 'original']
    .every(key => normalized[key as keyof CreationDeclarations] !== 'pending'))
  return checks
}

export function deliveryReady(checks: DeliveryReadinessCheck[]): boolean {
  return checks.every(check => check.state === 'ready')
}

/** 从正文中解析 #话题 行（发布话题与正文分离存储；话题行不再写入正文）。 */
export function parseHashtagTopics(content: string): string[] {
  const topics: string[] = []
  for (const match of content.matchAll(/#([^\s#，。！？.,!?]{1,30})/g)) {
    const topic = match[1]?.trim()
    if (topic && !topics.includes(topic)) topics.push(topic)
  }
  return topics.slice(0, 10)
}
