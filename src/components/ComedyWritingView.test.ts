// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import ComedyWritingView from '../views/comedy/ComedyWritingView.vue'
import { STYLE_TEMPLATES } from '../config/style-templates'
import type { CreationHandoff } from '../types/ai-creation'

/**
 * ComedyWritingView 特征测试（重构安全网）。
 *
 * 锁定：渲染骨架、时长选择器初始状态、主按钮可用性条件、
 * 生成请求的 URL/payload，以及失败时错误提示可观察。
 * 新增：六种抽象风格模板渲染/默认选中/提示词拼装，无特定在世创作者字样。
 * 组件依赖 inject('comedyInitialTopic')（内部对返回值做非空断言），必须 provide。
 */

type FetchCall = { url: string; init?: RequestInit }

const calls: FetchCall[] = []

function stubFetch(impl: (call: FetchCall) => Promise<Partial<Response>> | Partial<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return impl(call)
  }))
}

beforeEach(() => {
  calls.length = 0
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountView(creationHandoff: CreationHandoff | null = null) {
  return mount(ComedyWritingView, {
    props: { creationHandoff },
    global: {
      provide: { comedyInitialTopic: ref('') },
    },
  })
}

describe('ComedyWritingView 渲染骨架与初始状态', () => {
  test('锁定标题、说明、题材输入框', () => {
    const wrapper = mountView()

    expect(wrapper.find('.section-title').text()).toBe('脱口秀创作')
    expect(wrapper.find('.section-desc').text()).toContain('AI 帮你生成脱口秀文稿')
    const textarea = wrapper.find('textarea.topic-input')
    expect(textarea.exists()).toBe(true)
    expect(textarea.attributes('placeholder')).toContain('输入题材')
    expect(wrapper.find('.char-count').text()).toBe('0 / 200')
  })

  test('时长选择器锁定：四个选项，默认选中 1 分钟', () => {
    const wrapper = mountView()

    const buttons = wrapper.findAll('.dur-btn')
    expect(buttons.map((b) => b.text())).toEqual(['30 秒', '1 分钟', '90 秒', '2 分钟'])
    expect(buttons[1].classes()).toContain('dur-btn-active')
    buttons.forEach((b, i) => {
      if (i !== 1) expect(b.classes()).not.toContain('dur-btn-active')
    })
  })

  test('主按钮初始禁用，输入题材后启用', async () => {
    const wrapper = mountView()
    const genBtn = wrapper.get('button.gen-btn')

    expect(genBtn.text()).toBe('开始创作')
    expect(genBtn.attributes('disabled')).toBe('')

    await wrapper.find('textarea.topic-input').setValue('相亲')
    expect(genBtn.attributes('disabled')).toBeUndefined()
  })

  test('初始无结果卡与错误提示', () => {
    const wrapper = mountView()

    expect(wrapper.find('.script-card').exists()).toBe(false)
    expect(wrapper.find('.error-msg').exists()).toBe(false)
  })

  test('页面不含特定在世创作者字样（去李继刚）', () => {
    const wrapper = mountView()
    expect(wrapper.text()).not.toContain('李继刚')
  })
})

describe('ComedyWritingView 风格模板选择', () => {
  test('渲染六种抽象风格卡片，展示 label 与表达特征描述', () => {
    const wrapper = mountView()

    const cards = wrapper.findAll('.style-card')
    expect(cards).toHaveLength(6)
    expect(cards.map((c) => c.find('.style-card-title').text())).toEqual(
      STYLE_TEMPLATES.map((t) => t.label),
    )
    cards.forEach((card, i) => {
      expect(card.find('.style-card-desc').text()).toBe(STYLE_TEMPLATES[i].description)
    })
  })

  test('默认选中 light-comedy', () => {
    const wrapper = mountView()

    const cards = wrapper.findAll('.style-card')
    expect(cards[0].classes()).toContain('style-card-active')
    cards.slice(1).forEach((c) => expect(c.classes()).not.toContain('style-card-active'))
  })

  test('点击可切换风格，选中态跟随变化', async () => {
    const wrapper = mountView()

    const cards = wrapper.findAll('.style-card')
    await cards[3].trigger('click')

    expect(cards[3].classes()).toContain('style-card-active')
    expect(cards[0].classes()).not.toContain('style-card-active')
  })
})

describe('ComedyWritingView 生成交互', () => {
  test('生成成功后消费流尾 safety 帧并展示提醒', async () => {
    const frames = [
      { content: '这是生成的文稿' },
      { type: 'safety', safety: { findings: [{ category: 'absolute_claims', severity: 'medium', match: '最好', index: 0, advice: '改为具体描述', deep: false }], lexiconVersion: 'lexicon-v1', deepCheck: false } },
    ]
    const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
    lines.push('data: [DONE]', '')
    stubFetch(() => new Response(lines.join('\n'), {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('上班摸鱼')
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    expect(wrapper.get('.script-content').text()).toBe('这是生成的文稿')
    expect(wrapper.get('[aria-label="内容安全检查"]').text()).toContain('广告法极限词')
  })

  test('点击开始创作：POST 正确的 URL 与 payload', async () => {
    stubFetch(() => ({
      ok: false,
      status: 500,
      json: async () => ({ error: '生成服务暂不可用' }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('上班摸鱼')
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/comedy-generation/generate-script')
    expect(calls[0].init?.method).toBe('POST')
    // 后端契约字段保持 topic/duration；默认 light-comedy 风格描述并入 topic 文本
    const payload = JSON.parse(String(calls[0].init?.body)) as { topic: string; duration: number }
    expect(payload.topic).toContain('上班摸鱼')
    expect(payload.topic).toContain(STYLE_TEMPLATES[0].description)
    expect(payload.duration).toBe(60)
    expect(payload).not.toHaveProperty('taskMode')
    expect(payload).not.toHaveProperty('contextSnapshotId')
    expect(payload).not.toHaveProperty('targetPlatform')
    // 失败时展示服务端错误信息，并恢复按钮
    expect(wrapper.find('.error-msg').text()).toBe('生成服务暂不可用')
    expect(wrapper.get('button.gen-btn').text()).toBe('开始创作')
  })

  test('切换时长后 payload 跟随变化', async () => {
    stubFetch(() => ({
      ok: false,
      status: 500,
      json: async () => ({ error: 'boom' }),
    }))
    const wrapper = mountView()

    await wrapper.findAll('.dur-btn')[3].trigger('click')
    expect(wrapper.findAll('.dur-btn')[3].classes()).toContain('dur-btn-active')

    await wrapper.find('textarea.topic-input').setValue('减肥')
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    const payload = JSON.parse(String(calls[0].init?.body)) as { topic: string; duration: number }
    expect(payload.topic).toContain('减肥')
    expect(payload.duration).toBe(120)
  })

  test('切换风格后 payload 中 topic 含新风格描述拼装', async () => {
    stubFetch(() => ({
      ok: false,
      status: 500,
      json: async () => ({ error: 'boom' }),
    }))
    const wrapper = mountView()

    const reversal = STYLE_TEMPLATES.find((t) => t.id === 'reversal-opening')!
    const cards = wrapper.findAll('.style-card')
    const idx = STYLE_TEMPLATES.indexOf(reversal)
    await cards[idx].trigger('click')

    await wrapper.find('textarea.topic-input').setValue('相亲')
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    const payload = JSON.parse(String(calls[0].init?.body)) as { topic: string; duration: number }
    expect(payload.topic).toContain('相亲')
    expect(payload.topic).toContain(reversal.label)
    expect(payload.topic).toContain(reversal.description)
    // 原默认风格描述不再出现
    expect(payload.topic).not.toContain(STYLE_TEMPLATES[0].description)
    expect(payload.duration).toBe(60)
  })

  test('任务 handoff 预填主题，并让重复生成复用同一冻结快照', async () => {
    stubFetch(() => ({
      ok: false,
      status: 502,
      json: async () => ({ error: '测试结束' }),
    }))
    const snapshotId = '11111111-1111-1111-1111-111111111111'
    const handoff: CreationHandoff = {
      revision: 21,
      platformId: 'douyin',
      contentFormId: 'video',
      source: { type: 'task', taskId: 'task-21', applicationId: 'app-21', taskVersion: 3 },
      workflowId: 'comedy-script',
      targetView: 'comedy',
      prefill: { topic: '冻结任务主题' },
      contextSnapshotId: snapshotId,
    }
    const wrapper = mountView(handoff)

    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value)
      .toBe('冻结任务主题')
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    const payloads = calls.map((call) => JSON.parse(String(call.init?.body)) as Record<string, unknown>)
    expect(payloads).toHaveLength(2)
    expect(payloads.every((payload) => payload.taskMode === true)).toBe(true)
    expect(payloads.map((payload) => payload.contextSnapshotId)).toEqual([snapshotId, snapshotId])
    expect(payloads.every((payload) => payload.targetPlatform === 'douyin')).toBe(true)
  })

  test('相同 revision 不覆盖编辑，新 revision 才重新绑定任务上下文', async () => {
    const first: CreationHandoff = {
      revision: 31,
      platformId: 'douyin',
      contentFormId: 'video',
      source: { type: 'task', taskId: 'task-31' },
      workflowId: 'comedy-script',
      targetView: 'comedy',
      prefill: { topic: '初始主题' },
      contextSnapshotId: '11111111-1111-1111-1111-111111111111',
    }
    const wrapper = mountView(first)
    await wrapper.get('textarea.topic-input').setValue('用户已修改')
    await wrapper.setProps({ creationHandoff: { ...first, prefill: { topic: '不应覆盖' } } })
    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('用户已修改')

    await wrapper.setProps({
      creationHandoff: {
        ...first,
        revision: 32,
        platformId: 'kuaishou',
        prefill: { topic: '新任务主题' },
        contextSnapshotId: '22222222-2222-2222-2222-222222222222',
      },
    })
    expect((wrapper.get('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('新任务主题')
  })
})
