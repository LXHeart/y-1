<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import type { ApplicationSettlement } from '../../../types/grassland'

const props = defineProps<{ state?: ApplicationSettlement | null }>()
const now = ref(Date.now())
const timer = setInterval(() => { now.value = Date.now() }, 30000)
onScopeDispose(() => clearInterval(timer))
const countdown = computed(() => {
  const due = Date.parse(props.state?.nextActionDueAt || '')
  if (!Number.isFinite(due)) return ''
  const minutes = Math.ceil((due - now.value) / 60000)
  if (minutes <= 0) return '已到截止时间'
  if (minutes < 60) return `剩余 ${minutes} 分钟`
  if (minutes < 1440) return `剩余 ${Math.ceil(minutes / 60)} 小时`
  return `剩余 ${Math.ceil(minutes / 1440)} 天`
})
const benefitLabels: Record<string, string> = {
  pending_booking: '待预约', booked: '已预约', fulfilled: '已兑现',
  merchant_defaulted: '商家失约', cancelled: '已取消',
}
</script>

<template>
  <div class="next-action" data-testid="engagement-next-action">
    <span>{{ state?.nextActionLabel || '状态待刷新' }}</span>
    <time v-if="state?.nextActionDueAt" :datetime="state.nextActionDueAt" :title="new Date(state.nextActionDueAt).toLocaleString('zh-CN')" class="gl-num">{{ countdown }}</time>
    <span v-if="state?.blockedReason" class="gl-hint">{{ state.blockedReason }}</span>
    <span v-if="state?.benefitStatus" class="badge badge-neutral">体验权益：{{ benefitLabels[state.benefitStatus] || state.benefitStatus }}</span>
  </div>
</template>

<style scoped>
.next-action { display: flex; flex-direction: column; gap: var(--space-xs); overflow-wrap: anywhere; font-size: var(--text-sm); }
</style>
