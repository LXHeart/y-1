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
 * - `activateAccountCache` 在账号会话建立时订阅广播（幂等、实例级可释放）；远端清理使本页
 *   旧 AccountTicket 失效（不冒充登出），本页清理接收模式不转发、不无条件废弃当前会话。
 *   会话是显式授权（#106 D02）：默认会话只属于从未建立会话的初始兼容路径；已撤销账号
 *   （clear/release/远端失效/换号）迟到 register 一律回收写入；通知只去重，失效与否以
 *   当前持久墓碑与激活会话捕获代次比较为准（乱序旧通知不废弃当前代次会话）。
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
  /** 激活实例身份：释放回调只作用于自己创建的会话（#106 D02——旧实例释放不撤销同账号新激活）。 */
  instance: object
  accountId: string
  generation: string
  active: boolean
  onInvalidate?: () => void
}

let session: CacheSession | null = null
let channel: BroadcastChannel | null | undefined
let storageListenerInstalled = false
/** 显式激活实例持有的监听引用计数：register 只确保监听在位、不计数（不产生永不释放的引用）。 */
let listenerRefCount = 0
/** 本页建立过会话的账号（显式或默认）：初始兼容路径只属于从未建立会话的页面（#106 D02）。 */
const sessionedAccounts = new Set<string>()
/** 已 clear/release/远端失效/被换号撤销的账号：迟到 register 一律回收写入（不复活）。 */
const revokedAccounts = new Set<string>()
/**
 * 内容删除失败待重试（#106 D03）：account → `${area}:${key}` 集合。创建于 clear/register
 * 的内容删除失败；失效于确认删除（重试成功/值已不在）或重核为当前代次合法新值。仅内存——
 * 标签页关闭即失，不承诺跨重启物删（如实限制，计数从不假报成功）。
 */
const failedContentKeys = new Map<string, Set<string>>()
const seenOperations = new Set<string>()

function markFailedContent(accountId: string, area: PrivateStorageArea, key: string): void {
  let entries = failedContentKeys.get(accountId)
  if (!entries) {
    entries = new Set<string>()
    failedContentKeys.set(accountId, entries)
  }
  entries.add(`${area}:${key}`)
}

function unmarkFailedContent(accountId: string, area: PrivateStorageArea, key: string): void {
  failedContentKeys.get(accountId)?.delete(`${area}:${key}`)
}

function hasFailedContent(accountId: string, area: PrivateStorageArea, key: string): boolean {
  return failedContentKeys.get(accountId)?.has(`${area}:${key}`) === true
}

/** 重试单个失败残留：确认删除或判定为当前代次合法新值时清债；仍失败保留。返回 true=债已清。 */
function retryFailedContentKey(accountId: string, area: PrivateStorageArea, key: string): boolean {
  const storage = areaStorage(area)
  if (!storage) return false
  if (!existed(storage, key)) {
    unmarkFailedContent(accountId, area, key)
    return true
  }
  if (!staleForClear(accountId, area, key, currentGeneration(accountId))) {
    // 合法新值（重新激活后登记）：不误删，债视为已转化。
    unmarkFailedContent(accountId, area, key)
    return true
  }
  if (removeQuietly(storage, key)) {
    removeQuietly(storage, metaKey(accountId, area, key))
    unmarkFailedContent(accountId, area, key)
    return true
  }
  return false
}

/** 显式激活时重试该账号全部已知失败残留（#106 D03：下次 clear/显式激活/恢复读取重试）。 */
function retryFailedContent(accountId: string): void {
  const entries = failedContentKeys.get(accountId)
  if (!entries) return
  for (const packed of [...entries]) {
    const separator = packed.indexOf(':')
    const area = packed.slice(0, separator) as PrivateStorageArea
    const key = packed.slice(separator + 1)
    retryFailedContentKey(accountId, area, key)
  }
  if ((failedContentKeys.get(accountId)?.size ?? 0) === 0) failedContentKeys.delete(accountId)
}

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
        receiveRemoteClear(data.accountId, data.operationId, { fromBroadcast: true })
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
  ensureListeners()
}

/** 只确保监听在位、不增引用计数（register 使用；监听归实际激活实例管理）。 */
function ensureListeners(): void {
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
 * 远端清理接收端：操作 ID 仅去重；代次以当前持久墓碑为准（不按 operationId 排序/覆盖）。
 * 当前会话仍持旧代次才失效一次；已在当前墓碑重新激活的会话，晚到通知只清 stale 代次
 * 记录，不使其 inactive、不回调失效（#106 D02/F03）。
 *
 * BC 消息降级（#106 D03/F05）：operationId 未成为当前墓碑=写方墓碑失败（storage 事件只在
 * 真实写入时触发，无此歧义）——接收页仍须尽力清理本页已知键（不按旧墓碑保护）并撤销
 * 会话；该降级不伪装成全页持久代次成功。
 */
function receiveRemoteClear(accountId: string, operationId: string, options: { fromBroadcast?: boolean } = {}): void {
  if (seenOperations.has(operationId)) return
  seenOperations.add(operationId)
  const tombstone = currentGeneration(accountId)
  const degrade = options.fromBroadcast === true && tombstone !== operationId
  if (session && session.active && session.accountId === accountId
    && (degrade || session.generation !== tombstone)) {
    session.active = false
    revokedAccounts.add(accountId)
    try { session.onInvalidate?.() } catch { /* 回调失败不阻塞清理 */ }
  }
  // 接收模式清本页：不写新墓碑、不转发、不无条件废弃当前会话。
  clearKeysForAccount(accountId, tombstone, { force: degrade })
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
 * 释放函数是实例级的：只释放自己创建的会话，不撤销同账号后续新激活（#106 D02）。
 * 换号时旧账号写资格即时撤销（迟到 register 回收）；已失效会话不能由迟到
 * register 复活，必须再次显式激活。onInvalidate 只失效当前账号旧票据，
 * 不得回调 clear 形成通知环。
 */
export function activateAccountCache(accountId: string, onInvalidate?: () => void): () => void {
  if (typeof accountId !== 'string' || accountId.trim() === '') return () => { /* 匿名无可归属清理 */ }
  const instance: object = {}
  if (session && session.accountId !== accountId) {
    // 换号：旧账号写资格撤销，不由新激活继承。
    revokedAccounts.add(session.accountId)
  }
  sessionedAccounts.add(accountId)
  if (session && session.accountId === accountId) {
    session.instance = instance
    session.generation = currentGeneration(accountId)
    session.active = true
    session.onInvalidate = onInvalidate
  } else {
    session = { instance, accountId, generation: currentGeneration(accountId), active: true, onInvalidate }
  }
  installListeners()
  retryFailedContent(accountId)
  importV1Records(accountId)
  let released = false
  return () => {
    if (released) return
    released = true
    // 实例级释放：只处置自己创建的会话；旧实例释放不动同账号新激活（D02）。
    if (session && session.instance === instance) {
      if (session.active) {
        session.active = false
      } else {
        session = null
      }
      revokedAccounts.add(accountId)
    }
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
 *
 * 会话授权（#106 D02）：缓存会话是显式授权——他账号会话占用或本账号已撤销
 * （clear/release/远端失效/换号）时迟到 register 一律回收写入；默认会话只属于
 * 从未建立任何会话且该账号从未被撤销的初始兼容路径。
 */
export function registerAccountKey(
  accountId: string | null,
  area: PrivateStorageArea,
  key: string,
): void {
  if (!accountId || accountId.trim() === '' || !key) return
  const storage = areaStorage(area)
  if (!storage) return
  if (!session) {
    // 初始兼容路径：仅限本页从未建立任何会话、且该账号从未被撤销。
    if (sessionedAccounts.size > 0 || revokedAccounts.has(accountId)) {
      removeQuietly(storage, key)
      return
    }
    session = { instance: {}, accountId, generation: currentGeneration(accountId), active: true }
    sessionedAccounts.add(accountId)
  } else if (session.accountId !== accountId) {
    // 他账号会话占用：迟到登记回收写入，不建新默认会话、不覆盖当前账号回调。
    removeQuietly(storage, key)
    return
  } else if (!session.active) {
    removeQuietly(storage, key)
    return
  }
  ensureListeners()
  const generation = currentGeneration(accountId)
  if (session.generation !== generation) {
    // 远端已清理（墓碑前进）：本页会话失效，旧写资格作废。
    session.active = false
    revokedAccounts.add(accountId)
    removeQuietly(storage, key)
    return
  }
  const record: KeyRecord = { version: 2, accountId, area, key, generation }
  try {
    storage.setItem(metaKey(accountId, area, key), JSON.stringify(record))
  } catch {
    // 登记失败：删除刚写的实际值，禁用该次持久缓存（内存态由调用方继续）；
    // 删除也失败时留内存重试依据（不静默丢线索，#106 D03）。
    if (!removeQuietly(storage, key)) markFailedContent(accountId, area, key)
    return
  }
  if (currentGeneration(accountId) !== generation) {
    // 写入间隙远端清理：回收本条。
    removeQuietly(storage, key)
    removeQuietly(storage, metaKey(accountId, area, key))
    session.active = false
    revokedAccounts.add(accountId)
  }
}

/**
 * 恢复读取入口（两处实际消费者）：会话 + 代次 + 元数据三重核验（#106 D02）。
 * 会话存在时须属本账号且仍有效、捕获代次与当前墓碑一致；他账号会话占用或本账号
 * 已撤销（clear/release/失效后无会话）不得经读取复活旧缓存。storage 不可读视为
 * 未知（不是初始空代次），返回 null、内存业务继续。元数据 owner/area/key 不符
 * 不猜（返回 null 且不删归属不可证明的内容）。v1 值只在尚无清理墓碑时导入；
 * 存在墓碑时旧值删除而不猜它是新缓存；迟到/乱序清理以当前墓碑为准。
 */
export function readAccountKey(
  accountId: string | null,
  area: PrivateStorageArea,
  key: string,
): string | null {
  if (!accountId || accountId.trim() === '' || !key) return null
  const storage = areaStorage(area)
  if (!storage) return null
  if (!localAvailable()) return null
  const generation = currentGeneration(accountId)
  if (session) {
    if (session.accountId !== accountId) return null
    if (!session.active || session.generation !== generation) return null
  } else if (revokedAccounts.has(accountId)) {
    return null
  }
  // 已宣告清理的失败残留不外读；恢复读取触发按代次核对的重试（#106 D03）——
  // 仍失败返回 null，重核为合法新值时继续常规读取。
  if (hasFailedContent(accountId, area, key) && !retryFailedContentKey(accountId, area, key)) {
    return null
  }
  let record: KeyRecord | null
  try {
    record = parseRecord(storage.getItem(metaKey(accountId, area, key)))
  } catch {
    return null
  }
  if (record) {
    if (record.accountId !== accountId || record.area !== area || record.key !== key) return null
    if (generation && record.generation !== generation) {
      removeQuietly(storage, key)
      removeQuietly(storage, metaKey(accountId, area, key))
      return null
    }
    try {
      return storage.getItem(key)
    } catch {
      return null
    }
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
  const legacyOwner = legacyKeyOwner(area, key)
  const v1Clue = readV1Registry()[accountId]?.find((entry) => entry.s === area && entry.k === key)
  let value: string | null
  try {
    value = storage.getItem(key)
  } catch {
    return null
  }
  // 归属可证明属他人账号的键不读（A 与 A1 完整相等隔离）；未登记且无格式归属的键按公开键读。
  if (!v1Clue && legacyOwner !== null && legacyOwner !== accountId) return null
  if (!v1Clue && legacyOwner === null) return value === null ? null : value
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
 * 拥有的键、最后广播 `{version:2,type:'clear',accountId,operationId}`，并撤销
 * 该账号在本页的写资格（迟到 register 回收）；接收模式（receiveRemoteClear 直调
 * {@link clearKeysForAccount}）不写新墓碑、不发送、不无条件废弃当前会话。
 *
 * 返回本页实际移除且此前存在的私有内容键数量（不含元数据、他页、主题）；
 * 重复清理返回 0。墓碑写失败时仍尽力删除本页已知值并广播该次操作，
 * 但不能宣称未收到通知的其他页已完成清理。
 */
export function clearAccountCache(accountId: string, options: { broadcast?: boolean } = {}): number {
  if (typeof accountId !== 'string' || accountId.trim() === '') return 0
  const active = options.broadcast !== false
  let operationId = ''
  let tombstoneWritten = false
  if (active) {
    operationId = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `op-${Date.now()}-${Math.random().toString(36).slice(2)}`
    tombstoneWritten = writeTombstone(accountId, operationId)
  }
  // 墓碑写失败的主动清理必须强制遍历本页已知键——不用旧墓碑保护本次待清值（#106 D03/F05）。
  const cleared = clearKeysForAccount(accountId, currentGeneration(accountId),
    { force: active && !tombstoneWritten })
  if (active) {
    // 本页主动清理：该账号写资格作废（迟到 register 回收），本页会话（若有）终止。
    revokedAccounts.add(accountId)
    if (session && session.accountId === accountId) session.active = false
    if (operationId) {
      try {
        ensureChannel()?.postMessage({ version: 2, type: 'clear', accountId, operationId } satisfies ClearMessage)
      } catch { /* 广播失败不影响本页清理 */ }
    }
  }
  return cleared
}

/**
 * 键扫描与删除（主动/接收模式共用；不触碰会话状态）。force=true 时不按代次过滤
 * （墓碑写失败降级）。内容删除失败：不计数、不删其元数据（保留线索）、进待重试
 * 集合；确认删除（内容此前存在且已物理删除）才计数；元数据删除失败不影响已确认计数，
 * 残留元数据由收尾扫描/下次清理回收且不重复计数（#106 D03/F04）。
 */
function clearKeysForAccount(accountId: string, generation: string, options: { force?: boolean } = {}): number {
  const force = options.force === true
  const local = areaStorage('local')
  const tabSession = areaStorage('session')
  const v1Entries = readV1Registry()[accountId] ?? []
  const removed = new Set<string>()
  let cleared = 0
  const attempt = (area: PrivateStorageArea, key: string): void => {
    if (!key || removed.has(`${area}:${key}`)) return
    if (!force && !staleForClear(accountId, area, key, generation)) return
    const storage = area === 'session' ? tabSession : local
    const contentExisted = existed(storage, key)
    const contentRemoved = removeQuietly(storage, key)
    if (contentExisted && contentRemoved) {
      cleared += 1
      removeQuietly(storage, metaKey(accountId, area, key))
      unmarkFailedContent(accountId, area, key)
    } else if (contentExisted) {
      // 内容删除失败：值与元数据保留为重试线索，不假报成功。
      markFailedContent(accountId, area, key)
    } else {
      removeQuietly(storage, metaKey(accountId, area, key))
      unmarkFailedContent(accountId, area, key)
    }
    removed.add(`${area}:${key}`)
  }
  // 先重试已知失败残留，再常规扫描。
  for (const packed of [...(failedContentKeys.get(accountId) ?? [])]) {
    const separator = packed.indexOf(':')
    attempt(packed.slice(0, separator) as PrivateStorageArea, packed.slice(separator + 1))
  }
  // v2 元数据 + 两种截断格式精确扫描（session 只清本页 sessionStorage）。
  for (const key of contentKeysOf(accountId, 'session')) attempt('session', key)
  for (const key of contentKeysOf(accountId, 'local')) attempt('local', key)
  // v1 兼容线索（session 条目对他页是线索，对本页 removeItem 幂等无害；登记行保留）。
  for (const entry of v1Entries) {
    if (entry && (entry.s === 'local' || entry.s === 'session')) attempt(entry.s, entry.k)
  }
  // 收尾：清掉本账号代次过期的 v2 元数据（含并发旧代次残留）；当前代次存活键的元数据保留。
  // 仍在待重试集合的键跳过——失败键的最后线索不能被扫掉（#106 D03）。
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
            && typeof triple[2] === 'string' && !hasFailedContent(accountId, area, triple[2])
            && (force || staleForClear(accountId, area, triple[2], generation))) {
            removeQuietly(storage, storageKey)
          }
        } catch { /* 坏元数据键跳过 */ }
      }
    } catch { /* 枚举失败跳过 */ }
  }
  return cleared
}
