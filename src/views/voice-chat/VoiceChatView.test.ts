// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, expect, test, vi } from 'vitest'
import VoiceChatView from './VoiceChatView.vue'
import * as api from './chat-api'

const auth = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('../../composables/useAuth', () => ({ useAuth: () => ({ currentUser: auth.current }) }))
vi.mock('./chat-api', async original => ({ ...await original<typeof api>(), reply: vi.fn(), transcribe: vi.fn() }))
afterEach(() => { vi.clearAllMocks() })

function setup(loggedIn = true) {
  const current = ref<{ id: string } | null>(loggedIn ? { id: 'a' } : null)
  auth.current = current
  const wrapper = mount(VoiceChatView)
  return { wrapper, current }
}

test('未登录显示真实登录入口并禁止模型与录音操作', async () => {
  const { wrapper } = setup(false)
  await wrapper.get('textarea').setValue('你好')
  expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('.voice-chat-hold').attributes('disabled')).toBeDefined()
  const login = wrapper.findAll('button').find(button => button.text() === '登录 / 注册')!
  await login.trigger('click')
  expect(wrapper.emitted('request-login')).toHaveLength(1)
  wrapper.unmount()
})

test('输入法回车不发送，快捷键发送；错误保留输入；账号切换清空', async () => {
  vi.mocked(api.reply).mockResolvedValue({ content: '回复', provider: 'configured', model: 'model', runId: 'r' })
  const { wrapper, current } = setup()
  await wrapper.get('input[type="checkbox"]').setValue(false)
  await wrapper.get('textarea').setValue('问题')
  await wrapper.get('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true, isComposing: true })
  expect(api.reply).not.toHaveBeenCalled()
  await wrapper.get('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true })
  await flushPromises()
  expect(wrapper.get('[role="log"]').text()).toContain('回复')
  vi.mocked(api.reply).mockRejectedValueOnce(new Error('模型未配置'))
  await wrapper.get('textarea').setValue('第二次')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('模型未配置')
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('第二次')
  current.value = { id: 'b' }; await flushPromises()
  expect(wrapper.get('[role="log"]').text()).not.toContain('回复')
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  wrapper.unmount()
})

test('真实路由卸载后，迟到回答不触发朗读或页面更新', async () => {
  let resolve!: (value: api.ChatReply) => void
  vi.mocked(api.reply).mockImplementation(() => new Promise(yes => { resolve = yes }))
  const { wrapper } = setup()
  await wrapper.get('textarea').setValue('问题')
  await wrapper.get('form').trigger('submit')
  const signal = vi.mocked(api.reply).mock.calls[0]![2]
  wrapper.unmount()
  expect(signal.aborted).toBe(true)
  resolve({ content: '迟到回答', provider: 'p', model: 'm', runId: 'r' }); await flushPromises()
})
