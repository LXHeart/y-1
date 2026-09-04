<template>
  <GlModal v-if="open && user" :title="mode === 'suspend' ? '停用账号' : '恢复账号'" persistent @close="emit('close')">
    <template v-if="mode === 'suspend'">
      <p class="dialog-hint">确认停用 <strong>{{ user.email }}</strong> ？停用后：</p>
      <ul class="consequence-list">
        <li>该账号立即无法登录与调用（既有会话即时失效）</li>
        <li v-if="ownedOrgNames">该账号是商家 owner，其名下组织将<b>一并冻结</b>：{{ ownedOrgNames }}</li>
        <li v-if="ownedOrgNames">恢复需管理员<b>分别恢复</b>账号与组织</li>
      </ul>
    </template>
    <template v-else>
      <p class="dialog-hint">确认恢复 <strong>{{ user.email }}</strong> 的登录与使用？</p>
      <p class="dialog-hint">其名下已冻结组织不随本操作恢复，需要时请在账号详情中单独恢复组织。</p>
    </template>
    <p v-if="error" class="error-msg" role="alert">{{ error }}</p>

    <template #actions>
      <button class="btn-cancel" type="button" :disabled="submitting" @click="emit('close')">取消</button>
      <button class="btn-confirm" :class="{ danger: mode === 'suspend' }" type="button"
        :disabled="submitting" data-testid="suspend-dialog-confirm" @click="submit">
        {{ submitting ? '提交中...' : mode === 'suspend' ? '确认停用' : '确认恢复' }}
      </button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import { request } from '../../../composables/grassland-http'
import type { AdminUserRowData } from './AdminUserDetailDrawer.vue'

const props = defineProps<{
  open: boolean
  user: AdminUserRowData | null
  mode: 'suspend' | 'restore'
}>()

const emit = defineEmits<{
  close: []
  done: []
}>()

const submitting = ref(false)
const error = ref('')
const ownedOrgNames = computed(() => props.user?.identities?.ownedOrgNames || '')

watch(() => props.open, (open) => {
  if (open) error.value = ''
})

async function submit(): Promise<void> {
  if (!props.user || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await request(`/api/admin/users/${encodeURIComponent(props.user.id)}/${props.mode}`, { method: 'POST' },
      { fallbackError: '操作失败' })
    emit('done')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    submitting.value = false
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

.consequence-list li::marker {
  color: var(--color-danger);
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
