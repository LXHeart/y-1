// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import LoginModal from './LoginModal.vue'
import type { RegisterFormValues } from '../types/auth'

/**
 * 登录/注册弹窗。锁住注册流程新增的“初始身份选择”：
 * - 仅注册模式渲染，默认推荐官，可切换商家；
 * - 选择结果作为 initialIdentity 进入注册请求，由服务端创建对应身份档案。
 * 同时锁定既有注册流程（验证码/邮箱验证码字段）不被破坏。
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

function mountModal() {
  return mount(LoginModal, {
    props: { visible: true, submitting: false, error: '' },
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

describe('初始身份选择渲染', () => {
  test('登录模式下不渲染身份选择', () => {
    const wrapper = mountModal()
    expect(wrapper.findAll('.login-identity-card')).toHaveLength(0)
  })

  test('注册模式下渲染两张身份卡片，默认选中推荐官', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)

    const cards = wrapper.findAll('.login-identity-card')
    expect(cards).toHaveLength(2)
    expect(wrapper.text()).toContain('推荐官')
    expect(wrapper.text()).toContain('商家')
    expect(cards[0].attributes('aria-checked')).toBe('true')
    expect(cards[1].attributes('aria-checked')).toBe('false')
    expect(cards[0].classes()).toContain('login-identity-card-active')
  })

  test('点击商家卡片切换选中状态', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)

    const cards = wrapper.findAll('.login-identity-card')
    await cards[1].trigger('click')
    // 点击后重渲染，重新查询卡片引用
    const after = wrapper.findAll('.login-identity-card')
    expect(after[1].attributes('aria-checked')).toBe('true')
    expect(after[0].attributes('aria-checked')).toBe('false')
    expect(after[1].classes()).toContain('login-identity-card-active')

    // 可再切回推荐官
    await after[0].trigger('click')
    const afterSwitchBack = wrapper.findAll('.login-identity-card')
    expect(afterSwitchBack[0].attributes('aria-checked')).toBe('true')
  })
})

describe('注册提交与初始身份契约', () => {
  test('默认推荐官提交：payload 携带 initialIdentity=recommender', async () => {
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
      initialIdentity: 'recommender',
    })
  })

  test('切换商家后提交：payload 携带 initialIdentity=merchant', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)
    await fillRegisterForm(wrapper)
    await wrapper.findAll('.login-identity-card')[1].trigger('click')

    await wrapper.find('form').trigger('submit')

    const emitted = wrapper.emitted('register')
    expect(emitted).toBeTruthy()
    const values = emitted![0][0] as RegisterFormValues
    expect(values.initialIdentity).toBe('merchant')
  })

  test('表单未填完时提交不触发注册', async () => {
    const wrapper = mountModal()
    await switchToRegister(wrapper)
    await wrapper.find('#login-email').setValue('grass@test.local')

    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('register')).toBeUndefined()
  })
})
