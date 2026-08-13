// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useBilibiliVideoAnalysis } from './useBilibiliVideoAnalysis'
import { useDouyinVideoAnalysis } from './useDouyinVideoAnalysis'
import { useVideoContentAdaptation } from './useVideoContentAdaptation'
import { useVideoRecreation } from './useVideoRecreation'
import type { VideoTaskExecutionContext } from '../types/video-recreation'

const taskContext: VideoTaskExecutionContext = {
  taskMode: true,
  contextSnapshotId: '13131313-1313-1313-1313-131313131313',
  targetPlatform: 'xiaohongshu',
}

afterEach(() => vi.unstubAllGlobals())

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

const analysis = {
  video_captions: '字幕',
  video_script: '脚本',
  characters_description: '人物',
  voice_description: '声音',
  props_description: '道具',
  scene_description: '场景',
}

describe('视频复刻任务上下文请求', () => {
  test('抖音与 B 站分析复用同一个快照，独立分析不发送任务字段', async () => {
    const bodies: Record<string, unknown>[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      return jsonResponse(analysis)
    }))

    await useDouyinVideoAnalysis().analyzeVideo('/api/douyin/proxy/a', taskContext)
    await useBilibiliVideoAnalysis().analyzeVideo('/api/bilibili/proxy/b', taskContext)
    await useDouyinVideoAnalysis().analyzeVideo('/api/douyin/proxy/c')

    expect(bodies.slice(0, 2).every((body) => body.contextSnapshotId === taskContext.contextSnapshotId)).toBe(true)
    expect(bodies.slice(0, 2).every((body) => body.targetPlatform === 'xiaohongshu')).toBe(true)
    expect(bodies[2]).not.toHaveProperty('taskMode')
    expect(bodies[2]).not.toHaveProperty('contextSnapshotId')
  })

  test('内容改编 JSON 请求附加任务字段，独立请求保持原契约', async () => {
    const bodies: Record<string, unknown>[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      return jsonResponse({ adapted_summary: '摘要' })
    }))
    const composable = useVideoContentAdaptation()

    await composable.adaptContent(
      'douyin', '/api/douyin/proxy/a', { videoScript: '脚本' }, undefined, undefined, taskContext)
    await composable.adaptContent(
      'douyin', '/api/douyin/proxy/a', { videoScript: '脚本' })

    expect(bodies[0]).toMatchObject(taskContext)
    expect(bodies[1]).not.toHaveProperty('taskMode')
    expect(bodies[1]).not.toHaveProperty('contextSnapshotId')
  })

  test('单张与批量场景出图始终复用任务快照', async () => {
    const bodies: Record<string, unknown>[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      return url.includes('generate-all')
        ? jsonResponse({ images: [{ imageUrl: '/all.png' }] })
        : jsonResponse({ imageUrl: '/one.png' })
    }))
    const scene = {
      shotDescription: '特写', characterDescription: '店员', actionMovement: '端菜',
      dialogueVoiceover: '欢迎', sceneEnvironment: '夜市',
    }
    const recreation = useVideoRecreation(taskContext)

    await recreation.generateSceneImage(0, scene, '纪实')
    await recreation.generateAllImages([scene], '纪实')

    expect(bodies).toHaveLength(2)
    expect(bodies.every((body) => body.contextSnapshotId === taskContext.contextSnapshotId)).toBe(true)
    expect(bodies.every((body) => body.taskMode === true)).toBe(true)
  })
})
