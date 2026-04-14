import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  fetchTextMock,
  fetchPageWithBrowserMock,
  getPersistedDouyinStorageStatePathMock,
  markDouyinSessionExpiredMock,
  markDouyinSessionUsedMock,
  loggerInfoMock,
  loggerWarnMock,
} = vi.hoisted(() => ({
  fetchTextMock: vi.fn(),
  fetchPageWithBrowserMock: vi.fn(),
  getPersistedDouyinStorageStatePathMock: vi.fn(),
  markDouyinSessionExpiredMock: vi.fn(),
  markDouyinSessionUsedMock: vi.fn(),
  loggerInfoMock: vi.fn(),
  loggerWarnMock: vi.fn(),
}))

vi.mock('../lib/http.js', () => ({
  fetchText: fetchTextMock,
}))

vi.mock('../lib/browser.js', () => ({
  fetchPageWithBrowser: fetchPageWithBrowserMock,
}))

vi.mock('./douyin-session.service.js', () => ({
  getPersistedDouyinStorageStatePath: getPersistedDouyinStorageStatePathMock,
  markDouyinSessionExpired: markDouyinSessionExpiredMock,
  markDouyinSessionUsed: markDouyinSessionUsedMock,
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: loggerWarnMock,
  },
}))

import { extractDouyinEntryUrl, resolveDouyinSource, resolveDouyinVideoAsset, type DouyinSourceMaterial } from './douyin-resolve.service.js'

const playableSnippet = '{"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'

function buildHtml(input: {
  title?: string
  bodyText?: string
  scripts?: string[]
  metaDescription?: string
}): string {
  const title = input.title ? `<title>${input.title}</title>` : ''
  const metaDescription = input.metaDescription ? `<meta name="description" content="${input.metaDescription}">` : ''
  const scripts = (input.scripts || []).map((content) => `<script>${content}</script>`).join('')
  const bodyText = input.bodyText ? `<p>${input.bodyText}</p>` : ''

  return `<html><head>${title}${metaDescription}</head><body>${bodyText}${scripts}</body></html>`
}

beforeEach(() => {
  fetchTextMock.mockReset()
  fetchPageWithBrowserMock.mockReset()
  getPersistedDouyinStorageStatePathMock.mockReset()
  markDouyinSessionExpiredMock.mockReset()
  markDouyinSessionUsedMock.mockReset()
  loggerInfoMock.mockReset()
  loggerWarnMock.mockReset()

  getPersistedDouyinStorageStatePathMock.mockResolvedValue(undefined)
  markDouyinSessionExpiredMock.mockResolvedValue(undefined)
  markDouyinSessionUsedMock.mockResolvedValue(undefined)
})

describe('extractDouyinEntryUrl', () => {
  it('extracts a valid douyin short link from share text', () => {
    const input = '7.54 复制打开抖音，看看【测试】 https://v.douyin.com/AbCdEfG/ '

    expect(extractDouyinEntryUrl(input)).toBe('https://v.douyin.com/AbCdEfG/')
  })

  it('ignores trailing punctuation around the url', () => {
    const input = '点这个：https://www.douyin.com/video/1234567890?foo=bar。'

    expect(extractDouyinEntryUrl(input)).toBe('https://www.douyin.com/video/1234567890?foo=bar')
  })

  it('throws when the text does not contain an allowed douyin url', () => {
    expect(() => extractDouyinEntryUrl('https://example.com/video/1')).toThrow(AppError)
  })
})

describe('resolveDouyinSource', () => {
  it('returns after desktop http when page json already contains a playable video url', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: [playableSnippet],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(1)
    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('desktop_http')
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('extracts durationSeconds from aweme detail duration when video duration is absent', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"aweme_detail":{"duration":118000,"video":{"play_addr":{"url_list":["https://v3-dy-o.zjcdn.com/play/video.mp4"]}}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(118)
  })

  it('extracts durationSeconds from itemStruct duration when video duration is absent', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"itemInfo":{"itemStruct":{"duration":87,"video":{"play_addr":{"url_list":["https://v3-dy-o.zjcdn.com/play/video.mp4"]}}}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(87)
  })

  it('extracts durationSeconds from awemeDetail durationMs when video duration is absent', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"awemeDetail":{"durationMs":120500,"video":{"playAddr":{"urlList":["https://v3-dy-o.zjcdn.com/play/video.mp4"]}}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(121)
  })

  it('extracts durationSeconds from play address duration when other duration fields are absent', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"aweme_detail":{"video":{"play_addr":{"duration":94000,"url_list":["https://v3-dy-o.zjcdn.com/play/video.mp4"]}}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(94)
  })

  it('extracts durationSeconds from loaderData videoInfoRes item list video duration', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"video":{"duration":52245}}]}}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(53)
    expect(result.durationSourceStage).toBe('page_json')
    expect(result.durationSourceSnippetIndex).toBe(0)
    expect(result.durationSourcePath).toBe('loaderData.video_(id)/page.videoInfoRes.item_list[0].video.duration')
  })

  it('records duration metadata source when duration is extracted from itemStruct duration', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"itemInfo":{"itemStruct":{"duration":87,"video":{"play_addr":{"url_list":["https://v3-dy-o.zjcdn.com/play/video.mp4"]}}}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(87)
    expect(result.durationSourceStage).toBe('page_json')
    expect(result.durationSourcePath).toBe('itemInfo.itemStruct.duration')
    expect(result.durationSourceSnippetIndex).toBe(0)
  })

  it('extracts durationSeconds from aweme video metadata in page json snippets', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: ['{"aweme_detail":{"video":{"duration":118000}},"play_addr":"https://v3-dy-o.zjcdn.com/play/video.mp4"}'],
      }),
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(result.durationSeconds).toBe(118)
  })

  it('skips the canonical desktop retry when the first desktop response is already the canonical video page', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '桌面正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '移动正文',
          scripts: [playableSnippet],
        }),
      })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(2)
    expect(fetchTextMock).not.toHaveBeenNthCalledWith(
      2,
      'https://www.douyin.com/video/1234567890',
      expect.any(Object),
      expect.any(Number),
    )
    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('mobile_http')
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('skips the canonical desktop retry when the first desktop response is already the canonical video page surface with query params', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890?previous_page=web_code_link',
        body: buildHtml({
          bodyText: '桌面正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '移动正文',
          scripts: [playableSnippet],
        }),
      })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(2)
    expect(fetchTextMock).not.toHaveBeenNthCalledWith(
      2,
      'https://www.douyin.com/video/1234567890',
      expect.any(Object),
      expect.any(Number),
    )
    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('mobile_http')
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('skips mobile http for direct video urls and goes straight to browser when desktop remains unresolved', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890?previous_page=web_code_link',
      body: buildHtml({
        bodyText: '桌面正文',
      }),
    })

    fetchPageWithBrowserMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      html: buildHtml({
        bodyText: '浏览器正文 captcha 安全验证',
      }),
      pageJsonSnippets: [],
      networkJsonSnippets: [playableSnippet],
      mediaUrls: [],
    })

    const result = await resolveDouyinSource('https://www.douyin.com/video/1234567890?previous_page=web_code_link')

    expect(fetchTextMock).toHaveBeenCalledTimes(1)
    expect(fetchPageWithBrowserMock).toHaveBeenCalledWith(
      'https://www.douyin.com/video/1234567890?previous_page=web_code_link',
      expect.objectContaining({
        preferMobile: false,
        allowMobileRetry: true,
      }),
    )
    expect(result.fetchStage).toBe('desktop_http')
    expect(result.isChallengePage).toBe(false)
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('keeps direct video url failures unresolved when browser only returns a challenge page', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890?previous_page=web_code_link',
      body: buildHtml({
        bodyText: '桌面正文',
      }),
    })

    fetchPageWithBrowserMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      html: buildHtml({
        bodyText: '浏览器正文 captcha 安全验证',
      }),
      pageJsonSnippets: [],
      networkJsonSnippets: [],
      mediaUrls: [],
    })

    const result = await resolveDouyinSource('https://www.douyin.com/video/1234567890?previous_page=web_code_link')

    expect(fetchTextMock).toHaveBeenCalledTimes(1)
    expect(fetchPageWithBrowserMock).toHaveBeenCalledTimes(1)
    expect(result.fetchStage).toBe('desktop_http')
    expect(result.isChallengePage).toBe(false)
    expect(() => resolveDouyinVideoAsset(result)).toThrow(AppError)
  })

  it('retries the canonical desktop video page before falling back when the first desktop response is not canonical', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/discover?modal_id=1234567890',
        body: buildHtml({
          bodyText: '短链跳转正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '直连正文',
          scripts: [playableSnippet],
        }),
      })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(2)
    expect(fetchTextMock).toHaveBeenNthCalledWith(
      2,
      'https://www.douyin.com/video/1234567890',
      expect.any(Object),
      expect.any(Number),
    )
    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('desktop_http')
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('continues to mobile http when desktop retry still has no playable video', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.iesdouyin.com/share/video/1234567890/',
        body: buildHtml({
          title: '分享页标题',
          bodyText: '桌面正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '移动正文',
          scripts: [playableSnippet],
        }),
      })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(2)
    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('mobile_http')
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('ignores untrusted playable-looking json urls and continues to the next fetch stage', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '桌面正文',
          scripts: ['{"play_addr":"https://evil.example.com/play/video.mp4"}'],
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '移动正文',
          scripts: [playableSnippet],
        }),
      })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(2)
    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('mobile_http')
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('disables mobile browser retry when desktop material is already the best non-challenge canonical video page', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/discover?modal_id=1234567890',
        body: buildHtml({
          bodyText: '桌面正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '直连正文更多内容',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '移动正文 captcha 安全验证',
        }),
      })

    fetchPageWithBrowserMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      html: buildHtml({
        bodyText: '浏览器正文 captcha 安全验证',
      }),
      pageJsonSnippets: [],
      networkJsonSnippets: [],
      mediaUrls: [],
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchPageWithBrowserMock).toHaveBeenCalledWith(
      'https://v.douyin.com/abc/',
      expect.objectContaining({
        preferMobile: false,
        allowMobileRetry: false,
      }),
    )
    expect(result.fetchStage).toBe('desktop_http')
    expect(result.resolvedUrl).toBe('https://www.douyin.com/video/1234567890')
    expect(result.isChallengePage).toBe(false)
  })


  it('returns merged desktop material after mobile http when challenge share page already provides a playable snippet', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/discover?modal_id=1234567890',
        body: buildHtml({
          bodyText: '桌面正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '直连正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.iesdouyin.com/share/video/1234567890/',
        body: buildHtml({
          title: '分享页标题',
          bodyText: '移动正文 captcha 安全验证',
          scripts: [playableSnippet],
        }),
      })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchPageWithBrowserMock).not.toHaveBeenCalled()
    expect(result.fetchStage).toBe('desktop_http')
    expect(result.isChallengePage).toBe(false)
    expect(resolveDouyinVideoAsset(result).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/play/video.mp4')
  })

  it('prefers mobile browser follow-up after mobile http already lands on a share page', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/discover?modal_id=1234567890',
        body: buildHtml({
          bodyText: '桌面正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '直连正文',
        }),
      })
      .mockResolvedValueOnce({
        finalUrl: 'https://www.iesdouyin.com/share/video/1234567890/',
        body: buildHtml({
          title: '分享页标题',
          bodyText: '移动正文',
        }),
      })

    fetchPageWithBrowserMock.mockResolvedValueOnce({
      finalUrl: 'https://www.iesdouyin.com/share/video/1234567890/',
      html: buildHtml({
        title: '浏览器分享页',
        bodyText: '浏览器正文',
        scripts: [playableSnippet],
      }),
      pageJsonSnippets: [],
      networkJsonSnippets: [],
      mediaUrls: [],
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchPageWithBrowserMock).toHaveBeenCalledWith(
      'https://v.douyin.com/abc/',
      expect.objectContaining({ preferMobile: true }),
    )
    expect(result.fetchStage).toBe('browser')
  })

  it('does not return early from challenge pages even when they contain playable-looking json', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: 'captcha 安全验证',
          scripts: [playableSnippet],
        }),
      })
      .mockRejectedValueOnce(new AppError('上游请求失败：502', 502))

    fetchPageWithBrowserMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      html: buildHtml({
        bodyText: '浏览器正文',
        scripts: [playableSnippet],
      }),
      pageJsonSnippets: [playableSnippet],
      networkJsonSnippets: [],
      mediaUrls: [],
    })

    const result = await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(fetchTextMock).toHaveBeenCalledTimes(2)
    expect(fetchPageWithBrowserMock).toHaveBeenCalledTimes(1)
    expect(result.isChallengePage).toBe(false)
    expect(result.fetchStage).toBe('browser')
  })

  it('logs duration metadata for a desktop-only resolve', async () => {
    fetchTextMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      body: buildHtml({
        bodyText: '正文',
        scripts: [playableSnippet],
      }),
    })

    await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(loggerInfoMock).toHaveBeenCalledWith(
      expect.objectContaining({
        fetchMode: 'anonymous',
        fetchStage: 'desktop_http',
        durationMs: expect.any(Number),
      }),
      'Douyin fetch stage timing',
    )

    expect(loggerInfoMock).toHaveBeenCalledWith(
      expect.objectContaining({
        durationMs: expect.any(Number),
        finalStage: 'desktop_http',
        usedSession: false,
      }),
      'Douyin resolve timing',
    )
  })

  it('logs duration metadata for fallback stage attempts and failures', async () => {
    fetchTextMock
      .mockResolvedValueOnce({
        finalUrl: 'https://www.douyin.com/video/1234567890',
        body: buildHtml({
          bodyText: '桌面正文',
        }),
      })
      .mockRejectedValueOnce(new AppError('上游请求失败：502', 502))

    fetchPageWithBrowserMock.mockResolvedValueOnce({
      finalUrl: 'https://www.douyin.com/video/1234567890',
      html: buildHtml({
        bodyText: '浏览器正文',
        scripts: [playableSnippet],
      }),
      pageJsonSnippets: [playableSnippet],
      networkJsonSnippets: [],
      mediaUrls: [],
    })

    await resolveDouyinSource('https://v.douyin.com/abc/')

    expect(loggerWarnMock).toHaveBeenCalledWith(
      expect.objectContaining({
        fetchMode: 'anonymous',
        fetchStage: 'mobile_http',
        durationMs: expect.any(Number),
      }),
      'Douyin mobile fetch failed',
    )

    expect(loggerInfoMock).toHaveBeenCalledWith(
      expect.objectContaining({
        fetchMode: 'anonymous',
        fetchStage: 'browser',
        durationMs: expect.any(Number),
      }),
      'Douyin fetch stage timing',
    )
  })
})

describe('resolveDouyinVideoAsset', () => {
  it('ignores lookalike media hosts', () => {
    const source: DouyinSourceMaterial = {
      sourceUrl: 'https://v.douyin.com/abc/',
      resolvedUrl: 'https://www.douyin.com/video/1234567890',
      visibleText: 'test',
      pageJsonSnippets: [],
      browserJsonSnippets: [],
      networkJsonSnippets: [],
      mediaUrls: [
        'https://evilzjcdn.com/video.mp4',
        'https://v3-dy-o.zjcdn.com/video.mp4',
      ],
      isChallengePage: false,
      challengeHints: [],
      fetchMode: 'anonymous',
      fetchStage: 'browser',
      usedSession: false,
      attemptedSession: false,
      durationSeconds: undefined,
    }

    expect(resolveDouyinVideoAsset(source).playableVideoUrl).toBe('https://v3-dy-o.zjcdn.com/video.mp4')
  })
})
