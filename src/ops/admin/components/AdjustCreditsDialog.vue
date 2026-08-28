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
            <input :value="amount" type="number" class="field-input" placeholder="例如：10 或 -5" @input="$emit('update:amount', Number(($event.target as HTMLInputElement).value))" />
          </label>

          <label class="field-label">
            备注
            <input :value="note" type="text" class="field-input" placeholder="例如：手动充值" maxlength="200" @input="$emit('update:note', ($event.target as HTMLInputElement).value)" />
          </label>

          <p v-if="error" class="error-msg">{{ error }}</p>

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
defineProps<{
  target: { email: string; balance: number } | null
  amount: number
  note: string
  error: string
  adjusting: boolean
}>()

defineEmits<{
  close: []
  'update:amount': [value: number]
  'update:note': [value: string]
  confirm: []
}>()
</script>
