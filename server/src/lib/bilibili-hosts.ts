const allowedBilibiliPageHosts = [
  'www.bilibili.com',
  'bilibili.com',
  'm.bilibili.com',
  'b23.tv',
]

const allowedBilibiliVideoHosts = [
  'upos-sz-mirrorcosov.bilivideo.com',
  'upos-sz-mirror08c.bilivideo.com',
  'upos-sz-mirrorali.bilivideo.com',
  'upos-sz-mirroralibstar1.bilivideo.com',
  'upos-sz-mirrorhw.bilivideo.com',
  'upos-sz-mirrorcos.bilivideo.com',
]

const allowedBilibiliVideoHostSuffixes = [
  'bilivideo.com',
  'bilivideo.cn',
]

function normalizeHostname(hostname: string): string {
  return hostname.toLowerCase()
}

function isTrustedHost(hostname: string, trustedHost: string): boolean {
  return hostname === trustedHost || hostname.endsWith(`.${trustedHost}`)
}

export function isAllowedBilibiliPageHost(hostname: string): boolean {
  return allowedBilibiliPageHosts.includes(normalizeHostname(hostname))
}

export function isAllowedBilibiliVideoHost(hostname: string): boolean {
  const normalizedHostname = normalizeHostname(hostname)
  return allowedBilibiliVideoHosts.includes(normalizedHostname)
    || allowedBilibiliVideoHostSuffixes.some((trustedHost) => isTrustedHost(normalizedHostname, trustedHost))
}
