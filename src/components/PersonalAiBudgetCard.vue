<template>
  <section class="personal-budget-card gl-tile gl-budget-card" aria-labelledby="personal-ai-budget-title">
    <header class="card-head gl-zone-head">
      <div>
        <h3 id="personal-ai-budget-title">个人 AI 预算</h3>
        <p class="gl-hint">约束你在无组织上下文下的独立创作调用量与平台消费；组织内任务走组织预算。留空 = 不限。</p>
      </div>
      <button type="button" :disabled="loading" @click="load">刷新</button>
    </header>

    <dl v-if="budget" class="usage gl-budget-usage">
      <div><dt>今日调用量</dt><dd>{{ formatCount(budget.usage.dailyTokens) }}</dd></div>
      <div><dt>今日消费</dt><dd>{{ formatCents(budget.usage.dailyCents) }}</dd></div>
      <div><dt>本月调用量</dt><dd>{{ formatCount(budget.usage.monthlyTokens) }}</dd></div>
      <div><dt>本月消费</dt><dd>{{ formatCents(budget.usage.monthlyCents) }}</dd></div>
    </dl>
    <p v-if="budget?.overCurrentUsage" class="form-error gl-alert gl-alert-error" role="alert">当前用量已超设定上限，独立创作 AI 能力可能已被硬停。</p>

    <form class="limits" @submit.prevent="save">
      <div class="limit-grid gl-budget-limits">
        <label v-for="key in LIMIT_KEYS" :key="key" class="field">
          <span>{{ LABELS[key] }}</span>
          <input v-model="form[key]" inputmode="numeric" placeholder="不限" />
        </label>
      </div>
      <p v-if="error" class="form-error gl-alert gl-alert-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="form-notice gl-alert gl-alert-ok" role="status">{{ notice }}</p>
      <div class="gl-actions">
        <button type="submit" class="gl-btn-primary" :disabled="saving || loading">{{ saving ? '保存中…' : '保存预算' }}</button>
      </div>
    </form>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { GrasslandHttpError } from '../composables/grassland-http'
import { useAiPersonalBudget } from '../composables/useAiPersonalBudget'
import type { AiOrgBudget, AiOrgBudgetLimits, UpdateAiOrgBudgetInput } from '../types/grassland'

type LimitKey = keyof AiOrgBudgetLimits

const LIMIT_KEYS: LimitKey[] = [
  'maxTokensPerRun', 'maxTokensDaily', 'maxTokensMonthly',
  'maxCentsPerRun', 'maxCentsDaily', 'maxCentsMonthly',
]
const LABELS: Record<LimitKey, string> = {
  maxTokensPerRun: '单次 Token 上限', maxTokensDaily: '每日 Token 上限', maxTokensMonthly: '每月 Token 上限',
  maxCentsPerRun: '单次金额上限（分）', maxCentsDaily: '每日金额上限（分）', maxCentsMonthly: '每月金额上限（分）',
}

const api = useAiPersonalBudget()
const budget = ref<AiOrgBudget | null>(null)
const form = reactive<Record<LimitKey, string>>({
  maxTokensPerRun: '', maxTokensDaily: '', maxTokensMonthly: '',
  maxCentsPerRun: '', maxCentsDaily: '', maxCentsMonthly: '',
})
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const hasAnyLimit = computed(() => LIMIT_KEYS.some((key) => form[key] !== ''))

function applyBudget(value: AiOrgBudget): void {
  budget.value = value
  for (const key of LIMIT_KEYS) form[key] = value[key]?.toString() ?? ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    applyBudget(await api.getBudget())
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

async function save(): Promise<void> {
  if (saving.value) return
  error.value = ''
  notice.value = ''
  let input: UpdateAiOrgBudgetInput
  try {
    const limits = Object.fromEntries(LIMIT_KEYS.map((key) => [key, parseLimit(key)])) as unknown as AiOrgBudgetLimits
    validateOrder('Token', [limits.maxTokensPerRun, limits.maxTokensDaily, limits.maxTokensMonthly])
    validateOrder('金额', [limits.maxCentsPerRun, limits.maxCentsDaily, limits.maxCentsMonthly])
    input = { expectedVersion: budget.value?.version ?? 0, ...limits }
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '预算输入无效'
    return
  }
  saving.value = true
  try {
    const saved = await api.saveBudget(input)
    applyBudget(saved)
    notice.value = saved.configured || hasAnyLimit.value ? '个人 AI 预算已保存' : '已恢复为不限'
  } catch (caught: unknown) {
    if (caught instanceof GrasslandHttpError && caught.status === 409) {
      error.value = '预算已被修改（可能在其他设备），请刷新后重试'
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

void load()
</script>
