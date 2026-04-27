import { lookup } from 'node:dns/promises'
import { isIP } from 'node:net'
import type { Dispatcher } from 'undici'
import { AppError } from './errors.js'
import { createPinnedProviderDispatcher } from './provider-fetch.js'

interface ProviderBaseUrlMessages {
  invalid: string
  protocol: string
  credentials: string
  privateHost: string
  dnsLookupFailed?: string
}

export interface ResolvedProviderBaseUrl {
  baseUrl: string
  addresses?: string[]
  dispatcher?: Dispatcher
}

function normalizeHostname(hostname: string): string {
  return hostname.toLowerCase().replace(/^\[/, '').replace(/\]$/, '').replace(/\.$/, '')
}

function isPrivateOrLocalIpv4(hostname: string): boolean {
  const parts = hostname.split('.').map((segment) => Number(segment))
  if (parts.length !== 4 || parts.some((part) => Number.isNaN(part) || part < 0 || part > 255)) {
    return false
  }

  const [first, second, third] = parts
  return first === 0
    || first === 10
    || first === 127
    || (first === 100 && second >= 64 && second <= 127)
    || (first === 169 && second === 254)
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 0 && third === 0)
    || (first === 192 && second === 0 && third === 2)
    || (first === 192 && second === 88 && third === 99)
    || (first === 192 && second === 168)
    || (first === 198 && second >= 18 && second <= 19)
    || (first === 198 && second === 51 && third === 100)
    || (first === 203 && second === 0 && third === 113)
    || first >= 224
}

function parseMappedIpv4Tail(value: string): string | undefined {
  if (isIP(value) === 4) {
    return value
  }

  const parts = value.split(':')
  if (parts.length !== 2) {
    return undefined
  }

  const numbers = parts.map((part) => Number.parseInt(part, 16))
  if (numbers.some((part) => Number.isNaN(part) || part < 0 || part > 0xffff)) {
    return undefined
  }

  return [
    numbers[0] >> 8,
    numbers[0] & 0xff,
    numbers[1] >> 8,
    numbers[1] & 0xff,
  ].join('.')
}

function parseIpv6Hextets(hostname: string): number[] | undefined {
  const normalized = normalizeHostname(hostname)
  if (!normalized || normalized.includes(':::')) {
    return undefined
  }

  const [headRaw, tailRaw = ''] = normalized.split('::')
  if (normalized.includes('::') && normalized.split('::').length !== 2) {
    return undefined
  }

  const parseSection = (section: string): number[] | undefined => {
    if (!section) {
      return []
    }

    const parts = section.split(':')
    const lastPart = parts.at(-1)
    if (lastPart && isIP(lastPart) === 4) {
      const ipv4Tail = parseMappedIpv4Tail(lastPart)
      if (!ipv4Tail) {
        return undefined
      }

      const ipv4Parts = ipv4Tail.split('.').map((segment) => Number(segment))
      return [
        ...parts.slice(0, -1).map((part) => Number.parseInt(part, 16)),
        (ipv4Parts[0]! << 8) | ipv4Parts[1]!,
        (ipv4Parts[2]! << 8) | ipv4Parts[3]!,
      ]
    }

    return parts.map((part) => Number.parseInt(part, 16))
  }

  const head = parseSection(headRaw)
  const tail = parseSection(tailRaw)
  if (!head || !tail) {
    return undefined
  }

  const allParts = [...head, ...tail]
  if (allParts.some((part) => Number.isNaN(part) || part < 0 || part > 0xffff)) {
    return undefined
  }

  if (normalized.includes('::')) {
    const fillCount = 8 - allParts.length
    if (fillCount < 1) {
      return undefined
    }

    return [...head, ...new Array<number>(fillCount).fill(0), ...tail]
  }

  return allParts.length === 8 ? allParts : undefined
}

function matchesIpv6Prefix(hextets: number[], expected: number[], prefixLength: number): boolean {
  let remainingBits = prefixLength

  for (let index = 0; index < expected.length && remainingBits > 0; index += 1) {
    const current = hextets[index] ?? 0
    const target = expected[index] ?? 0
    const bits = Math.min(16, remainingBits)
    const mask = bits === 16 ? 0xffff : (0xffff << (16 - bits)) & 0xffff

    if ((current & mask) !== (target & mask)) {
      return false
    }

    remainingBits -= bits
  }

  return true
}

function isPrivateOrLocalIpv6(hostname: string): boolean {
  const normalized = normalizeHostname(hostname)
  if (normalized === '::' || normalized === '::1') {
    return true
  }

  if (normalized.startsWith('::ffff:')) {
    const mappedIpv4 = parseMappedIpv4Tail(normalized.slice('::ffff:'.length))
    return mappedIpv4 ? isPrivateOrLocalIpv4(mappedIpv4) : true
  }

  const hextets = parseIpv6Hextets(normalized)
  if (!hextets) {
    return true
  }

  return matchesIpv6Prefix(hextets, [0x0064, 0xff9b, 0x0001], 48)
    || matchesIpv6Prefix(hextets, [0x0100, 0, 0, 0], 64)
    || matchesIpv6Prefix(hextets, [0x2001, 0x0000], 32)
    || matchesIpv6Prefix(hextets, [0x2001, 0x0002, 0x0000], 48)
    || matchesIpv6Prefix(hextets, [0x2001, 0x0db8], 32)
    || matchesIpv6Prefix(hextets, [0x2002], 16)
    || matchesIpv6Prefix(hextets, [0x3ffe], 16)
    || matchesIpv6Prefix(hextets, [0x5f00], 16)
    || matchesIpv6Prefix(hextets, [0xfc00], 7)
    || matchesIpv6Prefix(hextets, [0xfe80], 10)
    || matchesIpv6Prefix(hextets, [0xff00], 8)
}

function isDisallowedProviderHostname(hostname: string): boolean {
  const normalized = normalizeHostname(hostname)
  if (!normalized) {
    return true
  }

  if (normalized === 'localhost' || normalized.endsWith('.localhost')) {
    return true
  }

  const ipVersion = isIP(normalized)
  if (ipVersion === 4) {
    return isPrivateOrLocalIpv4(normalized)
  }

  if (ipVersion === 6) {
    return isPrivateOrLocalIpv6(normalized)
  }

  return false
}

const ALLOWED_PROTOCOLS = new Set(['http:', 'https:'])

const TRUSTED_PUBLIC_API_SUFFIXES = [
  'aliyuncs.com',
  'alicloudapi.com',
  'dashscope.aliyuncs.com',
  'api.openai.com',
  'anthropic.com',
  'api.anthropic.com',
  'googleapis.com',
  'azure.com',
  'api.deepseek.com',
  'api.moonshot.cn',
  'api.minimax.chat',
  'api.baichuan-ai.com',
  'api.zhipuai.vip',
  'api.siliconflow.cn',
  'siliconflow.cn',
  'api.lingyiwanwu.com',
]

function isTrustedPublicApiHostname(hostname: string): boolean {
  const normalized = normalizeHostname(hostname)
  return TRUSTED_PUBLIC_API_SUFFIXES.some(
    (suffix) => normalized === suffix || normalized.endsWith(`.${suffix}`),
  )
}

export function isSafeProviderBaseUrl(value: string): boolean {
  try {
    const parsedUrl = new URL(value)
    if (!ALLOWED_PROTOCOLS.has(parsedUrl.protocol)) {
      return false
    }

    if (parsedUrl.username || parsedUrl.password) {
      return false
    }

    return !isDisallowedProviderHostname(parsedUrl.hostname)
  } catch {
    return false
  }
}

function parseAndValidateProviderBaseUrl(value: string, messages: ProviderBaseUrlMessages): URL {
  let parsedUrl: URL
  try {
    parsedUrl = new URL(value)
  } catch {
    throw new AppError(messages.invalid, 400)
  }

  if (!ALLOWED_PROTOCOLS.has(parsedUrl.protocol)) {
    throw new AppError(messages.protocol, 400)
  }

  if (parsedUrl.username || parsedUrl.password) {
    throw new AppError(messages.credentials, 400)
  }

  if (isDisallowedProviderHostname(parsedUrl.hostname)) {
    throw new AppError(messages.privateHost, 400)
  }

  return parsedUrl
}

export function normalizeProviderBaseUrl(value: string, messages: ProviderBaseUrlMessages): string {
  return parseAndValidateProviderBaseUrl(value, messages).toString()
}

export async function resolveProviderBaseUrlAtRuntime(value: string, messages: ProviderBaseUrlMessages): Promise<ResolvedProviderBaseUrl> {
  const parsedUrl = parseAndValidateProviderBaseUrl(value, messages)
  const normalizedHostname = normalizeHostname(parsedUrl.hostname)

  if (isIP(normalizedHostname)) {
    return {
      baseUrl: parsedUrl.toString(),
    }
  }

  let addresses: Array<{ address: string }> = []

  try {
    addresses = await lookup(normalizedHostname, { all: true })
  } catch {
    throw new AppError(messages.dnsLookupFailed ?? messages.invalid, 502)
  }

  const normalizedAddresses = addresses
    .map((entry) => normalizeHostname(entry.address))
    .filter((address, index, list) => address.length > 0 && list.indexOf(address) === index)

  const hasPrivateAddress = normalizedAddresses.some((address) => isDisallowedProviderHostname(address))
  if (normalizedAddresses.length === 0 || (hasPrivateAddress && !isTrustedPublicApiHostname(normalizedHostname))) {
    throw new AppError(messages.privateHost, 400)
  }

  return {
    baseUrl: parsedUrl.toString(),
    addresses: normalizedAddresses,
    dispatcher: createPinnedProviderDispatcher({
      origin: parsedUrl.origin,
      addresses: normalizedAddresses,
    }),
  }
}
