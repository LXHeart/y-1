import { describe, expect, it, beforeEach } from 'vitest'
import { AppError } from '../lib/errors.js'

beforeEach(() => {
  process.env.BILIBILI_PROXY_TOKEN_SECRET = 'bilibili-proxy-test-secret-1234567890'
})

const {
  assertAllowedBilibiliMediaUrl,
  getSingleHeaderValue,
  resolveBilibiliMediaRedirectUrl,
  shouldFollowBilibiliMediaRedirect,
} = await import('./bilibili-media-http.service.js')

describe('shouldFollowBilibiliMediaRedirect', () => {
  it('returns true for supported upstream redirect status codes', () => {
    expect(shouldFollowBilibiliMediaRedirect(301)).toBe(true)
    expect(shouldFollowBilibiliMediaRedirect(307)).toBe(true)
  })

  it('returns false for non-redirect status codes', () => {
    expect(shouldFollowBilibiliMediaRedirect(200)).toBe(false)
    expect(shouldFollowBilibiliMediaRedirect(404)).toBe(false)
  })
})

describe('getSingleHeaderValue', () => {
  it('returns the first header value when upstream sends an array', () => {
    expect(getSingleHeaderValue(['video/mp4', 'application/octet-stream'])).toBe('video/mp4')
  })

  it('returns an empty string for missing headers', () => {
    expect(getSingleHeaderValue(undefined)).toBe('')
  })
})

describe('assertAllowedBilibiliMediaUrl', () => {
  it('accepts trusted bilivideo hosts', () => {
    expect(() => assertAllowedBilibiliMediaUrl('https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s')).not.toThrow()
  })

  it('rejects untrusted video urls', () => {
    expect(() => assertAllowedBilibiliMediaUrl('https://evilbilivideo.com/video.m4s')).toThrow(AppError)
  })
})

describe('resolveBilibiliMediaRedirectUrl', () => {
  it('accepts trusted redirected video urls', () => {
    expect(resolveBilibiliMediaRedirectUrl(
      'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      'https://cn-gotcha204-2.bilivideo.com/upgcxcode/video.m4s',
    )).toBe('https://cn-gotcha204-2.bilivideo.com/upgcxcode/video.m4s')
  })

  it('rejects untrusted redirected video urls', () => {
    expect(() => resolveBilibiliMediaRedirectUrl(
      'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      'https://evil.example.com/video.m4s',
    )).toThrow(AppError)
  })

  it('rejects redirect responses without a location header', () => {
    expect(() => resolveBilibiliMediaRedirectUrl(
      'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      undefined,
    )).toThrow(AppError)
  })
})
