import { describe, expect, it, vi, beforeEach } from 'vitest'
import { AppError } from './errors.js'

const { lookupMock } = vi.hoisted(() => ({
  lookupMock: vi.fn(),
}))

vi.mock('node:dns/promises', () => ({
  lookup: lookupMock,
}))

import { isSafeProviderBaseUrl, normalizeProviderBaseUrl, resolveProviderBaseUrlAtRuntime } from './provider-url.js'

const messages = {
  invalid: 'invalid',
  protocol: 'protocol',
  credentials: 'credentials',
  privateHost: 'privateHost',
  dnsLookupFailed: 'dnsLookupFailed',
} as const

describe('provider-url', () => {
  beforeEach(() => {
    lookupMock.mockReset()
  })

  it('accepts http and https provider urls synchronously', () => {
    expect(isSafeProviderBaseUrl('https://public.example.com/v1')).toBe(true)
    expect(isSafeProviderBaseUrl('http://public.example.com/v1')).toBe(true)
    expect(() => normalizeProviderBaseUrl('http://public.example.com/v1', messages)).not.toThrow()
    expect(() => normalizeProviderBaseUrl('https://public.example.com/v1', messages)).not.toThrow()
  })

  it('rejects non-http(s) provider urls', () => {
    expect(isSafeProviderBaseUrl('ftp://public.example.com/v1')).toBe(false)
    expect(() => normalizeProviderBaseUrl('ftp://public.example.com/v1', messages)).toThrowError(
      new AppError('protocol', 400),
    )
  })

  it('rejects obvious private hosts synchronously', () => {
    expect(isSafeProviderBaseUrl('http://127.0.0.1:8080/v1')).toBe(false)
    expect(() => normalizeProviderBaseUrl('http://127.0.0.1:8080/v1', messages)).toThrowError(
      new AppError('privateHost', 400),
    )
  })

  it('rejects hostnames that resolve to private IPs at runtime', async () => {
    lookupMock.mockResolvedValue([{ address: '127.0.0.1', family: 4 }])

    await expect(resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)).rejects.toEqual(
      new AppError('privateHost', 400),
    )
  })

  it('rejects IPv6-mapped private IPv4 hosts', async () => {
    expect(isSafeProviderBaseUrl('http://[::ffff:7f00:1]:8080/v1')).toBe(false)
    expect(() => normalizeProviderBaseUrl('http://[::ffff:7f00:1]:8080/v1', messages)).toThrowError(
      new AppError('privateHost', 400),
    )

    lookupMock.mockResolvedValue([{ address: '::ffff:7f00:1', family: 6 }])

    await expect(resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)).rejects.toEqual(
      new AppError('privateHost', 400),
    )
  })

  it('rejects mixed public and private DNS answers at runtime', async () => {
    lookupMock.mockResolvedValue([
      { address: '93.184.216.34', family: 4 },
      { address: '10.0.0.5', family: 4 },
    ])

    await expect(resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)).rejects.toEqual(
      new AppError('privateHost', 400),
    )
  })

  it('rejects additional special-use IPv4 and IPv6 ranges', async () => {
    expect(isSafeProviderBaseUrl('http://100.64.0.1/v1')).toBe(false)
    expect(isSafeProviderBaseUrl('http://198.18.0.1/v1')).toBe(false)
    expect(isSafeProviderBaseUrl('http://224.0.0.1/v1')).toBe(false)
    expect(isSafeProviderBaseUrl('http://[ff02::1]/v1')).toBe(false)
    expect(isSafeProviderBaseUrl('http://[2001:db8::1]/v1')).toBe(false)
    expect(isSafeProviderBaseUrl('http://[100::1]/v1')).toBe(false)

    lookupMock.mockResolvedValue([{ address: '198.18.0.10', family: 4 }])
    await expect(resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)).rejects.toEqual(
      new AppError('privateHost', 400),
    )
  })

  it('returns a dns lookup error when runtime hostname resolution fails', async () => {
    lookupMock.mockRejectedValue(new Error('getaddrinfo ENOTFOUND gateway.example.com'))

    await expect(resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)).rejects.toEqual(
      new AppError('dnsLookupFailed', 502),
    )
  })

  it('reuses dispatcher for the same origin and validated addresses', async () => {
    lookupMock.mockResolvedValue([{ address: '93.184.216.34', family: 4 }])

    const first = await resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)
    const second = await resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)

    expect(first.dispatcher).toBeDefined()
    expect(first.dispatcher).toBe(second.dispatcher)
  })

  it('accepts hostnames that resolve only to public IPs at runtime', async () => {
    lookupMock.mockResolvedValue([{ address: '93.184.216.34', family: 4 }])

    await expect(resolveProviderBaseUrlAtRuntime('https://gateway.example.com/v1', messages)).resolves.toMatchObject({
      baseUrl: 'https://gateway.example.com/v1',
      addresses: ['93.184.216.34'],
      dispatcher: expect.any(Object),
    })
  })

  it('allows trusted public API domains that resolve to private IPs', async () => {
    lookupMock.mockResolvedValue([{ address: '10.0.0.5', family: 4 }])

    await expect(resolveProviderBaseUrlAtRuntime('https://dashscope.aliyuncs.com/compatible-mode/v1', messages)).resolves.toMatchObject({
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      addresses: ['10.0.0.5'],
      dispatcher: expect.any(Object),
    })
  })

  it('allows trusted domain subdomains that resolve to private IPs', async () => {
    lookupMock.mockResolvedValue([{ address: '127.0.0.1', family: 4 }])

    await expect(resolveProviderBaseUrlAtRuntime('https://some-service.aliyuncs.com/v1', messages)).resolves.toMatchObject({
      baseUrl: 'https://some-service.aliyuncs.com/v1',
    })
  })

  it('rejects non-trusted domains that resolve to private IPs', async () => {
    lookupMock.mockResolvedValue([{ address: '10.0.0.5', family: 4 }])

    await expect(resolveProviderBaseUrlAtRuntime('https://my-custom-api.example.com/v1', messages)).rejects.toEqual(
      new AppError('privateHost', 400),
    )
  })
})
