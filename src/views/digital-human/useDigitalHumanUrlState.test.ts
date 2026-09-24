// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, useRoute, useRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { parseSelection, useDigitalHumanUrlState } from './useDigitalHumanUrlState'

/** TC105E-01-02 URL 安全：只保三项公开状态，敏感值不持久。 */
const VALID_PROFILE = '33333333-3333-4333-8333-333333333333'
const VALID_SESSION = '44444444-4444-4444-8444-444444444444'
/** 含十六进制字母的 UUID（全数字 UUID 的 toUpperCase 是恒等，测不出大小写拒绝）。 */
const LETTERED_UUID = 'abcdefab-cdef-4abc-8abc-abcdefabcdef'

/**
 * composable 依赖响应式 route（useRoute() 返回的代理）；直接喂 router.currentRoute.value
 * 快照对象 watch 不会触发。经探针组件以真实用法实例化。
 */
async function mountUrlState(initialQuery: string): Promise<{
  router: Router
  handle: ReturnType<typeof useDigitalHumanUrlState>
}> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/digital-human', name: 'digital-human', component: { template: '<div />' } }],
  })
  await router.push(`/digital-human${initialQuery}`)
  await router.isReady()
  let handle!: ReturnType<typeof useDigitalHumanUrlState>
  mount({
    setup() {
      handle = useDigitalHumanUrlState(useRoute(), useRouter())
      return () => null
    },
  }, { global: { plugins: [router] } })
  return { router, handle }
}

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  // URL 状态不落任何 storage；用例前后都断言为空。
  window.sessionStorage.clear()
  window.localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('TC105E-01-02 URL 安全（解析）', () => {
  test('合法 profile/session/view 组合参数化', () => {
    const cases: Array<{ query: Record<string, unknown>; expected: ReturnType<typeof parseSelection> }> = [
      { query: {}, expected: { profileId: null, sessionId: null, view: 'workbench' } },
      {
        query: { profile: VALID_PROFILE, session: VALID_SESSION },
        expected: { profileId: VALID_PROFILE, sessionId: VALID_SESSION, view: 'workbench' },
      },
      { query: { profile: VALID_PROFILE, view: 'history' }, expected: { profileId: VALID_PROFILE, sessionId: null, view: 'history' } },
    ]
    for (const { query, expected } of cases) {
      expect(parseSelection(query)).toEqual(expected)
    }
  })

  test('坏 UUID/空/数组（重复 query）一律丢弃', () => {
    const cases: unknown[] = [
      'not-a-uuid',
      '',
      'abcdefab-cdef-4abc-8abc-abcdefabcdefX',
      // 大写与无连写属非规范形态（K01 小写标准 UUID）。
      LETTERED_UUID.toUpperCase(),
      'abcdefabcdefcdef4abc8abcabcdefabcdef',
      [LETTERED_UUID, VALID_SESSION],
      123,
      null,
    ]
    for (const bad of cases) {
      expect(parseSelection({ profile: bad }).profileId).toBeNull()
      expect(parseSelection({ session: bad }).sessionId).toBeNull()
    }
  })

  test('view 只认 history；未知/数组回落 workbench', () => {
    expect(parseSelection({ view: 'history' }).view).toBe('history')
    expect(parseSelection({ view: 'bogus' }).view).toBe('workbench')
    expect(parseSelection({ view: '' }).view).toBe('workbench')
    expect(parseSelection({ view: ['history', 'workbench'] }).view).toBe('workbench')
  })

  test('token/prompt 等未知 query 不进入选择状态（结构上无字段可容纳）', () => {
    const selection = parseSelection({
      token: 'zz-tok',
      prompt: '正文不进 URL 状态',
      profile: VALID_PROFILE,
    })
    expect(selection).toEqual({ profileId: VALID_PROFILE, sessionId: null, view: 'workbench' })
    expect(JSON.stringify(selection)).not.toContain('zz-tok')
  })
})

describe('TC105E-01-02 URL 安全（replace）', () => {
  test('replaceSelection 用 replace 不 push，历史条目不增加', async () => {
    const { router, handle } = await mountUrlState(`?profile=${VALID_PROFILE}`)
    const replaceSpy = vi.spyOn(router, 'replace')
    const pushSpy = vi.spyOn(router, 'push')
    const historyLengthBefore = window.history.length

    await handle.replaceSelection({ profileId: VALID_PROFILE, sessionId: VALID_SESSION, view: 'workbench' })

    expect(replaceSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy).not.toHaveBeenCalled()
    expect(handle.selection.value).toEqual({ profileId: VALID_PROFILE, sessionId: VALID_SESSION, view: 'workbench' })
    expect(window.history.length).toBe(historyLengthBefore)
  })

  test('重复选择不追加：默认 view 不写 query，null id 不写 query', async () => {
    const { router, handle } = await mountUrlState('?view=history')

    await handle.replaceSelection({ profileId: null, sessionId: null, view: 'workbench' })
    expect(router.currentRoute.value.query).toEqual({})
  })

  test('敏感值不随写回传播：URL 只剩三项公开键', async () => {
    const { router, handle } = await mountUrlState('?token=opaque-fixture-token-value&prompt=%E6%AD%A3%E6%96%87&profile=bad-uuid')

    await handle.replaceSelection({ profileId: VALID_PROFILE, sessionId: null, view: 'history' })

    const query = router.currentRoute.value.query
    expect(Object.keys(query).sort()).toEqual(['profile', 'view'])
    expect(query.profile).toBe(VALID_PROFILE)
    expect(query.view).toBe('history')
    expect(JSON.stringify(query)).not.toContain('opaque-fixture-token-value')
    expect(JSON.stringify(query)).not.toContain('token')
  })

  test('storage 零写入：sessionStorage/localStorage 不出现选择或敏感值', async () => {
    const { handle } = await mountUrlState('?token=opaque-fixture-token-value&profile=' + VALID_PROFILE)
    await handle.replaceSelection({ profileId: VALID_PROFILE, sessionId: null, view: 'workbench' })

    expect(window.sessionStorage.length).toBe(0)
    expect(window.localStorage.length).toBe(0)
  })

  test('浏览器地址栏直接改 query（返回/前进）→ selection 跟随重解析', async () => {
    const { router, handle } = await mountUrlState('')
    expect(handle.selection.value.sessionId).toBeNull()

    await router.replace({ query: { session: VALID_SESSION } })
    expect(handle.selection.value.sessionId).toBe(VALID_SESSION)
  })
})
