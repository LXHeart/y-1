<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'

/**
 * 「绑定邮箱」——子账号（主体直建、账号名登录）在此自助绑定邮箱（任务书 #49 D10）。
 *
 * 子账号建号时不填邮箱：登录标识是 `前缀-登录名`，email 列是占位符。绑定真邮箱后：
 * - 账号名与邮箱均可登录；
 * - 邮件类通知能送达（占位域外发被短路）。
 *
 * 两步强验证：发送验证码到目标邮箱 → 填码换绑（错码/被占 409 由后端挡）。
 */
const emit = defineEmits<{ bound: [email: string] }>()

const grassland = useGrassland()
const { currentUser } = useAuth()

const email = ref('')
const code = ref('')
const codeSent = ref(false)
const notice = ref('')

const hasEmail = computed(() => currentUser.value?.hasEmail !== false)
const boundEmail = computed(() => currentUser.value?.email ?? '')

async function sendCode(): Promise<void> {
  const target = email.value.trim()
  if (!target.includes('@')) return
  notice.value = ''
  const sent = await grassland.sendBindEmailCode(target)
  if (!sent) return
  codeSent.value = true
  notice.value = `验证码已发送到 ${target}`
}

async function bind(): Promise<void> {
  const target = email.value.trim()
  const verify = code.value.trim()
  if (!target.includes('@') || !verify) return
  notice.value = ''
  const done = await grassland.bindEmail(target, verify)
  if (!done) return
  notice.value = `已绑定 ${target}，账号名与邮箱现在都可以登录`
  if (currentUser.value) {
    currentUser.value.email = target
    currentUser.value.hasEmail = true
  }
  email.value = ''
  code.value = ''
  codeSent.value = false
  emit('bound', target)
}
</script>

<template>
  <article class="gl-tile bind-email-card" aria-label="绑定邮箱">
    <h4 class="bind-email-title">绑定邮箱</h4>

    <p v-if="hasEmail" class="bind-email-hint">
      当前邮箱：<code>{{ boundEmail }}</code>
    </p>
    <p v-else class="bind-email-hint bind-email-warn" role="note">
      你的账号由商家主体创建（账号名登录，未绑定邮箱）。绑定后可用邮箱登录、接收通知邮件。
    </p>

    <div class="bind-email-row">
      <input
        v-model.trim="email"
        type="email"
        class="bind-email-input"
        placeholder="要绑定的邮箱"
        :disabled="grassland.loading.value"
        @keyup.enter="codeSent ? bind() : sendCode()"
      />
      <button
        v-if="!codeSent"
        type="button"
        class="bind-email-btn"
        :disabled="grassland.loading.value || !email.includes('@')"
        @click="sendCode"
      >发送验证码</button>
    </div>
    <div v-if="codeSent" class="bind-email-row">
      <input
        v-model.trim="code"
        class="bind-email-input bind-email-code"
        inputmode="numeric"
        placeholder="邮箱验证码（6 位）"
        :disabled="grassland.loading.value"
        @keyup.enter="bind"
      />
      <button
        type="button"
        class="bind-email-btn bind-email-primary"
        :disabled="grassland.loading.value || !code"
        @click="bind"
      >绑定</button>
      <button type="button" class="bind-email-btn" :disabled="grassland.loading.value" @click="codeSent = false">
        重填邮箱
      </button>
    </div>

    <p v-if="notice" class="bind-email-hint" role="status">{{ notice }}</p>
    <p v-if="grassland.error.value" class="bind-email-hint bind-email-err" role="alert">{{ grassland.error.value }}</p>
  </article>
</template>

<style scoped>
.bind-email-card { display: flex; flex-direction: column; gap: 10px; }
.bind-email-title { margin: 0; font-size: 14px; }
.bind-email-hint { margin: 0; font-size: 12px; opacity: 0.72; }
.bind-email-warn { padding: 7px 10px; border-radius: var(--radius-sm); background: color-mix(in srgb, var(--color-warning) 14%, transparent); opacity: 0.9; }
.bind-email-err { color: var(--color-danger); opacity: 1; }
.bind-email-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.bind-email-input { flex: 1; min-width: 200px; }
.bind-email-code { max-width: 220px; }
.bind-email-btn { padding: 6px 14px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text); cursor: pointer; font-size: 13px; }
.bind-email-btn:hover:not(:disabled) { background: var(--color-surface-hover); }
.bind-email-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.bind-email-primary { background: var(--color-accent); border-color: var(--color-accent); color: var(--color-accent-contrast, #fff); }
</style>
