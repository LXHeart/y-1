import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ResolvedProviderConfig } from './types.js'

const {
  loggerWarnMock,
  loggerErrorMock,
} = vi.hoisted(() => ({
  loggerWarnMock: vi.fn(),
  loggerErrorMock: vi.fn(),
}))

vi.mock('../../lib/logger.js', () => ({
  logger: {
    info: vi.fn(),
    warn: loggerWarnMock,
    error: loggerErrorMock,
  },
}))

import { cozeProvider } from './coze-provider.js'

describe('cozeProvider', () => {
  const originalFetch = globalThis.fetch
  const config: ResolvedProviderConfig = {
    baseUrl: 'https://workflow.example.com/run',
    apiKey: 'coze-token',
  }

  beforeEach(() => {
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('disables redirect following for analyze requests', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      video_captions: '字幕',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock

    const result = await cozeProvider.analyze('https://example.com/video.mp4', config, {
      timeoutMs: 5_000,
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://workflow.example.com/run',
      expect.objectContaining({
        method: 'POST',
        redirect: 'error',
      }),
    )
    expect(result).toEqual({
      videoCaptions: '字幕',
    })
  })
})
