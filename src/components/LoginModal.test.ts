// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import LoginModal from './LoginModal.vue'
import type { RegisterFormValues } from '../types/auth'

/**
 * 登录/注册弹窗（2026-09-04 身份模型改版：登录/注册表单彻底移除身份单选）：
 * - 两种模式都没有身份选择，进入身份按账号已有档案自动判定；注册即推荐官；
 * - 注册 payload 不携带 initialIdentity；
 * - 保留既有注册流程锁定（验证码/邮箱验证码字段不被破坏）；
 * - 密码可见切换（眼睛按钮，替代旧的「显示密码」勾选）；
 * - hideRegister（治理台）：无模式切换、恒登录模式。
 */

function stubFetch(): void {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
    ok: true,
    status: 200,
    headers: { get: () => 'image/svg+xml' },
    text: async () => (url.includes('/api/auth/captcha') ? '<svg></svg>' : ''),
    json: async () => ({ success: true }),
  })))
}

function mountModal(props: Record<string, unknown> = {}) {
  return mount(LoginModal, {
    props: { visible: true, submitting: false, error: '', ...props },
    global: { stubs: { Teleport: true } },
  })
}

async function switchToRegister(wrapper: ReturnType<typeof mountModal>): Promise<void> {
  const tabs = wrapper.findAll('.login-mode-btn')
  await tabs[1].trigger('click')
  await flushPromises()
}

async function fillRegisterForm(wrapper: ReturnType<typeof mountModal>): Promise<void> {
  await wrapper.find('#login-email').setValue('grass@test.local')
  await wrapper.find('#login-display-name').setValue('小草原')
  await wrapper.find('#login-password').setValue('password123')
  await wrapper.find('#login-confirm-password').setValue('password123')
  await wrapper.find('#login-captcha').setValue('abcd')
  await wrapper.find('#login-verification-code').setValue('123456')
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  stubFetch()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('注册即推荐官（无身份选择）', () => {
  test('注册模式没有身份选择卡片，文案为注册即推荐官', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)

    expect(wrapper.findAll('.login-identity-card')).toHaveLength(0)
    expect(wrapper.find('.login-identity-choice').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('初始身份')
    expect(wrapper.get('.login-subtitle').text()).toContain('注册即推荐官')
  })

  test('提交 payload 不携带 initialIdentity', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)
    await fillRegisterForm(wrapper)

    await wrapper.find('form').trigger('submit')

    const emitted = wrapper.emitted('register')
    expect(emitted).toBeTruthy()
    const values = emitted![0][0] as RegisterFormValues
    expect(values).toEqual({
      email: 'grass@test.local',
      displayName: '小草原',
      password: 'password123',
      confirmPassword: 'password123',
      verificationCode: '123456',
    })
  })

  test('表单未填完时提交不触发注册', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)
    await wrapper.find('#login-email').setValue('grass@test.local')

    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('register')).toBeUndefined()
  })
})

describe('登录与密码可见切换', () => {
  test('登录模式只有邮箱与密码，标题为登录草场，无身份选择', () => {
    const wrapper = mountModal()

    expect(wrapper.get('.login-title').text()).toBe('登录草场')
    expect(wrapper.find('#login-display-name').exists()).toBe(false)
    expect(wrapper.find('#login-captcha').exists()).toBe(false)
    expect(wrapper.find('#login-verification-code').exists()).toBe(false)
    expect(wrapper.find('.login-identity-choice').exists()).toBe(false)
    expect(wrapper.get('.login-subtitle').text()).toContain('进入身份按账号自动判定')
  })

  test('眼睛按钮切换密码明文', async () => {
    const wrapper = mountModal()

    expect(wrapper.get('#login-password').attributes('type')).toBe('password')
    await wrapper.get('.login-eye-btn').trigger('click')
    expect(wrapper.get('#login-password').attributes('type')).toBe('text')
    await wrapper.get('.login-eye-btn').trigger('click')
    expect(wrapper.get('#login-password').attributes('type')).toBe('password')
  })

  test('登录提交发出 submit 事件', async () => {
    const wrapper = mountModal()
    await wrapper.find('#login-email').setValue('grass@test.local')
    await wrapper.find('#login-password').setValue('password123')

    await wrapper.find('form').trigger('submit')

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toEqual({ email: 'grass@test.local', password: 'password123' })
  })
})

describe('hideRegister（治理台形态）', () => {
  test('隐藏模式切换与注册入口，副标题为治理台文案', () => {
    const wrapper = mountModal({ hideRegister: true })

    expect(wrapper.find('.login-mode-switch').exists()).toBe(false)
    expect(wrapper.find('#login-display-name').exists()).toBe(false)
    expect(wrapper.get('.login-subtitle').text()).toContain('治理')
    expect(wrapper.get('.login-title').text()).toBe('登录草场')
  })
})
