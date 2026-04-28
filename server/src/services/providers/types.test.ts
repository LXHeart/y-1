import { describe, expect, it } from 'vitest'
import { AppError } from '../../lib/errors.js'
import { completeVideoAnalysisResult, normalizeVideoAnalysisResult, normalizeVideoAdaptationResult } from './types.js'

describe('normalizeVideoAnalysisResult', () => {
  it('keeps provider fields and fills sparse fields conservatively', () => {
    const result = normalizeVideoAnalysisResult({
      video_captions: '[00:01] 主持人小美说今天带你们看新店',
      scene_description: '咖啡店内景，女生站在木桌旁，靠窗高脚凳，柜台有咖啡机。',
      voice_description: '女声清亮，语速轻快。',
    })

    expect(result.videoCaptions).toBe('[00:01] 主持人小美说今天带你们看新店')
    expect(result.videoScript).toBeUndefined()
    expect(result.charactersDescription).toBe([
      '可见出镜人物线索：咖啡店内景，女生站在木桌旁，靠窗高脚凳，柜台有咖啡机。',
      '可见出镜人物线索：[00:01] 主持人小美说今天带你们看新店',
    ].join('\n'))
    expect(result.voiceDescription).toBe('女声清亮，语速轻快。')
    expect(result.sceneDescription).toBe('咖啡店内景，女生站在木桌旁，靠窗高脚凳，柜台有咖啡机。')
    expect(result.runId).toBeUndefined()
    expect(result.propsDescription?.split('\n')).toEqual(expect.arrayContaining([
      '可见道具/物件：木桌',
      '可见道具/物件：高脚凳',
      '可见道具/物件：柜台',
      '可见道具/物件：咖啡机',
    ]))
    expect(result.propsDescription?.split('\n')).toHaveLength(4)
  })

  it('does not overwrite provider supplied fields', () => {
    const result = completeVideoAnalysisResult({
      videoCaptions: '字幕文本',
      videoScript: '原始脚本文案',
      charactersDescription: '原始人物描述',
      propsDescription: '原始道具描述',
      sceneDescription: '场景里还有桌子和灯。',
    })

    expect(result.videoScript).toBe('原始脚本文案')
    expect(result.charactersDescription).toBe('原始人物描述')
    expect(result.propsDescription).toBe('原始道具描述')
  })

  it('stays conservative when the source text is too weak', () => {
    const result = normalizeVideoAnalysisResult({
      video_captions: '欢迎观看',
      scene_description: '画面简洁',
    })

    expect(result.videoScript).toBeUndefined()
    expect(result.charactersDescription).toBeUndefined()
    expect(result.propsDescription).toBeUndefined()
  })

  it('does not infer a character from unrelated words like 吉他', () => {
    const result = normalizeVideoAnalysisResult({
      scene_description: '桌上放着一把吉他，画面里没有人物出镜。',
    })

    expect(result.charactersDescription).toBeUndefined()
  })

  it('prefers specific prop matches over overlapping shorter keywords', () => {
    const result = normalizeVideoAnalysisResult({
      scene_description: '桌上有笔记本电脑和马克杯。',
    })

    expect(result.propsDescription?.split('\n')).toEqual([
      '可见道具/物件：笔记本电脑',
      '可见道具/物件：马克杯',
    ])
  })

  it('accepts camelCase provider fields too', () => {
    const result = normalizeVideoAnalysisResult({
      videoCaptions: '字幕 A',
      videoScript: '脚本 A',
      charactersDescription: '人物 A',
      propsDescription: '道具 A',
      sceneDescription: '场景 A',
      voiceDescription: '音色 A',
      runId: 'run-a',
    })

    expect(result).toEqual({
      videoCaptions: '字幕 A',
      videoScript: '脚本 A',
      charactersDescription: '人物 A',
      voiceDescription: '音色 A',
      propsDescription: '道具 A',
      sceneDescription: '场景 A',
      runId: 'run-a',
    })
  })

  it('normalizes video_script storyboard array into formatted text', () => {
    const result = normalizeVideoAnalysisResult({
      video_captions: '字幕',
      video_script: [
        {
          shot_number: 1,
          shot_type: '特写',
          visual_content: '面条特写',
          camera_movement: '缓慢推进',
          dialogue_narration: '好吃',
          on_screen_text: '无',
          duration_seconds: 3,
          notes: '食物特写',
        },
      ],
      characters_description: '人物',
      voice_description: '音色',
      props_description: '道具',
      scene_description: '场景',
    })

    expect(result.videoScript).toContain('镜头 1 | 特写 | 3s')
    expect(result.videoScript).toContain('  画面：面条特写')
    expect(result.videoScript).toContain('  运镜：缓慢推进')
    expect(result.videoScript).toContain('  台词/旁白：好吃')
    expect(result.videoScript).not.toContain('字幕：无')
  })
})

describe('normalizeVideoAdaptationResult', () => {
  it('normalizes a storyboard array into formatted text', () => {
    const result = normalizeVideoAdaptationResult({
      adapted_summary: '改编摘要',
      adapted_script: [
        {
          shot_number: 1,
          shot_type: '中景',
          visual_content: '女生坐在面馆',
          camera_movement: '固定机位',
          dialogue_narration: '推荐一家面馆',
          on_screen_text: '今日推荐',
          duration_seconds: 5,
          notes: '暖色调',
        },
        {
          shot_number: 2,
          shot_type: '特写',
          visual_content: '牛肉面特写',
          camera_movement: '缓慢拉出',
          dialogue_narration: '无',
          on_screen_text: '无',
          duration_seconds: 3,
          notes: '',
        },
      ],
      character_sheets: [],
      scene_cards: [],
      prop_cards: [],
    })

    expect(result.adaptedScript).toContain('镜头 1 | 中景 | 5s')
    expect(result.adaptedScript).toContain('  画面：女生坐在面馆')
    expect(result.adaptedScript).toContain('  运镜：固定机位')
    expect(result.adaptedScript).toContain('  台词/旁白：推荐一家面馆')
    expect(result.adaptedScript).toContain('  字幕：今日推荐')
    expect(result.adaptedScript).toContain('  备注：暖色调')
    expect(result.adaptedScript).toContain('镜头 2 | 特写 | 3s')
    expect(result.adaptedScript).toContain('  画面：牛肉面特写')
    expect(result.adaptedScript).not.toContain('台词/旁白：无')
    expect(result.adaptedScript).not.toContain('字幕：无')
  })

  it('keeps adapted_script as-is when it is a plain string', () => {
    const result = normalizeVideoAdaptationResult({
      adapted_summary: '改编摘要',
      adapted_script: '这是一段普通脚本文字',
      character_sheets: [],
      scene_cards: [],
      prop_cards: [],
    })

    expect(result.adaptedScript).toBe('这是一段普通脚本文字')
  })

  it('returns undefined adapted_script for empty or invalid input', () => {
    const result = normalizeVideoAdaptationResult({
      adapted_summary: '改编摘要',
      adapted_script: [],
      character_sheets: [],
      scene_cards: [],
      prop_cards: [],
    })

    expect(result.adaptedScript).toBeUndefined()
  })

  it('throws on missing adapted_summary', () => {
    expect(() => normalizeVideoAdaptationResult({
      character_sheets: [],
      scene_cards: [],
      prop_cards: [],
    })).toThrow(AppError)
  })
})
