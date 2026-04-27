import { describe, expect, it } from 'vitest'
import {
  adaptContentRequestSchema,
  generateSceneImageRequestSchema,
  generateAllSceneImagesRequestSchema,
  generateAssetImageRequestSchema,
  generateAllAssetImagesRequestSchema,
} from './video-recreation.js'

describe('generateSceneImageRequestSchema', () => {
  const validScene = {
    shotDescription: 'Close-up of a sunrise over mountains',
    characterDescription: 'A lone hiker standing on a peak',
    actionMovement: 'Slowly raising arms toward the sky',
    dialogueVoiceover: '',
    sceneEnvironment: 'Snow-capped peaks at golden hour',
  }

  it('accepts a valid request with all scene fields and optional style', () => {
    const input = {
      scene: validScene,
      overallStyle: 'Cinematic, warm color grading',
      size: '1024x1792',
    }

    const result = generateSceneImageRequestSchema.parse(input)

    expect(result).toEqual({
      scene: validScene,
      overallStyle: 'Cinematic, warm color grading',
      size: '1024x1792',
    })
  })

  it('accepts a valid request without optional fields', () => {
    const input = {
      scene: validScene,
    }

    const result = generateSceneImageRequestSchema.parse(input)

    expect(result).toEqual({
      scene: validScene,
      overallStyle: undefined,
      size: '1024x1792',
    })
  })

  it('defaults size to 1024x1792 when omitted', () => {
    const input = {
      scene: validScene,
    }

    const result = generateSceneImageRequestSchema.parse(input)

    expect(result.size).toBe('1024x1792')
  })

  it('accepts all valid size values', () => {
    const sizes = ['1024x1024', '1024x1792', '1792x1024'] as const

    for (const size of sizes) {
      const result = generateSceneImageRequestSchema.parse({
        scene: validScene,
        size,
      })
      expect(result.size).toBe(size)
    }
  })

  it('strips extra fields not in the schema', () => {
    const input = {
      scene: validScene,
      overallStyle: 'Oil painting',
      extraField: 'should be removed',
      anotherExtra: 42,
    }

    const result = generateSceneImageRequestSchema.parse(input)

    expect(result).not.toHaveProperty('extraField')
    expect(result).not.toHaveProperty('anotherExtra')
  })

  it('trims whitespace from overallStyle', () => {
    const result = generateSceneImageRequestSchema.parse({
      scene: validScene,
      overallStyle: '  Cinematic style  ',
    })

    expect(result.overallStyle).toBe('Cinematic style')
  })

  it('trims whitespace-only overallStyle to empty string', () => {
    const result = generateSceneImageRequestSchema.parse({
      scene: validScene,
      overallStyle: '   ',
    })

    // z.string().trim().optional() trims to '' but does not reject or coerce to undefined
    expect(result.overallStyle).toBe('')
  })

  it('rejects overallStyle exceeding 500 characters', () => {
    const longStyle = 'A'.repeat(501)

    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: validScene,
        overallStyle: longStyle,
      }),
    ).toThrow()
  })

  it('accepts overallStyle at exactly 500 characters', () => {
    const exactStyle = 'A'.repeat(500)

    const result = generateSceneImageRequestSchema.parse({
      scene: validScene,
      overallStyle: exactStyle,
    })

    expect(result.overallStyle).toBe(exactStyle)
  })

  it('rejects missing scene', () => {
    expect(() =>
      generateSceneImageRequestSchema.parse({
        overallStyle: 'test',
      }),
    ).toThrow()
  })

  it('rejects scene with missing shotDescription', () => {
    const { shotDescription: _removed, ...sceneWithoutShot } = validScene

    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: sceneWithoutShot,
      }),
    ).toThrow()
  })

  it('rejects scene with empty shotDescription', () => {
    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: { ...validScene, shotDescription: '   ' },
      }),
    ).toThrow()
  })

  it('rejects scene with missing characterDescription', () => {
    const { characterDescription: _removed, ...sceneWithoutChar } = validScene

    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: sceneWithoutChar,
      }),
    ).toThrow()
  })

  it('rejects scene with empty characterDescription', () => {
    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: { ...validScene, characterDescription: '   ' },
      }),
    ).toThrow()
  })

  it('rejects scene with missing sceneEnvironment', () => {
    const { sceneEnvironment: _removed, ...sceneWithoutEnv } = validScene

    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: sceneWithoutEnv,
      }),
    ).toThrow()
  })

  it('rejects scene with empty sceneEnvironment', () => {
    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: { ...validScene, sceneEnvironment: '   ' },
      }),
    ).toThrow()
  })

  it('rejects an invalid size value', () => {
    expect(() =>
      generateSceneImageRequestSchema.parse({
        scene: validScene,
        size: '800x600',
      }),
    ).toThrow()
  })

  it('trims scene string fields', () => {
    const result = generateSceneImageRequestSchema.parse({
      scene: {
        shotDescription: '  Sunrise view  ',
        characterDescription: '  Hiker  ',
        actionMovement: '  Waves  ',
        dialogueVoiceover: '  Hello  ',
        sceneEnvironment: '  Mountains  ',
      },
    })

    expect(result.scene.shotDescription).toBe('Sunrise view')
    expect(result.scene.characterDescription).toBe('Hiker')
    expect(result.scene.actionMovement).toBe('Waves')
    expect(result.scene.dialogueVoiceover).toBe('Hello')
    expect(result.scene.sceneEnvironment).toBe('Mountains')
  })
})

describe('adaptContentRequestSchema', () => {
  it('accepts a valid request with extracted video content', () => {
    const result = adaptContentRequestSchema.parse({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
        videoScript: '旁白 1',
        charactersDescription: '人物 1',
        sceneDescription: '场景 1',
        propsDescription: '道具 1',
        voiceDescription: '音色 1',
      },
    })

    expect(result).toEqual({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
        videoScript: '旁白 1',
        charactersDescription: '人物 1',
        sceneDescription: '场景 1',
        propsDescription: '道具 1',
        voiceDescription: '音色 1',
      },
    })
  })

  it('rejects extractedContent when all fields are empty', () => {
    expect(() => adaptContentRequestSchema.parse({
      platform: 'bilibili',
      proxyVideoUrl: '/api/bilibili/proxy/token-1',
      extractedContent: {},
    })).toThrow()
  })

  it('rejects a proxy url that does not match the selected platform', () => {
    expect(() => adaptContentRequestSchema.parse({
      platform: 'douyin',
      proxyVideoUrl: '/api/bilibili/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
      },
    })).toThrow()
  })

  it('rejects a single extracted field longer than 10000 characters', () => {
    expect(() => adaptContentRequestSchema.parse({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字'.repeat(10001),
      },
    })).toThrow()
  })

  it('rejects extracted content whose total length exceeds 20000 characters', () => {
    expect(() => adaptContentRequestSchema.parse({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字'.repeat(10000),
        videoScript: '文'.repeat(10000),
        sceneDescription: '场',
      },
    })).toThrow('提取内容过长，请精简后重试')
  })
})

describe('generateAssetImageRequestSchema', () => {
  it('accepts a valid character three-view asset request', () => {
    const result = generateAssetImageRequestSchema.parse({
      assetType: 'character-three-view',
      visualStyle: 'Cinematic',
      asset: {
        id: 'character-1',
        name: '阿明',
        description: '黑色短发，白色卫衣',
        threeViewPrompt: '角色三视图，正侧背完整设定图',
      },
    })

    expect(result).toEqual({
      assetType: 'character-three-view',
      visualStyle: 'Cinematic',
      size: '1024x1792',
      asset: {
        id: 'character-1',
        name: '阿明',
        description: '黑色短发，白色卫衣',
        threeViewPrompt: '角色三视图，正侧背完整设定图',
      },
    })
  })

  it('accepts a valid scene asset request', () => {
    const result = generateAssetImageRequestSchema.parse({
      assetType: 'scene',
      asset: {
        id: 'scene-1',
        title: '教室门口',
        description: '黄昏下的学校走廊',
        imagePrompt: '黄昏学校走廊，电影感镜头',
      },
    })

    expect(result.size).toBe('1024x1792')
    expect(result.assetType).toBe('scene')
  })

  it('rejects a character asset without threeViewPrompt', () => {
    expect(() => generateAssetImageRequestSchema.parse({
      assetType: 'character-three-view',
      asset: {
        id: 'character-1',
        name: '阿明',
        description: '黑色短发，白色卫衣',
      },
    })).toThrow()
  })

  it('rejects an asset description longer than 2000 characters', () => {
    expect(() => generateAssetImageRequestSchema.parse({
      assetType: 'scene',
      asset: {
        id: 'scene-1',
        description: '长'.repeat(2001),
        imagePrompt: '黄昏学校走廊，电影感镜头',
      },
    })).toThrow()
  })
})

describe('generateAllAssetImagesRequestSchema', () => {
  it('accepts a valid batch prop asset request', () => {
    const result = generateAllAssetImagesRequestSchema.parse({
      assetType: 'prop',
      assets: [{
        id: 'prop-1',
        name: '旧相机',
        description: '银黑色胶片相机',
        imagePrompt: '银黑色胶片相机产品设定图',
      }],
    })

    expect(result).toEqual({
      assetType: 'prop',
      size: '1024x1792',
      visualStyle: undefined,
      assets: [{
        id: 'prop-1',
        name: '旧相机',
        description: '银黑色胶片相机',
        imagePrompt: '银黑色胶片相机产品设定图',
      }],
    })
  })

  it('rejects an empty assets array', () => {
    expect(() => generateAllAssetImagesRequestSchema.parse({
      assetType: 'scene',
      assets: [],
    })).toThrow()
  })
})

describe('generateAllSceneImagesRequestSchema', () => {
  const validScene = {
    shotDescription: 'Close-up of a sunrise over mountains',
    characterDescription: 'A lone hiker standing on a peak',
    actionMovement: 'Slowly raising arms toward the sky',
    dialogueVoiceover: '',
    sceneEnvironment: 'Snow-capped peaks at golden hour',
  }

  it('accepts a valid request with one scene', () => {
    const input = {
      scenes: [validScene],
    }

    const result = generateAllSceneImagesRequestSchema.parse(input)

    expect(result).toEqual({
      scenes: [validScene],
      overallStyle: undefined,
      size: '1024x1792',
    })
  })

  it('accepts a valid request with multiple scenes and optional fields', () => {
    const scene2 = {
      shotDescription: 'Wide shot of a city skyline at night',
      characterDescription: 'A couple walking down a neon-lit street',
      actionMovement: 'Walking hand in hand',
      dialogueVoiceover: 'This is where it all began',
      sceneEnvironment: 'Busy downtown with glowing billboards',
    }

    const input = {
      scenes: [validScene, scene2],
      overallStyle: 'Anime style, high contrast',
      size: '1024x1024',
    }

    const result = generateAllSceneImagesRequestSchema.parse(input)

    expect(result.scenes).toHaveLength(2)
    expect(result.overallStyle).toBe('Anime style, high contrast')
    expect(result.size).toBe('1024x1024')
  })

  it('rejects a scene with shotDescription longer than 2000 characters', () => {
    expect(() => generateAllSceneImagesRequestSchema.parse({
      scenes: [{
        shotDescription: '长'.repeat(2001),
        characterDescription: '角色',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: '环境',
      }],
    })).toThrow()
  })

  it('accepts up to 20 scenes', () => {
    const scenes = Array.from({ length: 20 }, (_, i) => ({
      shotDescription: `Scene ${i + 1} description`,
      characterDescription: `Character ${i + 1}`,
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: `Environment ${i + 1}`,
    }))

    const result = generateAllSceneImagesRequestSchema.parse({ scenes })

    expect(result.scenes).toHaveLength(20)
  })

  it('rejects more than 20 scenes', () => {
    const scenes = Array.from({ length: 21 }, (_, i) => ({
      shotDescription: `Scene ${i + 1} description`,
      characterDescription: `Character ${i + 1}`,
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: `Environment ${i + 1}`,
    }))

    expect(() =>
      generateAllSceneImagesRequestSchema.parse({ scenes }),
    ).toThrow()
  })

  it('rejects an empty scenes array', () => {
    expect(() =>
      generateAllSceneImagesRequestSchema.parse({ scenes: [] }),
    ).toThrow()
  })

  it('rejects missing scenes field', () => {
    expect(() =>
      generateAllSceneImagesRequestSchema.parse({ overallStyle: 'test' }),
    ).toThrow()
  })

  it('rejects a scene with invalid fields within the array', () => {
    const invalidScene = {
      shotDescription: 'Valid',
      characterDescription: '', // empty after trim
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Valid',
    }

    expect(() =>
      generateAllSceneImagesRequestSchema.parse({ scenes: [invalidScene] }),
    ).toThrow()
  })

  it('defaults size to 1024x1792 when omitted', () => {
    const result = generateAllSceneImagesRequestSchema.parse({
      scenes: [validScene],
    })

    expect(result.size).toBe('1024x1792')
  })

  it('strips extra fields from the top level', () => {
    const result = generateAllSceneImagesRequestSchema.parse({
      scenes: [validScene],
      unknownField: 'removed',
    })

    expect(result).not.toHaveProperty('unknownField')
  })
})
