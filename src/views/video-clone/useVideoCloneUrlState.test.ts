// @vitest-environment happy-dom
// useVideoCloneUrlState.test.ts — C107-21 (TC107-21-01 URL 恢复安全)。
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, useRoute, useRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { useVideoCloneUrlState } from './useVideoCloneUrlState'

const PROJECT = '44444444-4444-4444-8444-444444444444'

async function mountUrlState(initial: string): Promise<{ router: Router; handle: ReturnType<typeof useVideoCloneUrlState> }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/video-clone/:projectId?', name: 'video-clone', component: { template: '<div />' } }],
  })
  await router.push(`/video-clone${initial}`)
  await router.isReady()
  let handle!: ReturnType<typeof useVideoCloneUrlState>
  mount({
    setup() {
      handle = useVideoCloneUrlState(useRoute(), useRouter())
      return () => null
    },
  }, { global: { plugins: [router] } })
  return { router, handle }
}

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  window.sessionStorage.clear()
  window.localStorage.clear()
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('TC107-21-01 URL 解析与恢复', () => {
  test('合法 step 与服务端标识被接受', async () => {
    const { handle } = await mountUrlState(`/${PROJECT}?step=generate&run=b1&job=j2&asset=a3`)
    expect(handle.projectId.value).toBe(PROJECT)
    expect(handle.step.value).toBe('generate')
    expect(handle.runId.value).toBe('b1')
    expect(handle.jobId.value).toBe('j2')
    expect(handle.assetId.value).toBe('a3')
  })

  test('非法 step / 超长与特殊字符 id 一律忽略（URL 不是权限来源）', async () => {
    const { handle } = await mountUrlState(`/${PROJECT}?step=../admin&run=${'x'.repeat(80)}&job=<script>`)
    expect(handle.step.value).toBe('reference')
    expect(handle.runId.value).toBeNull()
    expect(handle.jobId.value).toBeNull()
  })

  test('update 只写安全字段并同步 URL', async () => {
    const { router, handle } = await mountUrlState(`/${PROJECT}`)
    await handle.update({ step: 'review', run: 'build-9' })
    expect(router.currentRoute.value.query.step).toBe('review')
    expect(router.currentRoute.value.query.run).toBe('build-9')
    await handle.update({ run: null })
    expect(router.currentRoute.value.query.run).toBeUndefined()
  })

  test('resetForProject 清空非工程状态回到首阶段', async () => {
    const { handle } = await mountUrlState(`/${PROJECT}?step=review&job=j1`)
    handle.resetForProject()
    expect(handle.step.value).toBe('reference')
    expect(handle.jobId.value).toBeNull()
  })
})
