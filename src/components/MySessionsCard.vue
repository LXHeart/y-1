<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import type { IdentityType, LoginSession } from '../types/grassland'

/**
 * 「登录设备」——多设备会话视图与撤销（identity Slice 2I / HLD D-08）。
 *
 * 后端能力早已具备却一直零 UI：用户看不到自己在哪些设备上登录着，也就无从发现异常登录。
 *
 * 语义：
 * - 列表来自**登录会话**（登录即有行），没切换过身份的设备也在其中，只是显示为「消费者 / 未知设备」；
 * - **撤销 = 那台设备真的登出**（清活动身份 + 删登录会话）；撤销「本机」就是把自己登出，故需二次确认。
 */

const emit = defineEmits<{ loggedOut: [] }>()

const grassland = useGrassland()
const { currentUser, logout } = useAuth()

const sessions = ref<LoginSession[]>([])
const notice = ref('')
/** 待确认撤销的本机 session——撤销自己会登出，不做二次确认太容易误点。 */
const confirmingSelf = ref('')

const IDENTITY_LABEL: Record<IdentityType, string> = {
  merchant: '商家',
  recommender: '推荐官',
}

function identityLabel(type: IdentityType | null): string {
  return type ? IDENTITY_LABEL[type] || type : '消费者'
}

/** 只给相对时间，绝对时刻对「这是不是我刚才那次登录」没有帮助。 */
function lastSeenLabel(iso: string | null): string {
  if (!iso) return '未知'
  const diffMs = Date.now() - new Date(iso).getTime()
  if (Number.isNaN(diffMs)) return '未知'
  const minutes = Math.floor(diffMs / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}

async function refresh(): Promise<void> {
  const list = await grassland.listMySessions()
  if (list) sessions.value = list
}

// 与 MyInvitationsCard 同理：卡片在未登录时也已挂载，只在 mounted 拉一次会停在空列表。
watch(() => currentUser.value?.id, (accountId) => {
  sessions.value = []
  notice.value = ''
  confirmingSelf.value = ''
  if (accountId) refresh()
}, { immediate: true })

async function revoke(session: LoginSession): Promise<void> {
  if (session.current && confirmingSelf.value !== session.sessionToken) {
    confirmingSelf.value = session.sessionToken
    return
  }
  notice.value = ''
  const revoked = await grassland.revokeSession(session.sessionToken)
  if (revoked === null) return  // 403/404 等由 error 条呈现
  confirmingSelf.value = ''
  if (session.current) {
    // 后端已删掉本机登录会话，此刻 cookie 已失效：同步清掉前端登录态，
    // 否则界面还显示「已登录」，之后每个请求都 401，用户一头雾水。
    await logout()
    emit('loggedOut')
    return
  }
  notice.value = '该设备已登出'
  await refresh()
}
</script>

<template>
  <article class="sess">
    <header class="sess-head">
      <h3>登录设备<span v-if="sessions.length" class="sess-count">{{ sessions.length }}</span></h3>
      <button type="button" class="sess-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="sess-alert sess-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="sess-alert sess-ok">{{ notice }}</p>

    <p v-if="sessions.length === 0" class="sess-hint">暂无记录。</p>
    <ul v-else class="sess-list">
      <li v-for="s in sessions" :key="s.sessionToken">
        <div class="sess-main">
          <span class="sess-name">
            {{ s.deviceLabel || (s.deviceId ? `设备 ${s.deviceId.slice(0, 8)}` : '未知设备') }}
            <span v-if="s.current" class="sess-badge">本机</span>
          </span>
          <span class="sess-meta">
            {{ identityLabel(s.activeIdentityType) }} · {{ s.ipAddress || '未知 IP' }} ·
            最近活动 {{ lastSeenLabel(s.lastSeenAt) }}
          </span>
        </div>
        <button
          type="button"
          :class="s.current && confirmingSelf === s.sessionToken ? 'sess-danger' : 'sess-quiet'"
          :disabled="grassland.loading.value"
          @click="revoke(s)"
        >{{ s.current && confirmingSelf === s.sessionToken ? '确认登出本机' : '撤销' }}</button>
      </li>
    </ul>

    <p class="sess-hint">
      撤销会让那台设备<strong>立即登出</strong>。列表为本账号当前有效的登录会话；
      从未切换过身份的设备显示为「消费者」，设备信息可能为未知。
    </p>
  </article>
</template>

<style scoped>
.sess { display: flex; flex-direction: column; gap: 10px; }
.sess-head { display: flex; justify-content: space-between; align-items: center; }
.sess-head h3 { margin: 0; font-size: 15px; display: flex; align-items: center; gap: 6px; }
.sess-count { font-size: 11px; padding: 1px 7px; border-radius: 9px; background: var(--color-surface-strong); }
.sess-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.sess-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.sess-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.sess-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.sess-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.sess-list li { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.sess-main { display: flex; flex-direction: column; gap: 2px; }
.sess-name { font-size: 13px; display: flex; align-items: center; gap: 6px; }
.sess-badge { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-accent); color: #fff; }
.sess-meta { font-size: 12px; opacity: 0.62; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.sess-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
.sess-danger { font-size: 12px; padding: 4px 10px; color: var(--color-danger); border-color: var(--color-danger); }
</style>
