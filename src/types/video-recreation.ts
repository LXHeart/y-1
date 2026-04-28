export interface VideoAnalysisResult {
  videoCaptions?: string
  videoScript?: string
  charactersDescription?: string
  voiceDescription?: string
  propsDescription?: string
  sceneDescription?: string
  runId?: string
  segmented?: boolean
  clipCount?: number
  runIds?: string[]
}

export interface VideoScene {
  shotDescription: string
  characterDescription: string
  actionMovement: string
  dialogueVoiceover: string
  sceneEnvironment: string
}

export interface VideoRecreationResult {
  scenes: VideoScene[]
  overallStyle?: string
  runId?: string
  segmented?: boolean
  clipCount?: number
  runIds?: string[]
}

export interface SceneImageState {
  imageUrl?: string
  loading: boolean
  error?: string
}

export interface VideoAnalysisDisplayCard {
  key: 'script' | 'character-three-view' | 'scene-props' | 'voice'
  label: string
  content: string
  isFallback?: boolean
}

function splitAnalysisLines(value: string | undefined): string[] {
  if (!value) {
    return []
  }

  return value
    .split(/\n+/u)
    .map((line) => line.trim())
    .filter(Boolean)
}

function joinAnalysisLines(...values: Array<string | undefined>): string | undefined {
  const seen = new Set<string>()
  const lines: string[] = []

  for (const value of values) {
    for (const line of splitAnalysisLines(value)) {
      if (seen.has(line)) {
        continue
      }

      seen.add(line)
      lines.push(line)
    }
  }

  return lines.length > 0 ? lines.join('\n') : undefined
}

export function buildVideoAnalysisDisplayCards(analysis: VideoAnalysisResult | null): VideoAnalysisDisplayCard[] {
  if (!analysis) {
    return []
  }

  return [
    {
      key: 'script',
      label: '视频脚本文案',
      content: analysis.videoScript
        ?? analysis.videoCaptions
        ?? '暂未提取到可直接复用的脚本文案，建议重新分析或换口播更清晰的片段。',
      isFallback: !analysis.videoScript,
    },
    {
      key: 'character-three-view',
      label: '人物三视图描述',
      content: analysis.charactersDescription ?? '暂未提取到稳定的人物三视图线索，建议重新分析或换更聚焦人物的视频片段。',
      isFallback: !analysis.charactersDescription,
    },
    {
      key: 'scene-props',
      label: '场景道具描述',
      content: joinAnalysisLines(analysis.sceneDescription, analysis.propsDescription) ?? '暂未提取到明确的场景道具信息，建议重新分析或换画面元素更清晰的片段。',
      isFallback: !analysis.sceneDescription && !analysis.propsDescription,
    },
    {
      key: 'voice',
      label: '人物音色描述',
      content: analysis.voiceDescription ?? '暂未提取到明确的人物音色信息，可能是原视频语音不清晰、无明显人声或该轮结果缺失。',
      isFallback: !analysis.voiceDescription,
    },
  ]
}

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

export interface VideoAdaptationResult {
  adaptedTitle?: string
  adaptedSummary: string
  adaptedScript?: string
  adaptedVoiceDescription?: string
  visualStyle?: string
  tone?: string
  characterSheets: AdaptedCharacterSheet[]
  sceneCards: AdaptedSceneCard[]
  propCards: AdaptedPropCard[]
  runId?: string
}

export interface VideoAdaptationUserInstructions {
  scriptInstruction?: string
  characterInstruction?: string
  scenePropsInstruction?: string
  voiceInstruction?: string
}

export interface VideoAdaptationDisplayCard {
  key: string
  label: string
  content: string
  copyLabel: string
  targetApi: string
}

export function buildVideoAdaptationDisplayCards(result: VideoAdaptationResult | null): VideoAdaptationDisplayCard[] {
  if (!result) {
    return []
  }

  const cards: VideoAdaptationDisplayCard[] = []

  if (result.adaptedScript) {
    cards.push({
      key: 'adapted-script',
      label: '改编视频脚本',
      content: result.adaptedScript,
      copyLabel: '复制脚本',
      targetApi: 'Seedance 2.0',
    })
  }

  if (result.characterSheets.length > 0) {
    const content = result.characterSheets.map((sheet) => {
      const parts = [`【${sheet.name}】`, sheet.description, `三视图提示词：${sheet.threeViewPrompt}`]
      return parts.join('\n')
    }).join('\n\n')
    cards.push({
      key: 'adapted-character',
      label: '改编人物三视图',
      content,
      copyLabel: '复制人物设定',
      targetApi: 'Nano Banana 2 / GPT-Image-2',
    })
  }

  const scenePropsParts: string[] = []
  if (result.sceneCards.length > 0) {
    scenePropsParts.push(...result.sceneCards.map((card) => {
      const title = card.title ? `【${card.title}】` : `【场景 ${card.id}】`
      return `${title}\n${card.description}\n图片提示词：${card.imagePrompt}`
    }))
  }
  if (result.propCards.length > 0) {
    scenePropsParts.push(...result.propCards.map((card) => {
      return `【${card.name}】\n${card.description}\n图片提示词：${card.imagePrompt}`
    }))
  }
  if (scenePropsParts.length > 0) {
    cards.push({
      key: 'adapted-scene-props',
      label: '改编场景道具描述',
      content: scenePropsParts.join('\n\n'),
      copyLabel: '复制场景道具',
      targetApi: 'Nano Banana 2 / GPT-Image-2',
    })
  }

  if (result.adaptedVoiceDescription) {
    cards.push({
      key: 'adapted-voice',
      label: '改编人物音色描述',
      content: result.adaptedVoiceDescription,
      copyLabel: '复制音色描述',
      targetApi: 'MiniMax',
    })
  }

  return cards
}
