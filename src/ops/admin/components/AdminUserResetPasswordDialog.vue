<template>
  <GlModal v-if="open && user" title="重置密码" persistent @close="handleClose">
    <template v-if="!result">
      <p class="dialog-hint">确认重置 <strong>{{ user.email }}</strong> 的密码？</p>
      <ul class="consequence-list">
        <li>将生成一次性初始密码，仅本次展示，需线下交付本人</li>
        <li>该账号<b>全部登录会话立即失效</b>（含当前在线设备）</li>
        <li>首次登录将被强制修改密码</li>
      </ul>
    </template>
    <template v-else>
      <div class="reset-success">
        <p class="reset-title">新初始密码已生成</p>
        <div class="password-row">
          <code class="initial-password" data-testid="reset-initial-password">{{ result.initialPassword }}</code>
          <button class="copy-btn" type="button" data-testid="copy-reset-password" @click="copyPassword">
            {{ copied ? '已复制' : '复制密码' }}
          </button>
        </div>
        <p class="reset-warning">仅本次展示，关闭后不可再查；请立即复制并线下交付。该账号旧会话已全部失效，首登将强制改密。</p>
      </div>
    </template>
    <p v-if="error" class="error-msg" role="alert">{{ error }}</p>

    <template #actions>
      <template v-if="!result">
        <button class="btn-cancel" type="button" :disabled="submitting" @click="emit('close')">取消</button>
        <button class="btn-confirm" type="button" :disabled="submitting" data-testid="reset-dialog-confirm" @click="submit">
          {{ submitting ? '重置中...' : '重置密码' }}
        </button>
      </template>
      <button v-else class="btn-confirm" type="button" data-testid="reset-done" @click="emit('done')">
        完成
      </button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import { request } from '../../../composables/grassland-http'
import type { AdminUserRowData } from './AdminUserDetailDrawer.vue'

const props = defineProps<{
  open: boolean
  user: AdminUserRowData | null
}>()

const emit = defineEmits<{
  close: []
  done: []
}>()

interface ResetResult {
  initialPassword: string
}

const submitting = ref(false)
const error = ref('')
const result = ref<ResetResult | null>(null)
const copied = ref(false)

watch(() => props.open, (open) => {
  if (open) {
    error.value = ''
    result.value = null
    copied.value = false
  }
})

async function submit(): Promise<void> {
  if (!props.user || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    result.value = await request<ResetResult>(
      `/api/admin/users/${encodeURIComponent(props.user.id)}/reset-password`,
      { method: 'POST' },
      { fallbackError: '重置失败' },
    )
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '重置失败'
  } finally {
    submitting.value = false
  }
}

/** 已拿到明文时点遮罩不丢结果——只有显式按钮可关闭（persistent + 空实现）。 */
function handleClose(): void {
  if (submitting.value) return
  emit('close')
}

async function copyPassword(): Promise<void> {
  if (!result.value) return
  try {
    await navigator.clipboard.writeText(result.value.initialPassword)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // 降级：选中文本让管理员手动复制（MerchantAccountInitDialog 同款）
    const el = document.querySelector('[data-testid="reset-initial-password"]')
    if (!el) return
    const range = document.createRange()
    range.selectNodeContents(el)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  }
}
</script>

<style scoped>
.dialog-hint {
  margin: 0 0 10px;
  color: var(--color-text);
  font-size: 0.86rem;
  line-height: 1.6;
}

.consequence-list {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.6;
}

.reset-success {
  display: grid;
  gap: 10px;
  justify-items: start;
}

.reset-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--color-text);
}

.password-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.initial-password {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-sm);
  background: var(--color-surface-highlight);
  font-family: var(--font-mono);
  font-size: 0.9rem;
  letter-spacing: 0.06em;
  color: var(--color-text);
  user-select: all;
}

.copy-btn {
  flex-shrink: 0;
  min-height: 32px;
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent);
  font-size: 0.78rem;
  cursor: pointer;
}

.reset-warning {
  margin: 0;
  color: var(--color-warning);
  font-size: 0.78rem;
  line-height: 1.6;
}

.error-msg {
  margin: 12px 0 0;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent);
  color: var(--color-danger);
  font-size: 0.8rem;
}
</style>
