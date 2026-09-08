<template>
  <Teleport to="body">
    <div v-if="target" class="modal-overlay" @click.self="$emit('close')">
      <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="adjust-credits-title">
        <header class="modal-header">
          <h3 id="adjust-credits-title" class="modal-title">调整积分 — {{ target.email }}</h3>
          <button class="modal-close" type="button" @click="$emit('close')" aria-label="关闭">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
          </button>
        </header>

        <div class="modal-body">
          <p class="current-balance">当前积分：<strong>{{ target.balance }}</strong></p>

          <label class="field-label">
            调整数量（正数增加，负数减少）
            <input ref="amountInput" :value="amount" type="number" class="field-input" placeholder="例如：10 或 -5" @input="$emit('update:amount', ($event.target as HTMLInputElement).value)" @blur="$emit('blur-amount')" />
          </label>

          <label class="field-label">
            备注
            <input :value="note" type="text" class="field-input" placeholder="例如：手动充值" maxlength="200" @input="$emit('update:note', ($event.target as HTMLInputElement).value)" />
          </label>

          <p v-if="error" class="error-msg" role="alert">{{ error }}</p>

          <div class="modal-actions">
            <button class="btn-cancel" type="button" @click="$emit('close')">取消</button>
            <button class="btn-confirm" type="button" :disabled="adjusting" @click="$emit('confirm')">
              {{ adjusting ? '提交中...' : '确认调整' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

/**
 * 调整积分弹窗（任务书 #94 C94-03）：数量为受控字符串（空串/小数原样可见，由父层校验），
 * blur 时请父层校验；错误出现时聚焦数量输入。
 */
const props = defineProps<{
  target: { email: string; balance: number } | null
  amount: string
  note: string
  error: string
  adjusting: boolean
}>()

defineEmits<{
  close: []
  'update:amount': [value: string]
  'update:note': [value: string]
  'blur-amount': []
  confirm: []
}>()

const amountInput = ref<HTMLInputElement | null>(null)
// 校验错误出现 → 聚焦数量输入（§8 键盘可达）
watch(() => props.error, (message) => {
  if (message) amountInput.value?.focus()
})
</script>
