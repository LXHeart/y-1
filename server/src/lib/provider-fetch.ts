import { Agent, interceptors } from 'undici'
import type { Dispatcher } from 'undici'

interface ProviderDispatcherOptions {
  origin: string
  addresses: string[]
}

const dispatcherCache = new Map<string, Dispatcher>()

function toDnsRecords(addresses: string[]): Array<{ address: string; family: 4 | 6; ttl: number }> {
  return addresses.map((address) => ({
    address,
    family: address.includes(':') ? 6 : 4,
    ttl: 60_000,
  }))
}

function getDispatcherCacheKey(origin: string, addresses: string[]): string {
  return `${origin}::${[...new Set(addresses)].sort().join(',')}`
}

export function createPinnedProviderDispatcher(options: ProviderDispatcherOptions): Dispatcher {
  const cacheKey = getDispatcherCacheKey(options.origin, options.addresses)
  const cachedDispatcher = dispatcherCache.get(cacheKey)
  if (cachedDispatcher) {
    return cachedDispatcher
  }

  const records = toDnsRecords(options.addresses)
  const origin = new URL(options.origin)
  const dispatcher = new Agent().compose(interceptors.dns({
    maxTTL: 60_000,
    lookup(requestOrigin, _lookupOptions, callback): void {
      if (requestOrigin.hostname !== origin.hostname) {
        callback(new Error(`Unexpected provider hostname: ${requestOrigin.hostname}`) as NodeJS.ErrnoException, [])
        return
      }

      callback(null, records)
    },
  }))

  dispatcherCache.set(cacheKey, dispatcher)
  return dispatcher
}
