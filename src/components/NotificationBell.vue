<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import NotificationPanel from './NotificationPanel.vue'
import { useAuth } from '../composables/useAuth'
import { useNotifications } from '../composables/useNotifications'
import type { NotificationLinkTarget } from '../types/notification'

/**
 * 顶栏通知入口。未读数轮询 + 折叠面板。
 *
 * 按**账号**启停轮询而不是 onMounted 拉一次：顶栏不随视图切换卸载，同一页面内登录后
 * 必须自动开始轮询（`MyInvitationsCard` 踩过这个坑）；登出则 reset，不把上一个账号的
 * 通知留在界面上。
 */

const emit = defineEmits<{ navigate: [target: NotificationLinkTarget] }>()

const { currentUser } = useAuth()
const notifications = useNotifications()

const open = ref(false)

watch(() => currentUser.value?.id, (accountId) => {
  if (accountId) {
    notifications.startPolling()
  } else {
    open.value = false
    notifications.reset()
  }
}, { immediate: true })

// 组件卸载但模块级 timer 不会自动停 —— 单例状态的代价，显式收尾
onUnmounted(() => {
  notifications.stopPolling()
})

/** 打开面板顺手刷一次未读数，避免徽标停在上一轮轮询的旧值。 */
function toggle(): void {
  open.value = !open.value
  if (open.value) void notifications.refreshUnreadCount()
}

function handleNavigate(target: NotificationLinkTarget): void {
  emit('navigate', target)
}
</script>

<template>
  <div class="nt-bell-wrap">
    <button
      type="button" class="nt-bell"
      :aria-expanded="open" aria-haspopup="dialog"
      :title="notifications.unreadCount.value ? `${notifications.unreadCount.value} 条未读通知` : '通知'"
      @click="toggle"
    >
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M8 2a3.5 3.5 0 00-3.5 3.5v2.2L3.2 10.2A.6.6 0 003.7 11h8.6a.6.6 0 00.5-.8L11.5 7.7V5.5A3.5 3.5 0 008 2z"
          stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"
        />
        <path d="M6.4 11.6a1.7 1.7 0 003.2 0" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
      </svg>
      <span class="nt-bell-label">通知</span>
      <span v-if="notifications.unreadCount.value" class="nt-bell-badge" aria-live="polite">
        {{ notifications.unreadCount.value > 99 ? '99+' : notifications.unreadCount.value }}
      </span>
    </button>

    <!-- 点击遮罩关闭：面板在遮罩之上，故遮罩不吞面板内的点击 -->
    <div v-if="open" class="nt-backdrop" @click="open = false"></div>
    <div v-if="open" class="nt-pop">
      <NotificationPanel @navigate="handleNavigate" @close="open = false" />
    </div>
  </div>
</template>

<style scoped>
.nt-bell-wrap { position: relative; display: inline-flex; }
.nt-bell {
  display: inline-flex; align-items: center; gap: 6px; position: relative;
  padding: 6px 10px; font-size: 13px; border-radius: 8px; cursor: pointer;
  border: 1px solid var(--color-border); background: transparent; color: inherit;
}
.nt-bell:hover { background: var(--surface-hover); }
.nt-bell-badge {
  min-width: 17px; padding: 0 5px; border-radius: 9px; font-size: 11px; line-height: 17px;
  text-align: center; color: var(--color-on-accent); background: var(--color-danger); font-variant-numeric: tabular-nums;
}
.nt-backdrop { position: fixed; inset: 0; z-index: 40; }
.nt-pop { position: absolute; top: calc(100% + 8px); right: 0; z-index: 41; }
@media (max-width: 640px) {
  .nt-bell-label { display: none; }
  .nt-pop { position: fixed; top: 64px; right: 12px; left: auto; }
}
</style>
