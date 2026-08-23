<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import type { MembershipRole, MyInvitation, StoreRole } from '../types/grassland'

/**
 * 「我的邀请」——被邀请人侧入口（`/api/me/invitations`）。
 *
 * 与组织侧的邀请记录是**两个视角、两套字段**：这边按登录账号的邮箱匹配，只看得到
 * 未过期的待接受邀请，带组织名、不带邮箱/状态。接受即成为该组织成员（角色由邀请方定）。
 *
 * 与商家/推荐官视角无关（任何账号都可能被邀请），故挂在角色切换之外。
 */

const emit = defineEmits<{ joined: [organizationId: string] }>()

const grassland = useGrassland()
const { currentUser } = useAuth()

const invitations = ref<MyInvitation[]>([])
const notice = ref('')

const ROLE_LABEL: Record<Exclude<MembershipRole, 'owner'>, string> = {
  admin: '管理员',
  member: '成员',
}
const STORE_ROLE_LABEL: Record<StoreRole, string> = {
  manager: '店长',
  staff: '店员',
}

/** 邀请角色标签：组织级与门店级并存，逐档回退。 */
function roleLabel(role: MyInvitation['role']): string {
  return ROLE_LABEL[role as Exclude<MembershipRole, 'owner'>]
    || STORE_ROLE_LABEL[role as StoreRole] || role
}

/** 邀请范围的展示名：门店级「组织 · 门店」，组织级只显示组织名。 */
function scopeLabel(invitation: MyInvitation): string {
  return invitation.storeId
    ? `${invitation.organizationName} · ${invitation.storeName || '门店'}`
    : invitation.organizationName
}

async function refresh(): Promise<void> {
  const list = await grassland.listMyInvitations()
  if (list) invitations.value = list
}

/**
 * 按**账号**重拉，而不是 onMounted 拉一次。
 *
 * 邀请是按登录邮箱匹配的，而本卡片在未登录时也已挂载（工作台不因登出而卸载）：
 * 只在 mounted 拉一次的话，在同一页面里登录后列表仍停在「暂无邀请」，必须手点刷新才出现——
 * 浏览器实测抓到的就是这个。登出则清空，避免把上一个账号的邀请留在界面上。
 */
watch(() => currentUser.value?.id, (accountId) => {
  if (accountId) {
    refresh()
  } else {
    invitations.value = []
    notice.value = ''
  }
}, { immediate: true })

async function accept(invitation: MyInvitation): Promise<void> {
  notice.value = ''
  const result = await grassland.acceptInvitation(invitation.id)
  if (!result) return
  // alreadyMember：本就是成员时后端不报错（幂等消费邀请），如实告知而不是假装刚加入
  notice.value = result.alreadyMember
    ? `你本来就是「${scopeLabel(invitation)}」的成员，邀请已关闭`
    : `已加入「${scopeLabel(invitation)}」（${roleLabel(invitation.role)}）`
  await refresh()
  emit('joined', invitation.organizationId)
}

async function decline(invitation: MyInvitation): Promise<void> {
  notice.value = ''
  const declined = await grassland.declineInvitation(invitation.id)
  if (declined === null) return  // 已过期/已处理等由 error 条呈现
  notice.value = `已谢绝「${scopeLabel(invitation)}」的邀请`
  await refresh()
}
</script>

<template>
  <article class="inv">
    <header class="inv-head">
      <h3>我的邀请<span v-if="invitations.length" class="inv-badge">{{ invitations.length }}</span></h3>
      <button type="button" class="inv-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="inv-alert inv-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="inv-alert inv-ok">{{ notice }}</p>

    <p v-if="invitations.length === 0" class="inv-hint">
      暂无待接受的邀请。商家主体或门店用你的注册邮箱发出邀请后，这里会出现。
    </p>
    <ul v-else class="inv-list">
      <li v-for="i in invitations" :key="i.id">
        <div class="inv-main">
          <strong>{{ scopeLabel(i) }}</strong>
          <span class="inv-tag">{{ i.storeId ? '门店' : '主体' }}</span>
          <span class="inv-tag">{{ roleLabel(i.role) }}</span>
        </div>
        <div class="inv-actions">
          <button type="button" :disabled="grassland.loading.value" @click="accept(i)">接受</button>
          <button type="button" class="inv-quiet" :disabled="grassland.loading.value" @click="decline(i)">谢绝</button>
        </div>
      </li>
    </ul>
  </article>
</template>

<style scoped>
.inv { display: flex; flex-direction: column; gap: 10px; }
.inv-head { display: flex; justify-content: space-between; align-items: center; }
.inv-head h3 { margin: 0; font-size: 15px; display: flex; align-items: center; gap: 6px; }
.inv-badge { font-size: 11px; padding: 1px 7px; border-radius: 9px; background: var(--color-accent); color: var(--color-on-accent); }
.inv-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.inv-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.inv-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.inv-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.inv-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.inv-list li { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.inv-main { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.inv-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); }
.inv-actions { display: flex; gap: 8px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.inv-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
