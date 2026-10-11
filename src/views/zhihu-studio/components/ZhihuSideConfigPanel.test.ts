// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import ZhihuSideConfigPanel from './ZhihuSideConfigPanel.vue'
import { useAuth } from '../../../composables/useAuth'

/**
 * 左栏选题台：回答/文章形态分叉输入、questionRef 溯源展示、任务锁定问题只读、
 * 风格 chips、生成门槛与未登录上抛。
 */

vi.mock('../../../composables/useAuth', () => ({
  useAuth: () => ({ isAuthenticated: { value: true } }),
}))
void useAuth

const baseProps = {
  answerMode: true,
  question: '',
  questionRef: '',
  topic: '',
  genreOptions: [{
    category: 'GENRE' as const, code: 'tech', name: '科技数码', description: '', sortOrder: 1,
    applicablePlatforms: ['zhihu'],
  }],
  styleOptions: [{
    category: 'STYLE' as const, code: 'calm', name: '克制说理', description: '', sortOrder: 1,
    applicablePlatforms: [],
  }],
  genre: '',
  style: '',
  loading: false,
  error: '',
  audience: '',
  balance: 12,
  balanceError: '',
  formatSummary: '知乎规范建议：正文 200-3000 字；标题上限 30 字。',
  canGenerate: true,
  generating: false,
  generateLabel: '✦ AI 生成回答',
}

describe('形态分叉输入', () => {
  test('回答模式：问题输入 + 补充说明；无选题方向输入', () => {
    const wrapper = mount(ZhihuSideConfigPanel, { props: { ...baseProps } })
    expect(wrapper.find('[data-test="zhihu-question-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="zhihu-supplement-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="zhihu-topic-input"]').exists()).toBe(false)
  })

  test('文章模式：选题方向输入；无问题/补充说明', () => {
    const wrapper = mount(ZhihuSideConfigPanel, { props: { ...baseProps, answerMode: false } })
    expect(wrapper.find('[data-test="zhihu-topic-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="zhihu-question-input"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="zhihu-supplement-input"]').exists()).toBe(false)
  })

  test('问题输入经 update:question 上抛（engine.setQuestion 接线）', async () => {
    const wrapper = mount(ZhihuSideConfigPanel, { props: { ...baseProps } })
    await wrapper.find('[data-test="zhihu-question-input"]').setValue('AI 写的文章为什么一眼能被认出来')
    expect(wrapper.emitted('update:question')?.[0]).toEqual(['AI 写的文章为什么一眼能被认出来'])
  })

  test('questionRef 有值时展示溯源提示（仅本地、不发起抓取）', () => {
    const wrapper = mount(ZhihuSideConfigPanel, {
      props: { ...baseProps, question: '问题链接', questionRef: '123456789' },
    })
    expect(wrapper.find('[data-test="zhihu-question-ref"]').text()).toContain('123456789')
    expect(wrapper.find('[data-test="zhihu-question-ref"]').text()).toContain('不发起抓取')
  })

  test('任务锁定问题时输入禁用并提示', () => {
    const wrapper = mount(ZhihuSideConfigPanel, {
      props: { ...baseProps, question: '商家给定问题', questionLocked: true },
    })
    expect(wrapper.find('[data-test="zhihu-question-input"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('任务指定问题，不可修改')
  })
})

describe('风格与读者', () => {
  test('领域/语气 chips 单选上抛；目标读者可清空（再点同项取消）', async () => {
    const wrapper = mount(ZhihuSideConfigPanel, { props: { ...baseProps, audience: '泛读者' } })
    await wrapper.find('[data-test="zhihu-genre-tech"]').trigger('click')
    expect(wrapper.emitted('update:genre')?.[0]).toEqual(['tech'])
    await wrapper.find('[data-test="zhihu-style-calm"]').trigger('click')
    expect(wrapper.emitted('update:style')?.[0]).toEqual(['calm'])
    // 已选读者再点同项 → 清空。
    const audienceChip = wrapper.findAll('.gl-chip').find(c => c.text() === '泛读者')
    await audienceChip!.trigger('click')
    expect(wrapper.emitted('update:audience')?.[0]).toEqual([''])
  })

  test('目录加载失败展示错误与重试入口', async () => {
    const wrapper = mount(ZhihuSideConfigPanel, {
      props: { ...baseProps, error: '风格目录拉取失败' },
    })
    expect(wrapper.find('[role="alert"]').text()).toContain('风格目录拉取失败')
    await wrapper.find('.zhihu-config-retry').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})

describe('生成门槛', () => {
  test('canGenerate=false 时按钮禁用且不 emit generate', async () => {
    const wrapper = mount(ZhihuSideConfigPanel, {
      props: { ...baseProps, canGenerate: false },
    })
    const button = wrapper.find('[data-test="zhihu-generate"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(wrapper.emitted('generate')).toBeUndefined()
  })

  test('generating 时按钮禁用并显示生成中文案', () => {
    const wrapper = mount(ZhihuSideConfigPanel, {
      props: { ...baseProps, generating: true },
    })
    const button = wrapper.find('[data-test="zhihu-generate"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toContain('生成中')
  })
})
