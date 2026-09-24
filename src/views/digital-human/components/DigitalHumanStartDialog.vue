<template>
  <GlModal v-if="open" title="开始数字人会话" wide scroll :persistent="submitting" @close="emit('close')">
    <div v-if="preflight" class="dh-start-dialog" data-testid="dh-start-dialog">
      <h3 class="dh-start-section-title">费用与来源（按实际调用结算）</h3>
      <table class="gl-table dh-billing-table">
        <thead>
          <tr>
            <th scope="col">环节</th>
            <th scope="col">来源</th>
            <th scope="col">承担</th>
            <th scope="col">预估上限</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in preflight.billingItems" :key="item.stage">
            <th scope="row">{{ stageLabel(item.stage) }}</th>
            <td>{{ sourceLabel(item) }}</td>
            <td>{{ chargeLabel(item.chargeTo) }}</td>
            <td>
              <span v-if="item.estimatedCents != null" class="gl-num">{{ formatYuan(item.estimatedCents) }}</span>
              <span v-else>按你的自有账户计费</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="dh-hint" data-testid="dh-start-cost-cap">
        本场成本授权上限 {{ formatYuan(preflight.platformCostCapCents) }}；积分换算按已展示政策；配音与渲染当前由平台补贴；按实际调用结算。
        <template v-if="hasOwnText">文本走你指定的模型账户，费用以该服务为准。</template>
      </p>

      <h3 class="dh-start-section-title">时限与规则</h3>
      <ul class="dh-start-rules">
        <li>单场最长 {{ durationMinutes }} 分钟，超时自动结束；空闲 {{ idleMinutes }} 分钟无活动将提示并可能结束。</li>
        <li>未派发的请求取消不产生该项费用；已开始生成后打断，按实际已发生的用量结算。</li>
        <li>渲染为第三方服务、平台补贴提供；平台实际成本计入本场授权上限。</li>
      </ul>

      <label class="dh-start-consent">
        <input v-model="saveTranscript" type="checkbox" data-testid="dh-start-save-transcript" />
        <span>保存本次会话的字幕文本（默认不保存；仅保存最终文字，不含语音）</span>
      </label>
    </div>
    <p v-else class="dh-hint" role="status">正在获取报价…</p>
    <p v-if="error" class="gl-alert gl-alert-error" role="alert" data-testid="dh-start-error">{{ error }}</p>

    <template #actions>
      <button type="button" class="gl-btn-secondary" :disabled="submitting" @click="emit('close')">取消</button>
      <button
        type="button"
        class="gl-btn-primary"
        :disabled="submitting || !preflight"
        data-testid="dh-start-confirm"
        @click="emit('confirm', { saveTranscript })"
      >
        {{ submitting ? '正在创建…' : '确认并开始' }}
      </button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：只展示预检报价/规则/保存同意；create 由父级在 confirm 后执行
// （E-02 只证明「无确认不 create」，实际 create 接线随 E-03 useDigitalHumanSession）。
import { computed, ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import { formatYuan } from '../../../lib/money'
import type { BillingItem, Preflight } from '../../../types/digital-human'

const props = defineProps<{
  preflight: Preflight | null
  open: boolean
  submitting: boolean
  error: string | null
}>()

const emit = defineEmits<{
  (e: 'confirm', payload: { saveTranscript: boolean }): void
  (e: 'close'): void
}>()

// 默认不保存（K03 API08/K11）；每次打开重置为 false。
const saveTranscript = ref(false)
watch(() => props.open, (open) => {
  if (open) saveTranscript.value = false
})

const hasOwnText = computed(() =>
  props.preflight?.billingItems.some((item) => item.stage === 'llm' && item.chargeTo === 'provider_direct'))

const durationMinutes = computed(() =>
  props.preflight ? Math.round(props.preflight.limits.sessionDurationMs / 60000) : 10)
const idleMinutes = computed(() =>
  props.preflight ? Math.round(props.preflight.limits.idleTimeoutMs / 60000) : 2)

function stageLabel(stage: BillingItem['stage']): string {
  switch (stage) {
    case 'llm': return '对话文本'
    case 'stt': return '语音转写'
    case 'tts': return '配音'
    case 'render': return '数字人渲染'
    default: return stage
  }
}

function sourceLabel(item: BillingItem): string {
  if (item.stage === 'llm' && item.chargeTo === 'provider_direct') {
    return `${item.modelLabel}（你指定的模型）`
  }
  return item.modelLabel
}

function chargeLabel(chargeTo: BillingItem['chargeTo']): string {
  switch (chargeTo) {
    case 'user': return '你的积分'
    case 'platform': return '平台补贴'
    case 'provider_direct': return '你的自有账户'
    default: return chargeTo
  }
}
</script>

<style scoped>
.dh-start-dialog { display: grid; gap: var(--space-sm); }
.dh-start-section-title {
  margin: 0; font-size: var(--type-card-title); font-family: var(--font-display);
  font-weight: var(--weight-heading); color: var(--color-text);
}
.dh-billing-table { margin: 0; }
.dh-start-rules {
  margin: 0; padding-left: 1.2em; display: grid; gap: var(--space-xxs);
  font-size: var(--type-body-sm); color: var(--color-text-secondary);
}
.dh-start-consent {
  display: flex; gap: var(--space-xs); align-items: flex-start;
  font-size: var(--type-body-sm); color: var(--color-text-secondary);
}
</style>
