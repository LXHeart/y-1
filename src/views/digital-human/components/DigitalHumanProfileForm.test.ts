// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import DigitalHumanProfileForm from './DigitalHumanProfileForm.vue'
import DigitalHumanWorkbench from '../DigitalHumanWorkbench.vue'
import { useAuth } from '../../../composables/useAuth'
import type { AvatarItem, VoiceItem } from '../../../types/digital-human'

/**
 * C105E-02 表单（TC105E-02-01 配置边界与并发 / TC105E-02-03 失败与空态）。
 * 表单纯 props/emits：合法→emit save；非法→贴字段错误不发；冲突文案+保留输入+重载入口。
 * 空态三分支挂真实 Workbench（fetch 只 stub 最外层 HTTP）。
 */

const AVATAR: AvatarItem = {
  id: '11111111-1111-4111-8111-111111111111',
  revision: 1,
  name: '草场助手',
  previewMediaId: 'media-1',
  source: 'preset',
  state: 'ready',
  compatibleBackendIds: ['backend-mock-1'],
  reasonCode: null,
}

const VOICE: VoiceItem = {
  id: 'preset-zh-natural-01',
  name: '自然女声',
  language: 'zh-CN',
  providerModelRef: 'tts-model-1',
  compatibleBackendIds: ['backend-mock-1'],
  enabled: true,
}

function mountForm(overrides: Record<string, unknown> = {}): VueWrapper {
  return mount(DigitalHumanProfileForm, {
    props: {
      profile: null,
      catalogVersion: 1,
      avatars: [AVATAR],
      voices: [VOICE],
      approvedBackendIds: ['backend-mock-1'],
      ...overrides,
    },
    attachTo: document.body,
  })
}

/** 填齐合法必选项（形象/音色走控件交互，保持真实路径）。 */
async function fillValid(wrapper: VueWrapper, name: string, persona: string): Promise<void> {
  await wrapper.get('[data-testid="dh-profile-name"]').setValue(name)
  await wrapper.get('[data-testid="dh-profile-persona"]').setValue(persona)
  await wrapper.get('[data-testid="dh-profile-voice"]').setValue(VOICE.id)
  await wrapper.get(`[data-testid="dh-avatar-${AVATAR.id}"]`).trigger('click')
}

beforeEach(() => {
  useAuth().currentUser.value = null
  window.history.replaceState(null, '', '/')
})

afterEach(() => {
  useAuth().currentUser.value = null
})

enableAutoUnmount(afterEach)

describe('TC105E-02-01 配置边界与并发', () => {
  test.each([
    { label: '40 个 emoji 名字（合法）', name: '😀'.repeat(40), expectError: false },
    { label: '41 个 emoji 名字（非法，码点计数而非 UTF-16）', name: '😀'.repeat(41), expectError: true },
    { label: '40 个汉字（合法）', name: '字'.repeat(40), expectError: false },
    { label: '41 个汉字（非法）', name: '字'.repeat(41), expectError: true },
  ])('$label', async ({ name, expectError }) => {
    const wrapper = mountForm()
    await fillValid(wrapper, name, '人设')
    await wrapper.get('[data-testid="dh-profile-save"]').trigger('click')

    const emits = wrapper.emitted('save')
    if (expectError) {
      expect(emits).toBeUndefined()
      const error = wrapper.get('#dh-profile-name-error')
      expect(error.text()).toContain('不能超过 40')
      // 错误贴近输入并 aria 关联。
      expect(wrapper.get('[data-testid="dh-profile-name"]').attributes('aria-invalid')).toBe('true')
      expect(wrapper.get('[data-testid="dh-profile-name"]').attributes('aria-describedby')).toBe('dh-profile-name-error')
      // 输入保留，不清空。
      expect((wrapper.get('[data-testid="dh-profile-name"]').element as HTMLInputElement).value).toBe(name)
    } else {
      expect(emits).toHaveLength(1)
      expect(emits![0][0]).toMatchObject({ name, persona: '人设', voiceId: VOICE.id, avatarId: AVATAR.id })
    }
  })

  test.each([
    { label: 'persona 4000 码点（合法）', persona: '设'.repeat(4000), expectError: false },
    { label: 'persona 4001 码点（非法）', persona: '设'.repeat(4001), expectError: true },
    { label: '空 persona（非法）', persona: '', expectError: true },
  ])('$label', async ({ persona, expectError }) => {
    const wrapper = mountForm()
    await fillValid(wrapper, '合法名字', persona)
    await wrapper.get('[data-testid="dh-profile-save"]').trigger('click')

    const emits = wrapper.emitted('save')
    if (expectError) {
      expect(emits).toBeUndefined()
      expect(wrapper.find('#dh-profile-persona-error').exists()).toBe(true)
      expect((wrapper.get('[data-testid="dh-profile-persona"]').element as HTMLTextAreaElement).value).toBe(persona)
    } else {
      expect(emits).toHaveLength(1)
      const counter = wrapper.text()
      expect(counter).toContain('4000/4000')
    }
  })

  test('保存提交期间只锁本表单（按钮禁用、不重复提交），字数计数按码点显示', async () => {
    const wrapper = mountForm({ saving: true })
    await fillValid(wrapper, '名字', '人设')

    const saveButton = wrapper.get('[data-testid="dh-profile-save"]')
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(saveButton.text()).toContain('保存中…')
    await saveButton.trigger('click')
    expect(wrapper.emitted('save')).toBeUndefined()

    // 码点计数：emoji 名字显示 3/40 而非 UTF-16 的 6/40（需在可编辑态设置值）。
    await wrapper.setProps({ saving: false })
    await wrapper.get('[data-testid="dh-profile-name"]').setValue('😀😀😀')
    expect(wrapper.text()).toContain('3/40')
    expect(wrapper.text()).not.toContain('6/40')
  })

  test('版本冲突：显示冲突说明与重新载入入口，本地输入保留（emit reload）', async () => {
    const wrapper = mountForm({
      saveError: '角色已被其他页面更新。',
      versionConflict: true,
    })
    await wrapper.get('[data-testid="dh-profile-name"]').setValue('本地未保存的名字')
    await wrapper.get('[data-testid="dh-profile-persona"]').setValue('本地未保存的人设')

    const error = wrapper.get('[data-testid="dh-profile-save-error"]')
    expect(error.text()).toContain('角色已被其他页面更新')
    expect(error.text()).toContain('本地内容已保留')
    expect((wrapper.get('[data-testid="dh-profile-name"]').element as HTMLInputElement).value).toBe('本地未保存的名字')

    const reload = wrapper.get('[data-testid="dh-profile-reload"]')
    await reload.trigger('click')
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })
})

describe('TC105E-02-03 失败与空态（挂真实 Workbench，三分支文案与下一步各不相同）', () => {
  function envelope(data: unknown, status = 200): Response {
    return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
  }

  const CATALOG_WITH_BACKEND = {
    authenticated: true, version: 1, enabled: true, newSessionsAllowed: true,
    recordingEnabled: false, customAvatarEnabled: false, avatars: [], voices: [],
    backends: [{ id: 'backend-mock-1', model: 'fake-render', platformConfigId: 'cfg-1', platformModelVersion: null,
      transport: 'mock', state: 'approved', supportsCustomAvatar: false, supportsRecording: false,
      width: 1280, height: 720, fps: 25, measuredCapacity: 1, evidenceRef: null }],
    limits: { maxSessionsPerAccount: 1, maxSessionsGlobal: 1, maxQueuedGlobal: 10, sessionDurationMs: 600000,
      idleTimeoutMs: 120000, resumeWindowMs: 30000, contextPairs: 10, maxRecordingMs: 300000 },
    billingNoticeVersion: 'dh-billing-v1',
  }

  async function mountWorkbench(catalogResponse: () => Response): Promise<VueWrapper> {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/digital-human/catalog') return catalogResponse()
      if (url === '/api/digital-human/profiles') return envelope({ success: true, data: { items: [], nextCursor: null } })
      return envelope({ success: true, data: {} })
    }))
    const router: Router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/digital-human', name: 'digital-human', component: DigitalHumanWorkbench }],
    })
    await router.push('/digital-human')
    await router.isReady()
    const wrapper = mount(DigitalHumanWorkbench, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    return wrapper
  }

  test('catalog 503：加载失败态 + 重试；不当空角色、不当未开放', async () => {
    useAuth().currentUser.value = { id: 'u-1', email: 'a@example.com', role: 'user', roles: [] }
    let calls = 0
    const wrapper = await mountWorkbench(() => {
      calls += 1
      return envelope({ success: false, error: '服务暂不可用', code: 'dh_runtime_unavailable' }, 503)
    })

    expect(wrapper.text()).toContain('数字人服务暂时不可用')
    expect(wrapper.text()).not.toContain('从配置你的数字人角色开始')
    expect(wrapper.text()).not.toContain('数字人服务暂未开放')
    expect(wrapper.text()).not.toContain('当前没有可用的数字人渲染服务')
    // 失败保留重试入口（下一步 = 重试，而非创建角色）。
    expect(wrapper.findAll('button').some((button) => button.text() === '重试')).toBe(true)
    expect(calls).toBe(1)
  })

  test('无后端（目录开但无 approved 组合）：暂不可用空态，不给「创建第一个角色」主行动', async () => {
    useAuth().currentUser.value = { id: 'u-1', email: 'a@example.com', role: 'user', roles: [] }
    const wrapper = await mountWorkbench(() => envelope({ success: true, data: { ...CATALOG_WITH_BACKEND, backends: [] } }))

    expect(wrapper.text()).toContain('当前没有可用的数字人渲染服务')
    expect(wrapper.find('[data-testid="dh-create-profile"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('从配置你的数字人角色开始')
  })

  test('无角色（服务可用、列表空）：引导创建第一个角色', async () => {
    useAuth().currentUser.value = { id: 'u-1', email: 'a@example.com', role: 'user', roles: [] }
    const wrapper = await mountWorkbench(() => envelope({ success: true, data: CATALOG_WITH_BACKEND }))

    expect(wrapper.text()).toContain('从配置你的数字人角色开始')
    expect(wrapper.get('[data-testid="dh-create-profile"]').text()).toContain('创建第一个角色')
    expect(wrapper.text()).not.toContain('当前没有可用的数字人渲染服务')
  })
})
