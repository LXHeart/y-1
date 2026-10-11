import { describe, expect, it } from 'vitest'
import { projectContinueRoute } from './creation-workspace'
import type { CreationProject } from '../types/creation'

/** 首页「继续创作」目标路由：与 AiCreationCenter.continueProgram 同一套分流口径的最小构造。 */
function project(overrides: Partial<CreationProject>): CreationProject {
  return {
    id: 'draft-1', title: '标题', capability: 'article', status: 'draft', version: 1,
    workspace: {}, resultAssetIds: [], runIds: [], updatedAt: '2026-10-11T00:00:00Z',
    ...overrides,
  } as CreationProject
}

describe('projectContinueRoute', () => {
  it('小红书图文草稿在 AI 应用分流 xhs-studio，带 recipe 存量留在旧视图', () => {
    const fresh = project({ platform: 'xiaohongshu' })
    expect(projectContinueRoute(fresh, { aiApp: true })).toEqual({ name: 'xhs-studio', query: { draft: 'draft-1' } })
    const withRecipe = project({ platform: 'xiaohongshu', workspace: { inputs: { studio: { recipe: { id: 'cover-only' } } } } })
    expect(projectContinueRoute(withRecipe, { aiApp: true })).toEqual({ name: 'article', query: { draft: 'draft-1' } })
  })
  it('小红书图文在草场侧不分流；其余图文/朋友圈走能力视图', () => {
    expect(projectContinueRoute(project({ platform: 'xiaohongshu' }), { aiApp: false })).toEqual({ name: 'article', query: { draft: 'draft-1' } })
    expect(projectContinueRoute(project({ capability: 'moments' }), { aiApp: true })).toEqual({ name: 'moments', query: { draft: 'draft-1' } })
  })
  it('图片评价文案走图片分析；其余图片回图片生成', () => {
    expect(projectContinueRoute(project({ capability: 'image', workspace: { workflow: 'review-copy' } }), { aiApp: true }))
      .toEqual({ name: 'image', query: { draft: 'draft-1' } })
    expect(projectContinueRoute(project({ capability: 'image' }), { aiApp: true }))
      .toEqual({ name: 'images', query: { tab: 'generate' } })
  })
  it('视频脚本走制作台；其余视频回工具箱视频辅助', () => {
    expect(projectContinueRoute(project({ capability: 'video', workspace: { workflow: 'video-script' } }), { aiApp: true }))
      .toEqual({ name: 'video-production', query: { draft: 'draft-1' } })
    expect(projectContinueRoute(project({ capability: 'video' }), { aiApp: true }))
      .toEqual({ name: 'tools', query: { tab: 'video' } })
  })
})
