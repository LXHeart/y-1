import { logger } from '../lib/logger.js'
import type { VideoScene } from './providers/types.js'
import * as articleImageService from './article-image.service.js'

const SCENE_IMAGE_SIZE = '1024x1792' as const

type ImageSize = '1024x1024' | '1024x1792' | '1792x1024'
export type VideoAssetType = 'character-three-view' | 'scene' | 'prop'

export interface AdaptedCharacterSheet {
  id: string
  name: string
  description: string
  threeViewPrompt: string
}

export interface AdaptedSceneCard {
  id: string
  title?: string
  description: string
  imagePrompt: string
}

export interface AdaptedPropCard {
  id: string
  name: string
  description: string
  imagePrompt: string
}

export type VideoAsset = AdaptedCharacterSheet | AdaptedSceneCard | AdaptedPropCard

function joinPromptParts(parts: string[]): string {
  return parts.filter((part) => part.trim().length > 0).join('. ')
}

export function buildSceneImagePrompt(scene: VideoScene, overallStyle?: string): string {
  const parts: string[] = []

  if (scene.shotDescription) {
    parts.push(scene.shotDescription)
  }

  if (scene.characterDescription) {
    parts.push(scene.characterDescription)
  }

  if (scene.sceneEnvironment) {
    parts.push(scene.sceneEnvironment)
  }

  if (scene.actionMovement) {
    parts.push(`Action: ${scene.actionMovement}`)
  }

  if (overallStyle) {
    parts.push(`Style: ${overallStyle}`)
  }

  return joinPromptParts(parts)
}

export function buildAssetImagePrompt(
  assetType: VideoAssetType,
  asset: VideoAsset,
  visualStyle?: string,
): string {
  const parts: string[] = []

  if (assetType === 'character-three-view') {
    const characterAsset = asset as AdaptedCharacterSheet
    if (characterAsset.name) {
      parts.push(characterAsset.name)
    }
    if (characterAsset.description) {
      parts.push(characterAsset.description)
    }
    if (characterAsset.threeViewPrompt) {
      parts.push(characterAsset.threeViewPrompt)
    }
  } else if (assetType === 'scene') {
    const sceneAsset = asset as AdaptedSceneCard
    if (sceneAsset.title) {
      parts.push(sceneAsset.title)
    }
    if (sceneAsset.description) {
      parts.push(sceneAsset.description)
    }
    if (sceneAsset.imagePrompt) {
      parts.push(sceneAsset.imagePrompt)
    }
  } else {
    const propAsset = asset as AdaptedPropCard
    if (propAsset.name) {
      parts.push(propAsset.name)
    }
    if (propAsset.description) {
      parts.push(propAsset.description)
    }
    if (propAsset.imagePrompt) {
      parts.push(propAsset.imagePrompt)
    }
  }

  if (visualStyle) {
    parts.push(`Style: ${visualStyle}`)
  }

  return joinPromptParts(parts)
}

export async function generateSceneImage(input: {
  scene: VideoScene
  overallStyle?: string
  size?: ImageSize
  userId?: string
  signal?: AbortSignal
}): Promise<{ imageUrl: string; revisedPrompt?: string }> {
  const prompt = buildSceneImagePrompt(input.scene, input.overallStyle)

  logger.info({ promptLength: prompt.length }, 'Generating scene reference image')

  return articleImageService.generateImage({
    prompt,
    size: input.size ?? SCENE_IMAGE_SIZE,
    userId: input.userId,
    signal: input.signal,
  })
}

export async function generateAllSceneImages(input: {
  scenes: VideoScene[]
  overallStyle?: string
  size?: ImageSize
  userId?: string
  signal?: AbortSignal
}): Promise<Array<{ imageUrl: string; revisedPrompt?: string }>> {
  const results: Array<{ imageUrl: string; revisedPrompt?: string }> = []

  for (let i = 0; i < input.scenes.length; i++) {
    if (input.signal?.aborted) break

    logger.info({ sceneIndex: i + 1, totalScenes: input.scenes.length }, 'Generating scene image')

    const result = await generateSceneImage({
      scene: input.scenes[i],
      overallStyle: input.overallStyle,
      size: input.size,
      userId: input.userId,
      signal: input.signal,
    })

    results.push(result)
  }

  return results
}

export async function generateAssetImage(input: {
  assetType: VideoAssetType
  asset: VideoAsset
  visualStyle?: string
  size?: ImageSize
  userId?: string
  signal?: AbortSignal
}): Promise<{ imageUrl: string; revisedPrompt?: string }> {
  const prompt = buildAssetImagePrompt(input.assetType, input.asset, input.visualStyle)

  logger.info({ assetType: input.assetType, promptLength: prompt.length }, 'Generating asset reference image')

  return articleImageService.generateImage({
    prompt,
    size: input.size ?? SCENE_IMAGE_SIZE,
    userId: input.userId,
    signal: input.signal,
  })
}

export async function generateAllAssetImages(input: {
  assetType: VideoAssetType
  assets: VideoAsset[]
  visualStyle?: string
  size?: ImageSize
  userId?: string
  signal?: AbortSignal
}): Promise<Array<{ imageUrl: string; revisedPrompt?: string }>> {
  const results: Array<{ imageUrl: string; revisedPrompt?: string }> = []

  for (let i = 0; i < input.assets.length; i++) {
    if (input.signal?.aborted) break

    logger.info({ assetType: input.assetType, assetIndex: i + 1, totalAssets: input.assets.length }, 'Generating asset image')

    const result = await generateAssetImage({
      assetType: input.assetType,
      asset: input.assets[i],
      visualStyle: input.visualStyle,
      size: input.size,
      userId: input.userId,
      signal: input.signal,
    })

    results.push(result)
  }

  return results
}
