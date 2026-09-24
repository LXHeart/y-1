// @vitest-environment happy-dom
import { enableAutoUnmount, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import DigitalHumanTranscript from './DigitalHumanTranscript.vue'
import type { TranscriptEntry } from '../../../types/digital-human'

/** TC105E-05-04 无障碍长文本：可滚动/复制；aria-live 只报状态不逐 token 刷屏；删除需确认。 */

function longText(codePoints: number): string {
  return '这是一段用于可读性与滚动检查的字幕正文。'.repeat(Math.ceil(codePoints / 16)).slice(0, codePoints)
}

function mountTranscript(overrides: Record<string, unknown> = {}): VueWrapper {
  return mount(DigitalHumanTranscript, {
    props: {
      entries: [],
      live: null,
      saved: false,
      hasSaved: false,
      ...overrides,
    },
    attachTo: document.body,
    global: { stubs: { teleport: true } },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

enableAutoUnmount(afterEach)

describe('TC105E-05-04 无障碍长文本', () => {
  test('2000 码点多轮内容：列表可滚动（overflow-y:auto）、正文可选择复制、逐轮分卡', () => {
    const entries: TranscriptEntry[] = [
      { id: 'u-1', utteranceSeq: 1, role: 'user', text: longText(300), status: 'complete', startedAt: '2026-09-23T00:00:00Z', endedAt: '2026-09-23T00:00:01Z' },
      { id: 'a-1', utteranceSeq: 2, role: 'assistant', text: longText(2000), status: 'complete', startedAt: '2026-09-23T00:00:02Z', endedAt: '2026-09-23T00:00:10Z' },
      { id: 'a-2', utteranceSeq: 3, role: 'assistant', text: '被打断的一句', status: 'interrupted', startedAt: '2026-09-23T00:00:11Z', endedAt: '2026-09-23T00:00:12Z' },
    ]
    const wrapper = mountTranscript({ entries, hasSaved: true })

    const list = wrapper.get('[data-testid="dh-transcript-list"]')
    expect(list.attributes('role')).toBe('log')
    expect(getComputedStyle(list.element).overflowY).toBe('auto')

    // 每轮一张卡：角色标签 + 中断标记；正文 user-select 允许复制。
    const cards = wrapper.findAll('.dh-transcript-entry')
    expect(cards).toHaveLength(3)
    expect(cards[0].attributes('data-role')).toBe('user')
    expect(cards[0].text()).toContain('我')
    expect(cards[2].text()).toContain('已中断')
    const longText2 = cards[1].get('.dh-transcript-text')
    expect(getComputedStyle(longText2.element).userSelect).toBe('text')
    expect((longText2.element as HTMLElement).textContent?.length).toBeGreaterThanOrEqual(2000)
  })

  test('aria-live 只在状态行（生成区/正文不在 live region）——不逐 token 刷屏', () => {
    const wrapper = mountTranscript({
      entries: [],
      live: { turnId: 't-1', text: '正在生成的一句'.repeat(50), interrupted: false },
    })
    const liveRegion = wrapper.get('[data-testid="dh-transcript-status"]')
    expect(liveRegion.attributes('aria-live')).toBe('polite')
    // 状态行只含状态文案，不含生成正文。
    expect(liveRegion.text()).not.toContain('正在生成的一句')
    // 生成区与正文容器不带 aria-live。
    expect(wrapper.get('[data-testid="dh-transcript-live"]').attributes('aria-live')).toBeUndefined()
    expect(wrapper.get('[data-testid="dh-transcript-list"]').attributes('aria-live')).toBeUndefined()
  })

  test('删除需 GlModal 确认：按钮出现 → 确认弹窗 → emit delete；说明含独立素材不受影响', async () => {
    const wrapper = mountTranscript({ hasSaved: true })
    expect(wrapper.find('[data-testid="dh-transcript-delete-confirm"]').exists()).toBe(false)

    await wrapper.get('[data-testid="dh-transcript-delete"]').trigger('click')
    const dialog = wrapper.get('[data-testid="dh-transcript-delete-confirm"]')
    expect(wrapper.text()).toContain('不可恢复')
    expect(wrapper.text()).toContain('素材库')

    await dialog.trigger('click')
    expect(wrapper.emitted('delete')).toHaveLength(1)
  })

  test('保存同意开关 emit toggle-save；说明明确「只保存最终文字」', async () => {
    const wrapper = mountTranscript({ saved: false })
    const toggle = wrapper.get('[data-testid="dh-transcript-save-toggle"]')
    expect((toggle.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).toContain('只保存最终文字')

    await toggle.setValue(true)
    expect(wrapper.emitted('toggle-save')).toEqual([[true]])
  })
})
