// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useCardSeries } from '../../../composables/useCardSeries'
import { useImageAnalysis } from '../../../composables/useImageAnalysis'
import { useMomentsCreation } from '../../../composables/useMomentsCreation'
import { useVideoProduction } from '../../../composables/useVideoProduction'
import { useCreationWorkspace } from '../../../lib/creation-workspace'
import { useArticleWorkspace } from '../../article/composables/useArticleWorkspace'
import { useReviewWorkspace } from '../../image/composables/useReviewWorkspace'
import { useMomentsWorkspace } from '../../moments/composables/useMomentsWorkspace'
import { useVideoWorkspace } from '../../video-production/composables/useVideoWorkspace'
import type { CreationHandoff } from '../../../types/ai-creation'
import type { CreationProject } from '../../../types/creation'

enableAutoUnmount(afterEach)
const route = { query: {} } as RouteLocationNormalizedLoaded
let project: CreationProject
const writes: Array<Record<string, unknown>> = []
beforeEach(() => {
  document.documentElement.dataset.app = 'ai'
  writes.length = 0
  useCreationWorkspace().setPendingContinue(null)
  project = { id: 'fixture', title: '保存的项目', capability: 'article', status: 'draft', version: 3,
    sourceType: 'task', taskId: 'task-1', taskVersion: 7, platform: 'zhihu', contentForm: 'graphic',
    topic: '已保存主题', content: '保存的正文', articleTitle: '保存的标题', resultAssetIds: [], runIds: [],
    updatedAt: '2026-09-09T00:00:00Z', workspace: { schemaVersion: 1, currentStep: 'content', inputs: {
      contextSnapshotId: 'snapshot-1', brief: { processingMode: 'create', extraInstructions: '保留已确认事实' },
    } } }
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      const body = JSON.parse(String(init.body))
      writes.push(body)
      project = { ...project, ...body, version: project.version + 1 }
    }
    return new Response(JSON.stringify({ success: true, data: project }), { headers: { 'Content-Type': 'application/json' } })
  }))
})
afterEach(async () => {
  await flushPromises()
  delete document.documentElement.dataset.app
  useCreationWorkspace().setPendingContinue(null)
  vi.unstubAllGlobals()
})

describe('真实工作流恢复适配', () => {
  test('回答开头、正文、风格、完成态和三项声明恢复到原任务；无变化不创建版本', async () => {
    project.contentMode = 'answer'
    project.questionText = '如何验证一个来源的可信度？'
    project.status = 'completed'
    project.workspace.inputs!.article = { answerOpening: '首先核对资料来源。', titleFormula: 'question', genre: 'analysis', style: 'plain', completed: true }
    project.workspace.delivery = { version: 1, platform: 'zhihu', contentForm: 'graphic',
      declarations: { aiGenerated: 'confirmed', commercial: 'not-applicable', original: 'pending' } }
    useCreationWorkspace().setPendingContinue(project)
    let state!: ReturnType<typeof useArticleWorkspace>
    let article!: ReturnType<typeof useArticleCreation>
    mount(defineComponent({ setup() {
      article = useArticleCreation()
      state = useArticleWorkspace(article, route, () => null, useCardSeries('xiaohongshu'))
      return () => null
    } }), { global: { provide: { articleInitialTopic: ref('') } } })
    await flushPromises()
    expect(article.selectedTitle.value).toBe('首先核对资料来源。')
    expect(article.content.value).toBe('保存的正文')
    expect(article.style.value).toBe('plain')
    expect(article.completed.value).toBe(true)
    expect(state.declarations.value).toEqual({ aiGenerated: 'confirmed', commercial: 'not-applicable', original: 'pending' })
    await state.flush()
    expect(writes).toHaveLength(0)
    article.content.value = '编辑后的正文'
    await state.flush()
    expect(writes[0]).toMatchObject({ content: '编辑后的正文', contentMode: 'answer',
      workspace: { inputs: { contextSnapshotId: 'snapshot-1', article: { answerOpening: '首先核对资料来源。' } } } })
    expect((writes[0].workspace as CreationProject['workspace']).inputs).not.toHaveProperty('content')
  })

  test('点评恢复待润色结果、标签和事实，新编辑沿用同一任务上下文', async () => {
    project.capability = 'image'
    project.platform = 'dianping'
    project.workspace.workflow = 'review-copy'
    project.workspace.currentStep = 'draft-review'
    project.workspace.inputs!.review = { feelings: '排队提示不清楚', reviewLength: 300, imageCount: 2, tags: ['真实体验'] }
    project.runIds = ['run-1']
    useCreationWorkspace().setPendingContinue(project)
    let review!: ReturnType<typeof useImageAnalysis>
    let state!: ReturnType<typeof useReviewWorkspace>
    mount(defineComponent({ setup() {
      review = useImageAnalysis()
      state = useReviewWorkspace(review, route, () => null)
      return () => null
    } }))
    await flushPromises()
    expect(review.result.value).toMatchObject({ review: '保存的正文', tags: ['真实体验'], imageCount: 2, runId: 'run-1' })
    expect(review.generationStage.value).toBe('draft-review')
    review.startEditing()
    review.editReview.value = '保留不足的修订正文'
    await state.flush()
    expect(writes[0]).toMatchObject({ content: '保留不足的修订正文', platform: 'dianping',
      workspace: { workflow: 'review-copy', inputs: { contextSnapshotId: 'snapshot-1' } } })
  })

  test('朋友圈正文、图序和配文一起恢复，清空感受也能保存', async () => {
    project.capability = 'moments'
    project.platform = 'moments'
    project.workspace.inputs!.moments = { style: 'lifestyle', feelings: '旧感受',
      imageOrder: [{ index: 2, reason: '封面' }, { index: 1, reason: '细节' }], captions: [{ index: 2, text: '现场记录' }] }
    useCreationWorkspace().setPendingContinue(project)
    let moments!: ReturnType<typeof useMomentsCreation>
    let state!: ReturnType<typeof useMomentsWorkspace>
    mount(defineComponent({ setup() {
      moments = useMomentsCreation()
      state = useMomentsWorkspace(moments, route, () => null)
      return () => null
    } }))
    await flushPromises()
    expect(moments.result.value?.copy).toBe('保存的正文')
    expect(moments.result.value?.imageOrder.map(item => item.index)).toEqual([2, 1])
    moments.feelings.value = ''
    await state.flush()
    expect(writes[0]).toMatchObject({ topic: '已保存主题', content: '保存的正文',
      workspace: { inputs: { moments: { feelings: '', captions: [{ index: 2, text: '现场记录' }] } } } })
    expect((writes[0].workspace as CreationProject['workspace']).inputs).not.toHaveProperty('topic')
  })

  test('恢复视频引用失败仍保留 ID 与可编辑脚本，不落临时链接或调用生成', async () => {
    project.capability = 'video'
    project.platform = 'bilibili'
    project.workspace.workflow = 'video-script'
    project.workspace.currentStep = 'storyboard'
    project.workspace.inputs!.video = { productionTaskId: 'production-1', storyboardId: 'storyboard-1',
      form: { shopName: '测试店铺', customPrompt: '保留时长', industryType: '餐饮' },
      shots: [{ id: 'shot-1', seq: 1, visual: '门头', narration: '原始旁白', plannedSeconds: 5, cameraMove: '固定机位', anchorImageIndex: 1, prompt: '' }] }
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementationOnce(async () => new Response(JSON.stringify({ error: '素材不可用' }), { status: 404 }))
    useCreationWorkspace().setPendingContinue(project)
    let video!: ReturnType<typeof useVideoProduction>
    let state!: ReturnType<typeof useVideoWorkspace>
    mount(defineComponent({ setup() {
      video = useVideoProduction()
      state = useVideoWorkspace(video, route, () => null, () => {})
      return () => null
    } }))
    await flushPromises()
    expect(video.shots.value[0].narration).toBe('原始旁白')
    video.form.value.shopDescription = '补充资料'
    await state.flush()
    expect(writes[0]).toMatchObject({ workspace: { inputs: { video: { storyboardId: 'storyboard-1', productionTaskId: 'production-1' } } } })
    expect(JSON.stringify(writes[0])).not.toMatch(/anchorUrl|dataUrl|signedUrl/)
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).includes('storyboard') && init?.method === 'POST')).toBe(false)
  })

  test.each(['independent', 'task', 'store', 'hot-topic', 'reference'] as const)('来源 %s 的补充要求单独保存到 Brief', async sourceType => {
    const sources = { independent: { type: 'independent' }, task: { type: 'task', taskId: 'task-2' },
      store: { type: 'store', storeId: 'store-2', organizationId: 'org-2' },
      'hot-topic': { type: 'hot-topic', title: '热点' }, reference: { type: 'reference', sourceUrl: 'https://example.test/source' } }
    const handoff = { revision: 1, platformId: 'moments', contentFormId: 'image-text', targetView: 'moments',
      workflowId: 'moments-image-text', source: sources[sourceType], prefill: { topic: '主题', instructions: '保留金额 68 元' } } as CreationHandoff
    let moments!: ReturnType<typeof useMomentsCreation>
    mount(defineComponent({ setup() {
      moments = useMomentsCreation()
      useMomentsWorkspace(moments, route, () => handoff)
      return () => null
    } }))
    expect(moments.brief.value?.extraInstructions).toBe('保留金额 68 元')
    expect(moments.feelings.value).toBe('')
  })
})
