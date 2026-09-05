import type {
  AiContentFormId,
  AiPlatformId,
  CreationSourceType,
  VideoCreationWorkflowId,
  CreationWorkflowResolution,
} from '../types/ai-creation'

export const AI_PLATFORM_CAPABILITY_VERSION = '2026-08-19'

export interface AiContentFormDefinition {
  id: AiContentFormId
  label: string
}

export interface AiPlatformDefinition {
  id: AiPlatformId
  label: string
  shortLabel: string
  forms: readonly AiContentFormDefinition[]
}

const GRAPHIC: AiContentFormDefinition = Object.freeze({ id: 'graphic', label: '图文' })
const VIDEO: AiContentFormDefinition = Object.freeze({ id: 'video', label: '视频' })
const IMAGE_TEXT: AiContentFormDefinition = Object.freeze({ id: 'image-text', label: '图片 + 文字' })
const VIDEO_TEXT: AiContentFormDefinition = Object.freeze({ id: 'video-text', label: '视频 + 文字' })

const PLATFORM_DEFINITIONS: readonly AiPlatformDefinition[] = [
  { id: 'xiaohongshu', label: '小红书', shortLabel: '小红书', forms: [GRAPHIC, VIDEO] },
  { id: 'douyin', label: '抖音', shortLabel: '抖音', forms: [GRAPHIC, VIDEO] },
  { id: 'dianping', label: '大众点评', shortLabel: '点评', forms: [GRAPHIC, VIDEO] },
  { id: 'kuaishou', label: '快手', shortLabel: '快手', forms: [VIDEO] },
  { id: 'wechat-channels', label: '视频号', shortLabel: '视频号', forms: [VIDEO] },
  { id: 'bilibili', label: 'Bilibili', shortLabel: 'B站', forms: [VIDEO] },
  { id: 'wechat-official', label: '公众号', shortLabel: '公众号', forms: [GRAPHIC] },
  { id: 'zhihu', label: '知乎', shortLabel: '知乎', forms: [GRAPHIC] },
  { id: 'moments', label: '朋友圈', shortLabel: '朋友圈', forms: [IMAGE_TEXT, VIDEO_TEXT] },
]

export const AI_PLATFORM_DEFINITIONS: readonly AiPlatformDefinition[] = Object.freeze(
  PLATFORM_DEFINITIONS.map((platform) => Object.freeze({
    ...platform,
    forms: Object.freeze([...platform.forms]),
  })),
)

export function getPlatform(id: AiPlatformId): AiPlatformDefinition | null {
  return AI_PLATFORM_DEFINITIONS.find((platform) => platform.id === id) ?? null
}

export function getContentForm(platformId: AiPlatformId, formId: AiContentFormId): AiContentFormDefinition | null {
  return getPlatform(platformId)?.forms.find((form) => form.id === formId) ?? null
}

const PLATFORM_ALIASES: Readonly<Record<string, AiPlatformId>> = Object.freeze({
  xiaohongshu: 'xiaohongshu',
  xhs: 'xiaohongshu',
  小红书: 'xiaohongshu',
  douyin: 'douyin',
  抖音: 'douyin',
  dianping: 'dianping',
  大众点评: 'dianping',
  点评: 'dianping',
  kuaishou: 'kuaishou',
  快手: 'kuaishou',
  wechatchannels: 'wechat-channels',
  视频号: 'wechat-channels',
  bilibili: 'bilibili',
  b站: 'bilibili',
  wechatofficial: 'wechat-official',
  微信公众号: 'wechat-official',
  公众号: 'wechat-official',
  zhihu: 'zhihu',
  知乎: 'zhihu',
  moments: 'moments',
  微信朋友圈: 'moments',
  朋友圈: 'moments',
})

const CONTENT_FORM_ALIASES: Readonly<Record<string, AiContentFormId>> = Object.freeze({
  graphic: 'graphic',
  图文: 'graphic',
  article: 'graphic',
  文章: 'graphic',
  video: 'video',
  视频: 'video',
  短视频: 'video',
  imagetext: 'image-text',
  图片文字: 'image-text',
  videotext: 'video-text',
  视频文字: 'video-text',
})

function normalizeAliasKey(value: string): string {
  return value.trim().toLowerCase().replace(/[\s_+\-/]+/g, '')
}

export function normalizePlatformId(value: string | null | undefined): AiPlatformId | null {
  if (!value) return null
  return PLATFORM_ALIASES[normalizeAliasKey(value)] ?? null
}

/**
 * 任务书 #77 卡 C/D：列表平台列的中文映射（未知值兜底显原文——详情卡既有口径推广到列表）。
 * #57 后 platform 存 canonical id，但存量行可能是自由文本，归一失败时原样返回。
 */
export function platformDisplayLabel(value: string | null | undefined): string {
  if (!value) return ''
  const platformId = normalizePlatformId(value)
  return (platformId && getPlatform(platformId)?.label) ?? value
}

export function normalizeContentFormId(value: string | null | undefined): AiContentFormId | null {
  if (!value) return null
  return CONTENT_FORM_ALIASES[normalizeAliasKey(value)] ?? null
}

export function normalizeTaskCreationSelection(
  platform: string | null | undefined,
  contentForm: string | null | undefined,
): { platformId: AiPlatformId | null; contentFormId: AiContentFormId | null } {
  const platformId = normalizePlatformId(platform)
  const normalizedForm = normalizeContentFormId(contentForm)
  return {
    platformId,
    contentFormId: platformId && normalizedForm && getContentForm(platformId, normalizedForm)
      ? normalizedForm
      : null,
  }
}

const PLANNED: CreationWorkflowResolution = Object.freeze({
  status: 'planned', workflowId: null, targetView: null,
})
const UNSUPPORTED: CreationWorkflowResolution = Object.freeze({
  status: 'unsupported', workflowId: null, targetView: null,
})

function available(
  workflowId: NonNullable<CreationWorkflowResolution['workflowId']>,
  targetView: NonNullable<CreationWorkflowResolution['targetView']>,
): CreationWorkflowResolution {
  return { status: 'available', workflowId, targetView }
}

/**
 * 产品矩阵与当前工作流的唯一映射点。合法但未实现的 PRD 组合返回 planned，绝不静默降级。
 */
export function resolveWorkflow(
  platformId: AiPlatformId,
  formId: AiContentFormId,
  source: CreationSourceType,
  videoWorkflow: VideoCreationWorkflowId = 'video-script',
): CreationWorkflowResolution {
  if (!getContentForm(platformId, formId)) return UNSUPPORTED

  if (source === 'reference') {
    return platformId === 'moments' ? PLANNED : available('reference-analyze', 'video')
  }

  if (formId === 'video' || (formId === 'video-text' && platformId === 'moments')) {
    if (videoWorkflow === 'comedy-script') return available('comedy-script', 'comedy')
    if (videoWorkflow === 'video-recreation') return available('video-recreation', 'video')
    return available('video-script', 'video-production')
  }
  if (formId === 'graphic' && platformId === 'dianping') return available('review-copy', 'image')
  if (formId === 'graphic' && ['xiaohongshu', 'douyin', 'wechat-official', 'zhihu'].includes(platformId)) {
    return available('longform', 'article')
  }
  if (formId === 'image-text' && platformId === 'moments') {
    return available('moments-image-text', 'moments')
  }
  return PLANNED
}
