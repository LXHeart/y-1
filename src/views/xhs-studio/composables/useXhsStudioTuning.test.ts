// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useXhsStudioTuning, XHS_AUDIENCE_OPTIONS } from './useXhsStudioTuning'

/**
 * 左栏调参（方案 §4.1/§6）：目录平台过滤、audience 读写 brief、
 * 拉取失败 error + 显式 retry、草稿恢复回填。
 */

const SKILLS_FIXTURE = {
  success: true,
  data: {
    skills: [
      { category: 'GENRE', code: 'practical_guide', name: '干货攻略型', sortOrder: 1 },
      { category: 'GENRE', code: 'zhihu_only', name: '知乎专属', applicablePlatforms: ['zhihu'], sortOrder: 2 },
      { category: 'GENRE', code: 'xhs_scope', name: '小红书专属', applicablePlatforms: ['xiaohongshu'], sortOrder: 3 },
      { category: 'STYLE', code: 'bestie', name: '闺蜜种草风', applicablePlatforms: [], sortOrder: 1 },
      { category: 'TITLE_FORMULA', code: 'number', name: '数字型', sortOrder: 1 },
    ],
  },
}

function stubFetch(impl: () => Partial<Response> = () => ({ ok: true, json: async () => SKILLS_FIXTURE })) {
  const fetchMock = vi.fn(async () => impl() as Response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function withTuning() {
  const article = useArticleCreation()
  let tuning!: ReturnType<typeof useXhsStudioTuning>
  mount(defineComponent({
    setup() {
      tuning = useXhsStudioTuning(article)
      return () => h('div')
    },
  }))
  return { article, tuning }
}

beforeEach(() => { stubFetch() })
afterEach(() => { vi.unstubAllGlobals() })

describe('风格目录平台过滤', () => {
  test('空 applicablePlatforms=通用保留；含 xiaohongshu 保留；不含滤除', async () => {
    const { tuning } = withTuning()
    await flushPromises()
    expect(tuning.genreOptions.value.map((o) => o.code)).toEqual(['practical_guide', 'xhs_scope'])
    expect(tuning.styleOptions.value.map((o) => o.code)).toEqual(['bestie'])
    expect(tuning.formulaOptions.value.map((o) => o.code)).toEqual(['number'])
  })

  test('挂载即拉取一次；失败置 error 态并支持显式 retry', async () => {
    const fetchMock = vi.fn(async () => new Response('boom', { status: 500 }))
    vi.stubGlobal('fetch', fetchMock)
    const { article, tuning } = withTuning()
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(article.styleSkillsError.value).not.toBe('')
    expect(tuning.genreOptions.value).toHaveLength(0)

    // retry 复用引擎 fetchStyleSkills（内部 loading 防重入）。
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => SKILLS_FIXTURE }) as Response))
    await tuning.retry()
    await flushPromises()
    expect(tuning.genreOptions.value.map((o) => o.code)).toEqual(['practical_guide', 'xhs_scope'])
  })
})

describe('audience 读写 brief.audience', () => {
  test('写入 brief.audience；再清空移除字段（不发送空串）', async () => {
    const { article, tuning } = withTuning()
    await flushPromises()
    expect(tuning.audience.value).toBe('')

    tuning.audience.value = '职场人'
    expect(article.brief.value?.audience).toBe('职场人')
    expect(tuning.audience.value).toBe('职场人')

    tuning.audience.value = ''
    expect(article.brief.value?.audience).toBeUndefined()
  })

  test('草稿恢复回填：brief 被 applyProject 置后 audience getter 反映', async () => {
    const { article, tuning } = withTuning()
    await flushPromises()
    article.setBrief({ processingMode: 'create', audience: '宝妈' })
    expect(tuning.audience.value).toBe('宝妈')
    expect(XHS_AUDIENCE_OPTIONS).toContain('宝妈')
  })
})
