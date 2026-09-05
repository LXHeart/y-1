// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SafetyFindingsPanel from './SafetyFindingsPanel.vue'

afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

const twoFindingsReport = {
  findings: [
    { category: 'illegal', severity: 'high', match: '赌球', index: 0, advice: '立即删除', deep: true },
    { category: 'diversion', severity: 'low', match: '加微信', index: 5, advice: '删除导流', deep: false },
  ],
  lexiconVersion: 'lexicon-v1',
  deepCheck: true,
}

describe('SafetyFindingsPanel', () => {
  it('按严重度展示建议，并用编辑后的文本重新检查', async () => {
    const initial = {
      findings: [
        { category: 'diversion', severity: 'low', match: '加微信', index: 5, advice: '删除导流', deep: false },
        { category: 'illegal', severity: 'high', match: '赌球', index: 0, advice: '立即删除', deep: true },
      ],
      lexiconVersion: 'lexicon-v1',
      deepCheck: true,
    }
    const fresh = { findings: [], lexiconVersion: 'lexicon-v1', deepCheck: false }
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(JSON.stringify({ data: { safety: fresh } }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(SafetyFindingsPanel, { props: { report: initial, text: '编辑后的文案' } })

    const chips = wrapper.findAll('.sfp-chip').map((item) => item.text())
    expect(chips).toEqual(['涉嫌违法', '导流联系'])
    expect(wrapper.text()).toContain('AI 深检')

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ text: '编辑后的文案' })
    expect(wrapper.emitted('updated')?.[0]).toEqual([{ ...fresh, appliedOverlays: [] }])
  })

  it('展示重复度、低原创度与平台行业 overlay 文案', () => {
    const wrapper = mount(SafetyFindingsPanel, {
      props: {
        text: '测试文案',
        report: {
          findings: [
            { category: 'duplicate_content', severity: 'medium', match: '相似度 92%', index: 0, advice: '改写', deep: false },
            { category: 'low_originality', severity: 'low', match: '重复率 40%', index: 0, advice: '减少重复', deep: false },
            { category: 'platform_overlay', severity: 'medium', match: '平台词', index: 0, advice: '替换', deep: false },
            { category: 'industry_overlay', severity: 'medium', match: '行业词', index: 0, advice: '替换', deep: false },
          ],
          lexiconVersion: 'lexicon-v2',
          deepCheck: false,
          appliedOverlays: ['douyin', 'food'],
        },
      },
    })
    expect(wrapper.findAll('.sfp-chip').map((item) => item.text())).toEqual([
      '内容重复度', '平台规则', '行业规则', '低原创度',
    ])
    expect(wrapper.text()).toContain('已叠加：抖音、餐饮')
  })

  it('默认不开启修复：只有「重新检查」一个按钮，无逐项/一键修复（五旧视图零回归）', () => {
    const wrapper = mount(SafetyFindingsPanel, {
      props: { report: twoFindingsReport, text: '文案' },
    })
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.find('[data-test="sfp-fix-all"]').exists()).toBe(false)
  })

  it('BYOK 报告按本次路由隐藏平台修复，保留词库和原创度复查', async () => {
    const wrapper = mount(SafetyFindingsPanel, {
      props: {
        report: { ...twoFindingsReport, deepCheck: false, deepCheckSkipped: true },
        text: '文案', enableFix: true,
      },
    })
    expect(wrapper.text()).toContain('自有模型模式')
    expect(wrapper.text()).toContain('内容修复不提供')
    expect(wrapper.find('[data-test="sfp-fix-all"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="sfp-fix-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="sfp-view-0"]').exists()).toBe(true)
    expect(wrapper.get('.sfp-foot button').text()).toContain('词库与原创度')

    await wrapper.setProps({ report: twoFindingsReport })
    expect(wrapper.text()).toContain('已含 AI 语境深检')
    expect(wrapper.find('[data-test="sfp-fix-all"]').exists()).toBe(true)
  })

  it('enableFix：逐项查看/修复 + 一键修复（N 项），emit 载荷正确', async () => {
    const wrapper = mount(SafetyFindingsPanel, {
      props: { report: twoFindingsReport, text: '文案', enableFix: true },
    })
    expect(wrapper.get('[data-test="sfp-fix-all"]').text()).toBe('一键修复（2 项）')

    await wrapper.get('[data-test="sfp-view-0"]').trigger('click')
    await wrapper.get('[data-test="sfp-fix-1"]').trigger('click')
    await wrapper.get('[data-test="sfp-fix-all"]').trigger('click')
    expect(wrapper.emitted('view')?.[0]).toEqual([
      { category: 'illegal', severity: 'high', match: '赌球', index: 0, advice: '立即删除', deep: true },
    ])
    expect(wrapper.emitted('fix')?.[0]).toEqual([
      { category: 'diversion', severity: 'low', match: '加微信', index: 5, advice: '删除导流', deep: false },
    ])
    expect(wrapper.emitted('fix')?.[1]).toEqual(['all'])
  })

  it('fixing 时修复类按钮禁点，「重新检查」不受影响', async () => {
    const wrapper = mount(SafetyFindingsPanel, {
      props: { report: twoFindingsReport, text: '文案', enableFix: true, fixing: true },
    })
    expect(wrapper.get('[data-test="sfp-fix-all"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="sfp-fix-0"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="sfp-view-0"]').attributes('disabled')).toBeUndefined()

    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      data: { safety: { findings: [], lexiconVersion: 'lexicon-v1', deepCheck: false } },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    await wrapper.get('.sfp-foot button').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalled()
  })

  it('重新检查请求透传 platform/contentForm（enableFix 场景）', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(JSON.stringify({
      data: { safety: { findings: [], lexiconVersion: 'lexicon-v1', deepCheck: false } },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(SafetyFindingsPanel, {
      props: {
        report: twoFindingsReport, text: '文案',
        enableFix: true, platform: 'zhihu', contentForm: 'article',
      },
    })
    await wrapper.get('.sfp-foot button').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      text: '文案', platform: 'zhihu', contentForm: 'article',
    })
  })
})
