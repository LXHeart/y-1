// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import examples from '../../../../contracts/canvas-plan-v1.examples.json'
import type { CanvasPlanAction } from '../../../types/video-canvas'
import CanvasPlanPreview from './CanvasPlanPreview.vue'

describe('C102-06 shared Java/model/UI kind contract', () => {
  test('unknown kind never renders as preparation', () => {
    const wrapper = mount(CanvasPlanPreview, { props: { action: { kind: 'unknown' } as unknown as CanvasPlanAction, planStatus: 'ready' } })
    expect(wrapper.find('[data-test="canvas-plan-prepare"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="canvas-plan-invalid"]').text()).toContain('无法应用')
    wrapper.unmount()
  })
  for (const example of examples.valid) {
    test(example.name, () => {
      const wrapper = mount(CanvasPlanPreview, { props: {
        action: example.action as CanvasPlanAction, planStatus: 'ready',
      } })
      expect(wrapper.text()).toContain(example.uiLabel)
      if (example.action.kind === 'edit') expect(wrapper.find('[data-test="canvas-plan-prepare"]').exists()).toBe(false)
      wrapper.unmount()
    })
  }
})
