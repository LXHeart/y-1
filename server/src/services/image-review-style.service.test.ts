import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { loggerInfoMock } = vi.hoisted(() => ({
  loggerInfoMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: vi.fn(),
    error: vi.fn(),
  },
}))

vi.mock('../lib/fetch.js', () => ({
  providerFetch: vi.fn(),
}))

vi.mock('./user-settings.service.js', () => ({
  loadUserSettingsRecord: vi.fn(),
  saveUserSettingsRecord: vi.fn(),
}))

function mockJsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

import { providerFetch } from '../lib/fetch.js'
import { loadUserSettingsRecord, saveUserSettingsRecord } from './user-settings.service.js'
import { buildStylePreferenceAppendix, loadImageReviewStylePreferences, saveImageReviewStyleFromEdits } from './image-review-style.service.js'

const mockProviderFetch = vi.mocked(providerFetch)
const mockLoadSettings = vi.mocked(loadUserSettingsRecord)
const mockSaveSettings = vi.mocked(saveUserSettingsRecord)

const config = {
  baseUrl: 'https://api.example.com',
  apiKey: 'test-key',
  model: 'test-model',
}

describe('loadImageReviewStylePreferences', () => {
  beforeEach(() => {
    vi.resetModules()
    mockLoadSettings.mockReset()
  })

  it('returns empty array when no record exists', async () => {
    mockLoadSettings.mockResolvedValue(undefined)

    const result = await loadImageReviewStylePreferences('user-1')

    expect(result).toEqual([])
    expect(mockLoadSettings).toHaveBeenCalledWith('user-1', 'image-review-style')
  })

  it('returns preferences from stored record', async () => {
    mockLoadSettings.mockResolvedValue({
      preferences: ['偏好短句', '不用 emoji'],
      updatedAt: '2026-04-24T00:00:00Z',
    })

    const result = await loadImageReviewStylePreferences('user-1')

    expect(result).toEqual(['偏好短句', '不用 emoji'])
  })

  it('filters non-string and empty preferences', async () => {
    mockLoadSettings.mockResolvedValue({
      preferences: ['valid', '', null, 42, 'also valid'],
      updatedAt: '2026-04-24T00:00:00Z',
    })

    const result = await loadImageReviewStylePreferences('user-1')

    expect(result).toEqual(['valid', 'also valid'])
  })

  it('returns empty array when preferences is not an array', async () => {
    mockLoadSettings.mockResolvedValue({
      preferences: 'not-an-array',
    })

    const result = await loadImageReviewStylePreferences('user-1')

    expect(result).toEqual([])
  })
})

describe('saveImageReviewStyleFromEdits', () => {
  const original = { review: '味道不错，包装也好看。' }
  const edited = { review: '味道挺好的，包装也干净，下次还买。' }

  beforeEach(() => {
    vi.resetModules()
    mockLoadSettings.mockReset()
    mockSaveSettings.mockReset()
    mockProviderFetch.mockReset()
    loggerInfoMock.mockReset()
  })

  it('returns existing preferences without LLM call when no changes', async () => {
    mockLoadSettings.mockResolvedValue({
      preferences: ['偏好短句'],
    })

    const result = await saveImageReviewStyleFromEdits('user-1', { review: 'same' }, { review: 'same' }, config)

    expect(result).toEqual(['偏好短句'])
    expect(mockProviderFetch).not.toHaveBeenCalled()
  })

  it('summarizes edits and merges with existing preferences', async () => {
    mockLoadSettings.mockResolvedValueOnce(undefined)

    mockProviderFetch.mockResolvedValue(mockJsonResponse({
      choices: [{
        message: {
          content: '- 偏好口语化表达\n- 喜欢加回购意愿',
        },
      }],
    }))

    const result = await saveImageReviewStyleFromEdits('user-1', original, edited, config)

    expect(result).toEqual(['偏好口语化表达', '喜欢加回购意愿'])
    expect(mockSaveSettings).toHaveBeenCalledWith('user-1', 'image-review-style', expect.objectContaining({
      preferences: ['偏好口语化表达', '喜欢加回购意愿'],
    }))
  })

  it('merges new rules with existing without duplicates', async () => {
    mockLoadSettings.mockResolvedValueOnce({
      preferences: ['偏好口语化表达'],
    })

    mockProviderFetch.mockResolvedValue(mockJsonResponse({
      choices: [{
        message: {
          content: '- 喜欢加回购意愿\n- 偏好口语化表达',
        },
      }],
    }))

    const result = await saveImageReviewStyleFromEdits('user-1', original, edited, config)

    expect(result).toEqual(['偏好口语化表达', '喜欢加回购意愿'])
  })

  it('throws when LLM call fails', async () => {
    mockLoadSettings.mockResolvedValue(undefined)

    mockProviderFetch.mockResolvedValue(new Response('error', { status: 500 }))

    await expect(saveImageReviewStyleFromEdits('user-1', original, edited, config))
      .rejects.toThrow('风格总结失败（500）')
  })
})

describe('buildStylePreferenceAppendix', () => {
  it('returns empty string for empty preferences', () => {
    expect(buildStylePreferenceAppendix([])).toBe('')
  })

  it('returns formatted appendix with preferences', () => {
    const result = buildStylePreferenceAppendix(['偏好短句', '不用 emoji'])

    expect(result).toContain('用户个人风格偏好')
    expect(result).toContain('- 偏好短句')
    expect(result).toContain('- 不用 emoji')
  })
})
