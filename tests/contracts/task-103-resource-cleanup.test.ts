// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createPinia, getActivePinia, setActivePinia } from 'pinia'
import { describe, expect, test, vi } from 'vitest'
import NotificationBell from '../../src/components/NotificationBell.vue'
import { useAuth } from '../../src/composables/useAuth'
import { useNotifications } from '../../src/composables/useNotifications'
import type { AuthUser } from '../../src/types/auth'

/**
 * 任务书 #103 C103-22（TC103-22-02/03/04）共享测试环境资源清理合约：
 *
 * 1) 真实挂载/卸载 NotificationBell：轮询 interval、请求 stub 与 store 状态不残留；
 * 2) tests/setup-env.ts 的全局 afterEach：上一用例的 stub globals/fake timers/借用
 *    Pinia 槽位不泄漏进下一用例；
 * 3) 清理不干扰测试自有实例：用例内自建 pinia 的状态在用例期间保持；
 * 4) 配置守卫：vitest 不吞 unhandled rejection，setup-env 逐用例收尾在位。
 *
 * 用例间协作通过模块级 marker 变量完成（vitest 同文件内按声明顺序执行）。
 */

const repositoryRoot = resolve(import.meta.dirname, '../..')
const { currentUser } = useAuth()

function asUser(id: string): AuthUser {
  return { id, email: `${id}@test.local`, displayName: id, role: 'user' }
}

function stubFetch(): void {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data: { unreadCount: 1, items: [], nextBefore: null, nextBeforeId: null } }),
    text: async () => JSON.stringify({ success: true, data: { unreadCount: 1 } }),
  })))
}

describe('真实挂载卸载不残留（TC103-22-03）', () => {
  test('登录挂载产生轮询 timer，卸载后清零；换账号重复 startPolling 不叠加', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data: { unreadCount: 1, items: [], nextBefore: null, nextBeforeId: null } }),
        text: async () => JSON.stringify({ success: true, data: { unreadCount: 1 } }),
      }
    }))
    vi.useFakeTimers()
    currentUser.value = asUser('u-cleanup-1')
    const wrapper = mount(NotificationBell)
    await flushPromises()

    // 推进一个轮询周期（60s）：interval 真的驱动 refreshUnreadCount（发出请求），
    // 同时消化 Vue devtools 回放 timeout（3s，文件内首个 mount 的一次性框架噪音）。
    const pollsBefore = calls.length
    vi.advanceTimersByTime(60_000)
    await flushPromises()
    expect(calls.length).toBeGreaterThan(pollsBefore)
    // 此后计数里只剩轮询 interval 一个业务 timer。
    expect(vi.getTimerCount()).toBe(1)

    // 顶栏不随视图切换卸载：同一实例内换账号 → watch 再触发 startPolling，
    // store 的 startPolling 先 stop 再 start，仍然只有一个 timer。
    currentUser.value = asUser('u-cleanup-2')
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)

    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)

    // 卸载后再推进一个周期，不再发起任何请求。
    const afterUnmount = calls.length
    vi.advanceTimersByTime(60_000)
    await flushPromises()
    expect(calls.length).toBe(afterUnmount)
  })

  test('卸载后 store 清理：reset 清空未读数与列表', () => {
    stubFetch()
    vi.useFakeTimers()
    currentUser.value = asUser('u-cleanup-3')
    const wrapper = mount(NotificationBell)
    wrapper.unmount()

    const notifications = useNotifications()
    notifications.reset()
    expect(notifications.items.value).toEqual([])
    expect(notifications.unreadCount.value).toBe(0)
  })
})

describe('共享环境逐用例收尾（TC103-22-02/04）', () => {
  // 第一个用例故意弄脏全局槽位资源：stub fetch、fake timers（留给 setup-env 收尾）；
  // Pinia 槽位借用则由本用例自行归还——setup-env 刻意不管理槽位（TC103-22-04 红线），
  // 借用方自还就是仓库约定。
  const dirtyMarkerFetch = vi.fn(async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}), text: async () => '{}' }))

  test('脏用例：stub 全局 + fake timers 留给收尾；Pinia 槽位借用后自还', () => {
    vi.stubGlobal('fetch', dirtyMarkerFetch)
    vi.useFakeTimers()
    const own = createPinia()
    const shared = getActivePinia()
    setActivePinia(own)
    expect(getActivePinia()).toBe(own)
    // 借用方自还（约定）：下一个用例的模块级 store 绑定不被打断。
    setActivePinia(shared!)
    expect(getActivePinia()).toBe(shared)
  })

  test('下一用例看到干净环境：stub/timers 由 setup-env 归还，共享 store 绑定未被打断', () => {
    expect(globalThis.fetch).not.toBe(dirtyMarkerFetch)
    // fake timers 已还原为真实计时器：vi.getTimerCount() 仅在 mock 计时器下可用。
    expect(() => vi.getTimerCount()).toThrow(/not mocked/)
    // 模块级 store 引用仍然解析到同一共享实例。
    currentUser.value = asUser('u-after-cleanup')
    expect(useAuth().currentUser.value?.id).toBe('u-after-cleanup')
  })

  test('测试自建 Pinia 实例在用例内不被清理干扰（TC103-22-04）', () => {
    const own = createPinia()
    setActivePinia(own)
    const ownNotifications = useNotifications()
    ownNotifications.unreadCount.value = 7
    expect(ownNotifications.unreadCount.value).toBe(7)
    // 收尾只作用于全局槽位资源（stub/timers），自建实例的 store 状态保持。
    const again = useNotifications()
    expect(again.unreadCount.value).toBe(7)
  })
})

describe('配置守卫（不吞 worker 错误，TC103-22-03）', () => {
  test('vitest 配置未开启 dangerouslyIgnoreUnhandledErrors', () => {
    const config = readFileSync(resolve(repositoryRoot, 'vitest.config.ts'), 'utf8')
    expect(config).not.toContain('dangerouslyIgnoreUnhandledErrors')
  })

  test('setup-env 逐用例收尾在位：unstub/timers，且不触碰 Pinia 槽位', () => {
    const setup = readFileSync(resolve(repositoryRoot, 'tests/setup-env.ts'), 'utf8')
    expect(setup).toContain('afterEach')
    expect(setup).toContain('vi.unstubAllGlobals()')
    expect(setup).toContain('vi.useRealTimers()')
    // 红线注释在位：不得在全局收尾里替换/归还 Pinia 槽位（模块级 store 绑定模式依赖它）。
    expect(setup).toContain('不能干扰测试自有实例')
  })
})
