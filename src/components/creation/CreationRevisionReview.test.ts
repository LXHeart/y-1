// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import CreationRevisionReview from './CreationRevisionReview.vue'
it('原始事实和危险HTML只作文本，恢复需点击', async () => {
 const w = mount(CreationRevisionReview, { props: { original: '<script>x</script>36元', edited: '38元', brief: { processingMode: 'adapt', facts: [{ statement: '确认38元', basis: 'user-confirmed' }] } } })
 expect(w.find('script').exists()).toBe(false); expect(w.text()).toContain('确认38元'); expect(w.emitted('restore')).toBeUndefined()
 await w.findAll('button')[1]!.trigger('click'); expect(w.emitted('restore')).toHaveLength(1); w.unmount()
})
