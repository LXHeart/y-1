// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, reactive, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import type { CreationBrief } from '../types/creation'
import { useCreationVoice } from './useCreationVoice'
import { GrasslandHttpError } from './grassland-http'
const api = vi.hoisted(() => vi.fn())
vi.mock('./grassland-http', async importOriginal => ({ ...await importOriginal<object>(), request: api }))
vi.mock('../stores/account-session', () => ({ useAccountSessionStore: () => session }))
let session: ReturnType<typeof makeSession>
function makeSession() {
  const state = reactive({ epoch: 0, ownerAccountId: 'A' as string | null })
  return Object.assign(state, { capture: () => ({ epoch: state.epoch, accountId: state.ownerAccountId }), isCurrent: (t: { epoch: number }) => state.epoch === t.epoch })
}
const scopes: ReturnType<typeof effectScope>[] = []
const profile = (revision = 1, rule = '短句') => ({ role: 'consumer', revision, enabled: true, rules: [rule], samples: [], updatedAt: null })
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function setup() {
  const scope = effectScope(); scopes.push(scope)
  const brief = ref<CreationBrief | null>({ processingMode: 'create', authorRole: 'consumer', voice: { mode: 'none' } })
  const platform = ref('zhihu'), draft = ref('d1')
  const voice = scope.run(() => useCreationVoice(brief, () => platform.value, () => draft.value))!
  return { voice, brief, platform, draft, scope }
}
beforeEach(() => { session = makeSession(); api.mockReset(); api.mockResolvedValue(profile()) })
afterEach(() => { scopes.splice(0).forEach(s => s.stop()); vi.restoreAllMocks() })
describe('私有文风状态', () => {
  it('新UI明确关闭；启用选择仅保存引用', async () => {
    const { voice, brief } = setup(); await flushPromises(); voice.choose(true)
    expect(brief.value?.voice).toEqual({ mode: 'profile', role: 'consumer', revision: 1 })
    expect(JSON.stringify(brief.value)).not.toContain('短句')
    voice.choose(false); expect(brief.value?.voice).toEqual({ mode: 'none' })
  })
  it.each(['success', 'failure'])('账号 A→B→A 的旧%s/finally不能回填', async outcome => {
    const old = deferred<ReturnType<typeof profile>>()
    api.mockReturnValueOnce(old.promise)
    const { voice } = setup()
    session.ownerAccountId = 'B'; session.epoch++
    await flushPromises()
    api.mockResolvedValue(profile(3, 'A新规则')); session.ownerAccountId = 'A'; session.epoch++
    await flushPromises()
    if (outcome === 'success') old.resolve(profile(1, 'A旧规则')); else old.reject(new Error('旧错误'))
    await flushPromises()
    expect(voice.profile.value?.rules).toEqual(['A新规则']); expect(voice.error.value).toBe(''); expect(voice.loading.value).toBe(false)
  })
  it('身份往返/草稿切换使旧预览失效', async () => {
    const { voice, brief, draft } = setup(); await flushPromises()
    const old = deferred<{ candidates: string[] }>(); api.mockReturnValueOnce(old.promise)
    const extracting = voice.preview('原稿', '改稿', 'style', 'article')
    brief.value = { ...brief.value!, authorRole: 'merchant' }; await flushPromises()
    brief.value = { ...brief.value!, authorRole: 'consumer' }; draft.value = 'd2'; await flushPromises()
    old.resolve({ candidates: ['旧候选'] }); await extracting
    expect(voice.candidates.value).toEqual([]); expect(voice.extracting.value).toBe(false)
  })
  it('读取失败保留旧档案而非空成功', async () => {
    const { voice } = setup(); await flushPromises(); api.mockRejectedValueOnce(new Error('离线')); await voice.load()
    expect(voice.profile.value?.revision).toBe(1); expect(voice.error.value).toBe('离线')
  })
  it('保存冲突保留输入，读取最新后再次明确保存', async () => {
    const { voice } = setup(); await flushPromises(); voice.edit(); voice.rules.value = ['新规则']
    api.mockRejectedValueOnce(new GrasslandHttpError(409, 'conflict')); expect(await voice.save()).toBe(false)
    expect(voice.rules.value).toEqual(['新规则']); expect(voice.error.value).toContain('其他页面')
    api.mockResolvedValueOnce(profile(2)); await voice.load(); expect(voice.rules.value).toEqual(['新规则'])
    api.mockResolvedValueOnce(profile(3, '新规则')); expect(await voice.save()).toBe(true)
    expect(JSON.parse(api.mock.calls[api.mock.calls.length - 1]![1].body).expectedRevision).toBe(2)
  })
  it('写结果未知只GET核对，不重放PUT', async () => {
    const { voice } = setup(); await flushPromises(); voice.edit(); voice.rules.value = ['新规则']
    api.mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(profile(2, '新规则'))
    expect(await voice.save()).toBe(true)
    expect(api.mock.calls.filter(c => c[1]?.method === 'PUT')).toHaveLength(1)
  })
  it('卸载后的PUT成功不得回填', async () => {
    const { voice, scope } = setup(); await flushPromises(); voice.edit()
    const old = deferred<ReturnType<typeof profile>>(); api.mockReturnValueOnce(old.promise)
    const saving = voice.save(); scope.stop(); old.resolve(profile(2)); await saving
    expect(voice.profile.value).toBe(null); expect(voice.message.value).toBe('')
  })
  it('事实/一次性改稿零模型调用；非法规则不保存', async () => {
    const { voice } = setup(); await flushPromises(); api.mockClear()
    await voice.preview('36', '38', 'fact', 'article'); expect(api).not.toHaveBeenCalled()
    voice.edit(); voice.rules.value = ['x'.repeat(301)]; expect(await voice.save()).toBe(false); expect(api).not.toHaveBeenCalled()
  })
})

it('清空仅写当前身份空槽；范文限额与启用空档案拒绝', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const { voice, brief } = setup(); await flushPromises(); voice.choose(true); voice.edit()
  api.mockResolvedValueOnce({ ...profile(2), enabled: false, rules: [] })
  await voice.clear()
  expect(JSON.parse(api.mock.calls[api.mock.calls.length - 1]![1].body)).toEqual({ expectedRevision: 1, enabled: false, rules: [], samples: [] })
  expect(brief.value?.voice).toEqual({ mode: 'none' })
  voice.edit(); voice.enabled.value = true; expect(await voice.save()).toBe(false)
  voice.rules.value = ['有效']; voice.samples.value = [{ id: 'id', platform: 'zhihu', genre: 'article', text: '', consent: true }]
  expect(await voice.save()).toBe(false)
})
it('提炼成功仅入候选；旧偏好导入只改缓冲且超限不截断', async () => {
  const { voice } = setup(); await flushPromises(); voice.edit()
  api.mockResolvedValueOnce({ candidates: ['少用表情'] }); await voice.preview('原稿', '改稿', 'style', 'article')
  expect(voice.candidates.value).toEqual(['少用表情']); expect(voice.rules.value).toEqual(['短句'])
  voice.addRules(voice.candidates.value); expect(voice.rules.value).toEqual(['短句', '少用表情'])
  api.mockResolvedValueOnce({ preferences: Array.from({ length: 31 }, (_, i) => `旧规则${i}`) }); await voice.loadLegacy()
  expect(voice.legacy.value).toHaveLength(31); voice.addRules(voice.legacy.value)
  expect(voice.rules.value).toHaveLength(33); expect(await voice.save()).toBe(false)
  expect(api.mock.calls.filter(c => c[1]?.method === 'PUT')).toHaveLength(0)
})
it('未保存修改可取消丢弃，离线提炼保留编辑文本；不支持平台强制none', async () => {
  const { voice, platform, brief } = setup(); await flushPromises(); voice.edit(); voice.rules.value = ['未保存']
  vi.spyOn(window, 'confirm').mockReturnValue(false); expect(voice.confirmDiscard()).toBe(false); expect(voice.editing.value).toBe(true)
  api.mockRejectedValueOnce(new Error('提炼离线')); await voice.preview('原稿', '改稿', 'style', 'article')
  expect(voice.error.value).toBe('提炼离线'); expect(voice.rules.value).toEqual(['未保存'])
  await voice.preview('', '改稿', 'style', 'article'); expect(voice.error.value).toContain('12000')
  brief.value = { ...brief.value!, voice: { mode: 'profile', role: 'consumer', revision: 1 } }
  platform.value = 'wechat'; await flushPromises(); expect(brief.value?.voice).toEqual({ mode: 'none' }); expect(voice.profile.value).toBe(null)
})
it('网络失败且核对内容不同不假报保存成功', async () => {
  const { voice } = setup(); await flushPromises(); voice.edit(); voice.rules.value = ['未提交']
  api.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(profile(2, '其他内容'))
  expect(await voice.save()).toBe(false); expect(voice.rules.value).toEqual(['未提交']); expect(voice.error.value).toContain('待核实')
})
