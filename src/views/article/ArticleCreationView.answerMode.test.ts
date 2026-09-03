// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArticleCreationView from './ArticleCreationView.vue'

/**
 * 任务书 #62 卡6：知乎回答/文章双模式视图。
 *
 * 红线：本套件断言全程零 `zhihu.com` 出站——问题链接只做本地正则提取（§3.7）。
 */

type FetchCall = { url: string; init?: RequestInit }

const calls: FetchCall[] = []

/** 22 条通用 + 2 条知乎专属，用来验平台过滤（真实目录 29 条，形状一致即可）。 */
const SKILLS_FIXTURE = {
  success: true,
  data: {
    skills: [
      { category: 'TITLE_FORMULA', code: 'number', name: '数字型', description: '数字量化', sortOrder: 1, applicablePlatforms: [] },
      { category: 'TITLE_FORMULA', code: 'question', name: '疑问型', description: '真实困惑', sortOrder: 7, applicablePlatforms: ['zhihu'] },
      { category: 'GENRE', code: 'review', name: '种草测评型', description: '实测分维度', sortOrder: 2, applicablePlatforms: [] },
      { category: 'GENRE', code: 'opinion', name: '观点评论文', description: '议论文结构', sortOrder: 10, applicablePlatforms: ['zhihu'] },
      { category: 'STYLE', code: 'bestie', name: '闺蜜种草风', description: '闺蜜聊天感', sortOrder: 1, applicablePlatforms: [] },
      { category: 'STYLE', code: 'analytical', name: '理性分析流', description: '结论先行', sortOrder: 9, applicablePlatforms: ['zhihu'] },
    ],
  },
}

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

/** 目录 + titles(开头候选) + SSE 的常用组合桩。 */
function stubAll(openings: { title: string; hook: string }[] = [{ title: '我做这行 8 年，先说结论。', hook: '资历背书' }]) {
  stubFetch((call) => {
    if (call.url === '/api/creation-style-skills') {
      return { ok: true, json: async () => SKILLS_FIXTURE }
    }
    if (call.url.endsWith('/titles')) {
      return { ok: true, json: async () => ({ success: true, data: { titles: openings } }) }
    }
    return { ok: true, body: sseResponse('生成片段').body }
  })
}

beforeEach(() => { calls.length = 0 })
afterEach(() => { vi.unstubAllGlobals() })
enableAutoUnmount(afterEach)

function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/article', name: 'article', component: ArticleCreationView },
      { path: '/ai-center', name: 'ai-center', component: { template: '<div />' } },
    ],
  })
  return mount(ArticleCreationView, {
    props: { creationHandoff: null },
    global: { plugins: [router], provide: { articleInitialTopic: ref('') } },
  })
}

/** 切到知乎（平台按钮索引 1）并等目录就绪。 */
async function selectZhihu(wrapper: ReturnType<typeof mountView>) {
  await wrapper.findAll('.platform-btn')[1].trigger('click')
  await flushPromises()
}

describe('知乎双模式入口', () => {
  test('非知乎平台不渲染模式选择', async () => {
    stubAll()
    const wrapper = mountView()
    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)

    await wrapper.findAll('.platform-btn')[2].trigger('click') // 小红书
    await flushPromises()
    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)
  })

  test('切到知乎：出现两档模式选择，默认写回答', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)

    const toggle = wrapper.get('[data-testid="zhihu-mode-toggle"]')
    expect(toggle.findAll('.mode-btn').map((b) => b.text())).toEqual(['写回答', '写文章'])
    expect(wrapper.get('[data-testid="zhihu-mode-answer"]').classes()).toContain('mode-btn-active')
    expect(wrapper.get('[data-testid="zhihu-mode-article"]').classes()).not.toContain('mode-btn-active')
  })

  test('回答模式七步：问题/开头/大纲/正文/检查/配图（任务书 #63 插检查步）', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)

    expect(wrapper.findAll('.step-label').map((el) => el.text()))
      .toEqual(['问题', '开头', '大纲', '正文', '检查', '配图'])
  })

  test('知乎文章模式：现状五步 + 检查步', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')

    expect(wrapper.findAll('.step-label').map((el) => el.text()))
      .toEqual(['主题', '标题', '大纲', '正文', '检查', '配图'])
    expect(wrapper.find('textarea.topic-input').exists()).toBe(true)
    expect(wrapper.find('[data-testid="answer-question-input"]').exists()).toBe(false)
  })

  test('离开知乎强制回文章模式（显式同步，全局约束 5）', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    const vm = wrapper.vm as unknown as { contentMode: string }
    expect(vm.contentMode).toBe('answer')

    await wrapper.findAll('.platform-btn')[0].trigger('click') // 公众号
    await flushPromises()
    expect(vm.contentMode).toBe('article')
    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)
  })

  test('已有产物时切模式需确认，取消则保留产物', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    const vm = wrapper.vm as unknown as { content: string; contentMode: string }
    vm.content = '已生成的回答正文'
    await flushPromises()

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')
    expect(confirmSpy).toHaveBeenCalled()
    expect(vm.contentMode).toBe('answer')
    expect(vm.content).toBe('已生成的回答正文')

    confirmSpy.mockReturnValue(true)
    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')
    expect(vm.contentMode).toBe('article')
    expect(vm.content).toBe('')
    confirmSpy.mockRestore()
  })

  test('无产物时切模式不弹确认', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')

    expect(confirmSpy).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})

describe('问题步（纯手输，零网络请求）', () => {
  test('问题必填 ≥8 字才放开生成', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)

    const button = wrapper.get('[data-testid="answer-generate-openings"]')
    expect(button.text()).toBe('生成开头候选')
    expect(button.attributes('disabled')).toBe('')

    await wrapper.get('[data-testid="answer-question-input"]').setValue('太短了')
    expect(button.attributes('disabled')).toBe('')
    expect(wrapper.find('[role="alert"]').text()).toContain('至少 8 字')

    await wrapper.get('[data-testid="answer-question-input"]').setValue('为什么大厂都在弃用 Kubernetes？')
    expect(button.attributes('disabled')).toBeUndefined()
  })

  test('粘贴问题链接：展示识别提示且不发任何 zhihu.com 请求', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)

    await wrapper.get('[data-testid="answer-question-input"]')
      .setValue('https://www.zhihu.com/question/1999041081275355787')

    const hint = wrapper.get('[data-testid="question-ref-hint"]')
    expect(hint.text()).toContain('已识别问题链接 #1999041081275355787')
    expect(hint.text()).toContain('标题请手动填写')
    expect(calls.some((call) => call.url.includes('zhihu.com'))).toBe(false)
  })

  test('纯文本问题不显示链接提示', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)

    await wrapper.get('[data-testid="answer-question-input"]').setValue('这是一个手打的问题原文')

    expect(wrapper.find('[data-testid="question-ref-hint"]').exists()).toBe(false)
  })

  test('补充说明映射 topic，与问题一并进请求体', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.find('[data-test="skill-formula-question"]').setValue(true)
    await wrapper.get('[data-testid="answer-question-input"]').setValue('为什么这件事值得展开讲')
    await wrapper.get('[data-testid="answer-supplement-input"]').setValue('从我自己的踩坑经历切入')

    await wrapper.get('[data-testid="answer-generate-openings"]').trigger('click')
    await flushPromises()

    const titlesCall = calls.find((call) => call.url.endsWith('/titles'))!
    expect(JSON.parse(String(titlesCall.init?.body))).toEqual({
      topic: '从我自己的踩坑经历切入',
      platform: 'zhihu',
      answerMode: true,
      question: '为什么这件事值得展开讲',
      titleFormula: 'question',
    })
  })

  test('问题步也能换平台（首步不该困住用户）', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)

    expect(wrapper.find('[aria-label="文章平台"]').exists()).toBe(true)
    await wrapper.findAll('.platform-btn')[2].trigger('click') // 小红书
    await flushPromises()
    expect(wrapper.find('textarea.topic-input').exists()).toBe(true)
    expect(wrapper.find('[data-testid="answer-question-input"]').exists()).toBe(false)
  })
})

describe('开头候选步', () => {
  test('文案改为开头候选，候选全文多行渲染，自定义框是 textarea', async () => {
    stubAll([{ title: '第一行结论。\n第二行补充可信度交代。', hook: '先亮结论' }])
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.find('[data-test="skill-formula-question"]').setValue(true)
    await wrapper.get('[data-testid="answer-question-input"]').setValue('一个足够长的目标问题原文')
    await wrapper.get('[data-testid="answer-generate-openings"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.card-title').text()).toBe('从候选开头里选一个')
    expect(wrapper.get('.title-text').classes()).toContain('title-text-opening')
    expect(wrapper.get('.title-text').text()).toContain('第二行补充可信度交代')
    expect(wrapper.find('[data-testid="custom-opening-input"]').exists()).toBe(true)
    // 回答模式无标题上限校验（问题即标题）
    expect(wrapper.find('[data-testid="title-char-counter"]').exists()).toBe(false)
    expect(wrapper.find('.format-rule-bar').exists()).toBe(false)
  })

  test('选中候选后 outline 携带开头全文与问题', async () => {
    const opening = '我做这行 8 年，先说结论：这事儿没那么玄。'
    stubAll([{ title: opening, hook: '资历背书' }])
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.find('[data-test="skill-formula-question"]').setValue(true)
    await wrapper.get('[data-testid="answer-question-input"]').setValue('这个行业到底靠什么赚钱')
    await wrapper.get('[data-testid="answer-generate-openings"]').trigger('click')
    await flushPromises()

    await wrapper.get('.title-item').trigger('click')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const outlineCall = calls.find((call) => call.url.endsWith('/outline'))!
    const body = JSON.parse(String(outlineCall.init?.body))
    expect(body.answerMode).toBe(true)
    expect(body.question).toBe('这个行业到底靠什么赚钱')
    expect(body.title).toBe(opening)
  })

  test('知乎文章模式：标题步渲染 30 字计数（契约 maxTitleChars）', async () => {
    stubAll([{ title: '候选标题', hook: '' }])
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.find('textarea.topic-input').setValue('知乎文章主题')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const counter = wrapper.get('[data-testid="title-char-counter"]')
    expect(counter.text()).toBe('0 / 30 字')
    await wrapper.get('#custom-title').setValue('一二三四五')
    expect(wrapper.get('[data-testid="title-char-counter"]').text()).toBe('5 / 30 字')
  })
})

describe('风格三选按平台过滤', () => {
  test('知乎可见「通用 + 知乎专属」', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)

    const codes = wrapper.findAll('[data-test^="skill-formula-"]')
      .map((el) => el.attributes('data-test'))
    expect(codes).toContain('skill-formula-number')
    expect(codes).toContain('skill-formula-question')
  })

  test('小红书只见通用，知乎专属被滤掉（不串味）', async () => {
    stubAll()
    const wrapper = mountView()
    await wrapper.findAll('.platform-btn')[2].trigger('click')
    await flushPromises()

    const codes = wrapper.findAll('[data-test^="skill-formula-"]')
      .map((el) => el.attributes('data-test'))
    expect(codes).toContain('skill-formula-number')
    expect(codes).not.toContain('skill-formula-question')
  })

  test('换平台清掉已不适用的选择（后端风格注入不校验平台）', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.find('[data-test="skill-formula-question"]').setValue(true)
    const vm = wrapper.vm as unknown as { titleFormula: string }
    expect(vm.titleFormula).toBe('question')

    await wrapper.findAll('.platform-btn')[2].trigger('click') // 小红书
    await flushPromises()

    expect(vm.titleFormula).toBe('')
  })

  test('通用选择跨平台保留（决策 J：少重复选）', async () => {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)

    await wrapper.findAll('.platform-btn')[2].trigger('click')
    await flushPromises()

    expect((wrapper.vm as unknown as { titleFormula: string }).titleFormula).toBe('number')
  })
})

describe('完成步提示条', () => {
  /** 直接推 vm 到完成态：提示条只依赖 platform/mode，不必跑完整六步。 */
  async function completeAs(mode: 'answer' | 'article') {
    stubAll()
    const wrapper = mountView()
    await selectZhihu(wrapper)
    if (mode === 'article') await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')
    const vm = wrapper.vm as unknown as { completed: boolean; content: string; selectedTitle: string }
    vm.selectedTitle = mode === 'answer' ? '开头段全文' : '文章标题'
    vm.content = '正文'
    vm.completed = true
    await flushPromises()
    return wrapper
  }

  test('回答模式提示挂回原问题，且不再显示模式选择', async () => {
    const wrapper = await completeAs('answer')
    const hints = wrapper.get('[data-testid="publish-hints"]').findAll('li').map((li) => li.text())
    expect(hints).toEqual(['回答已就绪，发布时挂回原问题。'])
    expect(wrapper.get('.card-title').text()).toBe('回答已完成')
    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)
  })

  test('文章模式提示话题标签 + AI 声明', async () => {
    const wrapper = await completeAs('article')
    const hints = wrapper.get('[data-testid="publish-hints"]').findAll('li').map((li) => li.text())
    expect(hints).toHaveLength(2)
    expect(hints[1]).toContain('AI 辅助创作须声明')
    expect(wrapper.get('.card-title').text()).toBe('文章已完成')
  })

  test('非知乎平台不渲染提示条（零回归）', async () => {
    stubAll()
    const wrapper = mountView()
    const vm = wrapper.vm as unknown as { completed: boolean; content: string }
    vm.content = '公众号正文'
    vm.completed = true
    await flushPromises()

    expect(wrapper.find('[data-testid="publish-hints"]').exists()).toBe(false)
    expect(wrapper.get('.card-title').text()).toBe('文章已完成')
  })
})

describe('其余平台零回归', () => {
  test('公众号：无模式选择、无风格三选、五步+检查、载荷不带新字段', async () => {
    stubAll([{ title: '公众号标题', hook: '' }])
    const wrapper = mountView()

    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(false)
    expect(wrapper.findAll('.step-label').map((el) => el.text()))
      .toEqual(['主题', '标题', '大纲', '正文', '检查', '配图'])

    await wrapper.find('textarea.topic-input').setValue('公众号主题')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const titlesCall = calls.find((call) => call.url.endsWith('/titles'))!
    expect(JSON.parse(String(titlesCall.init?.body)))
      .toEqual({ topic: '公众号主题', platform: 'wechat' })
  })

  test('小红书：四步+检查（跳配图）+ 无模式选择，载荷不带新字段', async () => {
    stubAll([{ title: '小红书标题', hook: '' }])
    const wrapper = mountView()
    await wrapper.findAll('.platform-btn')[2].trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)
    expect(wrapper.findAll('.step-label').map((el) => el.text()))
      .toEqual(['主题', '标题', '大纲', '正文', '检查'])

    await wrapper.find('textarea.topic-input').setValue('探店')
    await wrapper.find('[data-test="skill-formula-number"]').setValue(true)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const titlesCall = calls.find((call) => call.url.endsWith('/titles'))!
    expect(JSON.parse(String(titlesCall.init?.body)))
      .toEqual({ topic: '探店', platform: 'xiaohongshu', titleFormula: 'number' })
  })

  test('抖音：无模式选择、无风格三选（platform 值为一等 douyin）', async () => {
    stubAll()
    const wrapper = mountView()
    await wrapper.findAll('.platform-btn')[3].trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="zhihu-mode-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="style-skills-titles"]').exists()).toBe(false)
    expect(calls.some((call) => call.url === '/api/creation-style-skills')).toBe(false)
  })
})

/**
 * 任务书 #62 卡7：知乎任务带目标问题 → 进入即锁回答形态、问题步只读预填。
 * 问题原文取 accept 时冻结的 taskContext（后端同卡也让快照优先于请求体）。
 */
describe('任务模式锁定回答形态（卡7）', () => {
  /** 带/不带目标问题的知乎任务 handoff。 */
  function taskHandoff(questionText?: string) {
    return {
      revision: 1,
      platformId: 'zhihu' as const,
      contentFormId: 'graphic' as const,
      source: { type: 'task' as const, taskId: 'task-1', applicationId: 'app-1', taskVersion: 2 },
      workflowId: 'longform' as const,
      targetView: 'article' as const,
      contextSnapshotId: '11111111-1111-1111-1111-111111111111',
      prefill: { topic: '补充说明预填' },
      taskContext: {
        taskId: 'task-1', taskVersion: 2, title: '知乎回答任务', description: null,
        contentForm: 'graphic', platform: 'zhihu', storeId: null, applicationId: 'app-1',
        recommenderAccountId: 'rec-1', bountyCents: 0, acceptedAt: null, requirements: {},
        ...(questionText ? { questionText, questionRef: '1999041081275355787' } : {}),
      },
    }
  }

  async function mountWithHandoff(questionText?: string) {
    stubAll()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/article', name: 'article', component: ArticleCreationView },
        { path: '/ai-center', name: 'ai-center', component: { template: '<div />' } },
      ],
    })
    const wrapper = mount(ArticleCreationView, {
      props: { creationHandoff: taskHandoff(questionText) },
      global: { plugins: [router], provide: { articleInitialTopic: ref('') } },
    })
    await flushPromises()
    return wrapper
  }

  test('带目标问题：两档模式都禁用、停在回答模式并给出锁定说明', async () => {
    const wrapper = await mountWithHandoff('为什么大厂都在弃用 Kubernetes？')

    expect(wrapper.get('[data-testid="zhihu-mode-answer"]').attributes('disabled')).toBe('')
    expect(wrapper.get('[data-testid="zhihu-mode-article"]').attributes('disabled')).toBe('')
    expect(wrapper.get('[data-testid="zhihu-mode-answer"]').classes()).toContain('mode-btn-active')
    expect(wrapper.get('[data-testid="task-mode-locked-note"]').text()).toContain('任务指定回答形态')
  })

  test('带目标问题：问题步只读预填快照原文', async () => {
    const wrapper = await mountWithHandoff('为什么大厂都在弃用 Kubernetes？')

    const input = wrapper.get('[data-testid="answer-question-input"]')
    expect((input.element as HTMLTextAreaElement).value).toBe('为什么大厂都在弃用 Kubernetes？')
    expect(input.attributes('readonly')).toBeDefined()
  })

  test('锁定态点「写文章」无效——形态由商家决定', async () => {
    const wrapper = await mountWithHandoff('为什么大厂都在弃用 Kubernetes？')

    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="zhihu-mode-answer"]').classes()).toContain('mode-btn-active')
    expect(wrapper.find('[data-testid="answer-question-input"]').exists()).toBe(true)
  })

  test('不带目标问题：用户自选（默认写回答，控件可用、问题可编辑）', async () => {
    const wrapper = await mountWithHandoff()

    expect(wrapper.get('[data-testid="zhihu-mode-answer"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="task-mode-locked-note"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="answer-question-input"]').attributes('readonly')).toBeUndefined()

    await wrapper.get('[data-testid="zhihu-mode-article"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="zhihu-mode-article"]').classes()).toContain('mode-btn-active')
  })

  test('任务模式全程零 zhihu.com 出站（§3.7）', async () => {
    await mountWithHandoff('为什么大厂都在弃用 Kubernetes？')
    expect(calls.some((call) => call.url.includes('zhihu.com'))).toBe(false)
  })
})
