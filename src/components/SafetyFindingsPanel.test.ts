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
    expect(wrapper.emitted('updated')?.[0]).toEqual([fresh])
  })
})
