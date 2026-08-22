<script setup lang="ts">
import { computed, ref, watch } from 'vue'
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
/** 待确认的「登出其它所有设备」——影响面大，同样需要二次确认。 */
const confirmingOthers = ref(false)

/** 其它设备（非本机）的会话数——「一键登出其它」只在确有其它设备时出现。 */
const otherSessions = computed(() => sessions.value.filter((s) => !s.current))

/** 分页：测试与多设备日常登录下清单可能很长（每次登录一台），每页 5 条。 */
const PAGE_SIZE = 5
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(sessions.value.length / PAGE_SIZE)))
const pagedSessions = computed(() =>
  sessions.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

function clampPage(): void {
  if (page.value > pageCount.value) page.value = pageCount.value
}

const IDENTITY_LABEL: Record<IdentityType, string> = {
  merchant: '商家',
  recommender: '推荐官',
}

function identityLabel(type: IdentityType | null): string {
  return type ? IDENTITY_LABEL[type] || type : '消费者'
}

/** 登录会话的过期时刻用绝对日期：它回答「我哪天会被登出」，相对时间没有意义。 */
function expiryLabel(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `有效期至 ${date.toLocaleDateString()}`
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
  // Array.isArray 防御：测试/降级路径的桩可能回非数组信封，进 filter 会炸渲染。
  if (Array.isArray(list)) sessions.value = list
  clampPage()
}

// 与 MyInvitationsCard 同理：卡片在未登录时也已挂载，只在 mounted 拉一次会停在空列表。
watch(() => currentUser.value?.id, (accountId) => {
  sessions.value = []
  page.value = 1
  notice.value = ''
  confirmingSelf.value = ''
  confirmingOthers.value = false
  if (accountId) refresh()
}, { immediate: true })

async function revokeOthers(): Promise<void> {
  if (!confirmingOthers.value) {
    confirmingOthers.value = true
    return
  }
  notice.value = ''
  confirmingOthers.value = false
  const result = await grassland.revokeOtherSessions()
  if (!result) return
  notice.value = result.revoked > 0
    ? `已登出其它 ${result.revoked} 台设备`
    : '没有其它设备在线'
  await refresh()
}

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
      <div class="sess-head-actions">
        <button
          v-if="otherSessions.length > 0"
          type="button"
          :class="confirmingOthers ? 'sess-danger' : 'sess-quiet'"
          :disabled="grassland.loading.value"
          @click="revokeOthers"
        >{{ confirmingOthers ? `确认登出其它 ${otherSessions.length} 台设备` : '登出其它设备' }}</button>
        <button type="button" class="sess-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
      </div>
    </header>

    <p v-if="grassland.error.value" class="sess-alert sess-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="sess-alert sess-ok">{{ notice }}</p>

    <p v-if="sessions.length === 0" class="sess-hint">暂无记录。</p>
    <ul v-else class="sess-list" :class="{ 'sess-list-paged': pageCount > 1 }">
      <li v-for="s in pagedSessions" :key="s.sessionToken">
        <div class="sess-main">
          <span class="sess-name">
            {{ s.deviceLabel || (s.deviceId ? `设备 ${s.deviceId.slice(0, 8)}` : '未知设备') }}
            <span v-if="s.current" class="sess-badge">本机</span>
          </span>
          <span class="sess-meta" :title="`${identityLabel(s.activeIdentityType)} · ${s.ipAddress || '未知 IP'} · 最近活动 ${lastSeenLabel(s.lastSeenAt)}${expiryLabel(s.expiresAt) ? ' · ' + expiryLabel(s.expiresAt) : ''}`">
            {{ identityLabel(s.activeIdentityType) }} · {{ s.ipAddress || '未知 IP' }} ·
            最近活动 {{ lastSeenLabel(s.lastSeenAt) }}<template v-if="expiryLabel(s.expiresAt)">
              · {{ expiryLabel(s.expiresAt) }}</template>
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

    <div v-if="pageCount > 1" class="sess-pager">
      <button type="button" class="sess-quiet" :disabled="page <= 1" @click="page -= 1">上一页</button>
      <span class="sess-page gl-num">第 {{ page }} / {{ pageCount }} 页 · 共 {{ sessions.length }} 条</span>
      <button type="button" class="sess-quiet" :disabled="page >= pageCount" @click="page += 1">下一页</button>
    </div>

    <p class="sess-hint">
      撤销会让那台设备<strong>立即登出</strong>；「有效期至」是该设备登录态的自然过期日。
      列表为本账号当前有效的登录会话；从未切换过身份的设备显示为「消费者」，设备信息可能为未知。
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
/* 分页态锁定满页高度：行高统一（名称/元信息各一行、超长省略号，全文在 title）
 * 与列表 min-height（5 行 + 间隙）共同保证任意页同高——此前 meta 换行行数不等，
 * 翻到末页卡片高度塌陷、页间跳版。 */
.sess-list li { flex-wrap: nowrap; }
.sess-main { min-width: 0; flex: 1; }
.sess-name, .sess-meta { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; display: block; }
.sess-list li > button { flex-shrink: 0; }
.sess-list-paged { min-height: calc(5 * 58px + 4 * 6px); }
.sess-list-paged li { height: 58px; align-items: center; }
.sess-list li { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.sess-main { display: flex; flex-direction: column; gap: 2px; }
.sess-name { font-size: 13px; display: flex; align-items: center; gap: 6px; }
.sess-badge { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-accent); color: #fff; }
.sess-meta { font-size: 12px; opacity: 0.62; }
.sess-head-actions { display: flex; align-items: center; gap: 6px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.sess-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
.sess-danger { font-size: 12px; padding: 4px 10px; color: var(--color-danger); border-color: var(--color-danger); }
.sess-pager { display: flex; align-items: center; justify-content: center; gap: 10px; }
.sess-page { font-size: 12px; opacity: 0.7; }
</style>
