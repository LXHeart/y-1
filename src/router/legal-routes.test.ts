// @vitest-environment happy-dom
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, test } from 'vitest'
import router from './index'
import { useAuthStore } from '../stores/auth'
import type { AuthUser } from '../types/auth'

/**
 * 用户端法律路由与守卫豁免（任务书 #85）。
 * 消费 ./index 导出的真实路由表；每用例新建 pinia（守卫内的 useAuthStore 绑定 active pinia），
 * 路由实例为模块单例、断言只看每次导航后的终态。
 */
beforeEach(() => {
  setActivePinia(createPinia())
})

describe('法律路由解析（任务书 #85 TC-85-011）', () => {
  test('/docs/user-agreement 解析到 user-agreement 且挂在 DefaultLayout 下', () => {
    const resolved = router.resolve('/docs/user-agreement')
    expect(resolved.name).toBe('user-agreement')
    expect(resolved.matched.length).toBeGreaterThan(0)
    expect(resolved.matched[0].path).toBe('/')
  })

  test('/docs/privacy-policy 解析到 privacy-policy 且挂在 DefaultLayout 下', () => {
    const resolved = router.resolve('/docs/privacy-policy')
    expect(resolved.name).toBe('privacy-policy')
    expect(resolved.matched.length).toBeGreaterThan(0)
    expect(resolved.matched[0].path).toBe('/')
  })

  test('/ 的 name 仍为 home（对照）', () => {
    expect(router.resolve('/').name).toBe('home')
  })
})

describe('首登改密最小豁免（任务书 #85 TC-85-014 / AC-005）', () => {
  test('mustChangePassword 时法律路由放行，业务路由拉去 first-password', async () => {
    const auth = useAuthStore()
    auth.currentUser = {
      id: 'u1',
      email: 'a@b.test',
      role: 'recommender',
      mustChangePassword: true,
    } as AuthUser
    auth.loaded = true

    await router.push('/docs/user-agreement')
    expect(router.currentRoute.value.name).toBe('user-agreement')

    await router.push('/video')
    expect(router.currentRoute.value.name).toBe('first-password')

    // not-found 不豁免（C-02 D-06）：未知路径在首登改密态仍被拉去改密
    await router.push('/no-such-page')
    expect(router.currentRoute.value.name).toBe('first-password')

    // 复位后业务路由恢复放行（既有硬闸语义回归）
    auth.currentUser = {
      id: 'u1',
      email: 'a@b.test',
      role: 'recommender',
      mustChangePassword: undefined,
    } as AuthUser
    await router.push('/video')
    expect(router.currentRoute.value.name).toBe('video')
  })

  test('mustChangePassword 时隐私政策路由同样放行', async () => {
    const auth = useAuthStore()
    auth.currentUser = {
      id: 'u1',
      email: 'a@b.test',
      role: 'recommender',
      mustChangePassword: true,
    } as AuthUser
    auth.loaded = true

    await router.push('/docs/privacy-policy')
    expect(router.currentRoute.value.name).toBe('privacy-policy')
  })
})

describe('catch-all 404 路由（任务书 #85 C-02 TC-85-021 路由侧）', () => {
  test('/no-such-page 与深层未匹配路径均解析到 not-found', () => {
    expect(router.resolve('/no-such-page').name).toBe('not-found')
    expect(router.resolve('/a/b/c/deep-miss').name).toBe('not-found')
  })

  test('catch-all 挂在 DefaultLayout 下（获得站点导航外壳）', () => {
    const resolved = router.resolve('/no-such-page')
    expect(resolved.matched.length).toBeGreaterThan(0)
    expect(resolved.matched[0].path).toBe('/')
  })
})

describe('旧 redirect 回归（任务书 #85 C-02 TC-85-022）', () => {
  test('四条旧 redirect 与根路径不被 catch-all 吞掉', async () => {
    // redirect 只在导航期生效，必须用 push 断言（resolve 不跟随 redirect）
    await router.push('/home')
    expect(router.currentRoute.value.name).toBe('home')

    await router.push('/image-gen')
    expect(router.currentRoute.value.name).toBe('ai-center')

    await router.push('/precedents')
    expect(router.currentRoute.value.fullPath).toBe('/grassland?settings=precedents')

    await router.push('/complaints')
    expect(router.currentRoute.value.fullPath).toBe('/grassland?settings=complaints')

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('home')
  })
})
