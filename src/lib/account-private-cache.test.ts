// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { clearAccountCache, registerAccountKey } from './account-private-cache'

/** 任务书 #103 C103-10 / TC103-10-04/06：账号私有缓存登记与清理只作用于本账号命名空间。 */
afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('account-private-cache', () => {
  test('只清登记账号的键：其他账号与未登记键（主题偏好）不动', () => {
    localStorage.setItem('theme', 'dark')
    sessionStorage.setItem('video-canvas-bind:acct-a:1:s1', 'a1')
    localStorage.setItem('subtitle-cues-acct-a:t1', '[]')
    localStorage.setItem('subtitle-cues-acct-b:t1', '[]')
    registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1')
    registerAccountKey('acct-a', 'local', 'subtitle-cues-acct-a:t1')
    registerAccountKey('acct-b', 'local', 'subtitle-cues-acct-b:t1')

    const cleared = clearAccountCache('acct-a')

    expect(cleared).toBe(2)
    expect(sessionStorage.getItem('video-canvas-bind:acct-a:1:s1')).toBeNull()
    expect(localStorage.getItem('subtitle-cues-acct-a:t1')).toBeNull()
    expect(localStorage.getItem('theme')).toBe('dark')
    expect(localStorage.getItem('subtitle-cues-acct-b:t1')).toBe('[]')
    // 清理后登记表不再含该账号；重复清理幂等（0 键可清）。
    expect(clearAccountCache('acct-a')).toBe(0)
    expect(clearAccountCache('acct-b')).toBe(1)
  })

  test('重复登记去重；匿名（null）不登记', () => {
    registerAccountKey('acct-a', 'local', 'k1')
    registerAccountKey('acct-a', 'local', 'k1')
    registerAccountKey(null, 'local', 'k2')
    localStorage.setItem('k1', 'v')
    expect(clearAccountCache('acct-a')).toBe(1)
  })

  test('默认向同源标签页广播；broadcast:false 不广播（接收侧环路防护）', () => {
    // 模块级 channel 在首个清理时已按环境创建——对原型 postMessage 打桩最稳。
    const postMessage = vi.spyOn(BroadcastChannel.prototype, 'postMessage').mockImplementation(() => {})

    sessionStorage.setItem('video-canvas-bind:acct-a:2:s2', 'a')
    registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:2:s2')
    clearAccountCache('acct-a')
    expect(postMessage).toHaveBeenCalledWith({ type: 'clear', accountId: 'acct-a' })

    // 另一标签页收到广播（broadcast:false 路径）后同样清理，且不再二次广播。
    localStorage.setItem('subtitle-cues-acct-c:t3', '[]')
    registerAccountKey('acct-c', 'local', 'subtitle-cues-acct-c:t3')
    // 重置模块级 channel 状态不易，直接验证接收逻辑：以 broadcast:false 清理不广播。
    postMessage.mockClear()
    expect(clearAccountCache('acct-c', { broadcast: false })).toBe(1)
    expect(postMessage).not.toHaveBeenCalled()
  })
})
