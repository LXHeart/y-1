import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const {
  loggerInfoMock,
  generateImageMock,
} = vi.hoisted(() => ({
  loggerInfoMock: vi.fn(),
  generateImageMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: vi.fn(),
    error: vi.fn(),
  },
}))

vi.mock('./article-image.service.js', () => ({
  generateImage: generateImageMock,
}))

const {
  buildSceneImagePrompt,
  buildAssetImagePrompt,
  generateSceneImage,
  generateAllSceneImages,
  generateAssetImage,
  generateAllAssetImages,
} = await import('./video-recreation-image.service.js')

beforeEach(() => {
  generateImageMock.mockReset()
  loggerInfoMock.mockReset()

  generateImageMock.mockResolvedValue({
    imageUrl: 'https://images.example.com/scene-1.png',
    revisedPrompt: 'Revised prompt for scene 1',
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ---------------------------------------------------------------------------
// buildSceneImagePrompt
// ---------------------------------------------------------------------------
describe('buildSceneImagePrompt', () => {
  it('builds prompt from all scene fields with overallStyle', () => {
    const scene = {
      shotDescription: 'Close-up of a sunrise over mountains',
      characterDescription: 'A lone hiker standing on a peak',
      actionMovement: 'Slowly raising arms toward the sky',
      dialogueVoiceover: 'This is my favorite place on earth',
      sceneEnvironment: 'Snow-capped peaks at golden hour',
    }

    const result = buildSceneImagePrompt(scene, 'Cinematic, warm color grading')

    expect(result).toBe(
      'Close-up of a sunrise over mountains. A lone hiker standing on a peak. Snow-capped peaks at golden hour. Action: Slowly raising arms toward the sky. Style: Cinematic, warm color grading',
    )
  })

  it('builds prompt from all scene fields without overallStyle', () => {
    const scene = {
      shotDescription: 'Wide shot of a city skyline',
      characterDescription: 'A detective in a trench coat',
      actionMovement: 'Looking through binoculars',
      dialogueVoiceover: 'Something is not right here',
      sceneEnvironment: 'Rainy rooftop overlooking downtown',
    }

    const result = buildSceneImagePrompt(scene)

    expect(result).toBe(
      'Wide shot of a city skyline. A detective in a trench coat. Rainy rooftop overlooking downtown. Action: Looking through binoculars',
    )
  })

  it('builds prompt with only shotDescription and sceneEnvironment', () => {
    const scene = {
      shotDescription: 'Aerial view of a tropical island',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Crystal clear turquoise water surrounding white sand beaches',
    }

    const result = buildSceneImagePrompt(scene, 'Documentary style')

    expect(result).toBe(
      'Aerial view of a tropical island. Crystal clear turquoise water surrounding white sand beaches. Style: Documentary style',
    )
  })

  it('builds prompt with only shotDescription and characterDescription', () => {
    const scene = {
      shotDescription: 'Medium shot of a musician on stage',
      characterDescription: 'A young woman playing an electric guitar',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: '',
    }

    const result = buildSceneImagePrompt(scene)

    expect(result).toBe(
      'Medium shot of a musician on stage. A young woman playing an electric guitar',
    )
  })

  it('builds prompt with only actionMovement', () => {
    const scene = {
      shotDescription: '',
      characterDescription: '',
      actionMovement: 'Running through a field of flowers',
      dialogueVoiceover: '',
      sceneEnvironment: '',
    }

    const result = buildSceneImagePrompt(scene)

    expect(result).toBe('Action: Running through a field of flowers')
  })

  it('builds prompt with only overallStyle', () => {
    const scene = {
      shotDescription: '',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: '',
    }

    const result = buildSceneImagePrompt(scene, 'Oil painting style')

    expect(result).toBe('Style: Oil painting style')
  })

  it('returns empty string when all fields are empty and no overallStyle', () => {
    const scene = {
      shotDescription: '',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: '',
    }

    const result = buildSceneImagePrompt(scene)

    expect(result).toBe('')
  })

  it('ignores dialogueVoiceover since it is not included in the prompt', () => {
    const scene = {
      shotDescription: 'Interior of a cozy cafe',
      characterDescription: 'An elderly man reading a newspaper',
      actionMovement: 'Turning the page slowly',
      dialogueVoiceover: 'They say the past always catches up with you',
      sceneEnvironment: 'Warm lighting, wooden furniture, steam rising from coffee cup',
    }

    const result = buildSceneImagePrompt(scene)

    expect(result).not.toContain('dialogueVoiceover')
    expect(result).not.toContain('They say the past always catches up with you')
  })

  it('joins parts with ". " separator', () => {
    const scene = {
      shotDescription: 'Part one',
      characterDescription: 'Part two',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Part three',
    }

    const result = buildSceneImagePrompt(scene)

    expect(result).toBe('Part one. Part two. Part three')
  })

  it('handles undefined overallStyle by not including it', () => {
    const scene = {
      shotDescription: 'A test scene',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: '',
    }

    const result = buildSceneImagePrompt(scene, undefined)

    expect(result).toBe('A test scene')
  })
})

// ---------------------------------------------------------------------------
// buildAssetImagePrompt
// ---------------------------------------------------------------------------
describe('buildAssetImagePrompt', () => {
  it('builds prompt for character three-view asset', () => {
    const result = buildAssetImagePrompt('character-three-view', {
      id: 'character-1',
      name: '阿明',
      description: '黑色短发，白色卫衣',
      threeViewPrompt: '角色三视图，正侧背完整设定图',
    }, 'Cinematic')

    expect(result).toContain('角色三视图，正侧背完整设定图')
    expect(result).toContain('Style: Cinematic')
  })

  it('builds prompt for scene asset with title and prompt', () => {
    const result = buildAssetImagePrompt('scene', {
      id: 'scene-1',
      title: '教室门口',
      description: '黄昏下的学校走廊',
      imagePrompt: '黄昏学校走廊，电影感镜头',
    })

    expect(result).toBe('教室门口. 黄昏下的学校走廊. 黄昏学校走廊，电影感镜头')
  })
})

// ---------------------------------------------------------------------------
// generateSceneImage
// ---------------------------------------------------------------------------
describe('generateSceneImage', () => {
  it('calls article image service with built prompt and default size', async () => {
    const scene = {
      shotDescription: 'A beautiful sunset',
      characterDescription: 'A woman with flowing hair',
      actionMovement: 'Standing still',
      dialogueVoiceover: '',
      sceneEnvironment: 'Beach with waves crashing',
    }

    const result = await generateSceneImage({ scene })

    expect(generateImageMock).toHaveBeenCalledWith({
      prompt: expect.stringContaining('A beautiful sunset'),
      size: '1024x1792',
      userId: undefined,
      signal: undefined,
    })
    expect(result).toEqual({
      imageUrl: 'https://images.example.com/scene-1.png',
      revisedPrompt: 'Revised prompt for scene 1',
    })
  })

  it('passes overallStyle through to the prompt', async () => {
    const scene = {
      shotDescription: 'A forest path',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Dense trees with sunlight filtering through',
    }

    await generateSceneImage({ scene, overallStyle: 'Watercolor painting' })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        prompt: expect.stringContaining('Style: Watercolor painting'),
      }),
    )
  })

  it('passes custom size when provided', async () => {
    const scene = {
      shotDescription: 'Square composition',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Abstract shapes',
    }

    await generateSceneImage({ scene, size: '1024x1024' })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        size: '1024x1024',
      }),
    )
  })

  it('passes userId when provided', async () => {
    const scene = {
      shotDescription: 'A portrait',
      characterDescription: 'A young girl',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Garden background',
    }

    await generateSceneImage({ scene, userId: 'user-42' })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        userId: 'user-42',
      }),
    )
  })

  it('passes abort signal when provided', async () => {
    const scene = {
      shotDescription: 'A landscape',
      characterDescription: '',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'Mountains',
    }
    const controller = new AbortController()

    await generateSceneImage({ scene, signal: controller.signal })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        signal: controller.signal,
      }),
    )
  })

  it('propagates errors from article image service', async () => {
    generateImageMock.mockRejectedValueOnce(new Error('Image generation failed'))

    const scene = {
      shotDescription: 'A scene',
      characterDescription: 'A character',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'An environment',
    }

    await expect(generateSceneImage({ scene })).rejects.toThrow('Image generation failed')
  })

  it('logs prompt length when generating', async () => {
    const scene = {
      shotDescription: 'A detailed scene description with many words',
      characterDescription: 'A detailed character',
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: 'A detailed environment description',
    }

    await generateSceneImage({ scene })

    expect(loggerInfoMock).toHaveBeenCalledWith(
      expect.objectContaining({
        promptLength: expect.any(Number),
      }),
      'Generating scene reference image',
    )
  })
})

// ---------------------------------------------------------------------------
// generateAssetImage
// ---------------------------------------------------------------------------
describe('generateAssetImage', () => {
  it('calls article image service with built asset prompt and default size', async () => {
    const result = await generateAssetImage({
      assetType: 'prop',
      asset: {
        id: 'prop-1',
        name: '旧相机',
        description: '银黑色胶片相机',
        imagePrompt: '银黑色胶片相机产品设定图',
      },
    })

    expect(generateImageMock).toHaveBeenCalledWith({
      prompt: '旧相机. 银黑色胶片相机. 银黑色胶片相机产品设定图',
      size: '1024x1792',
      userId: undefined,
      signal: undefined,
    })
    expect(result).toEqual({
      imageUrl: 'https://images.example.com/scene-1.png',
      revisedPrompt: 'Revised prompt for scene 1',
    })
  })
})

// ---------------------------------------------------------------------------
// generateAllSceneImages
// ---------------------------------------------------------------------------
describe('generateAllSceneImages', () => {
  it('generates images for all scenes sequentially', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
      {
        shotDescription: 'Scene 2',
        characterDescription: 'Character 2',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 2',
      },
    ]

    generateImageMock
      .mockResolvedValueOnce({ imageUrl: 'https://images.example.com/scene-1.png' })
      .mockResolvedValueOnce({ imageUrl: 'https://images.example.com/scene-2.png' })

    const results = await generateAllSceneImages({ scenes })

    expect(results).toHaveLength(2)
    expect(results[0].imageUrl).toBe('https://images.example.com/scene-1.png')
    expect(results[1].imageUrl).toBe('https://images.example.com/scene-2.png')
    expect(generateImageMock).toHaveBeenCalledTimes(2)
  })

  it('passes overallStyle to each scene image generation', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
    ]

    await generateAllSceneImages({ scenes, overallStyle: 'Anime style' })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        prompt: expect.stringContaining('Style: Anime style'),
      }),
    )
  })

  it('passes custom size to each scene image generation', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
    ]

    await generateAllSceneImages({ scenes, size: '1792x1024' })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        size: '1792x1024',
      }),
    )
  })

  it('passes userId to each scene image generation', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
    ]

    await generateAllSceneImages({ scenes, userId: 'user-99' })

    expect(generateImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        userId: 'user-99',
      }),
    )
  })

  it('returns empty array for empty scenes list', async () => {
    const results = await generateAllSceneImages({ scenes: [] })

    expect(results).toEqual([])
    expect(generateImageMock).not.toHaveBeenCalled()
  })

  it('stops early when abort signal is already triggered before first scene', async () => {
    const controller = new AbortController()
    controller.abort()

    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
      {
        shotDescription: 'Scene 2',
        characterDescription: 'Character 2',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 2',
      },
    ]

    const results = await generateAllSceneImages({ scenes, signal: controller.signal })

    expect(results).toEqual([])
    expect(generateImageMock).not.toHaveBeenCalled()
  })

  it('stops processing remaining scenes after abort signal fires between scenes', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
      {
        shotDescription: 'Scene 2',
        characterDescription: 'Character 2',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 2',
      },
      {
        shotDescription: 'Scene 3',
        characterDescription: 'Character 3',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 3',
      },
    ]

    generateImageMock.mockImplementation(async () => {
      // Abort after the first call completes
      return { imageUrl: 'https://images.example.com/scene-1.png' }
    })

    const controller = new AbortController()

    // Set up to abort after the first scene completes
    generateImageMock.mockImplementationOnce(async () => {
      controller.abort()
      return { imageUrl: 'https://images.example.com/scene-1.png' }
    })

    const results = await generateAllSceneImages({ scenes, signal: controller.signal })

    // Only the first scene should have been generated
    expect(results).toHaveLength(1)
    expect(generateImageMock).toHaveBeenCalledTimes(1)
  })

  it('propagates errors from individual scene generation', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Character 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Environment 1',
      },
    ]

    generateImageMock.mockRejectedValueOnce(new Error('Upstream service unavailable'))

    await expect(generateAllSceneImages({ scenes })).rejects.toThrow('Upstream service unavailable')
  })

  it('logs scene index and total for each scene', async () => {
    const scenes = [
      {
        shotDescription: 'Scene 1',
        characterDescription: 'Char 1',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Env 1',
      },
      {
        shotDescription: 'Scene 2',
        characterDescription: 'Char 2',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'Env 2',
      },
    ]

    await generateAllSceneImages({ scenes })

    expect(loggerInfoMock).toHaveBeenCalledWith(
      { sceneIndex: 1, totalScenes: 2 },
      'Generating scene image',
    )
    expect(loggerInfoMock).toHaveBeenCalledWith(
      { sceneIndex: 2, totalScenes: 2 },
      'Generating scene image',
    )
  })
})

// ---------------------------------------------------------------------------
// generateAllAssetImages
// ---------------------------------------------------------------------------
describe('generateAllAssetImages', () => {
  it('generates images for all assets sequentially', async () => {
    generateImageMock
      .mockResolvedValueOnce({ imageUrl: 'https://images.example.com/asset-1.png' })
      .mockResolvedValueOnce({ imageUrl: 'https://images.example.com/asset-2.png' })

    const results = await generateAllAssetImages({
      assetType: 'scene',
      assets: [
        {
          id: 'scene-1',
          title: '教室门口',
          description: '黄昏下的学校走廊',
          imagePrompt: '黄昏学校走廊，电影感镜头',
        },
        {
          id: 'scene-2',
          title: '操场边',
          description: '傍晚操场看台',
          imagePrompt: '傍晚操场看台，电影感镜头',
        },
      ],
    })

    expect(results).toHaveLength(2)
    expect(generateImageMock).toHaveBeenCalledTimes(2)
  })

  it('stops processing remaining assets after abort signal fires', async () => {
    const controller = new AbortController()

    generateImageMock.mockImplementationOnce(async () => {
      controller.abort()
      return { imageUrl: 'https://images.example.com/asset-1.png' }
    })

    const results = await generateAllAssetImages({
      assetType: 'prop',
      assets: [
        {
          id: 'prop-1',
          name: '旧相机',
          description: '银黑色胶片相机',
          imagePrompt: '银黑色胶片相机产品设定图',
        },
        {
          id: 'prop-2',
          name: '旧皮箱',
          description: '棕色旧皮箱',
          imagePrompt: '棕色旧皮箱产品设定图',
        },
      ],
      signal: controller.signal,
    })

    expect(results).toHaveLength(1)
    expect(generateImageMock).toHaveBeenCalledTimes(1)
  })
})
