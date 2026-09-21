// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

/**
 * 任务书 #104 C104-04（R03 / §3 D03 / §7.4）：账号私有缓存 v2——每键元数据、清理
 * 代次墓碑、激活订阅、双通道通知与 v1 迁移。用例覆盖 TC104-04-01～07 的单元层；
 * 真实两标签页实测（TC104-04-01/02 的浏览器层）另由 C104-10 三引擎 spec 承接。
 *
 * 「两页」模拟：同 realm 双模块实例（?tab=a / ?tab=b 查询串隔离模块状态）共享同一
 * localStorage、各自独立 sessionStorage（调用时切换 globalThis.sessionStorage）；
 * BroadcastChannel 用假实现，但消息派发走宏任务异步（与真实 BC 一致），保证接收端
 * 在自己页的 storage 上下文里执行——不用同一个模拟 Storage 冒充两页。
 */

class MemoryStorage implements Storage {
  private map = new Map<string, string>()
  get length(): number { return this.map.size }
  clear(): void { this.map.clear() }
  getItem(key: string): string | null { return this.map.has(key) ? this.map.get(key)! : null }
  key(index: number): string | null { return Array.from(this.map.keys())[index] ?? null }
  removeItem(key: string): void { this.map.delete(key) }
  setItem(key: string, value: string): void { this.map.set(key, String(value)) }
}

/** 消息异步派发（宏任务）——接收端回调在下一次事件循环执行，贴近真实 BroadcastChannel。 */
class FakeBroadcastChannel {
  static instances: FakeBroadcastChannel[] = []
  name: string
  onmessage: ((event: { data: unknown }) => void) | null = null
  constructor(name: string) {
    this.name = name
    FakeBroadcastChannel.instances.push(this)
  }
  postMessage(data: unknown): void {
    for (const instance of FakeBroadcastChannel.instances) {
      if (instance !== this && instance.onmessage) {
        const target = instance
        queueDelivery(() => { target.onmessage?.({ data }) })
      }
    }
  }
  close(): void { this.onmessage = null }
}

const deliveries: Array<() => void> = []
function queueDelivery(fn: () => void): void { deliveries.push(fn) }
/** 在指定「标签页」上下文里排空待派发的跨页消息。 */
async function flushDeliveries(): Promise<void> {
  await new Promise((resolve) => { setTimeout(resolve, 0) })
  while (deliveries.length > 0) deliveries.shift()!()
}

type CacheModule = typeof import('./account-private-cache')

const tabA = new MemoryStorage()
const tabB = new MemoryStorage()
let realSession: Storage

async function loadTab(suffix: string): Promise<CacheModule> {
  return await import(`./account-private-cache?tab=${suffix}`) as CacheModule
}

/** 在指定「标签页」的 sessionStorage 上下文里执行（localStorage 两页共享）。 */
async function withTab<T>(store: Storage, fn: () => T | Promise<T>): Promise<T> {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
  Object.defineProperty(globalThis, 'sessionStorage', { value: store, configurable: true, writable: true })
  try {
    return await fn()
  } finally {
    if (previous) Object.defineProperty(globalThis, 'sessionStorage', previous)
    else delete (globalThis as Record<string, unknown>).sessionStorage
  }
}

const tombstoneOf = (accountId: string): string | null =>
  localStorage.getItem('grassland:apc:gen:' + JSON.stringify([accountId]))

beforeEach(() => {
  localStorage.clear()
  tabA.clear()
  tabB.clear()
  FakeBroadcastChannel.instances = []
  deliveries.length = 0
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
  realSession = globalThis.sessionStorage
})

afterEach(() => {
  Object.defineProperty(globalThis, 'sessionStorage', { value: realSession, configurable: true, writable: true })
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.resetModules()
})

describe('account-private-cache v2 · 登记与清理基础（TC104-04-03/07）', () => {
  test('只清登记账号的键：其他账号与未登记键（主题偏好）不动；重复清理幂等', async () => {
    const cache = await loadTab('base')
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'a1')
      localStorage.setItem('subtitle-cues-acct-a:t1', '[]')
      localStorage.setItem('subtitle-cues-acct-b:t1', '[]')
      localStorage.setItem('theme', 'dark')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
      cache.registerAccountKey('acct-a', 'local', 'subtitle-cues-acct-a:t1')
      cache.registerAccountKey('acct-b', 'local', 'subtitle-cues-acct-b:t1')
    })
    const cleared = await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    expect(cleared).toBe(2)
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    expect(localStorage.getItem('subtitle-cues-acct-a:t1')).toBeNull()
    expect(localStorage.getItem('theme')).toBe('dark')
    expect(localStorage.getItem('subtitle-cues-acct-b:t1')).toBe('[]')
    // 清理后元数据不残留；重复清理幂等（0 键可清）。
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(0)
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-b'))).toBe(1)
  })

  test.each([499, 500, 501, 1001])('超 500 键不丢归属：%s 个键全部清理、计数准确', async (count) => {
    const cache = await loadTab('cap')
    await withTab(tabA, () => {
      for (let index = 0; index < count; index += 1) {
        const key = `video-canvas-bind:acct-a:${index}:s${index}:d${index}`
        sessionStorage.setItem(key, `v${index}`)
        cache.registerAccountKey('acct-a', 'session', key)
      }
      localStorage.setItem('subtitle-cues-acct-other:t1', '[]')
      cache.registerAccountKey('acct-other', 'local', 'subtitle-cues-acct-other:t1')
    })
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(count)
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(0)
    for (let index = 0; index < count; index += 1) {
      expect(tabA.getItem(`video-canvas-bind:acct-a:${index}:s${index}:d${index}`)).toBeNull()
    }
    expect(localStorage.getItem('subtitle-cues-acct-other:t1')).toBe('[]')
  })

  test('重复登记去重；匿名/空白账号不登记、不建跨账号条目', async () => {
    const cache = await loadTab('dedup')
    await withTab(tabA, () => {
      localStorage.setItem('k1', 'v')
      cache.registerAccountKey('acct-a', 'local', 'k1')
      cache.registerAccountKey('acct-a', 'local', 'k1')
      cache.registerAccountKey(null, 'local', 'k2')
      cache.registerAccountKey('   ', 'local', 'k3')
      cache.registerAccountKey('acct-a', 'local', '')
    })
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(1)
    expect(await withTab(tabA, () => cache.clearAccountCache(null as unknown as string))).toBe(0)
  })
})

describe('account-private-cache v2 · 激活、代次与跨页通知（TC104-04-01/02/04）', () => {
  test('B 页早期订阅：A 页清理后 B 无需先清即移除本页 session 值；主题/账号 C 值保留', async () => {
    const pageA = await loadTab('a')
    const pageB = await loadTab('b')
    let invalidations = 0
    await withTab(tabB, () => pageB.activateAccountCache('acct-a', () => { invalidations += 1 }))
    await withTab(tabA, () => pageA.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'a1')
      pageA.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    await withTab(tabB, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:2:s2:d2', 'b2')
      pageB.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:2:s2:d2')
    })
    localStorage.setItem('subtitle-cues-acct-c:t3', '[]')
    localStorage.setItem('theme', 'light')

    await withTab(tabA, () => pageA.clearAccountCache('acct-a'))
    await withTab(tabB, flushDeliveries)

    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    expect(tabB.getItem('video-canvas-bind:acct-a:2:s2:d2')).toBeNull()
    expect(localStorage.getItem('subtitle-cues-acct-c:t3')).toBe('[]')
    expect(localStorage.getItem('theme')).toBe('light')
    expect(invalidations).toBe(1)
  })

  test('同一操作重复投递只失效一次；接收端只清不转发（无广播环）', async () => {
    const pageA = await loadTab('a')
    const pageB = await loadTab('b')
    const postMessage = vi.spyOn(FakeBroadcastChannel.prototype, 'postMessage')
    let invalidations = 0
    await withTab(tabB, () => pageB.activateAccountCache('acct-a', () => { invalidations += 1 }))
    await withTab(tabB, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'x')
      pageB.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    await withTab(tabA, () => pageA.clearAccountCache('acct-a'))
    await withTab(tabB, flushDeliveries)
    expect(invalidations).toBe(1)
    expect(tabB.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    // 同一 operationId 重复投递：去重，不二次失效。
    const operationId = tombstoneOf('acct-a')
    expect(operationId).toBeTruthy()
    const aChannel = FakeBroadcastChannel.instances
      .find((instance) => instance.onmessage !== null && instance !== channelOf(pageB))
    aChannel!.postMessage({ version: 2, type: 'clear', accountId: 'acct-a', operationId: operationId! })
    postMessage.mockClear()  // 排除测试自身这一次显式投递
    await withTab(tabB, flushDeliveries)
    expect(invalidations).toBe(1)
    // 接收端清理不广播（broadcast:false 内部路径）。
    expect(postMessage).not.toHaveBeenCalled()
  })

  test('迟到写不复活：清理后旧会话 register 回收刚写的值；重新激活后恢复资格', async () => {
    const cache = await loadTab('stale')
    const peer = await loadTab('peer')
    const release = await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'old')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    // 远端清理（另一页发起，异步送达）：本页会话失效。
    await withTab(tabB, () => peer.clearAccountCache('acct-a'))
    await withTab(tabA, flushDeliveries)
    // 旧会话迟到 register：值被回收、不登记。
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'late')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    // 显式重新激活后恢复写资格（当前代次）。
    release()
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:3:s3:d3', 'fresh')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:3:s3:d3')
    })
    expect(await withTab(tabA, () =>
      cache.readAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:3:s3:d3'))).toBe('fresh')
  })

  test('乱序旧通知：不覆盖当前墓碑、不删除当前代次新值（A→B→A）', async () => {
    const pageA = await loadTab('a')
    const pagePeer = await loadTab('peer')
    // 捕获 pageA 的 storage 回退监听（激活时注册）。
    let storageListener: ((event: { key: string | null; newValue: string | null; oldValue?: string | null }) => void) | null = null
    const addSpy = vi.spyOn(window, 'addEventListener')
      .mockImplementation(((type: string, handler: unknown) => {
        if (type === 'storage' && typeof handler === 'function') {
          storageListener = handler as NonNullable<typeof storageListener>
        }
      }) as typeof window.addEventListener)
    const release1 = await withTab(tabA, () => pageA.activateAccountCache('acct-a'))
    addSpy.mockRestore()
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'gen1')
      pageA.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    await withTab(tabB, () => pagePeer.clearAccountCache('acct-a'))
    const op1 = tombstoneOf('acct-a')
    expect(op1).toBeTruthy()
    release1()
    // A→B→A：重新激活 A（捕获当前墓碑 op1），写入当前代次新值。
    await withTab(tabA, () => pageA.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:2:s2:d2', 'gen2')
      pageA.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:2:s2:d2')
    })
    // 更早的旧清理通知（op0）迟到到达：墓碑不被旧 operationId 覆盖，当前代次新值保留。
    await withTab(tabA, () => {
      storageListener!({ key: 'grassland:apc:gen:' + JSON.stringify(['acct-a']), newValue: 'op0-stale', oldValue: op1 })
    })
    expect(tombstoneOf('acct-a')).toBe(op1)
    expect(tabA.getItem('video-canvas-bind:acct-a:2:s2:d2')).toBe('gen2')
  })

  test('storage 事件回退：无 BroadcastChannel 环境同样触发接收端清理', async () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    const pageB = await loadTab('b')
    let storageListener: ((event: { key: string | null; newValue: string | null; oldValue?: string | null }) => void) | null = null
    const addSpy = vi.spyOn(window, 'addEventListener')
      .mockImplementation(((type: string, handler: unknown) => {
        if (type === 'storage' && typeof handler === 'function') {
          storageListener = handler as NonNullable<typeof storageListener>
        }
      }) as typeof window.addEventListener)
    let invalidations = 0
    await withTab(tabB, () => pageB.activateAccountCache('acct-a', () => { invalidations += 1 }))
    addSpy.mockRestore()
    await withTab(tabB, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'x')
      pageB.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    // 他页主动清理写入新墓碑（共享 localStorage 直接写入模拟），storage 事件送达本页。
    localStorage.setItem('grassland:apc:gen:' + JSON.stringify(['acct-a']), 'fallback-op-1')
    await withTab(tabB, () => {
      storageListener!({ key: 'grassland:apc:gen:' + JSON.stringify(['acct-a']), newValue: 'fallback-op-1', oldValue: null })
    })
    expect(tabB.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    expect(invalidations).toBe(1)
  })
})

/** 页面对应的假 channel 实例（用于定向重复投递）。 */
function channelOf(page: CacheModule): FakeBroadcastChannel | undefined {
  return FakeBroadcastChannel.instances
    .find((instance) => instance.onmessage !== null && instance.name === 'grassland:account-private-cache-clear')
}

describe('account-private-cache v2 · v1 迁移与 500 截断恢复（TC104-04-05）', () => {
  test('v1 local 记录导入 v2；session 记录只由实际持有值的页导入；登记行保留为线索', async () => {
    const cache = await loadTab('migrate')
    localStorage.setItem('grassland:account-private-cache', JSON.stringify({
      'acct-a': [
        { s: 'local', k: 'subtitle-cues-acct-a:t1' },
        { s: 'session', k: 'video-canvas-bind:acct-a:1:s1:d1' },
      ],
    }))
    localStorage.setItem('subtitle-cues-acct-a:t1', '[]')
    tabB.setItem('video-canvas-bind:acct-a:1:s1:d1', 'payload')
    // B 页激活：session 记录由实际持有值的 B 页导入（本页 sessionStorage 有值）。
    await withTab(tabB, () => cache.activateAccountCache('acct-a'))
    expect(await withTab(tabB, () =>
      cache.readAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1'))).toBe('payload')
    // v1 共享登记行保留（另一页的恢复线索不被提前删除）。
    expect(localStorage.getItem('grassland:account-private-cache')).toContain('video-canvas-bind:acct-a:1:s1:d1')
    // 清理后 v1 local 值与 session 值都消失。
    await withTab(tabB, () => cache.clearAccountCache('acct-a'))
    expect(localStorage.getItem('subtitle-cues-acct-a:t1')).toBeNull()
    expect(tabB.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
  })

  test('存在清理墓碑时 v1 值不导入当前代次（删除不猜新缓存）', async () => {
    const cache = await loadTab('tombstone-v1')
    localStorage.setItem('grassland:account-private-cache', JSON.stringify({
      'acct-a': [{ s: 'local', k: 'subtitle-cues-acct-a:t1' }],
    }))
    localStorage.setItem('subtitle-cues-acct-a:t1', '[]')
    await withTab(tabA, () => cache.clearAccountCache('acct-a'))  // 写入墓碑
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    expect(localStorage.getItem('subtitle-cues-acct-a:t1')).toBeNull()
    expect(await withTab(tabA, () =>
      cache.readAccountKey('acct-a', 'local', 'subtitle-cues-acct-a:t1'))).toBeNull()
  })

  test('两种 500 截断格式按完整账号相等精确清理：A 不误清 A1，未知前缀不动', async () => {
    const cache = await loadTab('legacy')
    localStorage.setItem('theme', 'dark')
    tabA.setItem('video-canvas-bind:acct-a:7:sb1:d1', 'bind')
    tabA.setItem('video-canvas-bind:acct-a1:8:sb2:d2', 'other-account')
    tabA.setItem('unknown-prefix-acct-a:whatever', 'keep')
    localStorage.setItem('subtitle-cues-acct-a:t2', '[]')
    localStorage.setItem('subtitle-cues-acct-a1:t3', '[]')
    // 未登记（模拟 500 截断丢失元数据）直接清理。
    await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    expect(tabA.getItem('video-canvas-bind:acct-a:7:sb1:d1')).toBeNull()
    expect(localStorage.getItem('subtitle-cues-acct-a:t2')).toBeNull()
    expect(tabA.getItem('video-canvas-bind:acct-a1:8:sb2:d2')).toBe('other-account')
    expect(localStorage.getItem('subtitle-cues-acct-a1:t3')).toBe('[]')
    expect(tabA.getItem('unknown-prefix-acct-a:whatever')).toBe('keep')
    expect(localStorage.getItem('theme')).toBe('dark')
  })
})

describe('account-private-cache v2 · 受限与损坏 storage（TC104-04-06）', () => {
  test('元数据写失败（QuotaExceeded）：删除刚写的值、业务内存继续，无未捕获异常', async () => {
    const cache = await loadTab('quota')
    const original = localStorage.setItem.bind(localStorage)
    vi.spyOn(localStorage, 'setItem').mockImplementation((key: string, value: string) => {
      if (key.startsWith('grassland:apc:v2:') || key.startsWith('grassland:apc:gen:')) {
        throw new DOMException('quota', 'QuotaExceededError')
      }
      original(key, value)
    })
    await withTab(tabA, () => {
      expect(() => {
        localStorage.setItem('plain-key', 'v')
        cache.registerAccountKey('acct-a', 'local', 'plain-key')
      }).not.toThrow()
    })
    expect(localStorage.getItem('plain-key')).toBeNull()
  })

  test('坏 JSON 元数据/伪键当噪声：不崩溃；归属可证明（键三元组）的键照常清理', async () => {
    const cache = await loadTab('corrupt')
    localStorage.setItem('grassland:apc:v2:not-json', 'broken')
    // 元数据值损坏但键三元组仍可证明归属 acct-a：按无元数据处理，清理照常。
    localStorage.setItem('grassland:apc:v2:' + JSON.stringify(['acct-a', 'local', 'corrupt-key']), '{bad json')
    localStorage.setItem('corrupt-key', 'provably-owned')
    localStorage.setItem('real-key', 'v')
    await withTab(tabA, () => cache.registerAccountKey('acct-a', 'local', 'real-key'))
    let cleared = -1
    await withTab(tabA, () => { cleared = cache.clearAccountCache('acct-a') })
    expect(cleared).toBe(2)
    expect(localStorage.getItem('real-key')).toBeNull()
    expect(localStorage.getItem('corrupt-key')).toBeNull()
    // 无法解析归属的伪键不当账号键处理，保持原样。
    expect(localStorage.getItem('grassland:apc:v2:not-json')).toBe('broken')
  })
})
