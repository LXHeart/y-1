import { z } from 'zod'
import { env } from '../lib/env.js'

const imageSizeSchema = z.enum(['1024x1024', '1024x1792', '1792x1024'])
const videoAssetTypeSchema = z.enum(['character-three-view', 'scene', 'prop'])
const VIDEO_EXTRACTED_CONTENT_MAX_FIELD_LENGTH = 10_000
const VIDEO_EXTRACTED_CONTENT_MAX_TOTAL_LENGTH = 20_000
const VIDEO_ASSET_ID_MAX_LENGTH = 100
const VIDEO_ASSET_NAME_MAX_LENGTH = 200
const VIDEO_ASSET_TEXT_MAX_LENGTH = 2_000
const VIDEO_SCENE_TEXT_MAX_LENGTH = 2_000
const VIDEO_SCENE_OPTIONAL_TEXT_MAX_LENGTH = 1_000
const VIDEO_USER_INSTRUCTION_MAX_LENGTH = 2_000

const trimmedOptionalStringSchema = z.string().trim().max(VIDEO_EXTRACTED_CONTENT_MAX_FIELD_LENGTH).optional()
const assetIdSchema = z.string().trim().min(1).max(VIDEO_ASSET_ID_MAX_LENGTH)
const assetNameSchema = z.string().trim().min(1).max(VIDEO_ASSET_NAME_MAX_LENGTH)
const assetOptionalNameSchema = z.string().trim().max(VIDEO_ASSET_NAME_MAX_LENGTH).optional()
const assetTextSchema = z.string().trim().min(1).max(VIDEO_ASSET_TEXT_MAX_LENGTH)

const videoSceneSchema = z.object({
  shotDescription: z.string().trim().min(1).max(VIDEO_SCENE_TEXT_MAX_LENGTH),
  characterDescription: z.string().trim().min(1).max(VIDEO_SCENE_TEXT_MAX_LENGTH),
  actionMovement: z.string().trim().max(VIDEO_SCENE_OPTIONAL_TEXT_MAX_LENGTH),
  dialogueVoiceover: z.string().trim().max(VIDEO_SCENE_OPTIONAL_TEXT_MAX_LENGTH),
  sceneEnvironment: z.string().trim().min(1).max(VIDEO_SCENE_TEXT_MAX_LENGTH),
})

const extractedContentSchema = z.object({
  videoCaptions: trimmedOptionalStringSchema,
  videoScript: trimmedOptionalStringSchema,
  charactersDescription: trimmedOptionalStringSchema,
  voiceDescription: trimmedOptionalStringSchema,
  propsDescription: trimmedOptionalStringSchema,
  sceneDescription: trimmedOptionalStringSchema,
}).superRefine((value, ctx) => {
  const textItems = Object.values(value).filter((item): item is string => typeof item === 'string' && item.length > 0)
  const totalLength = textItems.reduce((sum, item) => sum + item.length, 0)

  if (textItems.length === 0) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: '缺少可改编的视频内容',
      path: [],
    })
  }

  if (totalLength > VIDEO_EXTRACTED_CONTENT_MAX_TOTAL_LENGTH) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: '提取内容过长，请精简后重试',
      path: [],
    })
  }
})

function isAllowedProxyUrl(platform: 'douyin' | 'bilibili', value: string): boolean {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(value, 'http://localhost')
  } catch {
    return false
  }

  if (parsedUrl.origin !== 'http://localhost') {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      return false
    }

    try {
      if (parsedUrl.origin !== new URL(env.PUBLIC_BACKEND_ORIGIN).origin) {
        return false
      }
    } catch {
      return false
    }
  }

  return new RegExp(`^/api/${platform}/proxy/[^/]+$`, 'u').test(parsedUrl.pathname)
}

const userInstructionsSchema = z.object({
  scriptInstruction: z.string().trim().max(VIDEO_USER_INSTRUCTION_MAX_LENGTH).optional(),
  characterInstruction: z.string().trim().max(VIDEO_USER_INSTRUCTION_MAX_LENGTH).optional(),
  scenePropsInstruction: z.string().trim().max(VIDEO_USER_INSTRUCTION_MAX_LENGTH).optional(),
  voiceInstruction: z.string().trim().max(VIDEO_USER_INSTRUCTION_MAX_LENGTH).optional(),
}).optional()

export const adaptContentRequestSchema = z.object({
  platform: z.enum(['douyin', 'bilibili']),
  proxyVideoUrl: z.string().trim().min(1, '缺少视频代理地址'),
  extractedContent: extractedContentSchema,
  userInstructions: userInstructionsSchema,
}).superRefine((value, ctx) => {
  if (!isAllowedProxyUrl(value.platform, value.proxyVideoUrl)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: '视频代理地址无效',
      path: ['proxyVideoUrl'],
    })
  }
})

const adaptedCharacterSheetSchema = z.object({
  id: assetIdSchema,
  name: assetNameSchema,
  description: assetTextSchema,
  threeViewPrompt: assetTextSchema,
})

const adaptedSceneCardSchema = z.object({
  id: assetIdSchema,
  title: assetOptionalNameSchema,
  description: assetTextSchema,
  imagePrompt: assetTextSchema,
})

const adaptedPropCardSchema = z.object({
  id: assetIdSchema,
  name: assetNameSchema,
  description: assetTextSchema,
  imagePrompt: assetTextSchema,
})

const generateCharacterAssetImageRequestSchema = z.object({
  assetType: z.literal('character-three-view'),
  visualStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
  asset: adaptedCharacterSheetSchema,
})

const generateSceneAssetImageRequestSchema = z.object({
  assetType: z.literal('scene'),
  visualStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
  asset: adaptedSceneCardSchema,
})

const generatePropAssetImageRequestSchema = z.object({
  assetType: z.literal('prop'),
  visualStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
  asset: adaptedPropCardSchema,
})

export const generateAssetImageRequestSchema = z.discriminatedUnion('assetType', [
  generateCharacterAssetImageRequestSchema,
  generateSceneAssetImageRequestSchema,
  generatePropAssetImageRequestSchema,
])

const generateCharacterAssetsImageRequestSchema = z.object({
  assetType: z.literal('character-three-view'),
  visualStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
  assets: z.array(adaptedCharacterSheetSchema).min(1).max(20),
})

const generateSceneAssetsImageRequestSchema = z.object({
  assetType: z.literal('scene'),
  visualStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
  assets: z.array(adaptedSceneCardSchema).min(1).max(20),
})

const generatePropAssetsImageRequestSchema = z.object({
  assetType: z.literal('prop'),
  visualStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
  assets: z.array(adaptedPropCardSchema).min(1).max(20),
})

export const generateAllAssetImagesRequestSchema = z.discriminatedUnion('assetType', [
  generateCharacterAssetsImageRequestSchema,
  generateSceneAssetsImageRequestSchema,
  generatePropAssetsImageRequestSchema,
])

export const generateSceneImageRequestSchema = z.object({
  scene: videoSceneSchema,
  overallStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
})

export const generateAllSceneImagesRequestSchema = z.object({
  scenes: z.array(videoSceneSchema).min(1).max(20),
  overallStyle: z.string().trim().max(500).optional(),
  size: imageSizeSchema.default('1024x1792'),
})

export { videoAssetTypeSchema }
