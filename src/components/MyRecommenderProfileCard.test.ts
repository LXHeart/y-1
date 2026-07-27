// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import MyRecommenderProfileCard from './MyRecommenderProfileCard.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

/**
 * 推荐官「我的主页」。重点锁：
 * - PUT 整份覆盖：标签/社交账号收的是**数组**（逗号串只在输入框里，保存时拆）；
 * - 空画像（后端回 null 字段 + 空数组）也能正常编辑、保存按钮在无输入时禁用；
 * - 社交账号空行被丢弃、followers 空值归一为 null（后端字段可空，但 '' 不是数字）。
 */

const { currentUser } = useAuth()

function asUser(id: string): AuthUser {
  return { id, email: `${id}@test.local`, displayName: id, role: 'user' }
}

const PROFILE = {
  accountId: 'acct-1',
  displayName: '美食探店小王',
  bio: '专注本地美食',
  contentTags: ['美食', '探店'],
  domainTags: ['餐饮'],
  socialAccounts: [{ platform: '抖音', handle: '@xiaowang', followers: 12000 }],
  updatedAt: '2026-07-27T10:00:00Z',
}

const REP = {
  accountId: 'acct-1', level: 'Lv2', levelTitle: '进阶推荐官',
  acceptedCount: 10, completedCount: 8, completionRate: 0.8,
  ratingCount: 0, averageScore: null, averageResponseSeconds: null,
}

function stubFetch(byUrl: Record<string, unknown>): {
  calls: { url: string; method: string; body?: string }[]
} {
  const calls: { url: string; method: string; body?: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    calls.push({ url, method, body: init?.body as string | undefined })
    const data = method === 'PUT' ? byUrl.PUT : byUrl[url]
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
  }))
  return { calls }
}

function inputByPlaceholder(wrapper: ReturnType<typeof mount>, fragment: string) {
  return wrapper.findAll('input').find((i) => (i.element as HTMLInputElement).placeholder.includes(fragment))!
}

function saveButton(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').find((b) => b.text() === '保存资料')!
}

enableAutoUnmount(afterEach)
beforeEach(() => { currentUser.value = null })
afterEach(() => { vi.unstubAllGlobals(); currentUser.value = null })

async function mountLoggedIn(byUrl: Record<string, unknown>) {
  const { calls } = stubFetch(byUrl)
  const wrapper = mount(MyRecommenderProfileCard)
  currentUser.value = asUser('acct-1')
  await flushPromises()
  return { wrapper, calls }
}

describe('MyRecommenderProfileCard 加载', () => {
  test('登录后并发拉画像与声誉，数组字段灌成逗号串展示', async () => {
    const { wrapper, calls } = await mountLoggedIn({
      '/api/me/recommender-profile': PROFILE,
      '/api/reputation/acct-1': REP,
    })

    expect(calls.map((c) => c.url)).toEqual(expect.arrayContaining([
      '/api/me/recommender-profile', '/api/reputation/acct-1']))
    // 画像是数组、输入框里展示成逗号串（拆/合只在这一处）
    expect((inputByPlaceholder(wrapper, '美食, 探店').element as HTMLInputElement).value).toBe('美食, 探店')
    // 声誉徽章：等级 + 完成率
    expect(wrapper.text()).toContain('Lv2')
    expect(wrapper.text()).toContain('80%')
  })

  /** 空画像（null + 空数组）不是 404，要能直接绑到表单上。 */
  test('空画像时表单空白、保存按钮禁用', async () => {
    const { wrapper } = await mountLoggedIn({
      '/api/me/recommender-profile': { ...PROFILE, displayName: null, bio: null, contentTags: [], domainTags: [], socialAccounts: [] },
      '/api/reputation/acct-1': REP,
    })

    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()

    await inputByPlaceholder(wrapper, '展示给商家').setValue('新昵称')
    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()
  })
})

describe('MyRecommenderProfileCard 保存', () => {
  /** 后端要数组；若发成逗号串会存成单个带逗号的标签。 */
  test('保存发送数组（标签拆分去重），不是逗号串', async () => {
    const { wrapper, calls } = await mountLoggedIn({
      '/api/me/recommender-profile': PROFILE,
      '/api/reputation/acct-1': REP,
      PUT: PROFILE,
    })

    await inputByPlaceholder(wrapper, '美食, 探店').setValue('美食, 探店, 美食')  // 含重复
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe('/api/me/recommender-profile')
    const body = JSON.parse(put.body!)
    expect(body.contentTags).toEqual(['美食', '探店'])   // 数组 + 去重
    expect(body.domainTags).toEqual(['餐饮'])
    expect(body.displayName).toBe('美食探店小王')
  })

  /** 社交账号：全空行丢弃；followers 留空要归一为 null（不能是 ''）。 */
  test('社交账号丢弃空行、followers 空值归一为 null', async () => {
    const { wrapper, calls } = await mountLoggedIn({
      '/api/me/recommender-profile': { ...PROFILE, socialAccounts: [] },
      '/api/reputation/acct-1': REP,
      PUT: { ...PROFILE, socialAccounts: [{ platform: '小红书', handle: 'xhs', followers: null }] },
    })

    await wrapper.findAll('button').find((b) => b.text() === '+ 添加社交账号')!.trigger('click')
    await wrapper.findAll('button').find((b) => b.text() === '+ 添加社交账号')!.trigger('click')
    // 第一行填平台、留空 followers；第二行全空（应被丢弃）
    await wrapper.findAll('input[placeholder*="平台"]')[0].setValue('小红书')
    await wrapper.findAll('input[placeholder*="账号 / 主页"]')[0].setValue('xhs')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')!
    expect(JSON.parse(put.body!).socialAccounts).toEqual([{ platform: '小红书', handle: 'xhs', followers: null }])
  })
})
