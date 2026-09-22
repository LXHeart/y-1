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
    // #106 D02：缓存会话是显式授权——第二账号的登记经独立模块实例（另一「标签页」），
    // 同页他账号会话占用时迟到 register 一律回收。
    const peer = await loadTab('base-peer')
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'a1')
      localStorage.setItem('subtitle-cues-acct-a:t1', '[]')
      localStorage.setItem('subtitle-cues-acct-b:t1', '[]')
      localStorage.setItem('theme', 'dark')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
      cache.registerAccountKey('acct-a', 'local', 'subtitle-cues-acct-a:t1')
    })
    await withTab(tabB, () => peer.registerAccountKey('acct-b', 'local', 'subtitle-cues-acct-b:t1'))
    const cleared = await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    expect(cleared).toBe(2)
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    expect(localStorage.getItem('subtitle-cues-acct-a:t1')).toBeNull()
    expect(localStorage.getItem('theme')).toBe('dark')
    expect(localStorage.getItem('subtitle-cues-acct-b:t1')).toBe('[]')
    // 清理后元数据不残留；重复清理幂等（0 键可清）。
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(0)
    expect(await withTab(tabB, () => peer.clearAccountCache('acct-b'))).toBe(1)
  })

  test.each([499, 500, 501, 1001])('超 500 键不丢归属：%s 个键全部清理、计数准确', async (count) => {
    const cache = await loadTab('cap')
    const peer = await loadTab('cap-peer')
    await withTab(tabA, () => {
      for (let index = 0; index < count; index += 1) {
        const key = `video-canvas-bind:acct-a:${index}:s${index}:d${index}`
        sessionStorage.setItem(key, `v${index}`)
        cache.registerAccountKey('acct-a', 'session', key)
      }
    })
    localStorage.setItem('subtitle-cues-acct-other:t1', '[]')
    await withTab(tabB, () => peer.registerAccountKey('acct-other', 'local', 'subtitle-cues-acct-other:t1'))
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
    // 一次性清理发送端已关闭；独立发送器重放，不依赖泄漏的通道。
    const aChannel = new FakeBroadcastChannel('grassland:account-private-cache-clear')
    aChannel.postMessage({ version: 2, type: 'clear', accountId: 'acct-a', operationId: operationId! })
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

describe('account-private-cache v2 · #106 会话授权与乱序隔离（TC106-02-01～04）', () => {
  test('TC106-02-01/F02：A 清理撤销后迟到 register 不夺 B 会话——A 值回收、B 回调保留', async () => {
    const cache = await loadTab('t106f02')
    const releaseA = await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'old-a')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    releaseA()
    await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    let invalidationsB = 0
    await withTab(tabA, () => cache.activateAccountCache('acct-b', () => { invalidationsB += 1 }))
    // A 的旧回调迟到写入并登记：值回收，不建新默认会话、不夺 B 的会话。
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'late-a')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    // B 会话保持：登记正常、失效回调仍在（远端清 B 失效一次）。
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-b:1:s1:d1', 'b1')
      cache.registerAccountKey('acct-b', 'session', 'video-canvas-bind:acct-b:1:s1:d1')
    })
    expect(tabA.getItem('video-canvas-bind:acct-b:1:s1:d1')).toBe('b1')
    const peer = await loadTab('t106f02peer')
    await withTab(tabB, () => peer.clearAccountCache('acct-b'))
    await withTab(tabA, flushDeliveries)
    expect(invalidationsB).toBe(1)
  })

  test('TC106-02-02/F03：当前代次激活后旧通知不失效当前会话——新写保留、会话仍活', async () => {
    const cache = await loadTab('t106f03')
    let invalidations = 0
    let storageListener: ((event: { key: string | null; newValue: string | null; oldValue?: string | null }) => void) | null = null
    const addSpy = vi.spyOn(window, 'addEventListener')
      .mockImplementation(((type: string, handler: unknown) => {
        if (type === 'storage' && typeof handler === 'function') {
          storageListener = handler as NonNullable<typeof storageListener>
        }
      }) as typeof window.addEventListener)
    await withTab(tabA, () => cache.activateAccountCache('acct-a', () => { invalidations += 1 }))
    addSpy.mockRestore()
    await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    const op1 = tombstoneOf('acct-a')
    expect(op1).toBeTruthy()
    // 清理后重新激活：捕获当前墓碑 op1（当前代次会话）。
    await withTab(tabA, () => cache.activateAccountCache('acct-a', () => { invalidations += 1 }))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:2:s2:d2', 'fresh')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:2:s2:d2')
    })
    // 迟到投递该旧 operationId（跨页延迟送达的同值墓碑事件）：
    await withTab(tabA, () => {
      storageListener!({ key: 'grassland:apc:gen:' + JSON.stringify(['acct-a']), newValue: op1, oldValue: null })
    })
    expect(invalidations).toBe(0)
    expect(tabA.getItem('video-canvas-bind:acct-a:2:s2:d2')).toBe('fresh')
    // 会话仍活：后续登记正常（迟到通知不使当前代次失效）。
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:3:s3:d3', 'fresh2')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:3:s3:d3')
    })
    expect(tabA.getItem('video-canvas-bind:acct-a:3:s3:d3')).toBe('fresh2')
  })

  test('TC106-02-03：旧释放函数只作用于自己的实例——同账号新激活不被撤销、迟到释放不影响 B', async () => {
    const cache = await loadTab('t106rel')
    const oldRelease = await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    const newRelease = await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    oldRelease()
    oldRelease()  // 重复释放幂等
    // 新激活仍有效：登记成功。
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'v1')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBe('v1')
    newRelease()
    // 换 B 后迟到 oldRelease：B 的会话不受影响。
    await withTab(tabA, () => cache.activateAccountCache('acct-b'))
    oldRelease()
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-b:1:s1:d1', 'b1')
      cache.registerAccountKey('acct-b', 'session', 'video-canvas-bind:acct-b:1:s1:d1')
    })
    expect(tabA.getItem('video-canvas-bind:acct-b:1:s1:d1')).toBe('b1')
  })

  test('TC106-02-04：readAccountKey 会话/代次/元数据核验；v1 兼容保留、A/A1 隔离、storage 不可读=未知', async () => {
    const cache = await loadTab('t106read')
    // 初次兼容（无会话、无撤销、无墓碑）：legacy 截断格式导入读取。
    localStorage.setItem('subtitle-cues-acct-a:t1', '[]')
    expect(await withTab(tabA, () =>
      cache.readAccountKey('acct-a', 'local', 'subtitle-cues-acct-a:t1'))).toBe('[]')
    // A1 不串（完整账号相等，不用前缀）。
    expect(await withTab(tabA, () =>
      cache.readAccountKey('acct-a1', 'local', 'subtitle-cues-acct-a:t1'))).toBeNull()
    // 他账号会话占用：B 激活后读取 A2 不得绕开会话授权。
    await withTab(tabA, () => cache.activateAccountCache('acct-b'))
    localStorage.setItem('subtitle-cues-acct-a2:t2', '[]')
    expect(await withTab(tabA, () =>
      cache.readAccountKey('acct-a2', 'local', 'subtitle-cues-acct-a2:t2'))).toBeNull()
    // 元数据 owner 不符：不猜（返回 null 且不删归属不可证明的内容）。
    const forged = 'grassland:apc:v2:' + JSON.stringify(['acct-c', 'local', 'some-key'])
    localStorage.setItem(forged, JSON.stringify({ version: 2, accountId: 'someone-else', area: 'local', key: 'some-key', generation: '' }))
    localStorage.setItem('some-key', 'owned-by-who')
    expect(await withTab(tabA, () => cache.readAccountKey('acct-c', 'local', 'some-key'))).toBeNull()
    expect(localStorage.getItem('some-key')).toBe('owned-by-who')
    // localStorage 不可读视为未知（不是初始空代次）：当前账号 session 区读取 null、不导入。
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {
      get() { throw new Error('denied') },
      configurable: true,
    })
    try {
      tabA.setItem('video-canvas-bind:acct-b:1:s1:d1', 'stale')
      expect(await withTab(tabA, () =>
        cache.readAccountKey('acct-b', 'session', 'video-canvas-bind:acct-b:1:s1:d1'))).toBeNull()
    } finally {
      if (descriptor) Object.defineProperty(globalThis, 'localStorage', descriptor)
    }
  })
})

describe('account-private-cache v2 · #106 失败降级、计数与可重试（TC106-03-01～05）', () => {
  test('TC106-03-01/F04：内容删除失败计数0、保留值与元数据线索；恢复后重试删1、再次0', async () => {
    const cache = await loadTab('t106f04')
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'v')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    const meta = 'grassland:apc:v2:' + JSON.stringify(['acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1'])
    expect(tabA.getItem(meta)).toBeTruthy()
    // 只让内容键 removeItem 抛错（元数据可正常删除）。
    const original = tabA.removeItem.bind(tabA)
    const removeSpy = vi.spyOn(tabA, 'removeItem').mockImplementation((key: string) => {
      if (key === 'video-canvas-bind:acct-a:1:s1:d1') throw new DOMException('locked', 'InvalidStateError')
      original(key)
    })
    const cleared = await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    removeSpy.mockRestore()
    expect(cleared).toBe(0)
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBe('v')
    expect(tabA.getItem(meta)).toBeTruthy()
    // storage 恢复后：下次 clear 重试已知残留——删 1；再次 0。
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(1)
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()
    expect(tabA.getItem(meta)).toBeNull()
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(0)
  })

  test('TC106-03-02/F05：墓碑写失败时主动清理强制遍历本页已知键、撤销旧资格、仍广播；BC 接收端降级尽力清', async () => {
    const cache = await loadTab('t106f05')
    await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    const op1 = tombstoneOf('acct-a')
    expect(op1).toBeTruthy()
    // 重新激活（捕获当前墓碑 op1）并写当前代次新值。
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:2:s2:d2', 'new')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:2:s2:d2')
    })
    // 下一次清理的墓碑写失败（Quota）。
    const originalSet = localStorage.setItem.bind(localStorage)
    const setSpy = vi.spyOn(localStorage, 'setItem').mockImplementation((key: string, value: string) => {
      if (key.startsWith('grassland:apc:gen:')) throw new DOMException('quota', 'QuotaExceededError')
      originalSet(key, value)
    })
    let posted = 0
    const postSpy = vi.spyOn(FakeBroadcastChannel.prototype, 'postMessage')
      .mockImplementation(() => { posted += 1 })
    const cleared = await withTab(tabA, () => cache.clearAccountCache('acct-a'))
    setSpy.mockRestore()
    postSpy.mockRestore()
    // 本页已知值尽力清（不用旧墓碑保护本次待清值）、如实计数。
    expect(cleared).toBe(1)
    expect(tabA.getItem('video-canvas-bind:acct-a:2:s2:d2')).toBeNull()
    // 墓碑未前进（写失败如实），操作通知仍广播。
    expect(tombstoneOf('acct-a')).toBe(op1)
    expect(posted).toBe(1)
    // 旧资格撤销：迟到 register 回收。
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:3:s3:d3', 'late')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:3:s3:d3')
    })
    expect(tabA.getItem('video-canvas-bind:acct-a:3:s3:d3')).toBeNull()

    // 接收端降级：他页收到「operationId 未成为当前墓碑」的 BC 消息 → 尽力清 + 撤销会话。
    const pageB = await loadTab('t106f05b')
    let invalidations = 0
    await withTab(tabB, () => pageB.activateAccountCache('acct-a', () => { invalidations += 1 }))
    await withTab(tabB, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:9:s9:d9', 'peer-value')
      pageB.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:9:s9:d9')
    })
    const messenger = new FakeBroadcastChannel('grassland:account-private-cache-clear')
    messenger.postMessage({ version: 2, type: 'clear', accountId: 'acct-a', operationId: 'op-degraded-x', tombstoneWritten: false })
    await withTab(tabB, flushDeliveries)
    expect(tabB.getItem('video-canvas-bind:acct-a:9:s9:d9')).toBeNull()
    expect(invalidations).toBe(1)
  })

  test('TC106-03-03：登记元数据写失败+内容删失败→内存重试依据；内容删成功元数据删失败→计数一次不重复；新代次合法值不误删', async () => {
    const cache = await loadTab('t106f33')
    // a) register 元数据写失败且内容 removeItem 也失败：未登记值留内存重试依据（不静默丢线索）。
    const originalRemove = tabA.removeItem.bind(tabA)
    const originalSet = tabA.setItem.bind(tabA)
    const removeSpy = vi.spyOn(tabA, 'removeItem').mockImplementation(() => { throw new DOMException('locked') })
    const setSpy = vi.spyOn(tabA, 'setItem').mockImplementation((key: string, value: string) => {
      if (key.startsWith('grassland:apc:v2:')) throw new DOMException('quota', 'QuotaExceededError')
      originalSet(key, value)
    })
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:1:s1:d1', 'unregistered')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:1:s1:d1')
    })
    removeSpy.mockRestore()
    setSpy.mockRestore()
    // b) 恢复后 clear：重试依据生效（未登记内容也被清，计 1）。
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(1)
    expect(tabA.getItem('video-canvas-bind:acct-a:1:s1:d1')).toBeNull()

    // c) 内容删成功 + 元数据删失败：确认删才计数一次；下次 clear 清扫元数据不重复计数。
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem('video-canvas-bind:acct-a:4:s4:d4', 'meta-fail')
      cache.registerAccountKey('acct-a', 'session', 'video-canvas-bind:acct-a:4:s4:d4')
    })
    const metaC = 'grassland:apc:v2:' + JSON.stringify(['acct-a', 'session', 'video-canvas-bind:acct-a:4:s4:d4'])
    const removeSpyC = vi.spyOn(tabA, 'removeItem').mockImplementation((key: string) => {
      if (key === metaC) throw new DOMException('locked')
      originalRemove(key)
    })
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(1)
    removeSpyC.mockRestore()
    expect(tabA.getItem('video-canvas-bind:acct-a:4:s4:d4')).toBeNull()
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(0)
    expect(tabA.getItem(metaC)).toBeNull()

    // d) 新代次合法值不误删：删除失败残留 + 重激活写同键合法新值 → 读取触发的重试按代次核对不删。
    const keyD = 'video-canvas-bind:acct-a:7:s7:d7'
    const removeSpyD = vi.spyOn(tabA, 'removeItem').mockImplementation((key: string) => {
      if (key === keyD) throw new DOMException('locked')
      originalRemove(key)
    })
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem(keyD, 'old')
      cache.registerAccountKey('acct-a', 'session', keyD)
      cache.clearAccountCache('acct-a')  // 内容删除失败 → 值残留 + 重试依据；会话失效
    })
    expect(tabA.getItem(keyD)).toBe('old')
    // 重激活（激活重试仍失败：spy 在位）后写当前代次合法新值。
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      sessionStorage.setItem(keyD, 'legal-new')
      cache.registerAccountKey('acct-a', 'session', keyD)
    })
    removeSpyD.mockRestore()
    // 读取触发的重试：核对代次——合法新值保留并正常返回。
    expect(await withTab(tabA, () => cache.readAccountKey('acct-a', 'session', keyD))).toBe('legal-new')
    expect(tabA.getItem(keyD)).toBe('legal-new')
  })

  test('TC106-03-04：storage 派发/读/枚举抛错与不可达——不抛到业务、不假成功、可恢复重试', async () => {
    const cache = await loadTab('t106f34')
    // localStorage getter 抛错（完全不可达）：读 null、登出清理不抛出。
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {
      get() { throw new Error('denied') },
      configurable: true,
    })
    try {
      expect(await withTab(tabA, () =>
        cache.readAccountKey('acct-a', 'local', 'any-key'))).toBeNull()
      await withTab(tabA, () => {
        expect(() => cache.clearAccountCache('acct-a')).not.toThrow()
      })
      await withTab(tabA, () => {
        expect(() => cache.registerAccountKey('acct-a', 'local', 'k')).not.toThrow()
      })
    } finally {
      if (descriptor) Object.defineProperty(globalThis, 'localStorage', descriptor)
    }
    // 方法级抛错（getItem/key/length）：扫描跳过、不计数、不抛出；恢复后重试清掉。
    localStorage.setItem('retry-key', 'v')
    localStorage.setItem('grassland:apc:v2:' + JSON.stringify(['acct-a', 'local', 'retry-key']),
      JSON.stringify({ version: 2, accountId: 'acct-a', area: 'local', key: 'retry-key', generation: '' }))
    const originalGetItem = localStorage.getItem.bind(localStorage)
    const throwSpy = vi.spyOn(localStorage, 'getItem').mockImplementation((key: string) => {
      if (key === 'retry-key') throw new DOMException('unavailable')
      return originalGetItem(key)
    })
    vi.spyOn(localStorage, 'key').mockImplementation(() => { throw new DOMException('unavailable') })
    await withTab(tabA, () => {
      expect(() => cache.clearAccountCache('acct-a')).not.toThrow()
    })
    throwSpy.mockRestore()
    vi.restoreAllMocks()
    expect(originalGetItem('retry-key')).toBe('v')  // 枚举失败→未假成功（值仍在，不宣称已清）
    // 恢复后：正常扫描重试清掉（元数据仍在）。
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(1)
    expect(originalGetItem('retry-key')).toBeNull()
  })

  test('TC106-03-05：1001 键（local/session 混合）计数准确；100 轮激活释放无孤儿监听；主题/他账号不动', async () => {
    const cache = await loadTab('t106f35')
    // 100 轮激活/释放（无 peer 干扰）：引用计数归零——storage 监听移除、channel 关闭，无孤儿监听。
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    for (let round = 0; round < 100; round += 1) {
      const release = await withTab(tabA, () => cache.activateAccountCache('acct-a'))
      release()
    }
    expect(removeSpy).toHaveBeenCalledWith('storage', expect.any(Function))
    removeSpy.mockRestore()
    expect(FakeBroadcastChannel.instances
      .filter((instance) => instance.name === 'grassland:account-private-cache-clear' && instance.onmessage !== null))
      .toHaveLength(0)
    const peer = await loadTab('t106f35peer')
    // 显式激活后登记（真实消费者形态；释放后 register 回收属 D02 语义）。
    await withTab(tabA, () => cache.activateAccountCache('acct-a'))
    await withTab(tabA, () => {
      for (let index = 0; index < 500; index += 1) {
        const key = `video-canvas-bind:acct-a:${index}:s:d`
        sessionStorage.setItem(key, `v${index}`)
        cache.registerAccountKey('acct-a', 'session', key)
      }
      for (let index = 0; index < 501; index += 1) {
        const key = `subtitle-cues-acct-a:t${index}`
        localStorage.setItem(key, '[]')
        cache.registerAccountKey('acct-a', 'local', key)
      }
    })
    localStorage.setItem('theme', 'dark')
    localStorage.setItem('subtitle-cues-acct-other:t1', '[]')
    await withTab(tabB, () => peer.registerAccountKey('acct-other', 'local', 'subtitle-cues-acct-other:t1'))
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(1001)
    expect(await withTab(tabA, () => cache.clearAccountCache('acct-a'))).toBe(0)
    expect(localStorage.getItem('theme')).toBe('dark')
    expect(localStorage.getItem('subtitle-cues-acct-other:t1')).toBe('[]')
  })
})

/** 页面对应的假 channel 实例（用于定向重复投递）。 */
function channelOf(): FakeBroadcastChannel | undefined {
  return FakeBroadcastChannel.instances
    .find((instance) => instance.onmessage !== null && instance.name === 'grassland:account-private-cache-clear')
}

describe('#106 复核：独立异常与乱序反例', () => {
  test('登出 release 后 clear 广播不能重建无人持有的通道', async () => {
    const cache = await loadTab('review-logout-channel')
    await withTab(tabA, () => {
      const release = cache.activateAccountCache('acct-a')
      release()
      cache.clearAccountCache('acct-a')
      expect(FakeBroadcastChannel.instances.filter(c => c.onmessage !== null)).toHaveLength(0)
    })
  })
  test('BC 先于持久墓碑可见时，后到 storage 事件仍须失效旧会话', async () => {
    const cache = await loadTab('review-bc-storage-race')
    const invalidated = vi.fn()
    await withTab(tabA, () => {
      cache.activateAccountCache('acct-a', invalidated)
      channelOf()!.onmessage!({ data: { version: 2, type: 'clear', accountId: 'acct-a', operationId: 'next', tombstoneWritten: true } })
      localStorage.setItem('grassland:apc:gen:["acct-a"]', 'next')
      window.dispatchEvent(new StorageEvent('storage', { key: 'grassland:apc:gen:["acct-a"]', newValue: 'next' }))
      expect(invalidated).toHaveBeenCalledTimes(1)
    })
  })
  test('旧 BC operationId 不等于当前墓碑时仍不得废弃新激活', async () => {
    const cache = await loadTab('review-old-bc')
    localStorage.setItem('grassland:apc:gen:["acct-a"]', 'new-generation')
    const invalidated = vi.fn()
    await withTab(tabA, async () => {
      cache.activateAccountCache('acct-a', invalidated)
      sessionStorage.setItem('custom', 'fresh')
      cache.registerAccountKey('acct-a', 'session', 'custom')
      channelOf()!.onmessage!({ data: { version: 2, type: 'clear', accountId: 'acct-a', operationId: 'older-generation' } })
      expect(invalidated).not.toHaveBeenCalled()
      expect(cache.readAccountKey('acct-a', 'session', 'custom')).toBe('fresh')
    })
  })

  test('只有墓碑 getItem 抛错时，session 私有值不可读且不可登记', async () => {
    const cache = await loadTab('review-gen-read')
    await withTab(tabA, () => {
      cache.activateAccountCache('acct-a')
      sessionStorage.setItem('custom', 'private')
      cache.registerAccountKey('acct-a', 'session', 'custom')
      const get = localStorage.getItem.bind(localStorage)
      vi.spyOn(localStorage, 'getItem').mockImplementation(key => {
        if (key.startsWith('grassland:apc:gen:')) throw new Error('unreadable')
        return get(key)
      })
      expect(cache.readAccountKey('acct-a', 'session', 'custom')).toBeNull()
      sessionStorage.setItem('late', 'private')
      cache.registerAccountKey('acct-a', 'session', 'late')
      expect(sessionStorage.getItem('late')).toBeNull()
    })
  })

  test('只有元数据 getItem 抛错时 clear 不抛出并尽力清理', async () => {
    const cache = await loadTab('review-meta-read')
    await withTab(tabA, () => {
      cache.activateAccountCache('acct-a')
      sessionStorage.setItem('custom', 'private')
      cache.registerAccountKey('acct-a', 'session', 'custom')
      const get = tabA.getItem.bind(tabA)
      vi.spyOn(tabA, 'getItem').mockImplementation(key => {
        if (key.startsWith('grassland:apc:v2:')) throw new Error('unreadable metadata')
        return get(key)
      })
      expect(() => cache.clearAccountCache('acct-a')).not.toThrow()
      expect(sessionStorage.getItem('custom')).toBeNull()
    })
  })

  test('内容读与删除均失败不能抹掉元数据，恢复后必须可重试', async () => {
    const cache = await loadTab('review-read-delete')
    await withTab(tabA, () => {
      cache.activateAccountCache('acct-a')
      sessionStorage.setItem('custom', 'private')
      cache.registerAccountKey('acct-a', 'session', 'custom')
      const get = tabA.getItem.bind(tabA)
      const remove = tabA.removeItem.bind(tabA)
      vi.spyOn(tabA, 'getItem').mockImplementation(key => {
        if (key === 'custom') throw new Error('unreadable')
        return get(key)
      })
      vi.spyOn(tabA, 'removeItem').mockImplementation(key => {
        if (key === 'custom') throw new Error('locked')
        remove(key)
      })
      expect(cache.clearAccountCache('acct-a')).toBe(0)
      expect(get('grassland:apc:v2:["acct-a","session","custom"]')).not.toBeNull()
      vi.restoreAllMocks()
      expect(cache.clearAccountCache('acct-a')).toBe(1)
    })
  })

  test('被拒绝的任意迟到键删失败后仍须保留重试依据', async () => {
    const cache = await loadTab('review-rejected-delete')
    await withTab(tabA, () => {
      cache.activateAccountCache('acct-a')()
      sessionStorage.setItem('custom-late', 'private')
      const remove = vi.spyOn(tabA, 'removeItem').mockImplementation(() => { throw new Error('locked') })
      cache.registerAccountKey('acct-a', 'session', 'custom-late')
      remove.mockRestore()
      expect(cache.clearAccountCache('acct-a')).toBe(1)
      expect(sessionStorage.getItem('custom-late')).toBeNull()
    })
  })

  test('墓碑写失败和内容删失败同时发生，重激活不得把旧值当合法新值', async () => {
    const cache = await loadTab('review-degraded-debt')
    localStorage.setItem('grassland:apc:gen:["acct-a"]', 'unchanged')
    await withTab(tabA, () => {
      cache.activateAccountCache('acct-a')
      sessionStorage.setItem('custom', 'old')
      cache.registerAccountKey('acct-a', 'session', 'custom')
      vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('quota') })
      vi.spyOn(tabA, 'removeItem').mockImplementation(() => { throw new Error('locked') })
      expect(cache.clearAccountCache('acct-a')).toBe(0)
      vi.restoreAllMocks()
      cache.activateAccountCache('acct-a')
      expect(sessionStorage.getItem('custom')).toBeNull()
      expect(cache.readAccountKey('acct-a', 'session', 'custom')).toBeNull()
    })
  })

  test.each([{}, 'bad', [null]])('损坏 v1 条目 %j 不得打断 clear/read/activate', async entries => {
    const cache = await loadTab('review-v1-' + JSON.stringify(entries))
    localStorage.setItem('grassland:account-private-cache', JSON.stringify({ 'acct-a': entries }))
    await withTab(tabA, () => {
      expect(() => cache.activateAccountCache('acct-a')).not.toThrow()
      expect(() => cache.readAccountKey('acct-a', 'session', 'custom')).not.toThrow()
      expect(() => cache.clearAccountCache('acct-a')).not.toThrow()
    })
  })
})

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
