<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { GrasslandHttpError } from '../composables/grassland-http'
import { useAiOrgBudget } from '../composables/useAiOrgBudget'
import type { AiOrgBudget, AiOrgBudgetLimits, UpdateAiOrgBudgetInput } from '../types/grassland'

const props = defineProps<{ organizationId: string }>()
const api = useAiOrgBudget()

type LimitKey = keyof AiOrgBudgetLimits
type FormState = Record<LimitKey, string>

const LIMIT_KEYS: LimitKey[] = [
  'maxTokensPerRun', 'maxTokensDaily', 'maxTokensMonthly',
  'maxCentsPerRun', 'maxCentsDaily', 'maxCentsMonthly',
]
const form = reactive<FormState>({
  maxTokensPerRun: '', maxTokensDaily: '', maxTokensMonthly: '',
  maxCentsPerRun: '', maxCentsDaily: '', maxCentsMonthly: '',
})
const budget = ref<AiOrgBudget | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const conflict = ref(false)

const hasAnyLimit = computed(() => LIMIT_KEYS.some((key) => form[key] !== ''))

watch(() => props.organizationId, () => { void load() }, { immediate: true })

function applyBudget(value: AiOrgBudget): void {
  budget.value = value
  for (const key of LIMIT_KEYS) form[key] = value[key]?.toString() ?? ''
}

async function load(): Promise<void> {
  if (!props.organizationId) return
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    applyBudget(await api.getBudget(props.organizationId))
    conflict.value = false
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : 'AI 预算加载失败'
  } finally {
    loading.value = false
  }
}

function parseLimit(key: LimitKey): number | null {
  const raw = form[key].trim()
  if (!raw) return null
  const value = Number(raw)
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error('预算上限必须是大于或等于 0 的整数')
  }
  return value
}

function validateOrder(label: string, values: Array<number | null>): void {
  const populated = values.filter((value): value is number => value !== null)
  if (populated.some((value, index) => index > 0 && populated[index - 1] > value)) {
    throw new Error(`${label}上限必须满足单次 ≤ 每日 ≤ 每月`)
  }
}

function payload(): UpdateAiOrgBudgetInput {
  const limits = Object.fromEntries(LIMIT_KEYS.map((key) => [key, parseLimit(key)])) as unknown as AiOrgBudgetLimits
  validateOrder('Token', [limits.maxTokensPerRun, limits.maxTokensDaily, limits.maxTokensMonthly])
  validateOrder('金额', [limits.maxCentsPerRun, limits.maxCentsDaily, limits.maxCentsMonthly])
  return { expectedVersion: budget.value?.version ?? 0, ...limits }
}

async function save(): Promise<void> {
  if (!props.organizationId || saving.value) return
  error.value = ''
  notice.value = ''
  conflict.value = false
  let input: UpdateAiOrgBudgetInput
  try {
    input = payload()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '预算输入无效'
    return
  }
  saving.value = true
  try {
    const saved = await api.saveBudget(props.organizationId, input)
    applyBudget(saved)
    notice.value = saved.configured ? 'AI 预算已保存' : '已恢复为不限'
  } catch (caught: unknown) {
    if (caught instanceof GrasslandHttpError && caught.status === 409) {
      conflict.value = true
      error.value = '预算已被其他管理员修改，请重新载入后重试'
    } else {
      error.value = caught instanceof Error ? caught.message : 'AI 预算保存失败'
    }
  } finally {
    saving.value = false
  }
}

function formatCount(value: number | null): string {
  return value == null ? '暂无计量' : value.toLocaleString('zh-CN')
}

function formatCents(value: number | null): string {
  return value == null ? '暂无计量' : `${value.toLocaleString('zh-CN')} 分`
}
</script>

<template>
  <section class="budget-panel" aria-labelledby="ai-org-budget-title">
    <header class="budget-head">
      <div>
        <h3 id="ai-org-budget-title">AI 预算</h3>
        <p>{{ hasAnyLimit ? '空白项不设上限' : '未设置，当前不限' }}</p>
      </div>
      <button type="button" class="quiet" :disabled="loading || saving" @click="load">刷新</button>
    </header>

    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert success">{{ notice }}</p>
    <div v-if="conflict" class="conflict-actions">
      <button type="button" @click="load">重新载入</button>
    </div>
    <p v-if="budget?.overCurrentUsage" class="alert warning">
      当前用量已超过新上限，保存后将立即阻止后续 AI 运行。
    </p>

    <p v-if="loading && !budget" class="empty">正在加载预算...</p>
    <template v-else>
      <div class="budget-groups">
        <fieldset>
          <legend>Token 上限</legend>
          <label>单次<input v-model.trim="form.maxTokensPerRun" name="maxTokensPerRun" type="number" min="0" step="1" placeholder="不限" /></label>
          <label>每日<input v-model.trim="form.maxTokensDaily" name="maxTokensDaily" type="number" min="0" step="1" placeholder="不限" /></label>
          <label>每月<input v-model.trim="form.maxTokensMonthly" name="maxTokensMonthly" type="number" min="0" step="1" placeholder="不限" /></label>
        </fieldset>
        <fieldset>
          <legend>金额上限（分）</legend>
          <label>单次<input v-model.trim="form.maxCentsPerRun" name="maxCentsPerRun" type="number" min="0" step="1" placeholder="不限" /></label>
          <label>每日<input v-model.trim="form.maxCentsDaily" name="maxCentsDaily" type="number" min="0" step="1" placeholder="不限" /></label>
          <label>每月<input v-model.trim="form.maxCentsMonthly" name="maxCentsMonthly" type="number" min="0" step="1" placeholder="不限" /></label>
        </fieldset>
      </div>

      <section class="usage" aria-label="当前 AI 用量">
        <h4>当前用量</h4>
        <p v-if="!budget?.usage.measured" class="empty">暂无计量</p>
        <dl v-else>
          <div><dt>今日 Token</dt><dd>{{ formatCount(budget.usage.dailyTokens) }}</dd></div>
          <div><dt>本月 Token</dt><dd>{{ formatCount(budget.usage.monthlyTokens) }}</dd></div>
          <div><dt>今日金额</dt><dd>{{ formatCents(budget.usage.dailyCents) }}</dd></div>
          <div><dt>本月金额</dt><dd>{{ formatCents(budget.usage.monthlyCents) }}</dd></div>
        </dl>
      </section>

      <div class="actions">
        <span>0 表示立即停用对应范围，空白表示不限</span>
        <button type="button" class="primary" :disabled="saving || loading" @click="save">
          {{ saving ? '保存中...' : '保存预算' }}
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.budget-panel { display: grid; gap: 14px; }
.budget-head, .actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.budget-head h3, .usage h4 { margin: 0; font-size: 15px; letter-spacing: 0; }
.budget-head p, .actions span, .empty { margin: 3px 0 0; color: var(--color-text-muted); font-size: 12px; }
.budget-groups { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
fieldset { min-width: 0; margin: 0; padding: 12px; border: 1px solid var(--color-border); border-radius: 7px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
legend { padding: 0 5px; font-size: 13px; font-weight: 600; }
label { min-width: 0; display: grid; gap: 5px; color: var(--color-text-secondary); font-size: 12px; }
input { width: 100%; min-width: 0; box-sizing: border-box; padding: 7px 8px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); font: inherit; }
.usage { display: grid; gap: 8px; padding-top: 12px; border-top: 1px solid var(--color-border); }
dl { margin: 0; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
dl div { min-width: 0; padding: 8px; background: var(--color-surface-muted); border-radius: 6px; }
dt { color: var(--color-text-muted); font-size: 11px; } dd { margin: 3px 0 0; font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
button { min-height: 32px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); cursor: pointer; }
button:disabled { opacity: .5; cursor: wait; }.primary { border-color: var(--color-accent); background: var(--color-accent); color: #fff; }.quiet { color: var(--color-text-secondary); }
.alert { margin: 0; padding: 8px 10px; border-radius: 6px; font-size: 12px; }.error { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 12%, transparent); }.success { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 12%, transparent); }.warning { color: #9a6700; background: color-mix(in srgb, #d29922 16%, transparent); }
.conflict-actions { display: flex; justify-content: flex-end; }
@media (max-width: 760px) { .budget-groups, dl { grid-template-columns: 1fr; } fieldset { grid-template-columns: 1fr; } .actions { align-items: flex-start; flex-direction: column; } }
</style>
