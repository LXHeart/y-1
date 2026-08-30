// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArticleCreationView from '../../views/article/ArticleCreationView.vue'
import type { CreationHandoff } from '../../types/ai-creation'

/**
 * ArticleCreationView 特征测试（重构安全网）。
 *
 * 锁定：五步导航骨架、主题阶段渲染、平台选择器默认值、
 * 「生成标题」按钮的可用条件与请求契约（URL/method/payload）、
 * 成功后进入标题选择阶段。
 * 组件依赖 inject('articleInitialTopic')（内部做非空断言），必须 provide。
 */

type FetchCall = { url: string; init?: RequestInit }

const calls: FetchCall[] = []

function stubFetch(impl: (call: FetchCall) => Partial<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return impl(call)
  }))
}

function sseResponse(content: string): Response {
  const payload = `data: ${JSON.stringify({ content })}\n\ndata: [DONE]\n\n`
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(payload))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

/** 任务书 #57：风格目录下发（titles/content 生成前的小红书三选择器数据源）。 */
const SKILLS_FIXTURE = {
  success: true,
  data: {
    skills: [
      { category: 'TITLE_FORMULA', code: 'number', name: '数字型', description: '数字量化收获，阅读门槛低', sortOrder: 1 },
      { category: 'TITLE_FORMULA', code: 'suspense', name: '悬念型', description: '钩子留到正文揭晓', sortOrder: 2 },
      { category: 'GENRE', code: 'practical_guide', name: '干货攻略型', description: '分步保姆级教程，收藏率高', sortOrder: 1 },
      { category: 'GENRE', code: 'review', name: '种草测评型', description: '实测分维度，结论明确不骑墙', sortOrder: 2 },
      { category: 'STYLE', code: 'professional', name: '专业博主风', description: '数据依据，克制冷静', sortOrder: 2 },
      { category: 'STYLE', code: 'bestie', name: '闺蜜种草风', description: '闺蜜聊天感，热情种草', sortOrder: 1 },
    ],
  },
}

/** 目录 + 标题响应的常见组合桩（小红书流必用）。 */
function stubFetchWithCatalog(titlesImpl: () => Partial<Response>) {
  stubFetch((call) => {
    if (call.url === '/api/creation-style-skills') {
      return { ok: true, json: async () => SKILLS_FIXTURE }
    }
    return titlesImpl()
  })
}

/** 选中小红书并等目录就绪（fetch 需已 stub）。 */
async function selectXiaohongshuWithCatalog(wrapper: Awaited<ReturnType<typeof mountView>>) {
  await wrapper.findAll('.platform-btn')[2].trigger('click')
  await flushPromises()
}

beforeEach(() => {
  calls.length = 0
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountView(handoff?: CreationHandoff | null) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/article', name: 'article', component: ArticleCreationView },
      { path: '/ai-center', name: 'ai-center', component: { template: '<div />' } },
    ],
  })
  return mount(ArticleCreationView, {
    props: { creationHandoff: handoff ?? null },
    global: {
      plugins: [router],
      provide: { articleInitialTopic: ref('') },
    },
  })
}

describe('ArticleCreationView 渲染骨架与初始状态', () => {
  test('锁定五步导航与主题阶段标题', () => {
    const wrapper = mountView()

    expect(wrapper.findAll('.step-label').map((el) => el.text())).toEqual(['主题', '标题', '大纲', '正文', '配图'])
    expect(wrapper.get('.card-title').text()).toBe('先确定主题和发布平台')
    expect(wrapper.find('textarea.topic-input').exists()).toBe(true)
  })

  test('平台选择器锁定：微信/知乎/小红书/抖音，默认选中微信公众号', () => {
    const wrapper = mountView()

    const tablist = wrapper.get('[aria-label="文章平台"]')
    const buttons = tablist.findAll('button')
    expect(buttons.map((b) => b.text())).toEqual(['微信公众号', '知乎', '小红书', '抖音'])
    expect(buttons[0].classes()).toContain('platform-btn-active')
    expect(buttons[1].classes()).not.toContain('platform-btn-active')
    expect(buttons[2].classes()).not.toContain('platform-btn-active')
    expect(buttons[3].classes()).not.toContain('platform-btn-active')
  })

  test('生成标题按钮初始禁用，输入主题后启用', async () => {
    const wrapper = mountView()
    const primaryBtn = wrapper.get('.action-row .btn-primary')

    expect(primaryBtn.text()).toBe('生成标题')
    expect(primaryBtn.attributes('disabled')).toBe('')

    await wrapper.find('textarea.topic-input').setValue('职场沟通技巧')
    expect(primaryBtn.attributes('disabled')).toBeUndefined()
  })
})

describe('ArticleCreationView 标题生成交互', () => {
  test('点击生成标题：POST 正确的 URL 与 payload，成功后进入标题选择', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          titles: [
            { title: '候选标题一', hook: 'hook-1' },
            { title: '候选标题二', hook: '' },
          ],
          safety: { findings: [{ category: 'absolute_claims', severity: 'medium', match: '最好', index: 0, advice: '改为具体描述', deep: false }], lexiconVersion: 'lexicon-v1', deepCheck: false },
        },
      }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('职场沟通技巧')
    await wrapper.get('[aria-label="文章平台"]').findAll('button')[1].trigger('click') // 切到知乎
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/article-generation/titles')
    expect(calls[0].init?.method).toBe('POST')
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ topic: '职场沟通技巧', platform: 'zhihu' })

    // 进入第二步：候选标题列表 + 自定义标题输入
    expect(wrapper.get('.card-title').text()).toBe('从候选标题里选一个方向')
    expect(wrapper.findAll('.title-item')).toHaveLength(2)
    expect(wrapper.find('#custom-title').exists()).toBe(true)
    expect(wrapper.get('[aria-label="内容安全检查"]').text()).toContain('广告法极限词')
    const outlineBtn = wrapper.get('.action-row .btn-primary')
    expect(outlineBtn.text()).toBe('生成大纲')
    // selectedTitle 为空时「生成大纲」禁用
    expect(outlineBtn.attributes('disabled')).toBe('')
  })

  test('标题生成失败时展示错误卡，不切换阶段', async () => {
    stubFetch(() => ({
      ok: false,
      status: 500,
      json: async () => ({ success: false, error: '标题服务暂不可用' }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('餐饮创业复盘')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-card').exists()).toBe(true)
    expect(wrapper.find('.error-text').text()).toBe('标题服务暂不可用')
    expect(wrapper.get('.card-title').text()).toBe('先确定主题和发布平台')
  })

  test('挂载时不发起任何网络请求', async () => {
    stubFetch(() => ({ ok: true, json: async () => ({ success: true, data: {} }) }))
    mountView()
    await flushPromises()

    expect(calls).toEqual([])
  })
})

describe('ArticleCreationView 抖音平台接入', () => {
  test('选中抖音后展示图集短文案定位提示', async () => {
    const wrapper = mountView()

    expect(wrapper.find('.platform-mode-hint').exists()).toBe(false)
    await wrapper.findAll('.platform-btn')[3].trigger('click')

    const douyinBtn = wrapper.findAll('.platform-btn')[3]
    expect(douyinBtn.classes()).toContain('platform-btn-active')
    expect(wrapper.find('.platform-mode-hint').text()).toContain('图集短文案')
    expect(wrapper.find('.platform-mode-hint').text()).toContain('话题')
  })

  test('选中抖音生成标题：payload platform 沿用既有契约值 xiaohongshu', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '图集标题一', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.findAll('.platform-btn')[3].trigger('click') // 切到抖音
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/article-generation/titles')
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ topic: '探店图文', platform: 'xiaohongshu' })
  })

  test('抖音的规范提示跟随抖音规则；切回公众号后切换为公众号规则', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '图集标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    await wrapper.findAll('.platform-btn')[3].trigger('click')
    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.format-rule-bar').text()).toContain('正文 15-300 字')
    expect(wrapper.find('.format-rule-bar').text()).toContain('标题上限 55 字')

    // 返回主题阶段后切换到公众号，再次生成时规范提示切换为公众号规则
    await wrapper.find('.btn-back').trigger('click')
    await wrapper.get('[aria-label="文章平台"]').findAll('button')[0].trigger('click')
    expect(wrapper.find('.platform-mode-hint').exists()).toBe(false)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.format-rule-bar').text()).toContain('正文 300-3000 字')
  })
})

describe('ArticleCreationView 平台规范提示条', () => {
  test('主题阶段不渲染规范提示条（无结果内容），标题阶段渲染字数范围与标题上限', async () => {
    stubFetchWithCatalog(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '候选标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    expect(wrapper.find('.format-rule-bar').exists()).toBe(false)

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await selectXiaohongshuWithCatalog(wrapper)
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const bar = wrapper.find('.format-rule-bar')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('正文 50-1000 字')
    expect(bar.text()).toContain('标题上限 20 字')
    expect(bar.classes()).not.toContain('format-rule-bar-warn')
  })

  test('标题超过平台上限：非阻断提示出现，但「生成大纲」仍可用', async () => {
    stubFetchWithCatalog(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '候选标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await selectXiaohongshuWithCatalog(wrapper)
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    await wrapper.find('#custom-title').setValue('这是一个远远超过小红书二十个字符限制的超长标题文案啊')

    const bar = wrapper.find('.format-rule-bar')
    expect(bar.classes()).toContain('format-rule-bar-warn')
    expect(bar.text()).toContain('标题已超过 20 字建议上限')
    // 非阻断：生成大纲按钮保持可用
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBeUndefined()
  })

  test('正文阶段：内容超限给出非阻断提示，复制按钮不被禁用', async () => {
    stubFetchWithCatalog(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '候选标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()
    const vm = wrapper.vm as unknown as { content: string; stage: string }

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await selectXiaohongshuWithCatalog(wrapper)
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    vm.stage = 'content'
    vm.content = '字'.repeat(1200)
    await flushPromises()

    const bar = wrapper.find('.format-rule-bar')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('正文 50-1000 字')
    expect(bar.classes()).toContain('format-rule-bar-warn')
    expect(bar.text()).toContain('超过建议上限 1000 字')
    // 非阻断：复制正文按钮仍可用
    expect(wrapper.get('.btn-sm').attributes('disabled')).toBeUndefined()
  })
})

describe('ArticleCreationView creationHandoff 预填', () => {
  function buildHandoff(platformId: CreationHandoff['platformId'], taskMode = false): CreationHandoff {
    return {
      revision: 1,
      platformId,
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'article',
      source: taskMode
        ? { type: 'task', taskId: 'task-1', applicationId: 'application-1', taskVersion: 3 }
        : { type: 'independent' },
      contextSnapshotId: taskMode ? '11111111-1111-1111-1111-111111111111' : undefined,
      prefill: { topic: '餐饮创业复盘' },
    }
  }

  test('挂载时传入真实 handoff：预填主题、锁定平台并展示只读标签', async () => {
    const wrapper = mountView(buildHandoff('zhihu'))
    await flushPromises()

    expect((wrapper.find('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('餐饮创业复盘')
    // 锁定态：平台切换按钮组不渲染，改为只读标签
    expect(wrapper.find('[aria-label="文章平台"]').exists()).toBe(false)
    expect(wrapper.get('.platform-locked .badge').text()).toBe('知乎')
    expect(wrapper.get('.platform-locked .field-note').text()).toContain('创作中心')
    expect(wrapper.get('.card-title').text()).toBe('确定创作主题')
    expect(wrapper.find('.platform-mode-hint').exists()).toBe(false)
  })

  test('handoff platformId 为 douyin：锁定态标签显示抖音并保留图集模式提示', async () => {
    const wrapper = mountView(buildHandoff('douyin'))
    await flushPromises()

    expect(wrapper.find('[aria-label="文章平台"]').exists()).toBe(false)
    expect(wrapper.get('.platform-locked .badge').text()).toBe('抖音')
    expect(wrapper.find('.platform-mode-hint').exists()).toBe(true)
  })

  test('handoff 会话显示「返回创作中心」，点击后跳转 ai-center', async () => {
    const wrapper = mountView(buildHandoff('zhihu'))
    await flushPromises()

    const back = wrapper.get('[data-testid="back-to-center"]')
    expect(back.text()).toContain('返回创作中心')
    await back.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$router.currentRoute.value.name).toBe('ai-center')
  })

  test('无 handoff 直入：不显示返回创作中心，平台四选保持可选', async () => {
    const wrapper = mountView()

    expect(wrapper.find('[data-testid="back-to-center"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="文章平台"]').exists()).toBe(true)
    expect(wrapper.get('.card-title').text()).toBe('先确定主题和发布平台')
  })

  test('锁定会话「重新开始」回到第一步且平台保持知乎', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({ success: true, data: { titles: [{ title: '候选标题', hook: '' }] } }),
    }))
    const wrapper = mountView(buildHandoff('zhihu'))
    await flushPromises()
    const vm = wrapper.vm as unknown as { stage: string; content: string }

    vm.stage = 'content'
    vm.content = '一段正文'
    await flushPromises()
    await wrapper.get('.action-row .btn-secondary').trigger('click') // 重新开始
    await flushPromises()

    expect(vm.stage).toBe('topic')
    expect(wrapper.get('.platform-locked .badge').text()).toBe('知乎')

    await wrapper.find('textarea.topic-input').setValue('新主题再来一篇')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(calls[0].init?.body)).platform).toBe('zhihu')
  })

  test('任务 handoff 的标题请求强制携带 taskMode 与冻结快照 ID', async () => {
    stubFetchWithCatalog(() => ({
      ok: true,
      json: async () => ({ success: true, data: { titles: [{ title: '任务标题', hook: '' }] } }),
    }))
    const wrapper = mountView(buildHandoff('xiaohongshu', true))
    await flushPromises()

    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const titlesCall = calls.find((call) => call.url.endsWith('/titles'))
    expect(JSON.parse(String(titlesCall?.init?.body))).toEqual({
      topic: '餐饮创业复盘',
      platform: 'xiaohongshu',
      titleFormula: 'number',
      taskMode: true,
      contextSnapshotId: '11111111-1111-1111-1111-111111111111',
    })
  })

  test('独立创作请求不伪装成任务模式', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({ success: true, data: { titles: [{ title: '独立标题', hook: '' }] } }),
    }))
    const wrapper = mountView(buildHandoff('zhihu'))
    await flushPromises()

    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(JSON.parse(String(calls[0].init?.body))).toEqual({
      topic: '餐饮创业复盘', platform: 'zhihu',
    })
  })

  test('任务模式三阶段始终复用同一个冻结快照 ID', async () => {
    stubFetch((call) => {
      if (call.url.endsWith('/titles')) {
        return { ok: true, json: async () => ({
          success: true, data: { titles: [{ title: '任务标题', hook: '' }] },
        }) }
      }
      return { ok: true, body: sseResponse('任务生成内容').body }
    })
    const wrapper = mountView(buildHandoff('wechat-official', true))
    await flushPromises()

    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    await wrapper.find('.title-item').trigger('click')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const bodies = calls.map((call) => JSON.parse(String(call.init?.body)))
    expect(bodies).toHaveLength(3)
    expect(bodies.map((body) => body.contextSnapshotId)).toEqual([
      '11111111-1111-1111-1111-111111111111',
      '11111111-1111-1111-1111-111111111111',
      '11111111-1111-1111-1111-111111111111',
    ])
    expect(bodies.every((body) => body.taskMode === true)).toBe(true)
  })

  test('任务文章配图复用冻结快照和 handoff 原始平台', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { imageUrl: '/api/article-generation/generated-images/task-image' },
      }),
    }))
    const wrapper = mountView(buildHandoff('douyin', true))
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      imageSlots: Array<Record<string, unknown>>
      generateImageForSlot: (index: number) => Promise<void>
    }
    vm.imageSlots = [{
      placement: {
        position: '封面', description: '门店封面', searchKeywords: '门店',
        prompt: '生成门店竖版封面',
      },
      mode: 'none', searchResults: [], selectedImage: null,
      generating: false, searching: false, skipped: false,
    }]

    await vm.generateImageForSlot(0)

    expect(calls[0].url).toBe('/api/article-generation/generate-image')
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({
      prompt: '生成门店竖版封面',
      size: '1024x1024',
      taskMode: true,
      contextSnapshotId: '11111111-1111-1111-1111-111111111111',
      targetPlatform: 'douyin',
    })
  })

  test('独立文章配图不携带任务字段', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { imageUrl: '/api/article-generation/generated-images/independent' },
      }),
    }))
    const wrapper = mountView(buildHandoff('zhihu'))
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      imageSlots: Array<Record<string, unknown>>
      generateImageForSlot: (index: number) => Promise<void>
    }
    vm.imageSlots = [{
      placement: {
        position: '正文', description: '插图', searchKeywords: '插图', prompt: '生成正文插图',
      },
      mode: 'none', searchResults: [], selectedImage: null,
      generating: false, searching: false, skipped: false,
    }]

    await vm.generateImageForSlot(0)

    expect(JSON.parse(String(calls[0].init?.body))).toEqual({
      prompt: '生成正文插图', size: '1024x1024',
    })
  })
})

describe('ArticleCreationView 风格三选择器（任务书 #57）', () => {
  test('小红书（非抖音）：目录拉取一次、标题套路 chips 渲染、选中显示描述', async () => {
    stubFetchWithCatalog(() => ({
      ok: true,
      json: async () => ({ success: true, data: { titles: [{ title: '候选', hook: '' }] } }),
    }))
    const wrapper = mountView()

    // 切换两次平台：目录只拉一次（懒取一次语义）
    await wrapper.findAll('.platform-btn')[2].trigger('click')
    await flushPromises()
    await wrapper.findAll('.platform-btn')[0].trigger('click')
    await wrapper.findAll('.platform-btn')[2].trigger('click')
    await flushPromises()
    expect(calls.filter((call) => call.url === '/api/creation-style-skills')).toHaveLength(1)

    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(true)
    expect(wrapper.findAll('input[name="title-formula"]')).toHaveLength(2)
    // 未选套路：生成标题禁用（有主题也禁用）
    await wrapper.find('textarea.topic-input').setValue('探店')
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBe('')

    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test="skill-formula-desc"]').text()).toBe('数字量化收获，阅读门槛低')
  })

  test('公众号/知乎/抖音：无 chips、请求体不带新字段', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({ success: true, data: { titles: [{ title: '候选', hook: '' }] } }),
    }))
    const wrapper = mountView()
    await wrapper.find('textarea.topic-input').setValue('探店')

    // 公众号（默认）与知乎：无选择器、无目录请求
    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(false)
    await wrapper.findAll('.platform-btn')[1].trigger('click')
    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(false)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ topic: '探店', platform: 'zhihu' })

    // 抖音：platform 值同为 xiaohongshu，但视图层标记 douyin——不展示、不携带
    await wrapper.find('.btn-back').trigger('click')
    await wrapper.findAll('.platform-btn')[3].trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(false)
    expect(calls.some((call) => call.url === '/api/creation-style-skills')).toBe(false)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(calls[1].init?.body))).toEqual({ topic: '探店', platform: 'xiaohongshu' })
  })

  test('titles 请求体携带所选标题套路；content 请求体携带体裁+文风', async () => {
    stubFetch((call) => {
      if (call.url === '/api/creation-style-skills') {
        return { ok: true, json: async () => SKILLS_FIXTURE }
      }
      if (call.url.endsWith('/titles')) {
        return {
          ok: true,
          json: async () => ({ success: true, data: { titles: [{ title: '候选', hook: '' }] } }),
        }
      }
      return { ok: true, body: sseResponse('正文段落').body }
    })
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('探店')
    await selectXiaohongshuWithCatalog(wrapper)
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(calls.find((c) => c.url.endsWith('/titles'))?.init?.body))).toEqual({
      topic: '探店', platform: 'xiaohongshu', titleFormula: 'number',
    })

    // 标题 → 大纲 → 正文：大纲阶段出现体裁+文风两组 chips，未选齐则生成正文禁用
    await wrapper.find('.title-item').trigger('click')
    await wrapper.get('.action-row .btn-primary').trigger('click') // 生成大纲（免费 SSE）
    await flushPromises()

    expect(wrapper.find('[data-test="style-skills-content"]').exists()).toBe(true)
    expect(wrapper.findAll('input[name="content-genre"]')).toHaveLength(2)
    expect(wrapper.findAll('input[name="content-style"]')).toHaveLength(2)
    await wrapper.find('textarea.stream-textarea').setValue('一、开头足够长的大纲内容供生成正文使用')
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBe('')

    await wrapper.find('[data-test="skill-genre-practical_guide"]').setValue(true)
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBe('')
    await wrapper.find('[data-test="skill-style-professional"]').setValue(true)
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBeUndefined()

    await wrapper.get('.action-row .btn-primary').trigger('click') // 生成正文
    await flushPromises()
    const contentCall = calls.find((c) => c.url.endsWith('/content'))
    expect(JSON.parse(String(contentCall?.init?.body))).toMatchObject({
      platform: 'xiaohongshu',
      genre: 'practical_guide',
      style: 'professional',
    })
  })

  test('目录加载失败：内联错误 + 重试按钮，不阻塞其它步骤', async () => {
    stubFetch((call) => {
      if (call.url === '/api/creation-style-skills') {
        return { ok: false, status: 500, json: async () => ({ success: false, error: '目录暂不可用' }) }
      }
      return { ok: true, json: async () => ({ success: true, data: { titles: [{ title: '候选', hook: '' }] } }) }
    })
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('探店')
    await wrapper.findAll('.platform-btn')[2].trigger('click')
    await flushPromises()

    const block = wrapper.get('[data-test="style-skills-titles"]')
    expect(block.find('[role="alert"]').text()).toContain('目录暂不可用')
    expect(block.find('[data-test="style-skills-retry"]').exists()).toBe(true)
    // 必选门控下生成仍被禁（未选项），但主题输入/平台切换未被阻塞
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBe('')
    expect(wrapper.find('textarea.topic-input').attributes('disabled')).toBeUndefined()
  })

  test('锁定会话「重新开始」保留三选择（决策 J：与平台保留一致）', async () => {
    stubFetchWithCatalog(() => ({
      ok: true,
      json: async () => ({ success: true, data: { titles: [{ title: '候选', hook: '' }] } }),
    }))
    // handoff 锁定小红书（keepPlatform=true）——「重新开始」不退回公众号，chips 常驻
    const wrapper = mountView({
      revision: 1,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'article',
      source: { type: 'independent' },
      prefill: { topic: '探店' },
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as { stage: string; content: string }

    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    vm.stage = 'content'
    vm.content = '一段正文'
    await flushPromises()

    await wrapper.get('.action-row .btn-secondary').trigger('click') // 重新开始
    await flushPromises()

    expect(vm.stage).toBe('topic')
    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(true)
    expect((wrapper.find('[data-test="skill-formula-number"]').element as HTMLInputElement).checked).toBe(true)
    // 直接生成：请求仍带已保留的套路
    await wrapper.find('textarea.topic-input').setValue('再来一篇')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()
    const titlesCall = calls.find((call) => call.url.endsWith('/titles'))
    expect(JSON.parse(String(titlesCall?.init?.body)).titleFormula).toBe('number')
  })
})
