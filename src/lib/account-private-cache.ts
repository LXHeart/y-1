/**
 * 账号私有缓存登记簿 v2（任务书 #104 C104-04 / §3 D03 / §7.4「浏览器缓存」行）。
 *
 * v1 缺陷（R03）：共享整对象 RMW 跨标签页竞态、register 不订阅（先清一次才收到通知）、
 * slice(-500) 丢旧键元数据。v2 改为：
 *
 * - 每键一条元数据（`[accountId, area, key]` JSON 编码的 storage key + 固定版本前缀），
 *   local 区写 localStorage、session 区写本标签页 sessionStorage；解析必须 JSON.parse 整个
 *   三元组，禁止 startsWith 模糊匹配账号。
 * - 每账号 localStorage 一个清理代次墓碑（值为独立操作 UUID）。清理先写新墓碑再删值；
 *   旧代次的迟到写/恢复一律拒绝并删除，不复活旧缓存。
 * - `activateAccountCache` 在账号会话建立时订阅广播（幂等、可释放）；远端清理使本页
 *   旧 AccountTicket 失效（不冒充登出），本页清理走 `{broadcast:false}` 接收模式不转发。
 * - BroadcastChannel 为主通道，`storage` 事件为无 BC 环境的同源回退；操作 ID 去重。
 * - 不再使用 500 条丢弃上限；v1 共享登记的 session 键元数据保留为兼容清理线索。
 *   旧 500 截断已丢的键只对两种已核实格式按「完整账号相等」精确恢复清理：
 *   `video-canvas-bind:accountId:epoch:storyboardId:draftId`（session）、
 *   `subtitle-cues-accountId:transcriptionId`（local）；未知前缀绝不全删，主题保留。
 *
 * 无法访问 storage 的浏览器无法保证物理删除，本模块不伪报成功；运行环境没有
 * localStorage（如 node 测试环境）时登记与清理都为安全空操作。
 */
export type PrivateStorageArea = 'local' | 'session'

/** v2 每键元数据（不含内容）。generation = 登记时的清理代次（墓碑值；'' = 尚无墓碑）。 */
interface KeyRecord {
  version: 2
  accountId: string
  area: PrivateStorageArea
  key: string
  generation: string
}

/** 远端清理广播载荷。 */
interface ClearMessage {
  version: 2
  type: 'clear'
  accountId: string
  operationId: string
}

const V1_REGISTRY_KEY = 'grassland:account-private-cache'
const V2_PREFIX = 'grassland:apc:v2:'
const GEN_PREFIX = 'grassland:apc:gen:'
const CHANNEL_NAME = 'grassland:account-private-cache-clear'

interface V1RegistryEntry { s: PrivateStorageArea; k: string }
type V1Registry = Record<string, V1RegistryEntry[]>

/** 本标签页的缓存会话：由账号 store 显式激活；远端清理或释放后失效，迟到 register 不得复活。 */
interface CacheSession {
  accountId: string
  generation: string
  active: boolean
  onInvalidate?: () => void
}

let session: CacheSession | null = null
let channel: BroadcastChannel | null | undefined
let storageListenerInstalled = false
let listenerRefCount = 0
const seenOperations = new Set<string>()

function metaKey(accountId: string, area: PrivateStorageArea, key: string): string {
  return V2_PREFIX + JSON.stringify([accountId, area, key])
}

function tombstoneKey(accountId: string): string {
  return GEN_PREFIX + JSON.stringify([accountId])
}

function areaStorage(area: PrivateStorageArea): Storage | null {
  try {
    const storage = area === 'session' ? sessionStorage : localStorage
    return storage ? storage : null
  } catch {
    return null
  }
}

function localAvailable(): boolean {
  try {
    return typeof localStorage !== 'undefined' && localStorage !== null
  } catch {
    return false
  }
}

/** 当前清理代次（墓碑值）；无墓碑或不可读返回 ''（初始代次）。 */
function currentGeneration(accountId: string): string {
  if (!localAvailable()) return ''
  try {
    return localStorage.getItem(tombstoneKey(accountId)) ?? ''
  } catch {
    return ''
  }
}

function writeTombstone(accountId: string, operationId: string): boolean {
  if (!localAvailable()) return false
  try {
    localStorage.setItem(tombstoneKey(accountId), operationId)
    return true
  } catch {
    return false
  }
}

function parseRecord(raw: string | null): KeyRecord | null {
  if (raw === null) return null
  try {
    const parsed = JSON.parse(raw) as Partial<KeyRecord>
    if (parsed?.version !== 2 || typeof parsed.accountId !== 'string'
      || (parsed.area !== 'local' && parsed.area !== 'session') || typeof parsed.key !== 'string'
      || typeof parsed.generation !== 'string') return null
    return parsed as KeyRecord
  } catch {
    return null
  }
}

function readV1Registry(): V1Registry {
  if (!localAvailable()) return {}
  try {
    const parsed = JSON.parse(localStorage.getItem(V1_REGISTRY_KEY) ?? '{}')
    return parsed && typeof parsed === 'object' ? parsed as V1Registry : {}
  } catch {
    return {}
  }
}

function ensureChannel(): BroadcastChannel | null {
  // 已有可用通道直接复用；null（曾不可用）在下次安装时重试——BroadcastChannel
  // 可能因测试桩/环境变化而可用。幂等，不会重复安装 onmessage。
  if (channel) return channel
  channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL_NAME)
  if (channel) {
    channel.onmessage = (event: MessageEvent) => {
      const data = event.data as ClearMessage | null
      if (data?.type === 'clear' && typeof data.accountId === 'string'
        && typeof data.operationId === 'string' && data.version === 2) {
        receiveRemoteClear(data.accountId, data.operationId)
      }
    }
  }
  return channel
}

/** storage 事件回退：他页写入新墓碑 → 视作该账号的清理通知（值即 operationId）。 */
function onStorageEvent(event: StorageEvent): void {
  if (!event.key || !event.key.startsWith(GEN_PREFIX) || event.newValue === null) return
  let accountId: string | null
  try {
    const parsed = JSON.parse(event.key.slice(GEN_PREFIX.length)) as unknown[]
    accountId = typeof parsed[0] === 'string' ? parsed[0] : null
  } catch {
    accountId = null
  }
  if (accountId !== null) receiveRemoteClear(accountId, event.newValue)
}

function installListeners(): void {
  listenerRefCount += 1
  // 幂等安装（不因引用计数>1 跳过）：确保通道与 storage 回退监听真实在位。
  try {
    ensureChannel()
  } catch { /* 广播通道创建失败不影响本页清理 */ }
  if (!storageListenerInstalled && typeof window !== 'undefined' && window.addEventListener) {
    window.addEventListener('storage', onStorageEvent)
    storageListenerInstalled = true
  }
}

function releaseListeners(): void {
  listenerRefCount = Math.max(0, listenerRefCount - 1)
  if (listenerRefCount > 0) return
  if (channel) {
    try { channel.close() } catch { /* 已关闭 */ }
  }
  channel = undefined
  if (storageListenerInstalled && typeof window !== 'undefined' && window.removeEventListener) {
    window.removeEventListener('storage', onStorageEvent)
    storageListenerInstalled = false
  }
}

/**
 * 远端清理接收端：操作 ID 去重；使同账号旧 AccountTicket 失效（一次），
 * 然后以接收模式清本页（不写新墓碑、不转发）。
 */
function receiveRemoteClear(accountId: string, operationId: string): void {
  if (seenOperations.has(operationId)) return
  seenOperations.add(operationId)
  if (session && session.active && session.accountId === accountId) {
    session.active = false
    try { session.onInvalidate?.() } catch { /* 回调失败不阻塞清理 */ }
  }
  clearAccountCache(accountId, { broadcast: false })
}

/** 两种 500 截断已丢格式的精确解析（完整账号相等；未知前缀不碰）。 */
function legacyKeyOwner(area: PrivateStorageArea, key: string): string | null {
  if (area === 'session' && key.startsWith('video-canvas-bind:')) {
    const parts = key.slice('video-canvas-bind:'.length).split(':')
    if (parts.length === 4 && parts.every((part) => part.length > 0 || parts.indexOf(part) === 3)) {
      return parts[0]
    }
    return null
  }
  if (area === 'local' && key.startsWith('subtitle-cues-')) {
    const rest = key.slice('subtitle-cues-'.length)
    const separator = rest.indexOf(':')
    if (separator > 0 && rest.indexOf(':', separator + 1) === -1) return rest.slice(0, separator)
  }
  return null
}

/** 扫描某区 storage，解析出归属该账号的全部 v2 内容键（元数据 JSON 解析，非前缀匹配账号）。 */
function contentKeysOf(accountId: string, area: PrivateStorageArea): string[] {
  const storage = areaStorage(area)
  if (!storage) return []
  const keys: string[] = []
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const storageKey = storage.key(index)
      if (!storageKey) continue
      if (storageKey.startsWith(V2_PREFIX)) {
        try {
          const triple = JSON.parse(storageKey.slice(V2_PREFIX.length)) as unknown[]
          if (triple.length === 3 && triple[0] === accountId && triple[1] === area
            && typeof triple[2] === 'string') keys.push(triple[2])
        } catch { /* 坏元数据键当噪声跳过 */ }
      } else if (legacyKeyOwner(area, storageKey) === accountId) {
        keys.push(storageKey)
      }
    }
  } catch { /* storage 不可枚举时跳过扫描 */ }
  return keys
}

/**
 * 清理目标判定：无墓碑（或墓碑写失败的降级清理）时尽力清全部已知键；
 * 有墓碑时只清代次过期的键——迟到/乱序的旧清理通知不得删除已在当前代次
 * 重新激活写入的新值，也不以晚到的旧 operationId 覆盖当前墓碑。
 */
function staleForClear(accountId: string, area: PrivateStorageArea, key: string, generation: string): boolean {
  if (!generation) return true
  const storage = areaStorage(area)
  if (!storage) return true
  const record = parseRecord(storage.getItem(metaKey(accountId, area, key)))
  return record === null || record.generation !== generation
}

function removeQuietly(storage: Storage | null, key: string): boolean {
  if (!storage) return false
  try {
    return storage.removeItem(key) === undefined && storage.getItem(key) === null
  } catch {
    return false
  }
}

function existed(storage: Storage | null, key: string): boolean {
  if (!storage) return false
  try {
    return storage.getItem(key) !== null
  } catch {
    return false
  }
}

/**
 * 激活账号缓存会话：捕获当前清理代次、安装通知监听并迁移本页 v1 记录。
 * 释放函数在换号/销毁时调用；已失效会话（远端清理后）不能由迟到 register 复活，
 * 必须再次显式激活。onInvalidate 只失效当前账号旧票据，不得回调 clear 形成通知环。
 */
export function activateAccountCache(accountId: string, onInvalidate?: () => void): () => void {
  if (typeof accountId !== 'string' || accountId.trim() === '') return () => { /* 匿名无可归属清理 */ }
  if (session && session.accountId === accountId) {
    session.generation = currentGeneration(accountId)
    session.active = true
    session.onInvalidate = onInvalidate
  } else {
    if (session) { /* 换账号：旧会话自然作废（票据失效由账号 store 负责） */ }
    session = { accountId, generation: currentGeneration(accountId), active: true, onInvalidate }
  }
  installListeners()
  importV1Records(accountId)
  let released = false
  return () => {
    if (released) return
    released = true
    if (session && session.accountId === accountId && !session.active) session = null
    else if (session && session.accountId === accountId) session.active = false
    releaseListeners()
  }
}

/**
 * v1 共享登记迁移（§7.4）：无墓碑时 local 记录可导入 v2，session 记录只由
 * 实际持有该值的标签页导入；存在墓碑时旧值删除而不猜它是新缓存。
 * v1 登记行本身保留为兼容清理线索（另一页可能仍需它恢复/清理）。
 */
function importV1Records(accountId: string): void {
  const registry = readV1Registry()
  const entries = registry[accountId]
  if (!Array.isArray(entries) || entries.length === 0) return
  const generation = currentGeneration(accountId)
  for (const entry of entries) {
    if (!entry || (entry.s !== 'local' && entry.s !== 'session') || typeof entry.k !== 'string') continue
    const storage = areaStorage(entry.s)
    if (!storage || !existed(storage, entry.k)) continue
    if (generation) {
      // 存在清理墓碑：v1 值是旧代次残留，删除不导入。
      removeQuietly(storage, entry.k)
      continue
    }
    if (storage.getItem(metaKey(accountId, entry.s, entry.k)) !== null) continue
    try {
      const record: KeyRecord = { version: 2, accountId, area: entry.s, key: entry.k, generation: '' }
      storage.setItem(metaKey(accountId, entry.s, entry.k), JSON.stringify(record))
    } catch { /* 元数据写失败：保留 v1 值与线索，不中断迁移 */ }
  }
}

/**
 * 登记一个账号私有键（内容已由调用方写入）。幂等并确保通知监听在位——
 * 不要求「先清一次才订阅」。登记前后都核对清理代次：本页会话已失效或代次
 * 变化时删除刚写的值并不登记（迟到写不复活旧缓存），业务内存态继续。
 */
export function registerAccountKey(
  accountId: string | null,
  area: PrivateStorageArea,
  key: string,
): void {
  if (!accountId || accountId.trim() === '' || !key) return
  const storage = areaStorage(area)
  if (!storage) return
  if (!session || session.accountId !== accountId) {
    // 兼容原调用者：无会话时建立默认会话（订阅通知）；已失效会话不在此复活。
    session = { accountId, generation: currentGeneration(accountId), active: true }
  } else if (!session.active) {
    removeQuietly(storage, key)
    return
  }
  installListeners()
  const generation = currentGeneration(accountId)
  if (session.generation !== generation) {
    // 远端已清理（墓碑前进）：本页会话失效，旧写资格作废。
    session.active = false
    removeQuietly(storage, key)
    return
  }
  const record: KeyRecord = { version: 2, accountId, area, key, generation }
  try {
    storage.setItem(metaKey(accountId, area, key), JSON.stringify(record))
  } catch {
    // 登记失败：删除刚写的实际值，禁用该次持久缓存（内存态由调用方继续）。
    removeQuietly(storage, key)
    return
  }
  if (currentGeneration(accountId) !== generation) {
    // 写入间隙远端清理：回收本条。
    removeQuietly(storage, key)
    removeQuietly(storage, metaKey(accountId, area, key))
    session.active = false
  }
}

/**
 * 恢复读取入口（两处实际消费者）：只接受与当前已激活代次相符的元数据。
 * v1 值只在尚无清理墓碑时导入；存在墓碑时旧值删除而不猜它是新缓存。
 * 迟到/乱序清理以当前墓碑为准，删除 stale 代次、不覆盖当前墓碑。
 */
export function readAccountKey(
  accountId: string | null,
  area: PrivateStorageArea,
  key: string,
): string | null {
  if (!accountId || accountId.trim() === '' || !key) return null
  const storage = areaStorage(area)
  if (!storage) return null
  const generation = currentGeneration(accountId)
  const record = parseRecord(storage.getItem(metaKey(accountId, area, key)))
  if (record) {
    if (generation && record.generation !== generation) {
      removeQuietly(storage, key)
      removeQuietly(storage, metaKey(accountId, area, key))
      return null
    }
    return storage.getItem(key)
  }
  if (generation) {
    // 无 v2 元数据却有墓碑：不猜它是新缓存（v1/截断残留），按旧代次删除。
    if (existed(storage, key) && record === null) {
      const v1Clue = readV1Registry()[accountId]?.some((entry) => entry.s === area && entry.k === key)
      if (v1Clue || legacyKeyOwner(area, key) === accountId) removeQuietly(storage, key)
    }
    return null
  }
  // 尚无墓碑：v1 线索导入（session 只导入本页实际持有的值）。
  const v1Clue = readV1Registry()[accountId]?.find((entry) => entry.s === area && entry.k === key)
  const value = storage.getItem(key)
  if (!v1Clue && legacyKeyOwner(area, key) !== accountId) return value === null ? null : value
  if (value === null) return null
  if (v1Clue && area === 'session' && !existed(storage, key)) return null
  try {
    const imported: KeyRecord = { version: 2, accountId, area, key, generation: '' }
    storage.setItem(metaKey(accountId, area, key), JSON.stringify(imported))
  } catch { /* 导入失败时仍返回值（线索保留） */ }
  return value
}

/**
 * 清空该账号登记的全部私有键。主动清理（默认）先写新清理代次墓碑、再清本页
 * 拥有的键、最后广播 `{version:2,type:'clear',accountId,operationId}`；
 * 接收模式（broadcast:false）不写新墓碑、不发送、不重复触发失效回调。
 *
 * 返回本页实际移除且此前存在的私有内容键数量（不含元数据、他页、主题）；
 * 重复清理返回 0。墓碑写失败时仍尽力删除本页已知值并广播该次操作，
 * 但不能宣称未收到通知的其他页已完成清理。
 */
export function clearAccountCache(accountId: string, options: { broadcast?: boolean } = {}): number {
  if (typeof accountId !== 'string' || accountId.trim() === '') return 0
  const local = areaStorage('local')
  const tabSession = areaStorage('session')
  const v1Entries = readV1Registry()[accountId] ?? []
  let operationId = ''
  if (options.broadcast !== false) {
    operationId = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `op-${Date.now()}-${Math.random().toString(36).slice(2)}`
    writeTombstone(accountId, operationId)
  }
  const removed = new Set<string>()
  let cleared = 0
  const generation = currentGeneration(accountId)
  const attempt = (area: PrivateStorageArea, key: string): void => {
    if (!key || removed.has(`${area}:${key}`)) return
    if (!staleForClear(accountId, area, key, generation)) return
    const storage = area === 'session' ? tabSession : local
    if (existed(storage, key)) cleared += 1
    removeQuietly(storage, key)
    removeQuietly(storage, metaKey(accountId, area, key))
    removed.add(`${area}:${key}`)
  }
  // v2 元数据 + 两种截断格式精确扫描（session 只清本页 sessionStorage）。
  for (const key of contentKeysOf(accountId, 'session')) attempt('session', key)
  for (const key of contentKeysOf(accountId, 'local')) attempt('local', key)
  // v1 兼容线索（session 条目对他页是线索，对本页 removeItem 幂等无害；登记行保留）。
  for (const entry of v1Entries) {
    if (entry && (entry.s === 'local' || entry.s === 'session')) attempt(entry.s, entry.k)
  }
  // 收尾：清掉本账号代次过期的 v2 元数据（含并发旧代次残留）；当前代次存活键的元数据保留。
  for (const area of ['local', 'session'] as const) {
    const storage = areaStorage(area)
    if (!storage) continue
    try {
      for (let index = storage.length - 1; index >= 0; index -= 1) {
        const storageKey = storage.key(index)
        if (!storageKey || !storageKey.startsWith(V2_PREFIX)) continue
        try {
          const triple = JSON.parse(storageKey.slice(V2_PREFIX.length)) as unknown[]
          if (triple.length === 3 && triple[0] === accountId && triple[1] === area
            && typeof triple[2] === 'string' && staleForClear(accountId, area, triple[2], generation)) {
            removeQuietly(storage, storageKey)
          }
        } catch { /* 坏元数据键跳过 */ }
      }
    } catch { /* 枚举失败跳过 */ }
  }
  if (session && session.accountId === accountId) session.active = false
  if (options.broadcast !== false && operationId) {
    try {
      ensureChannel()?.postMessage({ version: 2, type: 'clear', accountId, operationId } satisfies ClearMessage)
    } catch { /* 广播失败不影响本页清理 */ }
  }
  return cleared
}
