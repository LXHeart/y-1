// @vitest-environment happy-dom
import { defineComponent, h, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, test } from 'vitest'
import { useArticleUrlState } from './useArticleUrlState'

enableAutoUnmount(afterEach)
const draft = '00000101-0000-4000-8000-000000000001'
const plan = '00000101-0000-4000-8000-000000000002'
const job = '00000101-0000-4000-8000-000000000003'
async function host() {
  const owner = ref<string | null>(draft)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/article', component: { render: () => null } }] })
  await router.push({ path: '/article', query: { draft, keep: 'yes' } })
  let state!: ReturnType<typeof useArticleUrlState>
  mount(defineComponent({ setup: () => { state = useArticleUrlState(() => owner.value); return () => h('div') } }),
    { global: { plugins: [router] } })
  return { owner, router, state }
}

test('concurrent plan/job updates preserve both recovery IDs and unrelated query state', async () => {
  const { state, router } = await host()
  await Promise.all([state.remember('studioPlan', plan), state.remember('studioJob', job)])
  expect(router.currentRoute.value.query).toEqual({ draft, keep: 'yes', studioPlan: plan, studioJob: job })
  expect(state.jobId.value).toBe(job)
  await state.remember('studioPlan', null)
  expect(state.planId.value).toBeNull()
  expect(state.jobId.value).toBe(job)
})

test('a newly created job is exposed before queued URL navigation replaces the previous job', async () => {
  const { state, router } = await host()
  await state.remember('studioJob', job)
  const updating = state.remember('studioJob', plan)
  expect(router.currentRoute.value.query.studioJob).toBe(job)
  expect(state.jobId.value).toBe(plan)
  await updating
  expect(router.currentRoute.value.query.studioJob).toBe(plan)
  expect(state.jobId.value).toBe(plan)
})

test('draft switch drops queued IDs from the old project and clears its recovery parameters', async () => {
  const { owner, state, router } = await host()
  await state.remember('studioPlan', plan)
  const pending = state.remember('studioJob', job)
  owner.value = '00000101-0000-4000-8000-000000000004'
  await pending; await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ draft: owner.value, keep: 'yes' })
  expect(state.jobId.value).toBeNull()
})

test('malformed IDs and URLs belonging to another draft cannot become recovery state', async () => {
  const { state, router } = await host()
  await router.replace({ query: { draft, studioJob: 'not-a-job' } })
  expect(state.jobId.value).toBeNull()
  await router.replace({ query: { draft: plan, studioJob: job } })
  expect(state.jobId.value).toBeNull()
})
