// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArticleCreationView from '../views/article/ArticleCreationView.vue'
import ComedyWritingView from '../views/comedy/ComedyWritingView.vue'
import VideoAnalysisView from '../views/video/VideoAnalysisView.vue'
import VideoProductionView from '../views/video-production/VideoProductionView.vue'
import type { CreationHandoff } from '../types/ai-creation'

vi.mock('../composables/useArticleCreation', async () => {
  const { ref } = await import('vue')
  return {
    useArticleCreation: () => {
      const stage = ref('topic')
      const topic = ref('')
      const platform = ref('wechat')
      const reset = () => {
        stage.value = 'topic'
        topic.value = ''
      }
      return {
        stage,
        topic,
        platform,
        titles: ref([]),
        selectedTitle: ref(''),
        outline: ref(''),
        content: ref(''),
        titlesLoading: ref(false),
        outlineLoading: ref(false),
        contentLoading: ref(false),
        error: ref(''),
        titleFormula: ref(''),
        genre: ref(''),
        style: ref(''),
        styleSkillOptions: ref({ TITLE_FORMULA: [], GENRE: [], STYLE: [] }),
        styleSkillsLoading: ref(false),
        styleSkillsError: ref(''),
        styleSkillsActive: ref(false),
        imagesStageSkipped: ref(false), // 任务书 #60：替身须跟真实 composable 的返回契约一致
        fetchStyleSkills: async () => undefined,
        imageSlots: ref([]),
        imageRecommendations: ref(null),
        loadingRecommendations: ref(false),
        completed: ref(false),
        fetchTitles: async () => undefined,
        streamOutline: async () => undefined,
        streamContent: async () => undefined,
        selectTitle: () => undefined,
        goToTitles: () => undefined,
        goToOutline: () => undefined,
        goToContent: () => undefined,
        loadImageRecommendations: async () => undefined,
        searchImageForSlot: async () => undefined,
        generateImageForSlot: async () => undefined,
        selectImageForSlot: () => undefined,
        clearImageForSlot: () => undefined,
        toggleSlot: () => undefined,
        reset,
        cancel: () => undefined,
        setTopic: (value: string) => {
          topic.value = value
          stage.value = 'topic'
        },
        bindCreationContext: () => undefined,
        finish: () => undefined,
      }
    },
  }
})

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * ArticleCreationView 用 useRouter() 提供「返回创作中心」，挂载时必须装 router，
 * 否则 provide 缺失只发 warning、点击跳转静默失效。每个测试一个独立实例。
 */
function mountArticleView(handoff: CreationHandoff) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      // memory history 初始位置是 ""，缺 '/' 兜底会警告 No match found
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/article', name: 'article', component: ArticleCreationView },
      { path: '/ai-center', name: 'ai-center', component: { template: '<div />' } },
    ],
  })
  return mount(ArticleCreationView, {
    props: { creationHandoff: handoff },
    global: { plugins: [router], provide: { articleInitialTopic: ref('') } },
  })
}

function articleHandoff(revision: number, topic: string): CreationHandoff {
  return {
    revision,
    platformId: 'zhihu',
    contentFormId: 'graphic',
    source: { type: 'independent' },
    workflowId: 'longform',
    targetView: 'article',
    prefill: { topic },
  }
}

function videoProductionHandoff(revision: number): CreationHandoff {
  return {
    revision,
    platformId: 'kuaishou',
    contentFormId: 'video',
    source: { type: 'store', organizationId: 'org-1', storeId: 'store-1' },
    workflowId: 'video-script',
    targetView: 'video-production',
    prefill: {
      topic: '晚市新品',
      instructions: '突出手工现做',
      storeName: '云朵面馆',
      address: '人民路 8 号',
      storeDescription: '手工面与现熬汤底',
    },
  }
}

function videoTaskHandoff(revision: number): CreationHandoff {
  return {
    ...videoProductionHandoff(revision),
    source: { type: 'task', taskId: 'task-1', applicationId: 'application-1', taskVersion: 3 },
    contextSnapshotId: '11111111-1111-1111-1111-111111111111',
  }
}

function referenceHandoff(revision: number, platformId: 'douyin' | 'bilibili', sourceUrl: string): CreationHandoff {
  return {
    revision,
    platformId,
    contentFormId: 'video',
    source: { type: 'reference', sourceUrl },
    workflowId: 'reference-analyze',
    targetView: 'video',
  }
}

function stubBackendFetch(): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    let data: unknown = []
    if (url === '/api/video-production/capabilities') {
      data = { videoGeneration: { available: false, reason: '暂未开放' } }
    }
    if (url === '/api/douyin/session') {
      data = { status: 'anonymous' }
    }
    return new Response(JSON.stringify({ success: true, data }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }))
}

describe('创作工作流 handoff', () => {
  test('同一 revision 不覆盖文章页面中的后续编辑，新 revision 才重置预填', async () => {
    const first = articleHandoff(1, '最初主题')
    const wrapper = mountArticleView(first)
    const textarea = wrapper.get('textarea.topic-input')
    await textarea.setValue('用户已修改')

    await wrapper.setProps({ creationHandoff: { ...first, prefill: { topic: '不应覆盖' } } })
    expect((textarea.element as HTMLTextAreaElement).value).toBe('用户已修改')

    await wrapper.setProps({ creationHandoff: articleHandoff(2, '新一轮主题') })
    expect((textarea.element as HTMLTextAreaElement).value).toBe('新一轮主题')
    // handoff 会话平台锁定为只读标签（知乎）
    expect(wrapper.get('.platform-locked .badge').text()).toBe('知乎')
  })

  test('抖音图文 handoff 进入文章视图并启用抖音图集模式', () => {
    const wrapper = mountArticleView({
      revision: 5,
      platformId: 'douyin',
      contentFormId: 'graphic',
      source: { type: 'hot-topic', title: '城市夜经济升温' },
      workflowId: 'longform',
      targetView: 'article',
      prefill: { topic: '城市夜经济升温' },
    })

    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('城市夜经济升温')
    expect(wrapper.get('.platform-locked .badge').text()).toBe('抖音')
    expect(wrapper.get('.platform-mode-hint').text()).toContain('抖音定位图集短文案')
  })

  test('视频脚本 handoff 预填视频制作页的平台、门店资料和创作要求，且受 revision 保护', async () => {
    stubBackendFetch()
    const first = videoProductionHandoff(1)
    const wrapper = mount(VideoProductionView, { props: { creationHandoff: first } })
    await flushPromises()

    expect((wrapper.get('#vp-platform').element as HTMLSelectElement).value).toBe('kuaishou')
    expect((wrapper.get('#vp-shop-name').element as HTMLInputElement).value).toBe('云朵面馆')
    expect((wrapper.get('#vp-address').element as HTMLInputElement).value).toBe('人民路 8 号')
    expect((wrapper.get('#vp-desc').element as HTMLTextAreaElement).value).toBe('手工面与现熬汤底')
    const prompt = wrapper.get('#vp-prompt').element as HTMLTextAreaElement
    expect(prompt.value).toContain('创作主题：晚市新品')
    expect(prompt.value).toContain('突出手工现做')

    await wrapper.get('#vp-shop-name').setValue('用户改过的店名')
    await wrapper.setProps({ creationHandoff: { ...first, prefill: { ...first.prefill, storeName: '不应覆盖' } } })
    expect((wrapper.get('#vp-shop-name').element as HTMLInputElement).value).toBe('用户改过的店名')

    await wrapper.setProps({ creationHandoff: videoProductionHandoff(2) })
    expect((wrapper.get('#vp-shop-name').element as HTMLInputElement).value).toBe('云朵面馆')
  })

  test('视频任务 handoff 绑定冻结快照并保持任务来源', async () => {
    stubBackendFetch()
    const wrapper = mount(VideoProductionView, { props: { creationHandoff: videoTaskHandoff(1) } })
    await flushPromises()

    expect((wrapper.get('#vp-platform').element as HTMLSelectElement).value).toBe('kuaishou')
    expect(wrapper.props('creationHandoff')?.source.type).toBe('task')
    expect(wrapper.props('creationHandoff')?.contextSnapshotId)
      .toBe('11111111-1111-1111-1111-111111111111')
  })

  test('喜剧脚本任务 handoff 绑定冻结快照并预填主题', () => {
    const handoff: CreationHandoff = {
      revision: 9,
      platformId: 'douyin',
      contentFormId: 'video',
      source: { type: 'task', taskId: 'task-9', applicationId: 'app-9', taskVersion: 2 },
      workflowId: 'comedy-script',
      targetView: 'comedy',
      prefill: { topic: '任务喜剧主题' },
      contextSnapshotId: '99999999-9999-9999-9999-999999999999',
    }
    const wrapper = mount(ComedyWritingView, {
      props: { creationHandoff: handoff },
      global: { provide: { comedyInitialTopic: ref('') } },
    })

    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('任务喜剧主题')
    expect(wrapper.props('creationHandoff')?.contextSnapshotId)
      .toBe('99999999-9999-9999-9999-999999999999')
  })

  test('reference handoff 落到视频提取分析视图并预填平台与链接，新 revision 才重新预填', async () => {
    stubBackendFetch()
    const first = referenceHandoff(1, 'bilibili', 'https://www.bilibili.com/video/BV1example')
    const wrapper = mount(VideoAnalysisView, { props: { creationHandoff: first } })
    await flushPromises()

    expect(wrapper.get('.platform-tab-active').text()).toContain('B 站')
    expect((wrapper.get('#video-input').element as HTMLTextAreaElement).value).toBe('https://www.bilibili.com/video/BV1example')

    await wrapper.get('#video-input').setValue('https://www.bilibili.com/video/BV1edited')
    await wrapper.setProps({ creationHandoff: { ...first, source: { type: 'reference', sourceUrl: '不应覆盖' } } })
    expect((wrapper.get('#video-input').element as HTMLTextAreaElement).value).toBe('https://www.bilibili.com/video/BV1edited')

    await wrapper.setProps({ creationHandoff: referenceHandoff(2, 'douyin', 'https://v.douyin.com/xxxx/') })
    expect(wrapper.get('.platform-tab-active').text()).toContain('抖音')
    expect((wrapper.get('#video-input').element as HTMLTextAreaElement).value).toBe('https://v.douyin.com/xxxx/')
  })

  test('视频复刻任务分别保留发布平台、参考来源平台和冻结快照', async () => {
    stubBackendFetch()
    const handoff: CreationHandoff = {
      revision: 12,
      platformId: 'xiaohongshu',
      contentFormId: 'video',
      source: { type: 'task', taskId: 'task-12', applicationId: 'app-12', taskVersion: 4 },
      workflowId: 'video-recreation',
      targetView: 'video',
      prefill: {
        referencePlatform: 'bilibili',
        referenceUrl: 'https://www.bilibili.com/video/BV1task',
      },
      contextSnapshotId: '12121212-1212-1212-1212-121212121212',
    }
    const wrapper = mount(VideoAnalysisView, { props: { creationHandoff: handoff } })
    await flushPromises()

    expect(wrapper.get('.platform-tab-active').text()).toContain('B 站')
    expect((wrapper.get('#video-input').element as HTMLTextAreaElement).value)
      .toBe('https://www.bilibili.com/video/BV1task')
    expect(wrapper.props('creationHandoff')?.platformId).toBe('xiaohongshu')
    expect(wrapper.props('creationHandoff')?.contextSnapshotId)
      .toBe('12121212-1212-1212-1212-121212121212')
  })
})
