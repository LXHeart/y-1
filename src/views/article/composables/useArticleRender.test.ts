// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import { useArticleRender } from './useArticleRender'

/**
 * 任务书 #101 C101-17（TC101-078~080）：排版预览客户端——按版本请求、迟到响应 epoch
 * 丢弃、参数一致跳过、失败不渲染、卸载后全部失效。
 */

function setup(draftVersion = 3) {
  const scope = effectScope()
  const controller = scope.run(() => useArticleRender({
    draftId: () => 'draft-1',
    draftVersion: () => draftVersion,
  }))!
  return { scope, controller }
}

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), { status: 200 })
}

function fail(status: number, error: string): Response {
  return new Response(JSON.stringify({ success: false, error }), { status })
}

const PREVIEW = {
  draftId: 'draft-1', version: 3, renderVersion: 'creation-render-1.0.0',
  contentHash: 'h', html: '<div>ok</div>', text: 'ok', warnings: [], unresolvedMediaIds: [],
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ok(PREVIEW)))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useArticleRender', () => {
  test('按当前版本请求 render-previews；成功落预览', async () => {
    const { controller } = setup(3)
    const result = await controller.render({ theme: 'standard' })
    expect(result?.text).toBe('ok')
    expect(controller.preview.value?.renderVersion).toBe('creation-render-1.0.0')
    const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(url).toBe('/api/creation-studio/render-previews')
    expect(JSON.parse(String(init.body))).toMatchObject({ draftId: 'draft-1', version: 3, theme: 'standard' })
  })

  test('迟到响应 epoch 丢弃：连点新请求只认最后一次', async () => {
    const first: { resolve: ((response: Response) => void) | null } = { resolve: null }
    const mock = vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)) as { theme: string }
      if (body.theme === 'standard') {
        return new Promise<Response>((resolve) => { first.resolve = resolve })
      }
      return ok({ ...PREVIEW, html: '<div>compact</div>', text: 'compact' })
    })
    vi.stubGlobal('fetch', mock)
    const { controller } = setup()
    const firstRender = controller.render({ theme: 'standard' })
    const second = controller.render({ theme: 'compact' })
    first.resolve?.(ok({ ...PREVIEW, html: '<div>stale</div>', text: 'stale' }))
    expect(await firstRender).toBeNull()
    expect((await second)?.text).toBe('compact')
    expect(controller.preview.value?.text).toBe('compact')
  })

  test('失败落 error 且不渲染任何内容', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fail(404, '草稿不存在')))
    const { controller } = setup()
    const result = await controller.render({ theme: 'standard' })
    expect(result).toBeNull()
    expect(controller.preview.value).toBeNull()
    expect(controller.error.value).toContain('草稿不存在')
  })

  test('参数一致跳过（切主题/开关才算新请求）；dismiss 清空', async () => {
    const { controller } = setup()
    await controller.render({ theme: 'standard' })
    expect(controller.isSameRequest({ theme: 'standard', includeTitle: false, citeExternalLinks: false })).toBe(true)
    expect(controller.isSameRequest({ theme: 'compact', includeTitle: false, citeExternalLinks: false })).toBe(false)
    expect(controller.isSameRequest({ theme: 'standard', includeTitle: true, citeExternalLinks: false })).toBe(false)
    controller.dismiss()
    expect(controller.preview.value).toBeNull()
    expect(controller.isSameRequest({ theme: 'standard', includeTitle: false, citeExternalLinks: false })).toBe(false)
  })

  test('卸载（scope 停止）后迟到响应不落地', async () => {
    const deferred: { resolve: ((response: Response) => void) | null } = { resolve: null }
    vi.stubGlobal('fetch', vi.fn(async () => new Promise<Response>((resolve) => { deferred.resolve = resolve })))
    const { scope, controller } = setup()
    const pending = controller.render({ theme: 'standard' })
    scope.stop()
    deferred.resolve?.(ok(PREVIEW))
    expect(await pending).toBeNull()
    expect(controller.preview.value).toBeNull()
  })

  test('无草稿（未保存）拒绝请求', async () => {
    const scope = effectScope()
    const controller = scope.run(() => useArticleRender({ draftId: () => null, draftVersion: () => 1 }))!
    const result = await controller.render({ theme: 'standard' })
    expect(result).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
    scope.stop()
  })
})
