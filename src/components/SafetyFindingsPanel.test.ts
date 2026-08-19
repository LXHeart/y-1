// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SafetyFindingsPanel from './SafetyFindingsPanel.vue'

afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

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
})
