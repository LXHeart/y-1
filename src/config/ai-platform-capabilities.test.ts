import { describe, expect, test } from 'vitest'
import {
  AI_PLATFORM_CAPABILITY_VERSION,
  AI_PLATFORM_DEFINITIONS,
  getContentForm,
  normalizeContentFormId,
  normalizePlatformId,
  normalizeTaskCreationSelection,
  resolveWorkflow,
} from './ai-platform-capabilities'

describe('AI 平台能力矩阵', () => {
  test('按 PRD 暴露九个平台及合法内容形式', () => {
    expect(AI_PLATFORM_CAPABILITY_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(AI_PLATFORM_DEFINITIONS.map((item) => item.id)).toEqual([
      'xiaohongshu', 'douyin', 'dianping', 'kuaishou', 'wechat-channels',
      'bilibili', 'wechat-official', 'zhihu', 'moments',
    ])
    expect(AI_PLATFORM_DEFINITIONS.map((item) => item.forms.map((form) => form.id))).toEqual([
      ['graphic', 'video'],
      ['graphic', 'video'],
      ['graphic', 'video'],
      ['video'],
      ['video'],
      ['video'],
      ['graphic'],
      ['graphic'],
      ['image-text', 'video-text'],
    ])
  })

  test('区分 PRD 支持与已接入工作流，禁止把未接入组合静默降级', () => {
    expect(resolveWorkflow('wechat-official', 'graphic', 'independent')).toEqual({
      status: 'available', workflowId: 'longform', targetView: 'article',
    })
    expect(resolveWorkflow('dianping', 'graphic', 'independent')).toEqual({
      status: 'available', workflowId: 'review-copy', targetView: 'image',
    })
    expect(resolveWorkflow('douyin', 'graphic', 'independent')).toEqual({
      status: 'available', workflowId: 'longform', targetView: 'article',
    })
    expect(resolveWorkflow('xiaohongshu', 'graphic', 'independent')).toEqual({
      status: 'available', workflowId: 'longform', targetView: 'article',
    })
    expect(resolveWorkflow('zhihu', 'graphic', 'task')).toEqual({
      status: 'available', workflowId: 'longform', targetView: 'article',
    })
    expect(resolveWorkflow('moments', 'image-text', 'independent')).toEqual({
      status: 'planned', workflowId: null, targetView: null,
    })
    expect(resolveWorkflow('moments', 'video-text', 'store')).toEqual({
      status: 'planned', workflowId: null, targetView: null,
    })
    expect(resolveWorkflow('kuaishou', 'graphic', 'independent')).toEqual({
      status: 'unsupported', workflowId: null, targetView: null,
    })
  })

  test('视频参考来源进入参考分析，其余视频进入脚本制作', () => {
    expect(resolveWorkflow('douyin', 'video', 'reference').targetView).toBe('video')
    expect(resolveWorkflow('bilibili', 'video', 'reference').workflowId).toBe('reference-analyze')
    expect(resolveWorkflow('xiaohongshu', 'video', 'store').targetView).toBe('video-production')
    for (const platformId of ['kuaishou', 'wechat-channels', 'bilibili'] as const) {
      expect(resolveWorkflow(platformId, 'video', 'independent')).toEqual({
        status: 'available', workflowId: 'video-script', targetView: 'video-production',
      })
    }
  })

  test('视频创作可显式选择风格化喜剧脚本，默认工作流保持不变', () => {
    expect(resolveWorkflow('douyin', 'video', 'task')).toEqual({
      status: 'available', workflowId: 'video-script', targetView: 'video-production',
    })
    expect(resolveWorkflow('douyin', 'video', 'task', 'comedy-script')).toEqual({
      status: 'available', workflowId: 'comedy-script', targetView: 'comedy',
    })
    expect(resolveWorkflow('xiaohongshu', 'video', 'task', 'video-recreation')).toEqual({
      status: 'available', workflowId: 'video-recreation', targetView: 'video',
    })
    expect(resolveWorkflow('douyin', 'video', 'reference', 'comedy-script')).toEqual({
      status: 'available', workflowId: 'reference-analyze', targetView: 'video',
    })
  })

  test('非法平台或内容形式返回 null，不依赖 UI 隐藏来保证合法性', () => {
    expect(getContentForm('kuaishou', 'graphic')).toBeNull()
    expect(getContentForm('unknown' as never, 'video')).toBeNull()
  })

  test('集中归一草场自由文本，且不把未知或非法组合猜成工作流', () => {
    expect(normalizePlatformId(' B 站 ')).toBe('bilibili')
    expect(normalizePlatformId('微信公众号')).toBe('wechat-official')
    expect(normalizePlatformId('微博')).toBeNull()
    expect(normalizeContentFormId('短视频')).toBe('video')
    expect(normalizeContentFormId('种草笔记')).toBeNull()
    expect(normalizeTaskCreationSelection('抖音', '图文')).toEqual({
      platformId: 'douyin', contentFormId: 'graphic',
    })
    expect(normalizeTaskCreationSelection('快手', '图文')).toEqual({
      platformId: 'kuaishou', contentFormId: null,
    })
  })
})
