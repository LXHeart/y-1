// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import ArticleCreationView from './ArticleCreationView.vue'
import VideoAnalysisView from './VideoAnalysisView.vue'
import VideoProductionView from './VideoProductionView.vue'
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
        finish: () => undefined,
      }
    },
  }
})

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

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
    const wrapper = mount(ArticleCreationView, {
      props: { creationHandoff: first },
      global: { provide: { articleInitialTopic: ref('') } },
    })
    const textarea = wrapper.get('textarea.topic-input')
    await textarea.setValue('用户已修改')

    await wrapper.setProps({ creationHandoff: { ...first, prefill: { topic: '不应覆盖' } } })
    expect((textarea.element as HTMLTextAreaElement).value).toBe('用户已修改')

    await wrapper.setProps({ creationHandoff: articleHandoff(2, '新一轮主题') })
    expect((textarea.element as HTMLTextAreaElement).value).toBe('新一轮主题')
    expect(wrapper.get('.platform-btn-active').text()).toContain('知乎')
  })

  test('抖音图文 handoff 进入文章视图并启用抖音图集模式', () => {
    const wrapper = mount(ArticleCreationView, {
      props: {
        creationHandoff: {
          revision: 5,
          platformId: 'douyin',
          contentFormId: 'graphic',
          source: { type: 'hot-topic', title: '城市夜经济升温' },
          workflowId: 'longform',
          targetView: 'article',
          prefill: { topic: '城市夜经济升温' },
        },
      },
      global: { provide: { articleInitialTopic: ref('') } },
    })

    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('城市夜经济升温')
    expect(wrapper.get('.platform-btn-active').text()).toContain('抖音')
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
})
