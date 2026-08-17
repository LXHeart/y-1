import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCreationDraftVersions } from './useCreationDraftVersions'
import type { CreationDraftVersion } from '../types/creation-assistant'

afterEach(() => vi.unstubAllGlobals())

function envelope(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

function snapshot(version: number): CreationDraftVersion {
  return {
    version,
    createdAt: `2026-08-17T0${version}:00:00Z`,
    title: `草稿 v${version}`,
    sourceType: 'independent',
    status: 'draft',
    content: `正文 v${version}`,
  }
}

describe('useCreationDraftVersions', () => {
  it('按 nextCursor 追加版本页且不重复覆盖首屏', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('cursor=2')) {
        return envelope({ items: [snapshot(1)], nextCursor: null })
      }
      return envelope({ items: [snapshot(3), snapshot(2)], nextCursor: '2' })
    })
    vi.stubGlobal('fetch', fetchMock)
    const history = useCreationDraftVersions()

    await history.listVersions('d-1')
    await history.listVersions('d-1', true)

    expect(history.versions.value.map(item => item.version)).toEqual([3, 2, 1])
    expect(history.nextCursor.value).toBeNull()
    expect(fetchMock.mock.calls[1][0]).toContain('cursor=2')
  })

  it('指定版本读取后使用本地缓存', async () => {
    const fetchMock = vi.fn(async () => envelope(snapshot(2)))
    vi.stubGlobal('fetch', fetchMock)
    const history = useCreationDraftVersions()

    const first = await history.getVersion('d-1', 2)
    const second = await history.getVersion('d-1', 2)

    expect(first?.content).toBe('正文 v2')
    expect(second).toStrictEqual(first)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('切换草稿会丢弃迟到的旧草稿版本响应', async () => {
    let resolveOld!: (value: Response) => void
    const oldRequest = new Promise<Response>((resolve) => { resolveOld = resolve })
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/d-old/')) return oldRequest
      return envelope(snapshot(1))
    }))
    const history = useCreationDraftVersions()

    const old = history.getVersion('d-old', 2)
    await history.getVersion('d-new', 1)
    resolveOld(envelope(snapshot(2)))
    await old

    expect(history.snapshots.value[1]?.version).toBe(1)
    expect(history.snapshots.value[2]).toBeUndefined()
  })
})
