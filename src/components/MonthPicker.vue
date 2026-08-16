<script setup lang="ts">
import { computed } from 'vue'
import { formatMonthLabel, shiftMonth } from '../lib/month'

/**
 * 月份选择器（任务书 #29+#30 D8 共享件）。
 *
 * <p>两种模式：
 * <ul>
 *   <li>单月（{@code range=false}）：推荐官月度账单/商家月度账单，v-model 为单个 YYYY-MM。</li>
 *   <li>区间（{@code range=true}）：收入统计，v-model:from / v-model:to，跨度上限 {@code maxMonths}。</li>
 * </ul>
 * 只产 YYYY-MM 字符串（D2：时间戳展开在后端），并把跨度是否越限通过 {@code overLimit} 暴露给调用方。
 */
const props = withDefaults(defineProps<{
  range?: boolean
  maxMonths?: number
  from?: string
  to?: string
  modelValue?: string
}>(), {
  range: false,
  maxMonths: 12,
  from: '',
  to: '',
  modelValue: '',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'update:from', value: string): void
  (e: 'update:to', value: string): void
}>()

const single = computed({
  get: () => props.modelValue,
  set: (value: string) => emit('update:modelValue', value),
})

const fromValue = computed({
  get: () => props.from,
  set: (value: string) => emit('update:from', value),
})

const toValue = computed({
  get: () => props.to,
  set: (value: string) => emit('update:to', value),
})

/** 区间跨度（月数），供调用方与后端 12 个月上限对齐提示。 */
const spanMonths = computed(() => {
  if (!props.from || !props.to) return 0
  const [fy, fm] = props.from.split('-').map(Number)
  const [ty, tm] = props.to.split('-').map(Number)
  return ty * 12 + tm - (fy * 12 + fm) + 1
})

const overLimit = computed(() => spanMonths.value > props.maxMonths || spanMonths.value <= 0)

function shiftSingle(delta: number): void {
  if (!single.value) return
  emit('update:modelValue', shiftMonth(single.value, delta))
}

function shiftFrom(delta: number): void {
  if (!fromValue.value) return
  emit('update:from', shiftMonth(fromValue.value, delta))
}

function shiftTo(delta: number): void {
  if (!toValue.value) return
  emit('update:to', shiftMonth(toValue.value, delta))
}
</script>

<template>
  <div class="mp">
    <template v-if="!range">
      <button type="button" class="mp-btn" :disabled="!modelValue" @click="shiftSingle(-1)">←</button>
      <input v-model="single" type="month" class="mp-input" />
      <button type="button" class="mp-btn" :disabled="!modelValue" @click="shiftSingle(1)">→</button>
      <span v-if="modelValue" class="mp-label">{{ formatMonthLabel(modelValue) }}</span>
    </template>
    <template v-else>
      <div class="mp-field">
        <button type="button" class="mp-btn" :disabled="!from" @click="shiftFrom(-1)">←</button>
        <input v-model="fromValue" type="month" class="mp-input" />
        <button type="button" class="mp-btn" :disabled="!from" @click="shiftFrom(1)">→</button>
      </div>
      <span class="mp-sep">至</span>
      <div class="mp-field">
        <button type="button" class="mp-btn" :disabled="!to" @click="shiftTo(-1)">←</button>
        <input v-model="toValue" type="month" class="mp-input" />
        <button type="button" class="mp-btn" :disabled="!to" @click="shiftTo(1)">→</button>
      </div>
      <p v-if="overLimit" class="mp-warn" role="alert">
        月份跨度须在 1–{{ maxMonths }} 个月之间（当前 {{ spanMonths }}）
      </p>
    </template>
  </div>
</template>

<style scoped>
.mp { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mp-field { display: flex; align-items: center; gap: 4px; }
.mp-input {
  padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: 6px; font-size: 13px;
}
.mp-btn {
  padding: 5px 9px; border: 1px solid var(--color-border); background: transparent;
  color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px;
}
.mp-btn:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
.mp-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.mp-sep { font-size: 13px; opacity: 0.7; }
.mp-label { font-size: 13px; opacity: 0.75; }
.mp-warn { width: 100%; margin: 2px 0 0; font-size: 12px; color: var(--color-danger); }
</style>
