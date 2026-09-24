<template>
  <section class="dh-page" aria-labelledby="dh-title">
    <header class="dh-page-head">
      <div class="dh-page-head-copy">
        <h2 id="dh-title" class="dh-page-title">数字人工作台</h2>
        <p class="dh-page-sub">与你的数字人创作助手实时对话——会话状态由服务端事实驱动。</p>
      </div>
      <span v-if="stateBadge" class="badge badge-neutral" data-testid="dh-state-badge">{{ stateBadge }}</span>
    </header>

    <p v-if="phase === 'ready' && newSessionsBlocked" class="dh-notice" role="note">
      当前暂不开放新会话；已开始的会话不受影响。
    </p>

    <p v-if="phase === 'loading'" class="dh-hint" role="status">正在加载数字人服务…</p>

    <EmptyState
      v-else-if="phase === 'error'"
      card
      kicker="加载失败"
      title="数字人服务暂时不可用"
      :description="catalogErrorMessage"
    >
      <template #actions>
        <button type="button" class="gl-btn-primary" @click="void load()">重试</button>
      </template>
    </EmptyState>

    <EmptyState
      v-else-if="phase === 'disabled'"
      card
      kicker="未开放"
      title="数字人服务暂未开放"
      description="该功能尚未对你开放，请稍后再来查看。"
    />

    <EmptyState
      v-else-if="phase === 'login-required'"
      card
      kicker="需要登录"
      title="登录后开始与数字人创作"
      description="使用现有草场账号登录后即可配置角色并开始会话。"
    >
      <template #actions>
        <button type="button" class="gl-btn-primary" @click="emit('request-login')">登录 / 注册</button>
      </template>
    </EmptyState>

    <EmptyState
      v-else-if="phase === 'session-missing'"
      card
      kicker="会话不可用"
      title="会话不存在或无权访问"
      description="该会话可能已结束、被删除，或不属于当前账号。从工作台重新开始即可。"
    >
      <template #actions>
        <button
          type="button"
          class="gl-btn-primary"
          @click="void replaceSelection({ profileId: selection.profileId, sessionId: null, view: 'workbench' })"
        >
          返回工作台
        </button>
      </template>
    </EmptyState>

    <template v-else-if="phase === 'ready'">
      <!-- 历史入口（view=history）：API09 keyset 列表 + API38 异步删除（C105G-01）。 -->
      <template v-if="selection.view === 'history'">
        <div class="dh-history-back">
          <button
            type="button"
            class="gl-link"
            data-testid="dh-history-back"
            @click="void replaceSelection({ profileId: selection.profileId, sessionId: null, view: 'workbench' })"
          >
            ← 返回工作台
          </button>
        </div>
        <DigitalHumanHistory
          :items="historyItems"
          :loading="historyLoading"
          :error="historyError"
          :filters="historyFilters"
          :has-more="historyHasMore"
          :deleting-ids="historyDeletingIds"
          :delete-outcomes="historyDeleteOutcomes"
          @filter="applyHistoryFilters"
          @load-more="() => void loadHistoryMore()"
          @delete="(id) => void deleteHistorySession(id)"
          @retry="() => void reloadHistory()"
        />
      </template>

      <div v-else class="dh-workspace">
        <!-- 活动会话视图（E-03）：左舞台+控制，右用量/入口（K11 桌面轨道布局） -->
        <div v-if="liveSession && !sessionTerminal" class="dh-grid" data-testid="dh-live-session">
          <div class="dh-main">
            <DigitalHumanStage
              :stream="stream"
              :state="liveSession.state"
              :connecting="mediaConnecting"
              :error-code="mediaErrorCode"
              :error-message="mediaErrorMessage"
              :test-only="testOnlyBackend"
              @reconnect="handleMediaRetry"
            />
            <p v-if="gapNotice" class="dh-notice" role="status">部分实时字幕未恢复；正在按服务端状态重新同步。</p>
            <DigitalHumanComposer
              :session-state="liveSession.state"
              :mic-state="micState"
              :mic-ready="micReady"
              :submitting="sendingText"
              :interrupting="interrupting"
              :composer-error="micError"
              @send="handleSendText"
              @mic-start="micStart"
              @mic-submit="micSubmit"
              @mic-abort="micAbort"
              @interrupt="handleInterrupt"
            />
            <p v-if="leaseStale" class="gl-alert gl-alert-warning" role="alert" data-testid="dh-lease-stale">
              此会话已由其他页面接管或租约过期。
              <button type="button" class="gl-link" @click="handleTakeover">在本页重新接管</button>
            </p>
            <p v-if="sessionError" class="gl-alert gl-alert-error" role="alert">{{ sessionError }}</p>
            <DigitalHumanRecording
              :recording="recording"
              :supported="recordingSupported"
              :operable="sessionRecordable"
              :starting="recordingStarting"
              :stopping="recordingStopping"
              :saving="recordingSaving"
              :downloading="recordingDownloading"
              :poll-exhausted="pollExhausted"
              :saved-asset-id="savedAssetId"
              :error="recordingError"
              @start="() => void startRecording()"
              @stop="() => void stopRecording()"
              @save="(payload) => void saveRecording(payload.title, payload.includeSubtitles)"
              @download="(artifact) => void downloadRecording(artifact)"
              @refresh="() => void refreshRecording()"
            />
            <div class="gl-actions">
              <!-- 结束始终可点击（仅防在途重入），幂等。 -->
              <button
                type="button"
                class="gl-btn-secondary"
                :disabled="ending"
                data-testid="dh-end-session"
                @click="void end()"
              >
                {{ ending ? '结束中…' : '结束会话' }}
              </button>
            </div>
          </div>
          <aside class="dh-rail">
            <DigitalHumanUsage :billing="liveSession.billing" />
            <DigitalHumanTranscript
              :entries="transcriptEntries"
              :live="liveGeneration"
              :saved="transcriptSaved"
              :busy="transcriptBusy"
              :has-saved="transcriptHasSaved"
              :save-enabled="false"
              :deleted-notice="transcriptDeletedNotice"
              @toggle-save="(value) => void handleToggleSave(value)"
              @save-now="() => void handleSaveNow()"
              @export="() => void handleExport()"
              @delete="() => void handleDeleteTranscript()"
            />
            <div class="dh-history-entry">
              <button
                type="button"
                class="gl-link"
                @click="void replaceSelection({ profileId: selection.profileId, sessionId: null, view: 'history' })"
              >
                查看历史会话
              </button>
            </div>
          </aside>
        </div>

        <!-- 会话终态：待核对费用/保存窗口/删除说明（E-05）；字幕保留在轨道区。 -->
        <div v-else-if="liveSession && sessionTerminal" class="dh-grid" data-testid="dh-end-view">
          <div class="dh-main">
            <DigitalHumanEndPanel
              :confirmed-cents="liveSession.billing?.confirmedCents ?? null"
              :pending-count="liveSession.billing?.pendingCount ?? 0"
              :window-deadline="saveWindowDeadline"
              :window-expired="saveWindowExpired"
              :busy="transcriptBusy"
              :error="null"
              @save-now="() => void handleSaveNow()"
              @export="() => void handleExport()"
              @restart="handleRestart"
            />
            <!-- 录制段独立于会话终态（K13：session/recording 互相独立）：临时窗口内仍可保存/下载。 -->
            <DigitalHumanRecording
              :recording="recording"
              :supported="recordingSupported"
              :operable="false"
              :starting="recordingStarting"
              :stopping="recordingStopping"
              :saving="recordingSaving"
              :downloading="recordingDownloading"
              :poll-exhausted="pollExhausted"
              :saved-asset-id="savedAssetId"
              :error="recordingError"
              @start="() => void startRecording()"
              @stop="() => void stopRecording()"
              @save="(payload) => void saveRecording(payload.title, payload.includeSubtitles)"
              @download="(artifact) => void downloadRecording(artifact)"
              @refresh="() => void refreshRecording()"
            />
          </div>
          <aside class="dh-rail">
            <DigitalHumanTranscript
              :entries="transcriptEntries"
              :live="liveGeneration"
              :saved="transcriptSaved"
              :busy="transcriptBusy"
              :has-saved="transcriptHasSaved"
              :save-enabled="!saveWindowExpired"
              :deleted-notice="transcriptDeletedNotice"
              @toggle-save="(value) => void handleToggleSave(value)"
              @save-now="() => void handleSaveNow()"
              @export="() => void handleExport()"
              @delete="() => void handleDeleteTranscript()"
            />
          </aside>
        </div>

        <!-- 深链发现的活动会话（刷新/分享）：显式提示接管，不偷偷接管别的标签页（K13.4） -->
        <div v-else-if="sessionSnapshot" class="glass-card dh-session-card" data-testid="dh-session-panel">
          <h3 class="dh-session-title">此会话正在其他页面进行</h3>
          <p class="dh-session-meta">
            状态：<span class="gl-num">{{ sessionSnapshot.state }}</span>
            <template v-if="sessionSnapshot.errorCode">（{{ sessionSnapshot.errorCode }}）</template>
          </p>
          <p class="dh-hint">刷新或重新打开后只恢复服务端已保存的状态；未发送的草稿不会恢复。</p>
          <div class="gl-actions">
            <button type="button" class="gl-btn-primary" data-testid="dh-takeover" @click="handleTakeover">
              在本页接管会话
            </button>
            <button
              type="button"
              class="gl-btn-secondary"
              @click="void replaceSelection({ profileId: selection.profileId, sessionId: null, view: 'workbench' })"
            >
              返回工作台
            </button>
          </div>
        </div>

        <!-- 无可用后端：目录开着但没有 approved 组合——与「无角色」区分，不可开始（K02） -->
        <EmptyState
          v-if="profileAreaVisible && approvedBackendIds.length === 0"
          card
          kicker="暂不可用"
          title="当前没有可用的数字人渲染服务"
          description="管理员正在配置可用组合；角色配置可先行保存，开始会话需等待服务就绪。"
        />

        <EmptyState
          v-else-if="profileAreaVisible && profileList.length === 0 && profilesError == null"
          card
          kicker="准备开始"
          title="从配置你的数字人角色开始"
          description="创建一个角色：起个名字、写清人设，选择形象与音色，然后开始会话。"
        >
          <template #actions>
            <button type="button" class="gl-btn-primary" data-testid="dh-create-profile" @click="editProfile(null)">
              创建第一个角色
            </button>
          </template>
        </EmptyState>

        <template v-else>
          <p v-if="profilesError" class="gl-alert gl-alert-error" role="alert">
            角色列表加载失败，已保留已有数据；可重试。
            <button type="button" class="gl-link" @click="void load()">重试</button>
          </p>

          <div class="dh-profile-area">
            <div class="dh-profile-list" data-testid="dh-profile-list">
              <button
                v-for="item in profileList"
                :key="item.id"
                type="button"
                class="dh-chip"
                :class="{ 'dh-chip-active': item.id === editing?.id }"
                :data-testid="`dh-profile-${item.id.slice(0, 8)}`"
                @click="editProfile(item)"
              >
                {{ item.name }}
              </button>
              <button type="button" class="dh-chip" data-testid="dh-new-profile" @click="editProfile(null)">
                ＋ 新建角色
              </button>
            </div>

            <DigitalHumanProfileForm
              :profile="editing"
              :catalog-version="catalogVersion"
              :avatars="avatars"
              :voices="voices"
              :approved-backend-ids="approvedBackendIds"
              :saving="saving"
              :save-error="saveError"
              :version-conflict="versionConflict"
              :saved-notice="savedNotice"
              :preview-state="previewState"
              :preview-url="previewUrl"
              :preview-error="previewError"
              @save="handleSave"
              @preview="(voiceId) => void preview(voiceId, catalogVersion)"
              @reload="handleReloadProfile"
            />

            <div v-if="customAvatarEnabled" class="gl-zone dh-avatar-zone">
              <div class="gl-zone-head">
                <h3 class="gl-zone-title">上传自有形象</h3>
                <p class="gl-zone-note">处理通过后加入可选形象列表（保存角色时选择）。</p>
              </div>
              <DigitalHumanAvatarUpload
                :stage="avatarStage"
                :error="avatarError"
                :rights-version="AVATAR_RIGHTS_VERSION"
                @submit="(file) => void uploadAvatar(file)"
              />
            </div>

            <div class="gl-zone dh-start-area">
              <div class="gl-zone-head">
                <h3 class="gl-zone-title">开始会话</h3>
                <p class="gl-zone-note">开始前展示费用与规则，确认后才会创建会话。</p>
              </div>
              <div class="gl-form-field">
                <label class="field-label" for="dh-input-mode">输入方式</label>
                <select id="dh-input-mode" v-model="inputMode" data-testid="dh-input-mode">
                  <option value="text">文字输入</option>
                  <option value="microphone">按住说话（语音）</option>
                </select>
              </div>
              <p v-if="startError" class="gl-alert gl-alert-error" role="alert" data-testid="dh-start-preflight-error">
                {{ startError }}
              </p>
              <div class="gl-actions">
                <button
                  type="button"
                  class="gl-btn-primary"
                  :disabled="newSessionsBlocked || editing == null"
                  data-testid="dh-start-button"
                  @click="handleStart"
                >
                  查看费用并开始
                </button>
              </div>
            </div>
          </div>
        </template>

        <div class="dh-history-entry">
          <button
            type="button"
            class="gl-link"
            @click="void replaceSelection({ profileId: selection.profileId, sessionId: null, view: 'history' })"
          >
            查看历史会话
          </button>
        </div>
      </div>
    </template>

    <DigitalHumanStartDialog
      :open="startDialogOpen"
      :preflight="currentPreflight"
      :submitting="starting"
      :error="startError"
      @confirm="handleStartConfirm"
      @close="startDialogOpen = false"
    />
  </section>
</template>

<script setup lang="ts">
// 装配层（C105E-01/02/03）：URL 状态 + 角色域 + 会话/媒体/事件 composables + 子组件编排；
// 麦克风（E-04）、字幕面板（E-05）随后续卡接入，不在视图堆业务。
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../../components/shared/EmptyState.vue'
import { useAuth } from '../../composables/useAuth'
import { createDigitalHumanApi, isPersonalCatalog } from '../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../composables/grassland-http'
import { useAccountSessionStore } from '../../stores/account-session'
import type { InputMode, Preflight, Profile, ProfileInput, Session, SessionSnapshot } from '../../types/digital-human'
import { useDigitalHumanUrlState } from './useDigitalHumanUrlState'
import { useDigitalHumanHistory } from './composables/useDigitalHumanHistory'
import { useDigitalHumanProfiles } from './composables/useDigitalHumanProfiles'
import { applySessionEvent, useDigitalHumanSession } from './composables/useDigitalHumanSession'
import { useDigitalHumanEvents } from './composables/useDigitalHumanEvents'
import { useDigitalHumanMedia } from './composables/useDigitalHumanMedia'
import { useDigitalHumanMicrophone } from './composables/useDigitalHumanMicrophone'
import { useDigitalHumanRecording, RECORDABLE_SESSION_STATES } from './composables/useDigitalHumanRecording'
import { useDigitalHumanAvatar, AVATAR_RIGHTS_VERSION } from './composables/useDigitalHumanAvatar'
import DigitalHumanComposer from './components/DigitalHumanComposer.vue'
import DigitalHumanTranscript from './components/DigitalHumanTranscript.vue'
import DigitalHumanEndPanel from './components/DigitalHumanEndPanel.vue'
import DigitalHumanRecording from './components/DigitalHumanRecording.vue'
import DigitalHumanAvatarUpload from './components/DigitalHumanAvatarUpload.vue'
import DigitalHumanHistory from './components/DigitalHumanHistory.vue'
import { useDigitalHumanTranscript } from './composables/useDigitalHumanTranscript'
import DigitalHumanProfileForm from './components/DigitalHumanProfileForm.vue'
import DigitalHumanStartDialog from './components/DigitalHumanStartDialog.vue'
import DigitalHumanStage from './components/DigitalHumanStage.vue'
import DigitalHumanUsage from './components/DigitalHumanUsage.vue'

const emit = defineEmits<{ (e: 'request-login'): void }>()

const route = useRoute()
const router = useRouter()
const { isAuthenticated } = useAuth()
const account = useAccountSessionStore()
const api = createDigitalHumanApi()

const { selection, replaceSelection } = useDigitalHumanUrlState(route, router)

const {
  catalog, catalogLoading, catalogError, profiles: profileList, profilesError,
  approvedBackendIds, load, editing, saving, saveError, versionConflict,
  save, editProfile, previewState, previewUrl, previewError, preview, preflight, preflightError,
  controllerId,
} = useDigitalHumanProfiles(api, account)

// ---------- 会话 / 媒体 / 事件（C105E-03） ----------

const {
  session, starting, ending, error: sessionError, leaseStale,
  start, resume, end, notifyActivated, notifyDeactivated,
} = useDigitalHumanSession(api, account, { controllerId })

const {
  stream, connecting: mediaConnecting, errorCode: mediaErrorCode, errorMessage: mediaErrorMessage,
  connect: connectMedia, reset: resetMedia, retry: retryMedia, stop: stopMedia,
} = useDigitalHumanMedia(api, account)

// ---------- 历史会话（C105G-01：API09 列表 + API38 异步删除；请求代次在 composable） ----------

const {
  items: historyItems, loading: historyLoading, error: historyError, filters: historyFilters,
  hasMore: historyHasMore, deletingIds: historyDeletingIds, deleteOutcomes: historyDeleteOutcomes,
  applyFilters: applyHistoryFilters, loadMore: loadHistoryMore, remove: deleteHistorySession,
  reload: reloadHistory, clear: clearHistory,
} = useDigitalHumanHistory(api, account)

const {
  gapNotice, connect: connectEvents, close: closeEvents, onEvent,
} = useDigitalHumanEvents(api, account)

const liveSession = computed<Session | null>(() => session.value)
const sessionTerminal = computed(() => {
  const state = session.value?.state
  return state === 'ended' || state === 'failed'
})
/** 会话视图（含终态）/接管卡在场时收起配置区（同一时刻只呈现一条主工作流）。 */
const profileAreaVisible = computed(() =>
  liveSession.value == null && sessionSnapshot.value == null)

const testOnlyBackend = computed(() => {
  const view = catalog.value
  if (view == null || !isPersonalCatalog(view)) return false
  const approved = view.backends.filter((backend) => backend.state === 'approved')
  return approved.length > 0 && approved.every((backend) => backend.transport === 'mock')
})

// SSE → 会话状态投影 + media.reset 重建 peer + 活动轮次追踪（E-04 打断需 turnId/turnEpoch）。
const activeTurn = ref<{ id: string; epoch: number } | null>(null)
onEvent((event) => {
  session.value = applySessionEvent(session.value, event)
  const payload = event.payload as Record<string, unknown>
  if (event.type === 'media.reset' && session.value != null) {
    const nextEpoch = Number(payload.nextMediaEpoch)
    if (Number.isSafeInteger(nextEpoch) && nextEpoch > 0) {
      micReady.value = false
      void resetMedia(nextEpoch, session.value).finally(() => { micReady.value = true })
    }
  }
  if (event.type === 'turn.accepted' && typeof payload.turnId === 'string') {
    activeTurn.value = { id: payload.turnId, epoch: event.turnEpoch ?? 0 }
  }
  if (event.type === 'turn.completed') {
    activeTurn.value = null
  }
  applyTranscriptEvent(event)
})

onActivated(() => notifyActivated())
onDeactivated(() => {
  notifyDeactivated()
  stopMedia()
  disposeRecording() // 隐藏终止录制轮询（迟到回包不复活）
})

// ---------- 会话深链核对（E-01 保留；E-03 useDigitalHumanSession 接管完整生命周期） ----------

const sessionSnapshot = ref<SessionSnapshot | null>(null)
const sessionMissing = ref(false)
const sessionNeedsLogin = ref(false)

const phase = computed<'loading' | 'error' | 'disabled' | 'login-required' | 'session-missing' | 'ready'>(() => {
  if (catalogLoading.value) return 'loading'
  if (catalogError.value) return 'error'
  if (catalog.value?.enabled !== true) return 'disabled'
  if (!isAuthenticated.value || sessionNeedsLogin.value) return 'login-required'
  if (sessionMissing.value) return 'session-missing'
  return 'ready'
})

const newSessionsBlocked = computed(() =>
  catalog.value != null && isPersonalCatalog(catalog.value) && !catalog.value.newSessionsAllowed)

const catalogVersion = computed(() =>
  catalog.value != null && isPersonalCatalog(catalog.value) ? catalog.value.version : 0)

const avatars = computed(() =>
  catalog.value != null && isPersonalCatalog(catalog.value) ? catalog.value.avatars : [])

const voices = computed(() =>
  catalog.value != null && isPersonalCatalog(catalog.value) ? catalog.value.voices : [])

const stateBadge = computed(() => {
  if (phase.value === 'ready' && selection.value.view === 'history') return '历史'
  if (phase.value === 'ready' && sessionSnapshot.value) return '会话进行中'
  return null
})

const catalogErrorMessage = computed(() =>
  catalogError.value instanceof Error && catalogError.value.message
    ? catalogError.value.message
    : '请稍后重试；问题持续出现时可在治理台查看服务状态。')

/** session 深链：先本人 GET；404/403 与不存在同文案（K01），401 走登录入口。 */
async function refreshSessionLookup(): Promise<void> {
  sessionSnapshot.value = null
  sessionMissing.value = false
  sessionNeedsLogin.value = false
  if (!isAuthenticated.value || catalog.value?.enabled !== true) return
  const sessionId = selection.value.sessionId
  if (!sessionId) return
  const ticket = account.capture()
  try {
    const snapshot = await api.getSession(sessionId, ticket.signal)
    if (!account.isCurrent(ticket)) return
    sessionSnapshot.value = snapshot
  } catch (error) {
    if (!account.isCurrent(ticket)) return
    if (error instanceof GrasslandHttpError && error.status === 401) sessionNeedsLogin.value = true
    else sessionMissing.value = true
  }
}

async function loadAll(): Promise<void> {
  await load()
  await refreshSessionLookup()
}

onMounted(() => { void loadAll() })
watch(() => isAuthenticated.value, () => {
  void loadAll()
  clearHistory() // 换号/注销：清历史本地态，旧账号数据不残留
})
watch(() => selection.value.sessionId, () => { void refreshSessionLookup() })
// 进入历史视图（含深链直入）：拉首页；离开不保留在途（composable 代次丢弃迟到回包）。
watch(() => selection.value.view, (view) => {
  if (view === 'history') void reloadHistory()
}, { immediate: true })

// 角色列表就绪后默认选中第一个（无编辑目标时），开始区随即可用；用户显式切换不受影响。
watch(profileList, (list) => {
  if (editing.value == null && list.length > 0) editProfile(list[0])
}, { immediate: true })

// ---------- 角色保存 / 试听 / 开始确认（C105E-02） ----------

const savedNotice = ref<string | null>(null)

async function handleSave(input: ProfileInput): Promise<void> {
  savedNotice.value = null
  const ok = await save(input)
  if (ok) savedNotice.value = editing.value ? '修改已保存。' : '角色已创建。'
}

async function handleReloadProfile(): Promise<void> {
  // 版本冲突后的「重新载入」：拉最新 Profile 重置编辑目标；按钮文案已说明会覆盖本地未保存内容。
  const current: Profile | null = editing.value
  if (!current) return
  const ticket = account.capture()
  try {
    const fresh = await api.getProfile(current.id, ticket.signal)
    if (!account.isCurrent(ticket)) return
    editProfile(fresh)
    savedNotice.value = null
  } catch (error) {
    if (account.isCurrent(ticket)) {
      saveError.value = error instanceof Error ? error.message : '重新载入失败。'
    }
  }
}

const inputMode = ref<InputMode>('text')
const startDialogOpen = ref(false)
const currentPreflight = ref<Preflight | null>(null)
const startError = ref<string | null>(null)

async function handleStart(): Promise<void> {
  startError.value = null
  const result = await preflight(inputMode.value)
  if (result) {
    currentPreflight.value = result
    startDialogOpen.value = true
  } else {
    startError.value = preflightError.value ?? '预检失败，请稍后重试。'
  }
}

/** 确认 → create → offer/media-ready → SSE（K07 顺序；到 ready 前禁输入由各状态自身表达）。 */
async function handleStartConfirm(payload: { saveTranscript: boolean }): Promise<void> {
  const preflightToUse = currentPreflight.value
  if (!preflightToUse) return
  startDialogOpen.value = false
  const created = await start(preflightToUse, payload.saveTranscript)
  if (!created) return // 失败/过期（dh_preflight_expired 等）留在开始区错误提示，不自动改价续跑
  void replaceSelection({ profileId: selection.value.profileId, sessionId: created.id, view: 'workbench' })
  await connectMedia(created)
  await connectEvents(created.id)
}

/** 深链/接管：显式 takeover=true（K13.4：刷新换新 controllerId，必须显式接管）。 */
async function handleTakeover(): Promise<void> {
  await resume(true)
  const current = session.value
  if (!current || ['ended', 'failed'].includes(current.state)) return
  sessionSnapshot.value = null
  void replaceSelection({ profileId: selection.value.profileId, sessionId: current.id, view: 'workbench' })
  await connectMedia(current)
  await connectEvents(current.id)
}

function handleMediaRetry(): void {
  if (session.value != null) void retryMedia(session.value)
}

// ---------- 麦克风 / 文字发送 / 打断（C105E-04） ----------

const micReady = ref(true)
const {
  state: micState, errorMessage: micError, start: micStart, submit: micSubmit, abort: micAbort,
} = useDigitalHumanMicrophone(api, account, { session: () => session.value })

// ---------- 录制与素材交接（C105F-04：装配，状态在 composable） ----------

/** K02 能力声明：个人目录 recordingEnabled（后端仍是最终约束）。 */
const recordingSupported = computed(() =>
  catalog.value != null && isPersonalCatalog(catalog.value) && catalog.value.recordingEnabled)
const sessionRecordable = computed(() =>
  RECORDABLE_SESSION_STATES.includes(session.value?.state ?? ''))

const {
  recording, starting: recordingStarting, stopping: recordingStopping, saving: recordingSaving,
  downloading: recordingDownloading, error: recordingError, savedAssetId, pollExhausted,
  start: startRecording, stop: stopRecording, save: saveRecording, download: downloadRecording,
  refresh: refreshRecording, dispose: disposeRecording, reset: resetRecording,
} = useDigitalHumanRecording(api, account, {
  session: () => session.value,
  canRecord: () => recordingSupported.value,
})

// ---------- 自有形象上传（C105F-04：装配） ----------

const customAvatarEnabled = computed(() =>
  catalog.value != null && isPersonalCatalog(catalog.value) && catalog.value.customAvatarEnabled)
const {
  stage: avatarStage, error: avatarError, upload: uploadAvatar,
} = useDigitalHumanAvatar(api, account)

/** 形象就绪后刷新目录：可选形象列表（picker）即可选到新形象。 */
watch(avatarStage, (stage) => {
  if (stage === 'ready') void load()
})

// ---------- 字幕 / 收尾（C105E-05） ----------

const transcriptBusy = ref(false)
const transcriptDeletedNotice = ref<string | null>(null)
const {
  entries: transcriptEntries, live: liveGeneration, saved: transcriptSaved,
  saveWindowDeadline, saveWindowExpired,
  setSave, saveNow, exportTxt, deleteSaved,
  applyEvent: applyTranscriptEvent, observeEnded, reload: reloadTranscript, clear: clearTranscript,
} = useDigitalHumanTranscript(api, account, { session: () => session.value })

watch(sessionTerminal, (terminal) => {
  if (terminal) {
    observeEnded() // 保存窗口起点（本地观测）
    void reloadTranscript()
  }
})

const transcriptHasSaved = computed(() => transcriptSaved.value || transcriptEntries.value.length > 0)

async function runTranscript(action: () => Promise<boolean>): Promise<boolean> {
  transcriptBusy.value = true
  try {
    return await action()
  } finally {
    transcriptBusy.value = false
  }
}

const handleToggleSave = (value: boolean) => runTranscript(() => setSave(value))
const handleSaveNow = () => runTranscript(async () => {
  const ok = await saveNow()
  if (!ok) transcriptDeletedNotice.value = null
  return ok
})
const handleExport = () => runTranscript(() => exportTxt())
const handleDeleteTranscript = () => runTranscript(async () => {
  const ok = await deleteSaved()
  transcriptDeletedNotice.value = ok ? '已删除本场已保存字幕；迟到的旧字幕事件不会恢复。' : '删除失败，请稍后重试。'
  return ok
})

async function handleRestart(): Promise<void> {
  // 重新开始一场：清本场本地态回配置区（新会话=新经济键，K04 不恢复旧场）。
  closeEvents()
  stopMedia()
  disposeRecording()
  resetRecording()
  session.value = null
  activeTurn.value = null
  clearTranscript()
  transcriptDeletedNotice.value = null
  await replaceSelection({ profileId: selection.value.profileId, sessionId: null, view: 'workbench' })
}

const sendingText = ref(false)
const interrupting = ref(false)

/** 普通文字发送：不重入（sendingText 门闸）；turn 后续由 SSE 推进。 */
async function handleSendText(text: string): Promise<void> {
  const current = session.value
  if (!current || sendingText.value) return
  sendingText.value = true
  sessionError.value = null
  try {
    await api.createTurn(current.id, {
      requestId: crypto.randomUUID(), leaseEpoch: current.leaseEpoch, text,
    })
  } catch (error) {
    if (error instanceof Error) sessionError.value = error.message
  } finally {
    sendingText.value = false
  }
}

/**
 * 打断并说话：本地立刻静音（stopMedia 停旧 srcObject/track）→ API20 → 确认新 media ready
 * （resetMedia 完成后）才重新允许麦克风（E-04 步骤5：不能旧轮与新录音抢同一 turn）。
 */
async function handleInterrupt(): Promise<void> {
  const current = session.value
  const turn = activeTurn.value
  if (!current || !turn || interrupting.value) return
  interrupting.value = true
  micReady.value = false
  stopMedia() // 本地立刻静音
  try {
    const receipt = await api.interrupt(current.id, {
      requestId: crypto.randomUUID(), leaseEpoch: current.leaseEpoch,
      turnId: turn.id, turnEpoch: turn.epoch,
    })
    if (session.value != null) {
      session.value = { ...session.value, mediaEpoch: receipt.nextMediaEpoch, state: receipt.state }
      activeTurn.value = null
      await resetMedia(receipt.nextMediaEpoch, session.value)
    }
  } catch (error) {
    if (error instanceof Error) sessionError.value = error.message
  } finally {
    micReady.value = true
    interrupting.value = false
  }
}
</script>
