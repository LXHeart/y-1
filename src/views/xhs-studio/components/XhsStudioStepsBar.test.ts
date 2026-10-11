// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import XhsStudioStepsBar from './XhsStudioStepsBar.vue'
import { XHS_STUDIO_STEPS } from '../types'

/** 四步条（阶段 0）：current/reached 标记、可点步 emit select、超深步 disabled 不 emit。 */

function mountBar(current: string, reached: string) {
  return mount(XhsStudioStepsBar, {
    props: {
      steps: XHS_STUDIO_STEPS,
      current: current as typeof XHS_STUDIO_STEPS[number]['key'],
      reached: reached as typeof XHS_STUDIO_STEPS[number]['key'],
    },
  })
}

describe('XhsStudioStepsBar', () => {
  test('current 高亮（aria-current=step）、浅于 current 的 done 态', () => {
    const wrapper = mountBar('proof', 'proof')
    const dots = wrapper.findAll('.xhs-step-dot')
    expect(dots.map((d) => d.text())).toEqual(['1选题', '2创作', '3校对', '4发布'])
    expect(dots[2].classes()).toContain('xhs-step-active')
    expect(dots[2].attributes('aria-current')).toBe('step')
    expect(dots[0].classes()).toContain('xhs-step-done')
    expect(dots[1].classes()).toContain('xhs-step-done')
    expect(dots[3].classes()).not.toContain('xhs-step-done')
  })

  test('超 reached 的步 disabled + aria-disabled；点击不 emit select', async () => {
    const wrapper = mountBar('generate', 'generate')
    const dots = wrapper.findAll('.xhs-step-dot')
    expect(dots[2].attributes('disabled')).toBeDefined()
    expect(dots[2].attributes('aria-disabled')).toBe('true')
    await dots[2].trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  test('可点步（含回看与最深步）emit select 并携带步 key', async () => {
    const wrapper = mountBar('proof', 'publish')
    const dots = wrapper.findAll('.xhs-step-dot')
    await dots[0].trigger('click')
    await dots[3].trigger('click')
    expect(wrapper.emitted('select')).toEqual([['pick'], ['publish']])
  })
})
