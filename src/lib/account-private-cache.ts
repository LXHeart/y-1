/**
 * 账号私有缓存登记簿（任务书 #103 C103-10 / §7.4「浏览器缓存」行）。
 *
 * 写入方把自己的 storage 键按 owner 登记；换号/注销时只清该账号登记过的键，
 * 并经 BroadcastChannel 通知同源标签页同步清理——主题偏好与其他账号的键不动。
 * 离线/第三方浏览器无法远程控制，本模块不宣称对它们物理删除。
 * 运行环境没有 localStorage（如 node 测试环境）时登记与清理都为安全空操作。
 */
export type PrivateStorageArea = 'local' | 'session'

interface RegistryEntry {
  s: PrivateStorageArea
  k: string
}

type Registry = Record<string, RegistryEntry[]>

const REGISTRY_KEY = 'grassland:account-private-cache'
const CHANNEL_NAME = 'grassland:account-private-cache-clear'
const MAX_KEYS_PER_ACCOUNT = 500

let channel: BroadcastChannel | null | undefined

function ensureChannel(): BroadcastChannel | null {
  if (channel !== undefined) return channel
  channel = typeof BroadcastChannel === 'undefined'
    ? null
    : new BroadcastChannel(CHANNEL_NAME)
  if (channel) {
    channel.onmessage = (event: MessageEvent) => {
      const data = event.data as { type?: string; accountId?: unknown } | null
      if (data?.type === 'clear' && typeof data.accountId === 'string') {
        clearAccountCache(data.accountId, { broadcast: false })
      }
    }
  }
  return channel
}

function localStorageAvailable(): boolean {
  return typeof localStorage !== 'undefined'
}

function readRegistry(): Registry {
  if (!localStorageAvailable()) return {}
  try {
    const parsed = JSON.parse(localStorage.getItem(REGISTRY_KEY) ?? '{}')
    return parsed && typeof parsed === 'object' ? parsed as Registry : {}
  } catch {
    return {}
  }
}

function writeRegistry(registry: Registry): void {
  if (!localStorageAvailable()) return
  try { localStorage.setItem(REGISTRY_KEY, JSON.stringify(registry)) } catch { /* quota 满时放弃登记，不影响业务写入 */ }
}

/** 登记一个账号私有键（匿名 null 不登记——匿名态无可归属清理）。 */
export function registerAccountKey(
  accountId: string | null,
  area: PrivateStorageArea,
  key: string,
): void {
  if (!accountId || !key) return
  const registry = readRegistry()
  const entries = registry[accountId] ?? []
  if (entries.some((entry) => entry.s === area && entry.k === key)) return
  entries.push({ s: area, k: key })
  registry[accountId] = entries.slice(-MAX_KEYS_PER_ACCOUNT)
  writeRegistry(registry)
}

/** 清空该账号登记的全部私有键；默认向同源标签页广播（接收侧不再转发，避免环路）。 */
export function clearAccountCache(accountId: string, options: { broadcast?: boolean } = {}): number {
  const registry = readRegistry()
  const entries = registry[accountId] ?? []
  let cleared = 0
  for (const entry of entries) {
    try {
      const storage = entry.s === 'session' ? sessionStorage : localStorage
      if (storage.getItem(entry.k) !== null) cleared += 1
      storage.removeItem(entry.k)
    } catch { /* storage 不可用时跳过该键 */ }
  }
  delete registry[accountId]
  writeRegistry(registry)
  if (options.broadcast !== false) {
    try { ensureChannel()?.postMessage({ type: 'clear', accountId }) } catch { /* 广播失败不影响本地清理 */ }
  }
  return cleared
}
