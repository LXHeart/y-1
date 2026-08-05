// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import ArticleCreationView from './ArticleCreationView.vue'
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
})
