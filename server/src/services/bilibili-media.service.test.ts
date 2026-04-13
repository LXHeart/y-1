import { describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'
import { prepareBilibiliMediaFile } from './bilibili-media.service.js'

describe('prepareBilibiliMediaFile', () => {
  it('returns downloaded progressive media file metadata', async () => {
    const ensureTempDir = vi.fn(async () => {})
    const createTempPaths = vi.fn(() => ({
      videoTrackFilePath: '/tmp/video.m4s',
      audioTrackFilePath: '/tmp/audio.m4s',
      outputFilePath: '/tmp/output.mp4',
    }))
    const downloadUpstreamFile = vi.fn(async () => {})
    const statFile = vi.fn(async () => ({ size: 123 }))

    const result = await prepareBilibiliMediaFile({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.mp4',
      filename: 'test.mp4',
    }, {
      ensureTempDir,
      createTempPaths,
      downloadUpstreamFile,
      runCommand: vi.fn(async () => {}),
      statFile,
      cleanupFile: vi.fn(async () => {}),
    })

    expect(downloadUpstreamFile).toHaveBeenCalledWith({
      url: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.mp4',
      requestHeaders: undefined,
      expectedContentTypePrefix: 'video/',
      targetFilePath: '/tmp/output.mp4',
    })
    expect(result).toMatchObject({
      filePath: '/tmp/output.mp4',
      fileSize: 123,
      filename: 'test.mp4',
      mimeType: 'video/mp4',
    })
    expect(result.createReadStream).toEqual(expect.any(Function))
  })

  it('downloads dash tracks and muxes them into mp4', async () => {
    const cleanupFile = vi.fn(async () => {})
    const runCommand = vi.fn(async () => {})

    const result = await prepareBilibiliMediaFile({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      filename: 'test.mp4',
    }, {
      ensureTempDir: vi.fn(async () => {}),
      createTempPaths: vi.fn(() => ({
        videoTrackFilePath: '/tmp/video.m4s',
        audioTrackFilePath: '/tmp/audio.m4s',
        outputFilePath: '/tmp/output.mp4',
      })),
      downloadUpstreamFile: vi.fn(async () => {}),
      runCommand,
      statFile: vi.fn(async () => ({ size: 321 })),
      cleanupFile,
    })

    expect(runCommand).toHaveBeenCalledWith(expect.any(String), [
      '-y',
      '-i',
      '/tmp/video.m4s',
      '-i',
      '/tmp/audio.m4s',
      '-c',
      'copy',
      '-movflags',
      '+faststart',
      '/tmp/output.mp4',
    ], expect.any(Number))
    expect(cleanupFile).toHaveBeenCalledWith('/tmp/video.m4s')
    expect(cleanupFile).toHaveBeenCalledWith('/tmp/audio.m4s')
    expect(result).toMatchObject({
      filePath: '/tmp/output.mp4',
      fileSize: 321,
      filename: 'test.mp4',
      mimeType: 'video/mp4',
    })
    expect(result.createReadStream).toEqual(expect.any(Function))
  })

  it('accepts swapped audio and video mp4 content types from upstream tracks', async () => {
    const downloadUpstreamFile = vi.fn(async () => {})

    await prepareBilibiliMediaFile({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      filename: 'test.mp4',
    }, {
      ensureTempDir: vi.fn(async () => {}),
      createTempPaths: vi.fn(() => ({
        videoTrackFilePath: '/tmp/video.m4s',
        audioTrackFilePath: '/tmp/audio.m4s',
        outputFilePath: '/tmp/output.mp4',
      })),
      downloadUpstreamFile,
      runCommand: vi.fn(async () => {}),
      statFile: vi.fn(async () => ({ size: 321 })),
      cleanupFile: vi.fn(async () => {}),
    })

    expect(downloadUpstreamFile).toHaveBeenNthCalledWith(1, {
      url: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: undefined,
      expectedContentTypePrefix: 'video/',
      targetFilePath: '/tmp/video.m4s',
    })
    expect(downloadUpstreamFile).toHaveBeenNthCalledWith(2, {
      url: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      requestHeaders: undefined,
      expectedContentTypePrefix: 'audio/',
      targetFilePath: '/tmp/audio.m4s',
    })
  })

  it('cleans up output file when mux fails', async () => {
    const cleanupFile = vi.fn(async () => {})

    await expect(prepareBilibiliMediaFile({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
    }, {
      ensureTempDir: vi.fn(async () => {}),
      createTempPaths: vi.fn(() => ({
        videoTrackFilePath: '/tmp/video.m4s',
        audioTrackFilePath: '/tmp/audio.m4s',
        outputFilePath: '/tmp/output.mp4',
      })),
      downloadUpstreamFile: vi.fn(async () => {}),
      runCommand: vi.fn(async () => {
        throw new AppError('mux failed', 502)
      }),
      statFile: vi.fn(async () => ({ size: 321 })),
      cleanupFile,
    })).rejects.toThrow('mux failed')

    expect(cleanupFile).toHaveBeenCalledWith('/tmp/output.mp4')
    expect(cleanupFile).toHaveBeenCalledWith('/tmp/video.m4s')
    expect(cleanupFile).toHaveBeenCalledWith('/tmp/audio.m4s')
  })
})
