import { describe, expect, it } from 'vitest'
import { buildVideoAnalysisDisplayCards, buildVideoAdaptationDisplayCards, type VideoAnalysisResult, type VideoAdaptationResult } from './video-recreation'

describe('buildVideoAnalysisDisplayCards', () => {
  it('maps raw analysis fields into four user-facing cards', () => {
    const analysis: VideoAnalysisResult = {
      videoCaptions: '字幕稿',
      videoScript: '口播脚本',
      charactersDescription: '女生长发，白色衬衫，侧面能看到耳饰。',
      sceneDescription: '办公室靠窗工位，冷白光。',
      propsDescription: '桌上有笔记本电脑和马克杯。',
      voiceDescription: '女生声线清亮，语速适中，语气轻松。',
    }

    expect(buildVideoAnalysisDisplayCards(analysis)).toEqual([
      {
        key: 'script',
        label: '视频脚本文案',
        content: '口播脚本',
        isFallback: false,
      },
      {
        key: 'character-three-view',
        label: '人物三视图描述',
        content: '女生长发，白色衬衫，侧面能看到耳饰。',
        isFallback: false,
      },
      {
        key: 'scene-props',
        label: '场景道具描述',
        content: '办公室靠窗工位，冷白光。\n桌上有笔记本电脑和马克杯。',
        isFallback: false,
      },
      {
        key: 'voice',
        label: '人物音色描述',
        content: '女生声线清亮，语速适中，语气轻松。',
        isFallback: false,
      },
    ])
  })

  it('falls back to captions for script and keeps four cards visible', () => {
    const analysis: VideoAnalysisResult = {
      videoCaptions: '只有字幕可用',
      sceneDescription: '办公室靠窗工位，冷白光。',
      propsDescription: '桌上有笔记本电脑和马克杯。',
    }

    expect(buildVideoAnalysisDisplayCards(analysis)).toEqual([
      {
        key: 'script',
        label: '视频脚本文案',
        content: '只有字幕可用',
        isFallback: true,
      },
      {
        key: 'character-three-view',
        label: '人物三视图描述',
        content: '暂未提取到稳定的人物三视图线索，建议重新分析或换更聚焦人物的视频片段。',
        isFallback: true,
      },
      {
        key: 'scene-props',
        label: '场景道具描述',
        content: '办公室靠窗工位，冷白光。\n桌上有笔记本电脑和马克杯。',
        isFallback: false,
      },
      {
        key: 'voice',
        label: '人物音色描述',
        content: '暂未提取到明确的人物音色信息，可能是原视频语音不清晰、无明显人声或该轮结果缺失。',
        isFallback: true,
      },
    ])
  })
})

describe('buildVideoAdaptationDisplayCards', () => {
  it('builds cards for all four asset categories', () => {
    const result: VideoAdaptationResult = {
      adaptedSummary: '改编摘要',
      adaptedScript: '改编后的视频脚本内容',
      adaptedVoiceDescription: '温柔女声，语速适中',
      characterSheets: [
        { id: 'c-1', name: '阿明', description: '黑色短发男生', threeViewPrompt: '角色三视图提示词' },
      ],
      sceneCards: [
        { id: 's-1', title: '教室', description: '黄昏教室', imagePrompt: '场景图提示词' },
      ],
      propCards: [
        { id: 'p-1', name: '相机', description: '银黑色胶片相机', imagePrompt: '道具图提示词' },
      ],
    }

    const cards = buildVideoAdaptationDisplayCards(result)
    expect(cards).toHaveLength(4)
    expect(cards[0]).toMatchObject({
      key: 'adapted-script',
      label: '改编视频脚本',
      targetApi: 'Seedance 2.0',
    })
    expect(cards[1].key).toBe('adapted-character')
    expect(cards[1].targetApi).toBe('Nano Banana 2 / GPT-Image-2')
    expect(cards[2].key).toBe('adapted-scene-props')
    expect(cards[3].key).toBe('adapted-voice')
    expect(cards[3].targetApi).toBe('MiniMax')
  })

  it('returns empty array for null result', () => {
    expect(buildVideoAdaptationDisplayCards(null)).toEqual([])
  })

  it('omits cards for missing asset types', () => {
    const result: VideoAdaptationResult = {
      adaptedSummary: '改编摘要',
      adaptedScript: '只有脚本',
      characterSheets: [],
      sceneCards: [],
      propCards: [],
    }

    const cards = buildVideoAdaptationDisplayCards(result)
    expect(cards).toHaveLength(1)
    expect(cards[0].key).toBe('adapted-script')
  })
})
