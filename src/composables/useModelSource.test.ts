import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAccountSessionStore } from '../stores/account-session'
import { useAuthStore } from '../stores/auth'
import { useModelSource } from './useModelSource'

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => useModelSource().reset())
afterEach(() => vi.unstubAllGlobals())

describe('useModelSource', () => {
  it('并发加载共用同一个请求且全部等待到状态就绪', async () => {
    let finish!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { finish = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const shared = useModelSource()
    const first = shared.load()
    const second = shared.load()
    expect(second).toBe(first)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    finish(json({ data: { modelSource: 'own', masterVersion: 2 } }))
    await Promise.all([first, second])
    expect(shared.modelSource.value).toBe('own')
    expect(shared.loading.value).toBe(false)
  })

  it('旧接口缺少总开关契约时明确报错，不误显示成 own', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ data: { items: [] } })))
    const shared = useModelSource()
    await shared.load()
    expect(shared.loaded.value).toBe(false)
    expect(shared.modelSource.value).toBe('platform')
    expect(shared.loadError.value).toContain('确认服务已更新')
    await expect(shared.setSource('own')).resolves.toContain('先成功加载')
  })

  it('换账号后忽略旧会话迟到的加载结果', async () => {
    let finishOld!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finishOld = resolve }))
      .mockResolvedValueOnce(json({ data: { modelSource: 'platform', masterVersion: 5 } }))
    vi.stubGlobal('fetch', fetchMock)
    const shared = useModelSource()
    const oldRequest = shared.load()
    shared.reset()
    await shared.load()
    finishOld(json({ data: { modelSource: 'own', masterVersion: 99 } }))
    await oldRequest
    expect(shared.modelSource.value).toBe('platform')
    expect(shared.masterVersion.value).toBe(5)
    expect(shared.loaded.value).toBe(true)
  })

  it('换账号后忽略旧会话迟到的保存结果', async () => {
    let finishSave!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ data: { modelSource: 'platform', masterVersion: 0 } }))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finishSave = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const shared = useModelSource()
    await shared.load()
    const saving = shared.setSource('own')
    shared.reset()
    finishSave(json({ data: { modelSource: 'own', masterVersion: 1 } }))
    await expect(saving).resolves.toContain('账号已变更')
    expect(shared.modelSource.value).toBe('platform')
    expect(shared.loaded.value).toBe(false)
  })

  it('409 后重载失败返回明确错误，不抛出未处理异常', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ data: { modelSource: 'platform', masterVersion: 1 } }))
      .mockResolvedValueOnce(json({ error: '版本冲突' }, 409))
      .mockRejectedValueOnce(new Error('连接已断开')))
    const shared = useModelSource()
    await shared.load()
    await expect(shared.setSource('own')).resolves.toBe('连接已断开')
    expect(shared.loaded.value).toBe(false)
    expect(shared.loading.value).toBe(false)
  })
})

describe('useModelSource 账号边界（任务书 #79 C79-02）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

  afterEach(() => {
    useAuthStore().currentUser = null
  })

  it('账号切换后忽略旧账号迟到的加载结果（即使入口忘了 reset）', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    let finishOld!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finishOld = resolve }))
      .mockResolvedValueOnce(json({ data: { modelSource: 'platform', masterVersion: 5 } }))
    vi.stubGlobal('fetch', fetchMock)
    const shared = useModelSource()
    const oldRequest = shared.load()

    auth.currentUser = userB // 换号但不调 reset：B 的加载不得被 A 的 pending 吞掉
    await shared.load()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    finishOld(json({ data: { modelSource: 'own', masterVersion: 99 } }))
    await oldRequest
    expect(shared.modelSource.value).toBe('platform')
    expect(shared.masterVersion.value).toBe(5)
    expect(shared.loaded.value).toBe(true)
  })

  it('账号切换后旧账号的保存结果返回「账号已变更」且不写状态', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    let finishSave!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ data: { modelSource: 'platform', masterVersion: 0 } }))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finishSave = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const shared = useModelSource()
    await shared.load()

    const saving = shared.setSource('own')
    auth.currentUser = userB
    finishSave(json({ data: { modelSource: 'own', masterVersion: 1 } }))
    await expect(saving).resolves.toContain('账号已变更')
    expect(shared.modelSource.value).toBe('platform')
    expect(shared.masterVersion.value).toBe(0)
  })

  it('同 owner 并发去重语义保留：同账号两次 load 共用一个请求', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    useAccountSessionStore() // 确保会话票据就绪
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ data: { modelSource: 'own', masterVersion: 2 } }))
    vi.stubGlobal('fetch', fetchMock)
    const shared = useModelSource()
    const first = shared.load()
    const second = shared.load()
    expect(second).toBe(first)
    await first
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
