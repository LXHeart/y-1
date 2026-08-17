# #25 Commission Ladder UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a complete Web Sandbox commission-ladder workflow covering task configuration, task display, merchant metric declaration, payout preview, and the existing confirmation API contract.

**Architecture:** Keep the Java Marketplace/Finance contract unchanged. Add shared API types, isolate ladder normalization and validation in one pure helper, let `MerchantTaskForm` emit a complete form value, reuse one summary component in both task views, and send a confirmation body only for ladder tasks.

**Tech Stack:** Vue 3.5, TypeScript, Vitest, Vue Test Utils, existing Grassland composables and CSS tokens.

---

## File Map

- Create `src/views/grassland/components/commission-ladder.ts`: defaults, normalization, validation, hydration, metric parsing, payout calculation.
- Create `src/views/grassland/components/commission-ladder.test.ts`: pure domain tests.
- Modify `src/types/grassland/task.ts`: public ladder contract.
- Create `src/views/grassland/components/MerchantTaskForm.commission.test.ts`: editor and funding-mode tests.
- Modify `src/views/grassland/components/MerchantTaskForm.vue`: ladder editor and whole-value event.
- Create `src/views/grassland/components/CommissionLadderSummary.vue`: reusable summary.
- Create `src/views/grassland/components/CommissionLadderSummary.test.ts`: display semantics.
- Modify `src/views/grassland/components/RecommenderTaskHall.vue`: recommender display.
- Modify `src/views/grassland/GrasslandWorkbench.vue`: persistence, hydration, merchant display, metric declaration, preview.
- Modify `src/components/GrasslandWorkbench.test.ts`: workbench payload and confirmation coverage.
- Modify `src/composables/useGrasslandMarketplace.ts`: optional metric request body.
- Modify `src/composables/useGrassland.test.ts`: request contracts.
- Modify `docs/草场开发进度与续接指南.md`: completion evidence.

### Task 1: Shared Contract And Pure Ladder Domain

**Files:**
- Modify: `src/types/grassland/task.ts`
- Create: `src/views/grassland/components/commission-ladder.ts`
- Create: `src/views/grassland/components/commission-ladder.test.ts`

- [ ] **Step 1: Write the failing pure-domain tests**

```ts
import { describe, expect, test } from 'vitest'
import {
  buildCommissionLadderPayload,
  calculateCommissionPayoutCents,
  commissionLadderFormFromTask,
  emptyCommissionLadderForm,
  getCommissionLadderValidationError,
  parseConfirmedMetricValue,
  type CommissionLadderFormData,
} from './commission-ladder'

const validForm = (): CommissionLadderFormData => ({
  enabled: true,
  policyVersion: 'ladder-v1',
  metricKey: 'douyin.play_count',
  tiers: [
    { threshold: 50_000, payoutYuan: 100 },
    { threshold: 10_000, payoutYuan: 50 },
  ],
})

describe('commission ladder domain', () => {
  test('builds sorted cents and omits a disabled ladder', () => {
    expect(buildCommissionLadderPayload(validForm())).toEqual({
      policyVersion: 'ladder-v1',
      metricKey: 'douyin.play_count',
      tiers: [
        { threshold: 10_000, payoutCents: 5_000 },
        { threshold: 50_000, payoutCents: 10_000 },
      ],
    })
    expect(buildCommissionLadderPayload({ ...validForm(), enabled: false })).toBeUndefined()
  })

  test('hydrates the frozen policy version and converts cents to yuan', () => {
    expect(commissionLadderFormFromTask({
      policyVersion: 'legacy-v3',
      metricKey: 'xiaohongshu.like_count',
      tiers: [{ threshold: 100, payoutCents: 12_345 }],
    })).toEqual({
      enabled: true,
      policyVersion: 'legacy-v3',
      metricKey: 'xiaohongshu.like_count',
      tiers: [{ threshold: 100, payoutYuan: 123.45 }],
    })
    expect(emptyCommissionLadderForm().policyVersion).toBe('ladder-v1')
  })

  test.each([
    [{ ...validForm(), policyVersion: '' }, 10_000, 0, '策略版本异常'],
    [{ ...validForm(), metricKey: '播放量' }, 10_000, 0, '指标标识'],
    [{ ...validForm(), tiers: [] }, 10_000, 0, '至少配置一个'],
    [{ ...validForm(), tiers: [{ threshold: 1.5, payoutYuan: 1 }] }, 10_000, 0, '非负整数'],
    [{ ...validForm(), tiers: [{ threshold: 10, payoutYuan: 5 }, { threshold: 10, payoutYuan: 6 }] }, 10_000, 0, '不能重复'],
    [{ ...validForm(), tiers: [{ threshold: 10, payoutYuan: 6 }, { threshold: 20, payoutYuan: 5 }] }, 10_000, 0, '不能下降'],
    [validForm(), 9_999, 0, '不能超过任务赏金'],
    [validForm(), 10_000, 1, '不能与霸王餐'],
  ])('rejects an invalid ladder', (form, bountyCents, freebieDepositCents, message) => {
    expect(getCommissionLadderValidationError(
      form as CommissionLadderFormData,
      bountyCents as number,
      freebieDepositCents as number,
    )).toContain(message)
  })

  test('rejects fractional cents and unsafe confirmed metrics', () => {
    expect(getCommissionLadderValidationError({
      ...validForm(), tiers: [{ threshold: 1, payoutYuan: 1.001 }],
    }, 1_000, 0)).toContain('两位小数')
    expect(parseConfirmedMetricValue(String(Number.MAX_SAFE_INTEGER + 1))).toEqual({
      value: null, error: '实际指标必须是非负安全整数',
    })
  })

  test('selects the highest fixed payout without accumulating tiers', () => {
    const ladder = buildCommissionLadderPayload(validForm())!
    expect(calculateCommissionPayoutCents(ladder, 9_999)).toBe(0)
    expect(calculateCommissionPayoutCents(ladder, 10_000)).toBe(5_000)
    expect(calculateCommissionPayoutCents(ladder, 99_999)).toBe(10_000)
    expect(parseConfirmedMetricValue('50000')).toEqual({ value: 50_000, error: null })
  })
})
```

- [ ] **Step 2: Run the test and verify the missing-module failure**

```bash
npx vitest run --config vitest.config.ts src/views/grassland/components/commission-ladder.test.ts
```

Expected: FAIL resolving `./commission-ladder`.

- [ ] **Step 3: Add public API types**

Add before `TaskRequirements` in `src/types/grassland/task.ts`:

```ts
export interface CommissionLadderTier {
  threshold: number
  payoutCents: number
}

export interface CommissionLadder {
  policyVersion: string
  metricKey: string
  tiers: CommissionLadderTier[]
}
```

Add to `TaskRequirements`:

```ts
commissionLadder?: CommissionLadder | null
```

- [ ] **Step 4: Implement the pure ladder module**

```ts
import type { CommissionLadder } from '../../../types/grassland'

export interface CommissionLadderFormTier {
  threshold: number
  payoutYuan: number
}

export interface CommissionLadderFormData {
  enabled: boolean
  policyVersion: string
  metricKey: string
  tiers: CommissionLadderFormTier[]
}

export interface ParsedConfirmedMetric {
  value: number | null
  error: string | null
}

export function emptyCommissionLadderForm(): CommissionLadderFormData {
  return {
    enabled: false,
    policyVersion: 'ladder-v1',
    metricKey: '',
    tiers: [{ threshold: 0, payoutYuan: 0 }],
  }
}

function centsFromYuan(value: number): number {
  return Math.round(value * 100)
}

function hasAtMostTwoDecimals(value: number): boolean {
  return Math.abs(value * 100 - Math.round(value * 100)) < 1e-8
}

export function buildCommissionLadderPayload(form: CommissionLadderFormData): CommissionLadder | undefined {
  if (!form.enabled) return undefined
  return {
    policyVersion: form.policyVersion.trim(),
    metricKey: form.metricKey.trim(),
    tiers: form.tiers
      .map((tier) => ({ threshold: tier.threshold, payoutCents: centsFromYuan(tier.payoutYuan) }))
      .sort((left, right) => left.threshold - right.threshold),
  }
}

export function commissionLadderFormFromTask(
  ladder: CommissionLadder | null | undefined,
): CommissionLadderFormData {
  if (!ladder) return emptyCommissionLadderForm()
  return {
    enabled: true,
    policyVersion: ladder.policyVersion,
    metricKey: ladder.metricKey,
    tiers: ladder.tiers.map((tier) => ({
      threshold: tier.threshold,
      payoutYuan: tier.payoutCents / 100,
    })),
  }
}

export function getCommissionLadderValidationError(
  form: CommissionLadderFormData,
  bountyCents: number,
  freebieDepositCents: number,
): string | null {
  if (!form.enabled) return null
  const policyVersion = form.policyVersion.trim()
  if (!policyVersion || policyVersion.length > 64) return '阶梯佣金策略版本异常，请重新配置'
  const metricKey = form.metricKey.trim()
  if (!metricKey || metricKey.length > 128 || !/^[a-zA-Z][a-zA-Z0-9_.-]*$/.test(metricKey)) {
    return '指标标识须以字母开头，且只能包含字母、数字、点、下划线或连字符'
  }
  if (form.tiers.length === 0) return '至少配置一个佣金档位'
  if (form.tiers.length > 20) return '最多配置 20 个佣金档位'
  if (bountyCents <= 0) return '阶梯佣金任务赏金必须大于 0'
  if (freebieDepositCents > 0) return '阶梯佣金不能与霸王餐押金同时启用'

  const tiers = [...form.tiers].sort((left, right) => left.threshold - right.threshold)
  for (const tier of tiers) {
    if (!Number.isSafeInteger(tier.threshold) || tier.threshold < 0) return '档位阈值必须是非负整数'
    if (!Number.isFinite(tier.payoutYuan) || tier.payoutYuan < 0) return '佣金金额不能为负数'
    if (!hasAtMostTwoDecimals(tier.payoutYuan)) return '佣金金额最多保留两位小数'
    if (!Number.isSafeInteger(centsFromYuan(tier.payoutYuan))) return '佣金金额超出安全范围'
  }
  for (let index = 1; index < tiers.length; index += 1) {
    if (tiers[index].threshold === tiers[index - 1].threshold) return '档位阈值不能重复'
    if (tiers[index].payoutYuan < tiers[index - 1].payoutYuan) return '佣金金额随档位升高不能下降'
  }
  if (centsFromYuan(tiers[tiers.length - 1].payoutYuan) > bountyCents) {
    return '最高档佣金不能超过任务赏金'
  }
  return null
}

export function parseConfirmedMetricValue(raw: string): ParsedConfirmedMetric {
  if (!raw.trim()) return { value: null, error: '请输入实际指标' }
  const value = Number(raw)
  if (!Number.isSafeInteger(value) || value < 0) {
    return { value: null, error: '实际指标必须是非负安全整数' }
  }
  return { value, error: null }
}

export function calculateCommissionPayoutCents(
  ladder: CommissionLadder,
  confirmedMetricValue: number,
): number {
  return [...ladder.tiers]
    .sort((left, right) => left.threshold - right.threshold)
    .reduce((payout, tier) => confirmedMetricValue >= tier.threshold ? tier.payoutCents : payout, 0)
}
```

- [ ] **Step 5: Run the pure tests and commit**

Expected: PASS, 6 tests.

```bash
git add src/types/grassland/task.ts src/views/grassland/components/commission-ladder.ts src/views/grassland/components/commission-ladder.test.ts
git commit -m "feat(frontend): add commission ladder domain model"
```

### Task 2: Merchant Ladder Editor

**Files:**
- Create: `src/views/grassland/components/MerchantTaskForm.commission.test.ts`
- Modify: `src/views/grassland/components/MerchantTaskForm.vue`

- [ ] **Step 1: Write failing component tests**

Mount `MerchantTaskForm` with a `commissionLadder` form value and assert:

```ts
expect(wrapper.text()).toContain('阶梯佣金')
expect(wrapper.text()).not.toContain('ladder-v1')
await wrapper.get('[aria-label="启用阶梯佣金"]').setValue(true)
expect(wrapper.emitted('update:commission-ladder')?.[0]?.[0]).toEqual(
  expect.objectContaining({ enabled: true }),
)
```

For an enabled form:

```ts
await wrapper.get('[aria-label="第 1 档阈值"]').setValue('10000')
expect(wrapper.emitted('update:commission-ladder')?.at(-1)?.[0]).toEqual({
  enabled: true,
  policyVersion: 'ladder-v1',
  metricKey: 'douyin.play_count',
  tiers: [{ threshold: 10000, payoutYuan: 50 }],
})
```

Also assert: freebie disables the ladder toggle; enabled ladder disables the freebie input; one tier cannot be deleted; the add button is absent at 20 tiers.

- [ ] **Step 2: Run the new test and verify missing controls**

```bash
npx vitest run --config vitest.config.ts src/views/grassland/components/MerchantTaskForm.commission.test.ts
```

Expected: FAIL because the editor does not exist.

- [ ] **Step 3: Implement whole-value events**

Import `CommissionLadderFormData`, add `commissionLadder` to `TaskFormData`, and add this emit:

```ts
'update:commission-ladder': [value: CommissionLadderFormData]
```

Add immutable helpers:

```ts
function emitCommissionLadder(value: CommissionLadderFormData): void {
  emit('update:commission-ladder', value)
}

function patchCommissionLadder(patch: Partial<CommissionLadderFormData>): void {
  emitCommissionLadder({ ...props.form.commissionLadder, ...patch })
}

function patchCommissionTier(index: number, patch: Partial<{ threshold: number; payoutYuan: number }>): void {
  emitCommissionLadder({
    ...props.form.commissionLadder,
    tiers: props.form.commissionLadder.tiers.map((tier, tierIndex) =>
      tierIndex === index ? { ...tier, ...patch } : tier),
  })
}

function addCommissionTier(): void {
  if (props.form.commissionLadder.tiers.length >= 20) return
  const last = props.form.commissionLadder.tiers.at(-1)
  emitCommissionLadder({
    ...props.form.commissionLadder,
    tiers: [...props.form.commissionLadder.tiers, {
      threshold: (last?.threshold ?? 0) + 1,
      payoutYuan: last?.payoutYuan ?? 0,
    }],
  })
}

function removeCommissionTier(index: number): void {
  if (props.form.commissionLadder.tiers.length <= 1) return
  emitCommissionLadder({
    ...props.form.commissionLadder,
    tiers: props.form.commissionLadder.tiers.filter((_, tierIndex) => tierIndex !== index),
  })
}
```

Render an enable checkbox, metric input, numbered threshold/payout inputs, and accessible add/remove buttons. Hide `policyVersion`. Disable the ladder checkbox for freebie tasks and disable the freebie input when the ladder is enabled.

- [ ] **Step 4: Run form regressions and commit**

```bash
npx vitest run --config vitest.config.ts \
  src/views/grassland/components/MerchantTaskForm.commission.test.ts \
  src/views/grassland/components/MerchantTaskForm.freebie.test.ts \
  src/views/grassland/components/MerchantTaskForm.interaction.test.ts
git add src/views/grassland/components/MerchantTaskForm.vue src/views/grassland/components/MerchantTaskForm.commission.test.ts
git commit -m "feat(frontend): add commission ladder task editor"
```

Expected: all three test files pass before committing.

### Task 3: Workbench Persistence And Hydration

**Files:**
- Modify: `src/views/grassland/GrasslandWorkbench.vue`
- Modify: `src/components/GrasslandWorkbench.test.ts`

- [ ] **Step 1: Add failing publish and validation tests**

Using the existing `stubFetch`, mount, log in, fill title/bounty/ladder fields, submit, and inspect:

```ts
const createCall = calls.find(([url, init]) => url === '/api/tasks' && init?.method === 'POST')
expect(JSON.parse(String(createCall?.[1]?.body))).toEqual(expect.objectContaining({
  bountyCents: 10_000,
  requirements: expect.objectContaining({
    commissionLadder: {
      policyVersion: 'ladder-v1',
      metricKey: 'douyin.play_count',
      tiers: [{ threshold: 10_000, payoutCents: 5_000 }],
    },
  }),
}))
```

Add an excessive-payout case asserting no POST and the notice `最高档佣金不能超过任务赏金`.

- [ ] **Step 2: Run and verify the missing-state failure**

```bash
npx vitest run --config vitest.config.ts src/components/GrasslandWorkbench.test.ts
```

Expected: FAIL because workbench state and payload omit the ladder.

- [ ] **Step 3: Wire state, validation, payload, reset, and hydration**

Import the ladder functions and type. Add `commissionLadder: emptyCommissionLadderForm()` to initial/reset state. Add:

```ts
function updateCommissionLadder(value: CommissionLadderFormData): void {
  taskForm.value.commissionLadder = value
}

function validateTaskCommissionLadder(bountyCents: number, freebieDepositCents: number): boolean {
  const error = getCommissionLadderValidationError(
    taskForm.value.commissionLadder,
    bountyCents,
    freebieDepositCents,
  )
  if (error) setNotice(error)
  return error == null
}
```

Call validation before every create/update/revise request. In `taskRequirements()` compute once:

```ts
const commissionLadder = buildCommissionLadderPayload(taskForm.value.commissionLadder)
```

Then include:

```ts
...(commissionLadder ? { commissionLadder } : {}),
```

Use `commissionLadderFormFromTask(task.requirements?.commissionLadder)` in both edit functions and bind:

```vue
@update:commission-ladder="updateCommissionLadder"
```

- [ ] **Step 4: Add revision preservation coverage**

Return a published task with `policyVersion: 'legacy-v3'`, click `编辑`, save, and assert the `/revise` body preserves `legacy-v3` while the version remains invisible in the UI.

- [ ] **Step 5: Run focused tests and commit**

Run Tasks 2 and 3 test commands. Expected: PASS.

```bash
git add src/views/grassland/GrasslandWorkbench.vue src/components/GrasslandWorkbench.test.ts
git commit -m "feat(frontend): persist commission ladders in task flows"
```

### Task 4: Shared Ladder Summary

**Files:**
- Create: `src/views/grassland/components/CommissionLadderSummary.vue`
- Create: `src/views/grassland/components/CommissionLadderSummary.test.ts`
- Modify: `src/views/grassland/components/RecommenderTaskHall.vue`
- Modify: `src/views/grassland/GrasslandWorkbench.vue`

- [ ] **Step 1: Write the failing summary test**

Mount the new component with two unsorted tiers and assert:

```ts
expect(wrapper.text()).toContain('阶梯佣金')
expect(wrapper.text()).toContain('douyin.play_count')
expect(wrapper.text()).toContain('¥50.00–¥100.00')
expect(wrapper.text()).toContain('10,000 → ¥50.00')
expect(wrapper.text()).toContain('50,000 → ¥100.00')
expect(wrapper.text()).toContain('固定佣金，不累加')
```

- [ ] **Step 2: Run and verify the missing-component failure**

```bash
npx vitest run --config vitest.config.ts src/views/grassland/components/CommissionLadderSummary.test.ts
```

Expected: FAIL resolving `CommissionLadderSummary.vue`.

- [ ] **Step 3: Implement the reusable summary**

Use this component contract and derived state:

```ts
import { computed } from 'vue'
import type { CommissionLadder } from '../../../types/grassland'

const props = withDefaults(defineProps<{
  ladder: CommissionLadder
  compact?: boolean
}>(), { compact: false })

const tiers = computed(() => [...props.ladder.tiers].sort((left, right) => left.threshold - right.threshold))
const payoutRange = computed(() => {
  const first = tiers.value[0]?.payoutCents ?? 0
  const last = tiers.value.at(-1)?.payoutCents ?? 0
  return `¥${(first / 100).toFixed(2)}–¥${(last / 100).toFixed(2)}`
})

function money(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`
}
```

Render a `阶梯佣金` tag, `metricKey`, payout range, and native `<details>` containing every `threshold → payout` line plus `固定佣金，不累加`. In compact mode keep the same semantics but use smaller CSS spacing.

- [ ] **Step 4: Integrate merchant and recommender views**

In `RecommenderTaskHall.vue`, render the summary in the bounty cell when `t.requirements.commissionLadder` exists, before the existing maximum bounty. In the merchant task list, render the compact summary beside the task status and bounty tag.

- [ ] **Step 5: Run focused tests and commit**

Run the summary test, all MerchantTaskForm tests, and `GrasslandWorkbench.test.ts`. Expected: PASS and ordinary/freebie task text unchanged.

```bash
git add src/views/grassland/components/CommissionLadderSummary.vue src/views/grassland/components/CommissionLadderSummary.test.ts src/views/grassland/components/RecommenderTaskHall.vue src/views/grassland/GrasslandWorkbench.vue
git commit -m "feat(frontend): display commission ladder terms"
```

### Task 5: Confirmation Request Contract

**Files:**
- Modify: `src/composables/useGrasslandMarketplace.ts`
- Modify: `src/composables/useGrassland.test.ts`

- [ ] **Step 1: Write failing request-contract tests**

```ts
test('阶梯佣金确认发送商家申报指标', async () => {
  const spy = mockFetchData({ applicationId: 'a-1', status: 'confirmed' })
  const { confirmEngagement } = useGrassland()
  await confirmEngagement('t-1', 'a-1', 50_000)
  expect(spy.mock.calls[0][0]).toBe('/api/tasks/t-1/applications/a-1/confirm')
  expect(JSON.parse(String((spy.mock.calls[0][1] as RequestInit).body))).toEqual({
    confirmedMetricValue: 50_000,
  })
})

test('固定佣金确认保持无请求体', async () => {
  const spy = mockFetchData({ applicationId: 'a-1', status: 'confirmed' })
  const { confirmEngagement } = useGrassland()
  await confirmEngagement('t-1', 'a-1')
  expect((spy.mock.calls[0][1] as RequestInit).body).toBeUndefined()
})
```

- [ ] **Step 2: Run and verify the signature failure**

```bash
npx vitest run --config vitest.config.ts src/composables/useGrassland.test.ts
```

Expected: FAIL because `confirmEngagement` accepts two arguments.

- [ ] **Step 3: Implement the optional request body**

```ts
async function confirmEngagement(
  taskId: string,
  appId: string,
  confirmedMetricValue?: number,
): Promise<boolean> {
  const result = await run(async () => {
    const response = await fetch(`/api/tasks/${taskId}/applications/${appId}/confirm`, {
      method: 'POST',
      credentials: 'include',
      ...(confirmedMetricValue === undefined ? {} : {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmedMetricValue }),
      }),
    })
    if (!response.ok) {
      throw new Error(await readError(response, `确认失败（${response.status}）`))
    }
    return true
  })
  return result === true
}
```

- [ ] **Step 4: Run the composable tests and commit**

Expected: `useGrassland.test.ts` passes.

```bash
git add src/composables/useGrasslandMarketplace.ts src/composables/useGrassland.test.ts
git commit -m "feat(frontend): send commission metric on confirmation"
```

### Task 6: Merchant Metric Declaration And Payout Preview

**Files:**
- Modify: `src/views/grassland/GrasslandWorkbench.vue`
- Modify: `src/components/GrasslandWorkbench.test.ts`

- [ ] **Step 1: Write failing confirmation UI tests**

Return a selected published ladder task and accepted application. Assert:

```ts
expect(wrapper.get('[aria-label="实际指标 douyin.play_count a-1"]').exists()).toBe(true)
expect(wrapper.text()).toContain('预计结算 ¥0.00')
```

Set the value to `50000`, assert `预计结算 ¥100.00`, click `确认履约`, and assert the POST body is `{ confirmedMetricValue: 50000 }`. Clear it and assert confirmation is disabled. Add a fixed-task case with no metric input and no request body.

- [ ] **Step 2: Run and verify the missing-UI failure**

```bash
npx vitest run --config vitest.config.ts src/components/GrasslandWorkbench.test.ts
```

Expected: FAIL because metric state and preview do not exist.

- [ ] **Step 3: Add state and exact derived helpers**

```ts
const confirmedMetricInputs = ref<Record<string, string>>({})

function selectedCommissionLadder() {
  return selectedTask.value?.requirements?.commissionLadder ?? null
}

function confirmedMetricResult(applicationId: string) {
  return parseConfirmedMetricValue(confirmedMetricInputs.value[applicationId] ?? '')
}

function previewCommissionCents(applicationId: string): number {
  const ladder = selectedCommissionLadder()
  const parsed = confirmedMetricResult(applicationId)
  return ladder && parsed.value != null
    ? calculateCommissionPayoutCents(ladder, parsed.value)
    : 0
}
```

Update `confirm(app)`:

```ts
const ladder = selectedCommissionLadder()
const parsedMetric = ladder ? confirmedMetricResult(app.id) : { value: null, error: null }
if (parsedMetric.error) {
  setNotice(parsedMetric.error)
  return
}
const started = await grassland.confirmEngagement(
  app.taskId,
  app.id,
  ladder ? parsedMetric.value! : undefined,
)
if (!started) {
  outcomes.value = { ...outcomes.value, [app.id]: '' }
  return
}
if (ladder) {
  const next = { ...confirmedMetricInputs.value }
  delete next[app.id]
  confirmedMetricInputs.value = next
}
```

Keep the existing settlement polling code after this block.

- [ ] **Step 4: Render the declaration input and preview**

For accepted applications on ladder tasks, render `type="number"`, `min="0"`, `step="1"`, the dynamic aria label, current validation error, and `预计结算 ¥X`. Disable `确认履约` when a ladder exists and parsing returns an error. Fixed tasks retain the current button.

- [ ] **Step 5: Run focused regressions and commit**

Run `GrasslandWorkbench.test.ts`, `useGrassland.test.ts`, the three MerchantTaskForm files, and `CommissionLadderSummary.test.ts`. Expected: PASS.

```bash
git add src/views/grassland/GrasslandWorkbench.vue src/components/GrasslandWorkbench.test.ts
git commit -m "feat(frontend): complete commission ladder confirmation flow"
```

### Task 7: Documentation And Full Verification

**Files:**
- Modify: `docs/草场开发进度与续接指南.md`

- [ ] **Step 1: Update #25 after fresh evidence exists**

Mark stable row #25 completed. Record task configuration, summary display, merchant metric declaration, payout preview, fixed-task compatibility, focused tests, full Vitest, typecheck, and build evidence. Remove #25 from the open execution queue without renumbering it.

- [ ] **Step 2: Run diff checks and focused tests**

```bash
git diff --check
npx vitest run --config vitest.config.ts \
  src/views/grassland/components/commission-ladder.test.ts \
  src/views/grassland/components/MerchantTaskForm.commission.test.ts \
  src/views/grassland/components/MerchantTaskForm.freebie.test.ts \
  src/views/grassland/components/MerchantTaskForm.interaction.test.ts \
  src/views/grassland/components/CommissionLadderSummary.test.ts \
  src/components/GrasslandWorkbench.test.ts \
  src/composables/useGrassland.test.ts
```

Expected: exit 0 with every focused test passing.

- [ ] **Step 3: Run full frontend gates**

```bash
npm run test
npm run typecheck
npm run build
```

Expected: each exits 0; the known `App.test.ts` KeepAlive case remains skipped and no new skips are introduced.

- [ ] **Step 4: Inspect the final scope**

```bash
git diff --check
git status --short
git diff --stat
```

Expected: only planned #25 frontend, test, plan, and progress-document files changed.

- [ ] **Step 5: Commit the verified delivery evidence**

```bash
git add docs/草场开发进度与续接指南.md
git commit -m "docs: mark issue 25 commission ladder UI complete"
```

Record exact test counts and commit hashes in the handoff before beginning #26.
