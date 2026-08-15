<script setup lang="ts">
import { onMounted } from 'vue'
import { useNotifications } from '../composables/useNotifications'
import {
  NOTIFICATION_CATEGORY_LABEL,
  type Notification,
  type NotificationLinkTarget,
} from '../types/notification'

/**
 * 通知列表面板。数据来自 {@link useNotifications} 的单例状态，与顶栏铃铛共享同一份。
 *
 * 点击一条通知 = 标已读 + （若 linkPath 可解析）向父级发 navigate。**不自己切视图**——
 * 视图状态在 `App.vue`，面板只表达意图，落点由 App 决定。
 */

const emit = defineEmits<{
  navigate: [target: NotificationLinkTarget]
  close: []
}>()

const notifications = useNotifications()

onMounted(() => {
  void notifications.loadFirstPage()
})

/** 标的摘要：正文刻意不含标的（后端约定），故这里从 payload 渲染出来。 */
function payloadSummary(item: Notification): string {
  const parts: string[] = []
  const p = item.payload || {}
  const id = (key: string): string | null => {
    const value = p[key]
    return typeof value === 'string' && value ? `${value.slice(0, 8)}…` : null
  }
  const taskId = id('taskId')
  if (taskId) parts.push(`任务 ${taskId}`)
  const disputeId = id('disputeId')
  if (disputeId) parts.push(`争议 ${disputeId}`)
  const engagementRef = id('engagementRef')
  if (engagementRef && !taskId) parts.push(`履约 ${engagementRef}`)
  const orgId = id('organizationId')
  if (orgId) parts.push(`组织 ${orgId}`)
  // 金额：后端给分，展示元。payoutCents 是到手，amountCents 是充值
  for (const key of ['payoutCents', 'amountCents'] as const) {
    const cents = p[key]
    if (typeof cents === 'number') parts.push(`¥${(cents / 100).toFixed(2)}`)
  }
  const status = p.status
  if (typeof status === 'string' && status) parts.push(status)
  const reason = p.reason
  if (typeof reason === 'string' && reason) parts.push(`原因：${reason}`)
  return parts.join(' · ')
}

function formatTime(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { hour12: false })
}

async function open(item: Notification): Promise<void> {
  if (!item.read) await notifications.markRead([item.id])
  const target = notifications.resolveLinkTarget(item.linkPath, item.payload)
  if (target) {
    emit('navigate', target)
    emit('close')
  }
}
</script>

<template>
  <div class="nt-panel" role="dialog" aria-label="通知中心">
    <header class="nt-head">
      <h3>通知<span v-if="notifications.unreadCount.value" class="nt-badge">{{ notifications.unreadCount.value }}</span></h3>
      <div class="nt-head-actions">
        <label class="nt-toggle">
          <input
            type="checkbox"
            :checked="notifications.unreadOnly.value"
            @change="notifications.setUnreadOnly(($event.target as HTMLInputElement).checked)"
          />
          只看未读
        </label>
        <button
          type="button" class="nt-quiet"
          :disabled="notifications.loading.value || notifications.unreadCount.value === 0"
          @click="notifications.markAllRead()"
        >全部已读</button>
        <button type="button" class="nt-quiet" :disabled="notifications.loading.value" @click="notifications.loadFirstPage()">
          刷新
        </button>
      </div>
    </header>

    <p v-if="notifications.error.value" class="nt-alert" role="alert">{{ notifications.error.value }}</p>

    <p v-if="notifications.items.value.length === 0 && !notifications.loading.value" class="nt-empty">
      {{ notifications.unreadOnly.value ? '没有未读通知' : '暂无通知' }}
    </p>

    <div class="nt-scroll">
      <section v-for="group in notifications.grouped.value" :key="group.category" class="nt-group">
        <h4 class="nt-group-title">{{ NOTIFICATION_CATEGORY_LABEL[group.category] }}</h4>
        <ul class="nt-list">
          <li v-for="item in group.items" :key="item.id" :class="{ unread: !item.read }">
            <button type="button" class="nt-item" @click="open(item)">
              <span class="nt-item-head">
                <span class="nt-dot" :class="{ on: !item.read }" aria-hidden="true"></span>
                <strong class="nt-title">{{ item.title }}</strong>
                <time class="nt-time">{{ formatTime(item.createdAt) }}</time>
              </span>
              <span class="nt-body">{{ item.body }}</span>
              <span v-if="payloadSummary(item)" class="nt-meta">{{ payloadSummary(item) }}</span>
            </button>
          </li>
        </ul>
      </section>

      <button
        v-if="notifications.hasMore.value"
        type="button" class="nt-more"
        :disabled="notifications.loading.value"
        @click="notifications.loadMore()"
      >{{ notifications.loading.value ? '加载中…' : '加载更多' }}</button>
    </div>
  </div>
</template>

<style scoped>
.nt-panel {
  display: flex; flex-direction: column; gap: 10px;
  width: min(400px, calc(100vw - 32px)); max-height: min(70vh, 560px);
  padding: 14px; border-radius: 12px;
  background: var(--surface, #fff);
  border: 1px solid var(--border, rgba(0, 0, 0, 0.1));
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.16);
}
.nt-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.nt-head h3 { margin: 0; font-size: 15px; display: flex; align-items: center; gap: 6px; }
.nt-badge {
  min-width: 18px; padding: 0 5px; border-radius: 9px; font-size: 11px; line-height: 18px;
  text-align: center; color: #fff; background: #e5484d;
}
.nt-head-actions { display: flex; align-items: center; gap: 8px; }
.nt-toggle { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-muted, #666); }
.nt-quiet {
  padding: 3px 8px; font-size: 12px; border-radius: 6px; cursor: pointer;
  border: 1px solid var(--border, rgba(0, 0, 0, 0.12)); background: transparent; color: inherit;
}
.nt-quiet:disabled { opacity: 0.5; cursor: not-allowed; }
.nt-alert { margin: 0; padding: 6px 8px; border-radius: 6px; font-size: 12px; background: rgba(229, 72, 77, 0.12); color: #b4262a; }
.nt-empty { margin: 12px 0; text-align: center; font-size: 13px; color: var(--text-muted, #888); }
.nt-scroll { overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.nt-group { display: flex; flex-direction: column; gap: 6px; }
.nt-group-title { margin: 0; font-size: 12px; font-weight: 600; color: var(--text-muted, #777); }
.nt-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.nt-item {
  width: 100%; display: flex; flex-direction: column; gap: 3px; padding: 8px;
  text-align: left; cursor: pointer; border: none; border-radius: 8px;
  background: transparent; color: inherit; font: inherit;
}
.nt-item:hover { background: var(--surface-hover, rgba(0, 0, 0, 0.04)); }
.nt-item-head { display: flex; align-items: center; gap: 6px; }
.nt-dot { width: 6px; height: 6px; border-radius: 50%; background: transparent; flex: none; }
.nt-dot.on { background: #e5484d; }
.nt-title { font-size: 13px; }
.nt-time { margin-left: auto; font-size: 11px; color: var(--text-muted, #999); }
.nt-body { font-size: 12px; color: var(--text-muted, #666); }
.nt-meta { font-size: 11px; color: var(--text-muted, #888); font-variant-numeric: tabular-nums; }
li.unread .nt-title { font-weight: 700; }
.nt-more {
  align-self: center; padding: 5px 12px; font-size: 12px; border-radius: 6px; cursor: pointer;
  border: 1px solid var(--border, rgba(0, 0, 0, 0.12)); background: transparent; color: inherit;
}
.nt-more:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
