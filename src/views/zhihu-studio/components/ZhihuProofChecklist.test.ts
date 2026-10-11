// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import ZhihuProofChecklist from './ZhihuProofChecklist.vue'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'

/**
 * 发布前检查清单（真实可算口径）：回答=问题字数/正文/段落/AI 声明；
 * 文章=标题字数/正文/话题数/段落/AI 声明。阈值取自知乎契约（200-3000 字、标题 30、
 * 话题 3-5）与 engine MIN_QUESTION_LENGTH（8）。
 */

const rule = getPlatformFormatRule('zhihu')!

const baseProps = {
  formatRule: rule,
  formatIssues: [] as string[],
  selectedTitle: '',
  content: '',
  topics: [] as string[],
  question: '',
  answerMode: true,
}

function itemText(wrapper: ReturnType<typeof mount>, key: string): string {
  const item = wrapper.find(`[data-test="zhihu-check-${key}"]`)
  return item.exists() ? item.text() : ''
}

describe('回答模式', () => {
  test('问题字数 ≥8 通过；过短建议关注', () => {
    const ok = mount(ZhihuProofChecklist, {
      props: { ...baseProps, question: 'AI 会取代哪些工作？哪些不会？', content: 'x'.repeat(300) },
    })
    expect(itemText(ok, 'question')).toContain('通过')

    const short = mount(ZhihuProofChecklist, {
      props: { ...baseProps, question: '短问题', content: 'x'.repeat(300) },
    })
    expect(itemText(short, 'question')).toContain('建议关注')
  })

  test('正文按契约 200-3000 判定；无话题检查项（回答绑定问题）', () => {
    const wrapper = mount(ZhihuProofChecklist, {
      props: { ...baseProps, question: '这个问题足够长了吧', content: 'x'.repeat(100) },
    })
    expect(itemText(wrapper, 'body')).toContain('建议关注')
    expect(itemText(wrapper, 'body')).toContain('200')
    expect(wrapper.find('[data-test="zhihu-check-topics"]').exists()).toBe(false)
  })
})

describe('文章模式', () => {
  test('标题 30 字内通过；无问题检查项', () => {
    const ok = mount(ZhihuProofChecklist, {
      props: {
        ...baseProps, answerMode: false,
        selectedTitle: '一个不太长的标题', content: 'x'.repeat(300), topics: ['AI', '职场', '成长'],
      },
    })
    expect(itemText(ok, 'title')).toContain('通过')
    expect(ok.find('[data-test="zhihu-check-question"]').exists()).toBe(false)

    const over = mount(ZhihuProofChecklist, {
      props: { ...baseProps, answerMode: false, selectedTitle: '长'.repeat(31), content: 'x'.repeat(300) },
    })
    expect(itemText(over, 'title')).toContain('建议关注')
  })

  test('话题数 3-5 通过；0 个建议关注', () => {
    const ok = mount(ZhihuProofChecklist, {
      props: {
        ...baseProps, answerMode: false,
        selectedTitle: '标题', content: 'x'.repeat(300), topics: ['AI', '职场', '成长'],
      },
    })
    expect(itemText(ok, 'topics')).toContain('通过')

    const none = mount(ZhihuProofChecklist, {
      props: { ...baseProps, answerMode: false, selectedTitle: '标题', content: 'x'.repeat(300) },
    })
    expect(itemText(none, 'topics')).toContain('建议关注')
  })
})

describe('通用', () => {
  test('段落超 200 字建议关注并给出段号', () => {
    const wrapper = mount(ZhihuProofChecklist, {
      props: { ...baseProps, question: '这个问题足够长了吧', content: `${'长'.repeat(210)}\n\n短段落` },
    })
    expect(itemText(wrapper, 'paragraphs')).toContain('建议关注')
    expect(itemText(wrapper, 'paragraphs')).toContain('第 1 段')
  })

  test('AI 辅助声明为必读提示项（真实平台要求）', () => {
    const wrapper = mount(ZhihuProofChecklist, {
      props: { ...baseProps, question: '这个问题足够长了吧', content: 'x'.repeat(300) },
    })
    expect(itemText(wrapper, 'ai-declaration')).toContain('AI')
  })

  test('formatIssues 非空时在规范提示区展示', () => {
    const wrapper = mount(ZhihuProofChecklist, {
      props: {
        ...baseProps, question: '这个问题足够长了吧', content: 'x'.repeat(300),
        formatIssues: ['正文约 3200 字，超过建议上限 3000 字'],
      },
    })
    expect(wrapper.find('[data-test="zhihu-check-issues"]').text()).toContain('超过建议上限')
  })

  test('返回创作修改入口上抛 goEdit', async () => {
    const wrapper = mount(ZhihuProofChecklist, {
      props: { ...baseProps, question: '这个问题足够长了吧', content: 'x'.repeat(300) },
    })
    await wrapper.find('[data-test="zhihu-check-go-edit"]').trigger('click')
    expect(wrapper.emitted('goEdit')).toHaveLength(1)
  })
})
