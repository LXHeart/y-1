import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../../stores/auth'
import type { AuthUser } from '../../../types/auth'
import { useAccountSessionStore } from '../../../stores/account-session'
import { useFeishuCredentials } from './useFeishuCredentials'

/**
 * TC79-02A/02B（任务书 #79 C79-02）飞书本地表单部分：跨账号清空 + PUT body 断言。
 * 用 effectScope 模拟组件生命周期（onScopeDispose = 卸载）。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => { resolve = res })
  return { promise, resolve }
}

/** 挂一个「组件」作用域返回 composable；stop() = 卸载。 */
function mountForm() {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const session = useAccountSessionStore()
  const scope = effectScope()
  let form!: ReturnType<typeof useFeishuCredentials>
  scope.run(() => { form = useFeishuCredentials() })
  return { auth, session, scope, form }
}

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useFeishuCredentials 跨账号（TC79-02A/B）', () => {
  it('02A：B 打开表单 → 输入并提交一次：PUT body 只含 B 字段、空密钥省略', async () => {
    const { auth, form } = mountForm()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: {} } } }))
    await form.toggleFeishuConfig()
    expect(form.showFeishuConfig.value).toBe(true)

    form.feishuAppId.value = 'qa-app-b'
    form.feishuFolderToken.value = 'qa-folder-b'
    form.feishuAppSecret.value = ''
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b', appSecret: '****1234', folderToken: 'qa-folder-b' } } } }))
    await form.submitFeishuCredentials()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const putBody = JSON.parse(String(fetchMock.mock.calls[1][1]?.body))
    expect(putBody).toEqual({ integrations: { feishu: { appId: 'qa-app-b', folderToken: 'qa-folder-b' } } })
    expect('appSecret' in putBody.integrations.feishu).toBe(false)
    // 保存成功：表单关闭、明文密钥已清
    expect(form.showFeishuConfig.value).toBe(false)
    expect(form.feishuAppSecret.value).toBe('')
  })

  it('E11：已开表单（A 配置回显）时切 B：表单同步清空并关闭', async () => {
    const { auth, form } = mountForm()
    auth.currentUser = userA
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-a', folderToken: 'qa-folder-a' } } } }))
    await form.toggleFeishuConfig()
    expect(form.feishuAppId.value).toBe('qa-app-a')

    form.feishuAppSecret.value = 'plain-a-secret'
    auth.currentUser = userB
    expect(form.showFeishuConfig.value).toBe(false)
    expect(form.feishuAppId.value).toBe('')
    expect(form.feishuFolderToken.value).toBe('')
    expect(form.feishuAppSecret.value).toBe('')
    expect(form.feishuSaveError.value).toBe('')
  })

  it('E11：加载中开表单时切 B：迟到的 A 配置不回填表单', async () => {
    const { auth, form } = mountForm()
    auth.currentUser = userA
    const aLoad = deferred<Response>()
    fetchMock.mockImplementationOnce(() => aLoad.promise)
    const togglePromise = form.toggleFeishuConfig()

    auth.currentUser = userB
    aLoad.resolve(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-a', folderToken: 'qa-folder-a' } } } }))
    await togglePromise
    expect(form.showFeishuConfig.value).toBe(false)
    expect(form.feishuAppId.value).toBe('')
  })

  it('E11/E17：保存中切 B：旧 PUT 不续发、不写 B 错误；B 自己的 PUT 只含 B 表单值', async () => {
    const { auth, form } = mountForm()
    auth.currentUser = userA
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-a', folderToken: 'qa-folder-a' } } } }))
    await form.toggleFeishuConfig()
    form.feishuAppId.value = 'qa-app-a'
    form.feishuFolderToken.value = 'qa-folder-a'

    const aSave = deferred<Response>()
    fetchMock.mockImplementationOnce(() => aSave.promise)
    const savingPromise = form.submitFeishuCredentials()
    expect(form.savingFeishu.value).toBe(true)

    auth.currentUser = userB
    // 切号同步清理：saving 归位、表单关闭
    expect(form.savingFeishu.value).toBe(false)
    expect(form.showFeishuConfig.value).toBe(false)

    aSave.resolve(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-a' } } } }))
    await savingPromise
    // 旧保存不向 B 重试（仍只有 A 的一次 PUT）
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(form.feishuSaveError.value).toBe('')

    // B 重新打开并保存：PUT 只含 B 的值，无 qa-app-a 残留
    fetchMock.mockResolvedValue(json({ success: true, data: { integrations: { feishu: {} } } }))
    await form.toggleFeishuConfig()
    form.feishuAppId.value = 'qa-app-b'
    form.feishuFolderToken.value = 'qa-folder-b'
    await form.submitFeishuCredentials()
    const calls = fetchMock.mock.calls
    const bPutBody = JSON.parse(String(calls[calls.length - 1]?.[1]?.body))
    expect(bPutBody).toEqual({ integrations: { feishu: { appId: 'qa-app-b', folderToken: 'qa-folder-b' } } })
    expect(JSON.stringify(bPutBody)).not.toContain('qa-app-a')
  })

  it('E18：当前保存失败保留原错误且密钥框已空', async () => {
    const { auth, form } = mountForm()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: {} } } }))
    await form.toggleFeishuConfig()
    form.feishuAppId.value = 'qa-app-b'
    form.feishuAppSecret.value = 'plain-secret'
    fetchMock.mockResolvedValueOnce(json({ success: false, error: '保存被拒' }, 403))
    await form.submitFeishuCredentials()
    expect(form.feishuSaveError.value).toBe('保存被拒')
    expect(form.showFeishuConfig.value).toBe(true)
    expect(form.feishuAppSecret.value).toBe('')
    expect(form.feishuAppId.value).toBe('qa-app-b')
  })

  it('E12：卸载后迟到的保存结果不重新打开弹窗/不写 notice', async () => {
    const { auth, scope, form } = mountForm()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: {} } } }))
    await form.toggleFeishuConfig()
    form.feishuAppId.value = 'qa-app-b'

    const aSave = deferred<Response>()
    fetchMock.mockImplementationOnce(() => aSave.promise)
    const savingPromise = form.submitFeishuCredentials()
    scope.stop() // 卸载：本地状态清理

    aSave.resolve(json({ success: true, data: { integrations: { feishu: { appId: 'qa-app-b' } } } }))
    await savingPromise
    expect(form.showFeishuConfig.value).toBe(false)
    expect(form.savingFeishu.value).toBe(false)
    expect(form.feishuSaveError.value).toBe('')
  })

  it('E02/E13：appId 超长等由现有服务校验（前端原样传递，不改阈值）', async () => {
    const { auth, form } = mountForm()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { integrations: { feishu: {} } } }))
    await form.toggleFeishuConfig()
    const longAppId = 'a'.repeat(257)
    form.feishuAppId.value = longAppId
    fetchMock.mockResolvedValueOnce(json({ success: false, error: 'appId 过长' }, 400))
    await form.submitFeishuCredentials()
    const body = JSON.parse(String(fetchMock.mock.calls[1][1]?.body))
    expect(body.integrations.feishu.appId).toBe(longAppId)
    expect(form.feishuSaveError.value).toBe('appId 过长')
  })

  it('E01（匿名）：打开表单不发私有加载；提交仍走原服务（权威 401 由服务端给出）', async () => {
    const { form } = mountForm() // 匿名：未设置 auth.currentUser
    await form.toggleFeishuConfig()
    expect(form.showFeishuConfig.value).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()

    form.feishuAppId.value = 'qa-app-guest'
    fetchMock.mockResolvedValueOnce(json({ success: false }, 401))
    await form.submitFeishuCredentials()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1]?.method).toBe('PUT')
    expect(form.feishuSaveError.value).toContain('401')
  })
})
