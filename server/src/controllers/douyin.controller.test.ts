import { describe, expect, it } from 'vitest'
import { AppError } from '../lib/errors.js'
import { buildAudioDownloadFilename } from '../services/douyin-audio.service.js'
import { assertAllowedVideoUrl, isAllowedVideoHost, resolveUpstreamRedirectUrl } from './douyin.controller.js'

describe('isAllowedVideoHost', () => {
  it('allows exact trusted cdn hosts', () => {
    expect(isAllowedVideoHost('zjcdn.com')).toBe(true)
  })

  it('allows trusted cdn subdomains', () => {
    expect(isAllowedVideoHost('v1.zjcdn.com')).toBe(true)
  })

  it('rejects lookalike hosts', () => {
    expect(isAllowedVideoHost('evilzjcdn.com')).toBe(false)
  })

  it('rejects suffix-trick hosts', () => {
    expect(isAllowedVideoHost('zjcdn.com.evil.com')).toBe(false)
  })
})

describe('assertAllowedVideoUrl', () => {
  it('rejects untrusted video urls', () => {
    expect(() => assertAllowedVideoUrl('https://evilzjcdn.com/video.mp4')).toThrow(AppError)
  })
})

describe('resolveUpstreamRedirectUrl', () => {
  it('accepts trusted redirected video urls', () => {
    expect(resolveUpstreamRedirectUrl(
      'https://aweme.snssdk.com/aweme/v1/playwm/?video_id=123',
      'https://v5-dy-o-abtest.zjcdn.com/video.mp4',
    )).toBe('https://v5-dy-o-abtest.zjcdn.com/video.mp4')
  })

  it('rejects untrusted redirected video urls', () => {
    expect(() => resolveUpstreamRedirectUrl(
      'https://aweme.snssdk.com/aweme/v1/playwm/?video_id=123',
      'https://evil.example.com/video.mp4',
    )).toThrow(AppError)
  })
})

describe('buildAudioDownloadFilename', () => {
  it('swaps the original extension for mp3', () => {
    expect(buildAudioDownloadFilename('sample-video.mp4')).toBe('sample-video.mp3')
  })

  it('falls back to a default mp3 filename', () => {
    expect(buildAudioDownloadFilename()).toBe('douyin-video.mp3')
  })
})
