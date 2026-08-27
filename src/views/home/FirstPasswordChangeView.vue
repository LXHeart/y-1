<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

/**
 * 首登强制改密页（任务书 #48）。
 *
 * 主体直建/重置密码的子账号拿到的是线下转交的一次性初始密码——登录后先改密再进业务面。
 * 服务端在 edge 有 428 硬闸兜底（/api/auth/** 豁免），本页是同一约束的体验层。
 */
const auth = useAuthStore()
const router = useRouter()

const newPassword = ref('')
const confirmPassword = ref('')
const error = ref('')
const submitting = ref(false)

async function submit(): Promise<void> {
  error.value = ''
  if (newPassword.value.length < 8) {
    error.value = '新密码至少 8 位'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    const ok = await auth.changePassword(newPassword.value)
    if (ok) await router.push({ name: 'home' })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '修改密码失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="fpc" aria-labelledby="fpc-title">
    <div class="fpc-card">
      <p class="fpc-kicker">安全设置</p>
      <h1 id="fpc-title">请先设置你的新密码</h1>
      <p class="fpc-copy">
        当前账号由管理员创建或重置了密码。为保护账号安全，请先设置只有你知道的新密码，
        之后即可正常使用平台。
      </p>

      <form class="gl-field" @submit.prevent="submit">
        <label>
          <span>新密码（至少 8 位）</span>
          <input v-model="newPassword" type="password" autocomplete="new-password" required />
        </label>
        <label>
          <span>确认新密码</span>
          <input v-model="confirmPassword" type="password" autocomplete="new-password" required />
        </label>
        <p v-if="error" class="fpc-error" role="alert">{{ error }}</p>
        <button type="submit" class="gl-btn-primary" :disabled="submitting">
          {{ submitting ? '保存中…' : '保存并继续' }}
        </button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.fpc { display: flex; justify-content: center; padding: 48px 16px; }
.fpc-card { width: min(460px, 100%); display: flex; flex-direction: column; gap: 12px; }
.fpc-kicker { margin: 0; font-size: 12px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--color-accent); }
.fpc-card h1 { margin: 0; font-family: var(--font-display, inherit); font-size: 24px; color: var(--color-text); }
.fpc-copy { margin: 0; font-size: 13px; opacity: 0.72; line-height: 1.6; }
.fpc-error { margin: 0; font-size: 13px; color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 12%, transparent); padding: 7px 11px; border-radius: var(--radius-sm); }
.fpc form { display: flex; flex-direction: column; gap: 10px; }
.fpc form button { margin-top: 6px; align-self: flex-start; }
</style>
