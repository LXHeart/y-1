import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'
import { buildAudioDownloadFilename, extractDouyinAudio } from './douyin-audio.service.js'

const ensureTempDir = vi.fn<(dirPath: string) => Promise<void>>()
const createTempPaths = vi.fn<(dirPath: string) => { videoFilePath: string, audioFilePath: string }>()
const downloadVideoToFile = vi.fn<(input: { playableVideoUrl: string, requestHeaders?: Record<string, string>, filename?: string }, targetFilePath: string) => Promise<void>>()
const runCommand = vi.fn<(command: string, args: string[], timeoutMs: number) => Promise<void>>()
const statFile = vi.fn<(filePath: string) => Promise<{ size: number }>>()
const cleanupFile = vi.fn<(filePath: string) => Promise<void>>()

beforeEach(() => {
  ensureTempDir.mockReset()
  createTempPaths.mockReset()
  downloadVideoToFile.mockReset()
  runCommand.mockReset()
  statFile.mockReset()
  cleanupFile.mockReset()

  ensureTempDir.mockResolvedValue(undefined)
  createTempPaths.mockReturnValue({
    videoFilePath: '/tmp/douyin-audio/input.mp4',
    audioFilePath: '/tmp/douyin-audio/output.mp3',
  })
  downloadVideoToFile.mockResolvedValue(undefined)
  runCommand.mockResolvedValue(undefined)
  statFile.mockResolvedValue({ size: 1024 })
  cleanupFile.mockResolvedValue(undefined)
})

describe('buildAudioDownloadFilename', () => {
  it('replaces the original extension with mp3', () => {
    expect(buildAudioDownloadFilename('summer-video.mp4')).toBe('summer-video.mp3')
  })

  it('falls back when filename is missing', () => {
    expect(buildAudioDownloadFilename()).toBe('douyin-video.mp3')
  })
})

describe('extractDouyinAudio', () => {
  it('downloads the video, extracts mp3 audio, and only cleans up the source video temp file', async () => {
    const result = await extractDouyinAudio({
      playableVideoUrl: 'https://v3-dy-o.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      filename: 'sample-video.mp4',
    }, {
      ensureTempDir,
      createTempPaths,
      downloadVideoToFile,
      runCommand,
      statFile,
      cleanupFile,
    })

    expect(ensureTempDir).toHaveBeenCalledTimes(1)
    expect(downloadVideoToFile).toHaveBeenCalledWith({
      playableVideoUrl: 'https://v3-dy-o.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      filename: 'sample-video.mp4',
    }, '/tmp/douyin-audio/input.mp4')
    expect(runCommand).toHaveBeenCalledWith('ffmpeg', [
      '-y',
      '-i',
      '/tmp/douyin-audio/input.mp4',
      '-vn',
      '-c:a',
      'libmp3lame',
      '-b:a',
      '192k',
      '/tmp/douyin-audio/output.mp3',
    ], 45000)
    expect(statFile).toHaveBeenCalledWith('/tmp/douyin-audio/output.mp3')
    expect(cleanupFile).toHaveBeenCalledTimes(1)
    expect(cleanupFile).toHaveBeenCalledWith('/tmp/douyin-audio/input.mp4')
    expect(result).toEqual({
      filePath: '/tmp/douyin-audio/output.mp3',
      fileSize: 1024,
      filename: 'sample-video.mp3',
      mimeType: 'audio/mpeg',
    })
  })

  it('still cleans up temp files when ffmpeg extraction fails', async () => {
    runCommand.mockRejectedValueOnce(new AppError('音频提取失败，请稍后重试', 502))

    await expect(extractDouyinAudio({
      playableVideoUrl: 'https://v3-dy-o.zjcdn.com/video.mp4',
    }, {
      ensureTempDir,
      createTempPaths,
      downloadVideoToFile,
      runCommand,
      statFile,
      cleanupFile,
    })).rejects.toThrow('音频提取失败，请稍后重试')

    expect(cleanupFile).toHaveBeenNthCalledWith(1, '/tmp/douyin-audio/output.mp3')
    expect(cleanupFile).toHaveBeenNthCalledWith(2, '/tmp/douyin-audio/input.mp4')
  })

  it('rejects extracted audio files that exceed the size limit', async () => {
    statFile.mockResolvedValueOnce({ size: 51 * 1024 * 1024 })

    await expect(extractDouyinAudio({
      playableVideoUrl: 'https://v3-dy-o.zjcdn.com/video.mp4',
    }, {
      ensureTempDir,
      createTempPaths,
      downloadVideoToFile,
      runCommand,
      statFile,
      cleanupFile,
    })).rejects.toThrow('提取后的音频文件过大，暂不支持下载')
  })

  it('rejects untrusted video hosts before downloading', async () => {
    await expect(extractDouyinAudio({
      playableVideoUrl: 'https://evil.example.com/video.mp4',
    }, {
      ensureTempDir,
      createTempPaths,
      downloadVideoToFile,
      runCommand,
      statFile,
      cleanupFile,
    })).rejects.toThrow('视频地址不受信任')

    expect(downloadVideoToFile).not.toHaveBeenCalled()
    expect(runCommand).not.toHaveBeenCalled()
  })
})
