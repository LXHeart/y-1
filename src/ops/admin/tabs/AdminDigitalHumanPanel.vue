<script setup lang="ts">
import { onActivated, onDeactivated, onMounted, onUnmounted } from 'vue'
import { useAccountSessionStore } from '../../../stores/account-session'
import { useDigitalHumanAdmin } from '../composables/useDigitalHumanAdmin'
import DigitalHumanConfigForm from '../components/DigitalHumanConfigForm.vue'
import DigitalHumanSessionTable from '../components/DigitalHumanSessionTable.vue'
import DigitalHumanReconcilePanel from '../components/DigitalHumanReconcilePanel.vue'

/**
 * 数字人治理面板（任务书 #105G C105G-04 / K10）：配置 / 会话 / 核对三个子区。
 *
 * 纯装配：状态与请求全部在 useDigitalHumanAdmin（串行 5s 轮询、失活/换号清状态）；
 * 本面板只接线 KeepAlive/挂载生命周期与错误/冲突/提交流。未在 ADMIN_STATEFUL_TABS
 * 缓存名单内——切页签即卸载并清状态（治理数据不跨账号残留）。
 */
const session = useAccountSessionStore()
const admin = useDigitalHumanAdmin(session)

function submitConfig(): void {
  void admin.updateConfig()
}

function terminate(sessionId: string, reason: string): void {
  void admin.terminateSession(sessionId, reason)
}

function reconcile(invocationId: string, expectedVersion: number, form: Parameters<typeof admin.reconcile>[2]): void {
  void admin.reconcile(invocationId, expectedVersion, form)
}

onMounted(() => admin.notifyActivated())
onActivated(() => admin.notifyActivated())
onDeactivated(() => admin.notifyDeactivated())
onUnmounted(() => admin.dispose())
</script>

<template>
  <article class="dh-admin-panel" data-test="dh-admin-panel">
    <div class="panel-toolbar">
      <div>
        <h3>数字人工作台治理</h3>
        <p>
          面板激活且页面可见时每 5 秒串行刷新（前次完成后计时）；切走页签或切换账号即停止并清空。
          待核对 {{ admin.pendingReconcileCount.value }} 项。
        </p>
      </div>
      <button type="button" class="refresh-btn" data-test="dh-admin-refresh" :disabled="admin.loading.value"
        @click="admin.refresh()">刷新</button>
    </div>

    <p v-if="admin.error.value" class="error-msg" role="alert" data-test="dh-admin-error">
      {{ admin.error.value }}
    </p>

    <DigitalHumanConfigForm v-model:draft="admin.configDraft.value" :server-version="admin.config.value?.version ?? null"
      :base-version="admin.draftBaseVersion.value" :updating="admin.updating.value"
      :conflict="admin.updateConflict.value" :error="admin.updateError.value" :notice="admin.updateNotice.value"
      @submit="submitConfig" @reload="admin.reloadDraft()" />

    <DigitalHumanSessionTable :sessions="admin.sessions.value" :loading="admin.loading.value"
      :error="null" :terminating="admin.terminating.value" :action-error="admin.terminateError.value"
      :notice="admin.terminateNotice.value" @terminate="terminate" @refresh="admin.refresh()" />

    <DigitalHumanReconcilePanel :invocations="admin.invocations.value" :loading="admin.loading.value"
      :error="null" :reconciling="admin.reconciling.value" :action-error="admin.reconcileError.value"
      :last-reconciled="admin.lastReconciled.value" @submit="reconcile" @refresh="admin.refresh()" />
  </article>
</template>

<style scoped src="../admin-shared.css"></style>
<style scoped>
.dh-admin-panel { display: grid; gap: var(--space-lg); }
</style>
