<template>
  <section class="sync-panel studio-panel" data-test="wechat-sync-panel" aria-label="公众号草稿同步状态">
    <header class="head">
      <h4>草稿箱同步</h4>
      <span v-if="sync" class="badge" :class="`state-${sync.state}`" data-test="wechat-sync-state">
        {{ syncStateLabel(sync.state) }}
      </span>
    </header>

    <template v-if="sync">
      <p class="hint" data-test="wechat-sync-version">
        同步版本 v{{ sync.draftVersion }}（快照已固化——本地继续编辑不影响本次同步）· {{ shortTime(sync.createdAt) }}
      </p>
      <p v-if="sync.state === 'succeeded'" class="hint ok" data-test="wechat-sync-done">
        已存入公众号草稿箱{{ sync.verifiedAt ? `（核实于 ${shortTime(sync.verifiedAt)}）` : '' }}；请到微信公众号后台查看，不会自动公开发布。
      </p>
      <p v-if="sync.state === 'unknown'" class="hint warn" data-test="wechat-sync-unknown">
        草稿写入结果未知（可能已存入）；不会自动重发——请核实草稿箱后确认。
      </p>
      <p v-if="sync.error" class="error" role="alert" data-test="wechat-sync-error">{{ sync.error.message }}</p>

      <div class="actions">
        <button type="button" class="secondary" data-test="wechat-sync-refresh" @click="emit('refresh')">刷新状态</button>
        <button
          v-if="isSyncActive(sync.state)"
          type="button"
          class="secondary"
          data-test="wechat-sync-cancel"
          :disabled="cancelling"
          @click="onCancel"
        >{{ cancelling ? '取消中…' : '取消同步' }}</button>
        <button
          v-if="sync.state === 'unknown'"
          type="button"
          class="secondary"
          data-test="wechat-sync-candidates"
          :disabled="loadingCandidates"
          @click="onLoadCandidates"
        >{{ loadingCandidates ? '搜索中…' : '核实草稿' }}</button>
      </div>

      <!-- 候选核实：账号内草稿箱条目；内容匹配由服务端给出，不默认选中 -->
      <div v-if="candidates" class="candidates" data-test="wechat-sync-candidates-list">
        <p class="hint">
          已搜索 {{ candidates.searchedCount }} 条{{ candidates.hasMore ? '（草稿箱条目较多，未搜完全部）' : '' }}；
          「内容一致」由服务端比对正文得出，请结合标题与时间判断后选择核实。
        </p>
        <ul>
          <li v-for="candidate in candidates.items" :key="candidate.externalDraftMediaId">
            <span class="candidate-title">{{ candidate.title || '（无标题草稿）' }}</span>
            <span class="hint">{{ candidate.updatedAt ? shortTime(candidate.updatedAt) : '时间未知' }}</span>
            <span class="badge" :class="candidate.contentMatches ? 'match' : 'no-match'">
              {{ candidate.contentMatches ? '内容一致' : '内容不一致' }}
            </span>
            <button
              type="button"
              class="secondary"
              :data-test="`wechat-sync-verify-${candidate.externalDraftMediaId}`"
              :disabled="reconciling"
              @click="onReconcile(candidate.externalDraftMediaId)"
            >核实此草稿</button>
          </li>
        </ul>
      </div>
        <label v-if="sync.state === 'unknown' || sync.state === 'failed'" class="manual">
          或输入草稿 media_id 核实
          <input v-model.trim="manualMediaId" maxlength="256" data-test="wechat-sync-manual-media-id" placeholder="media_id">
          <button
            type="button"
            class="secondary"
            data-test="wechat-sync-manual-verify"
            :disabled="reconciling || !manualMediaId"
            @click="onReconcile(manualMediaId)"
          >核实</button>
        </label>
    </template>

    <p v-if="loadError || actionError" class="error" role="alert" data-test="wechat-sync-action-error">{{ actionError || loadError }}</p>
    <p v-if="!sync && !loadError" class="hint" data-test="wechat-sync-empty">尚未发起草稿箱同步。</p>

    <details v-if="history.length > 1" class="history" data-test="wechat-sync-history">
      <summary>历史同步（{{ history.length }}）</summary>
      <ul>
        <li v-for="item in history" :key="item.id">
          v{{ item.draftVersion }} · {{ syncStateLabel(item.state) }} · {{ shortTime(item.createdAt) }}
          <span v-if="item.error" class="hint">（{{ item.error.message }}）</span>
          <button type="button" class="secondary" :disabled="item.id === sync?.id" @click="emit('updated', item)">查看详情</button>
        </li>
      </ul>
    </details>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useStudioGuard } from '../../../lib/creation-studio-http'
import {
  cancelDraftSync, fetchDraftSyncCandidates, isSyncActive, reconcileDraftSync, syncActionError,
  syncStateLabel, type DraftSyncCandidates, type WechatDraftSync,
} from '../creation/useWechatDraftSync'

/**
 * 任务书 #101 C101-22：同步状态/历史/候选核实（TC101-106/107）。
 * 状态文案真实（成功只说已存入草稿箱，无「已发布」与伪公开链接）；unknown 只提供核实动作；
 * 候选内容匹配结论来自服务端比对；版本冲突提示刷新。
 */
const props = defineProps<{
  sync: WechatDraftSync | null
  history: WechatDraftSync[]
  loadError?: string
}>()

const emit = defineEmits<{
  /** 状态外化（父层 composable 持有真相源）。 */
  updated: [sync: WechatDraftSync]
  refresh: []
}>()

const cancelling = ref(false)
const loadingCandidates = ref(false)
const candidates = ref<DraftSyncCandidates | null>(null)
const reconciling = ref(false)
const manualMediaId = ref('')
const actionError = ref('')
const guard = useStudioGuard(() => props.sync?.id)
const intents = new Map<string, string>()
guard.onInvalidate(() => {
  candidates.value = null; manualMediaId.value = ''; actionError.value = ''
  cancelling.value = false; loadingCandidates.value = false; reconciling.value = false; intents.clear()
})
function requestId(key: string): string {
  if (!intents.has(key)) intents.set(key, crypto.randomUUID())
  return intents.get(key)!
}

function shortTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' })
}

async function onCancel(): Promise<void> {
  const valid = guard.capture()
  const sync = props.sync
  if (!sync || cancelling.value) return
  cancelling.value = true
  actionError.value = ''
  try {
    const result = await cancelDraftSync(sync.id, sync.version, requestId(`cancel:${sync.id}:${sync.version}`))
    if (valid()) emit('updated', result)
  } catch (error) {
    if (valid()) actionError.value = syncActionError(error, '取消失败，请稍后重试').message
  } finally {
    if (valid()) cancelling.value = false
  }
}

async function onLoadCandidates(): Promise<void> {
  const valid = guard.capture()
  const sync = props.sync
  if (!sync || loadingCandidates.value) return
  loadingCandidates.value = true
  candidates.value = null
  try {
    const result = await fetchDraftSyncCandidates(sync.id)
    if (valid()) candidates.value = result
  } catch (error) {
    if (valid()) actionError.value = syncActionError(error, '候选搜索失败，请稍后重试').message
  } finally {
    if (valid()) loadingCandidates.value = false
  }
}

async function onReconcile(mediaId: string): Promise<void> {
  const valid = guard.capture()
  const sync = props.sync
  if (!sync || reconciling.value || !mediaId) return
  reconciling.value = true
  actionError.value = ''
  try {
    const result = await reconcileDraftSync(sync.id, sync.version, mediaId, requestId(`reconcile:${sync.id}:${sync.version}:${mediaId}`))
    if (!valid()) return
    emit('updated', result)
    candidates.value = null
    manualMediaId.value = ''
  } catch (error) {
    if (!valid()) return
    const failure = syncActionError(error, '核实失败')
    actionError.value = failure.message
    if (failure.versionConflict) emit('refresh')
  } finally {
    if (valid()) reconciling.value = false
  }
}
</script>

<style scoped>
.sync-panel { display: grid; gap: var(--space-sm); }
.head { display: flex; align-items: center; gap: var(--space-sm); }
.head h4 { margin: 0; font-size: var(--type-body); }
.hint { margin: 0; color: var(--color-text-muted); font-size: var(--type-body-sm); }
.hint.ok { color: var(--color-success, var(--color-text-secondary)); }
.hint.warn { color: var(--color-warning, var(--color-text-secondary)); }
.error { color: var(--color-danger); font-size: var(--type-body-sm); margin: 0; }
.badge { display: inline-block; padding: var(--space-micro) var(--space-sm); border-radius: var(--radius-pill); font-size: var(--type-caption); background: var(--surface-muted); color: var(--color-text-secondary); }
.badge.state-succeeded { color: var(--color-success, var(--color-text-secondary)); }
.badge.state-failed, .badge.state-unknown { color: var(--color-danger); }
.badge.match { color: var(--color-success, var(--color-text-secondary)); }
.badge.no-match { color: var(--color-text-muted); }
.actions { display: flex; gap: var(--space-xs); flex-wrap: wrap; }
.actions .secondary, .candidates .secondary { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); font-size: var(--type-body-sm); }
.candidates { display: grid; gap: var(--space-xs); padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-sm); }
.candidates ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-xs); }
.candidates li { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; font-size: var(--type-body-sm); }
.candidate-title { font-weight: var(--weight-heading); }
.manual { display: flex; align-items: center; gap: var(--space-xs); font-size: var(--type-body-sm); color: var(--color-text-secondary); flex-wrap: wrap; }
.manual input { padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border-control); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; min-width: 0; }
.history summary { cursor: pointer; font-size: var(--type-body-sm); color: var(--color-text-secondary); }
.history ul { list-style: none; margin: var(--space-xs) 0 0; padding: 0; display: grid; gap: var(--space-xxs); font-size: var(--type-body-sm); }
</style>
