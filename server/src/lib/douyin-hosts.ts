const allowedDouyinPageHosts = [
  'douyin.com',
  'www.douyin.com',
  'v.douyin.com',
  'iesdouyin.com',
  'www.iesdouyin.com',
]

const allowedDouyinVideoHosts = [
  'douyin.com',
  'www.douyin.com',
  'iesdouyin.com',
  'www.iesdouyin.com',
  'aweme.snssdk.com',
]

const allowedDouyinVideoHostSuffixes = [
  'zjcdn.com',
  'douyinvod.com',
  'byteimg.com',
  'bytedance.com',
]

function normalizeHostname(hostname: string): string {
  return hostname.toLowerCase()
}

function isTrustedHost(hostname: string, trustedHost: string): boolean {
  return hostname === trustedHost || hostname.endsWith(`.${trustedHost}`)
}

export function isAllowedDouyinPageHost(hostname: string): boolean {
  return allowedDouyinPageHosts.includes(normalizeHostname(hostname))
}

export function isAllowedDouyinVideoHost(hostname: string): boolean {
  const normalizedHostname = normalizeHostname(hostname)
  return allowedDouyinVideoHosts.includes(normalizedHostname)
    || allowedDouyinVideoHostSuffixes.some((trustedHost) => isTrustedHost(normalizedHostname, trustedHost))
}
