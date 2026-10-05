// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import VoiceProfilePanel from './VoiceProfilePanel.vue'
const api = vi.hoisted(() => vi.fn())
vi.mock('../../stores/account-session', () => ({ useAccountSessionStore: () => ({ epoch: 0, ownerAccountId: 'A', capture: () => ({ accountId: 'A' }), isCurrent: () => true }) }))
vi.mock('../../composables/grassland-http', async original => ({ ...await original<object>(), request: api }))
beforeEach(() => { api.mockReset(); api.mockResolvedValue({ role: 'consumer', revision: 0, enabled: false, rules: [], samples: [], updatedAt: null }) })
it('缺身份显示提示；首次空档案不自动开启', async () => {
 const w = mount(VoiceProfilePanel, { props: { modelValue: { processingMode: 'create' }, platform: 'zhihu', genre: 'article' } })
 expect(w.text()).toContain('先选择表达身份')
 await w.setProps({ modelValue: { processingMode: 'create', authorRole: 'consumer', voice: { mode: 'none' } } }); await flushPromises()
 expect(w.text()).toContain('还没有保存这类文风'); expect(w.get('input').element).toHaveProperty('checked', false)
 w.unmount()
})
it('模态框编辑、提炼、明确保存和只读导入均走真实组件事件', async () => {
 const w = mount(VoiceProfilePanel, { attachTo: document.body, global: { stubs: { Teleport: true } }, props: { modelValue: { processingMode: 'create', authorRole: 'consumer', voice: { mode: 'none' } }, platform: 'zhihu', genre: 'article' } })
 await flushPromises()
 const button = (text: string) => w.findAll('button').find(b => b.text() === text)!
 await button('编辑我的文风').trigger('click')
 expect(w.get('[role=dialog]').text()).toContain('长期表达规则')
 await w.get('textarea[aria-describedby=voice-rules-help]').setValue('少用感叹号')
 await w.findAll('input[type=checkbox]')[1]!.setValue(true)
 api.mockResolvedValueOnce({ preferences: ['保留否定'] }); await button('查看旧版风格偏好并导入').trigger('click'); await flushPromises()
 expect(w.text()).toContain('保留否定')
 await w.findAll('input[type=checkbox]')[2]!.setValue(true)
 await button('加入待保存规则').trigger('click')
 api.mockResolvedValueOnce({ role: 'consumer', revision: 1, enabled: true, rules: ['少用感叹号', '保留否定'], samples: [], updatedAt: null })
 await button('保存文风').trigger('click'); await flushPromises()
 expect(api.mock.calls.filter(c => c[1]?.method === 'PUT')).toHaveLength(1)
 expect(w.find('[role=dialog]').exists()).toBe(false); expect(w.text()).toContain('文风已保存'); w.unmount()
})

it('恢复草稿时平台与简报同批更新不丢弃已有文风引用', async () => {
 const w = mount(VoiceProfilePanel, { props: { modelValue: { processingMode: 'create', voice: { mode: 'none' } }, platform: 'wechat', genre: 'article' } })
 await w.setProps({ modelValue: { processingMode: 'create', authorRole: 'consumer', voice: { mode: 'profile', role: 'consumer', revision: 1 } }, platform: 'zhihu' })
 await flushPromises()
 expect(w.emitted('update:modelValue') ?? []).toEqual([])
 w.unmount()
})

it('鼠标打开弹窗即使浏览器未自动聚焦，关闭后仍返回触发按钮', async () => {
 const w = mount(VoiceProfilePanel, { attachTo: document.body, global: { stubs: { Teleport: true } }, props: { modelValue: { processingMode: 'create', authorRole: 'consumer', voice: { mode: 'none' } }, platform: 'zhihu', genre: 'article' } })
 await flushPromises()
 const opener = w.findAll('button').find(b => b.text() === '编辑我的文风')!
 await opener.trigger('click'); await flushPromises()
 await w.get('[data-action="close-modal"]').trigger('click'); await flushPromises()
 expect(document.activeElement).toBe(opener.element)
 w.unmount()
})
