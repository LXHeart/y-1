import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { AuthUser } from '../types/auth'
import { useAccountSessionStore } from './account-session'
import { useAnalysisSettingsStore } from './analysis-settings'

/**
 * TC79-02A/02B（任务书 #79 C79-02）设置部分：按账号隔离 + 迟到结果静默丢弃。
 * mock 在 fetch 边界（§12.2）；A/B 取固定合成账号。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason?: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function makeStore() {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const session = useAccountSessionStore()
  const store = useAnalysisSettingsStore()
  return { auth, session, store }
}

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('analysis-settings 隔离（TC79-02A/B）', () => {
  it('E01：匿名不加载私有设置', async () => {
    const { store } = makeStore()
    await store.loadSettings()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.loaded).toBe(false)
  })

  it('02A：B 加载与保存——GET 一次、PUT 完整 body 且不带 appSecret、UI 只显示 B', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b', folderToken: 'qa-folder-b' } } } }))
    await store.loadSettings()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/settings/analysis')
    expect(store.settings.integrations?.feishu?.appId).toBe('qa-app-b')
    expect(store.loaded).toBe(true)

    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b', appSecret: '****1234', folderToken: 'qa-folder-b' } } } }))
    const ok = await store.saveFeishuCredentials({ appId: 'qa-app-b', folderToken: 'qa-folder-b' })
    expect(ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const putCall = fetchMock.mock.calls[1]
    expect(putCall[1]?.method).toBe('PUT')
    expect(JSON.parse(String(putCall[1]?.body))).toEqual({
      integrations: { feishu: { appId: 'qa-app-b', folderToken: 'qa-folder-b' } },
    })
    expect(store.settings.integrations?.feishu?.appSecret).toBe('****1234')
  })

  it('E03：连点加载同 owner 合并，只发一次 GET', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: {} } } }))
    await Promise.all([store.loadSettings(), store.loadSettings(), store.loadSettings()])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('E08/E19：normalize 只取 feishu，旧 features 不影响前端形态；空 feishu 与未加载区分', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { features: { video: true }, integrations: { feishu: {} } } }))
    await store.loadSettings()
    expect(store.settings.integrations?.feishu?.appId).toBeUndefined()
    expect(store.loaded).toBe(true)
  })

  it('E06/E07：当前 401/403/500 保留原文案；401 不留旧配置', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b' } } } }))
    await store.loadSettings()
    expect(store.settings.integrations?.feishu?.appId).toBe('qa-app-b')

    for (const status of [401, 403, 500]) {
      fetchMock.mockResolvedValueOnce(json({ success: false, error: `加载失败${status}` }, status))
      await store.loadSettings()
      expect(store.error).toBe(`加载失败${status}`)
    }
  })

  it('02B：A 请求挂起 → 切 B → B 完成后释放 A（200/401/403/500/断网）：无 A 字段落进 B', async () => {
    for (const releaseKind of [200, 401, 403, 500, 'network'] as const) {
      const { auth, store } = makeStore()
      auth.currentUser = userA
      const aLoad = deferred<Response>()
      fetchMock.mockImplementationOnce(() => aLoad.promise)

      const oldLoad = store.loadSettings()
      expect(store.loading).toBe(true)

      auth.currentUser = userB
      // 换账号同步 reset：loading/loaded/error/表单全部回初始
      expect(store.loading).toBe(false)
      expect(store.loaded).toBe(false)
      expect(store.error).toBe('')
      expect(store.settings.integrations?.feishu?.appId).toBeUndefined()

      fetchMock.mockImplementationOnce(() => Promise.resolve(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b' } } } })))
      await store.loadSettings()
      expect(store.settings.integrations?.feishu?.appId).toBe('qa-app-b')

      if (releaseKind === 200) aLoad.resolve(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-a' } } } }))
      else if (releaseKind === 'network') aLoad.reject(new TypeError('Failed to fetch'))
      else aLoad.resolve(json({ success: false, error: `旧错误${releaseKind}` }, releaseKind))
      await oldLoad

      expect(store.settings.integrations?.feishu?.appId).toBe('qa-app-b')
      expect(store.loaded).toBe(true)
      expect(store.error).toBe('')
      expect(store.loading).toBe(false)
    }
  })

  it('02B（保存）：A 的 PUT 迟到返回 false 且不污染 B 的 saveError；A 保存最多一次、不向 B 重试', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userA
    const aSave = deferred<Response>()
    fetchMock.mockImplementationOnce(() => aSave.promise)

    const oldSave = store.saveFeishuCredentials({ appId: 'qa-app-a', folderToken: 'qa-folder-a' })
    auth.currentUser = userB
    expect(store.saving).toBe(false)
    expect(store.saveError).toBe('')

    aSave.resolve(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-a' } } } }))
    await expect(oldSave).resolves.toBe(false)
    // B 的状态未被 A 写入；也没有向 B 发起重试 PUT
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(store.settings.integrations?.feishu?.appId).toBeUndefined()
    expect(store.saveError).toBe('')
  })

  it('E13：密钥省略=保持（不传字段）；纯空白传原值由服务归一清除', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b' } } } }))
    await store.saveFeishuCredentials({ appId: 'qa-app-b', appSecret: '   ', folderToken: 'qa-folder-b' })
    const body = JSON.parse(String(fetchMock.mock.calls[0][1]?.body))
    expect(body.integrations.feishu).toEqual({ appId: 'qa-app-b', appSecret: '   ', folderToken: 'qa-folder-b' })
  })

  it('resetForAccount 仅重置不发网络', () => {
    const { store } = makeStore()
    store.resetForAccount(null)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
