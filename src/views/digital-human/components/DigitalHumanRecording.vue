<template>
  <!-- 录制操作区（任务书 #105F C105F-04 / K03 API33-37、K09）：纯展示与装配，状态由父层
       composable 持有。开始需显式确认（只录数字人输出）；录制中颜色+文字双提示、持续时长与
       停止动作；ready 给 partial/有效期/保存表单；saving 锁保存不锁结束；failed 不冒充可保存。 -->
  <div class="dh-recording-zone" data-testid="dh-recording-zone">
    <div class="gl-zone-head">
      <h3 class="gl-zone-title">录制数字人输出</h3>
      <p class="gl-zone-note">只录制数字人画面与声音（含字幕）；不采集你的摄像头或麦克风。</p>
    </div>

    <p v-if="error" class="gl-alert gl-alert-error" role="alert" data-testid="dh-recording-error">{{ error }}</p>

    <!-- 能力未开放：按钮不显示为可操作（后端仍是最终约束）。 -->
    <p v-if="!supported" class="dh-hint" data-testid="dh-recording-unsupported">录制能力未开放。</p>

    <template v-else-if="recording == null">
      <div class="gl-actions">
        <button
          type="button"
          class="gl-btn-secondary"
          :disabled="!operable || starting"
          data-testid="dh-recording-start"
          @click="confirmOpen = true"
        >
          {{ starting ? '正在开始…' : '开始录制' }}
        </button>
      </div>
    </template>

    <template v-else>
      <!-- 录制中：醒目标志（红点+文字双提示）+ 已录时长 + 停止。 -->
      <p
        v-if="recording.state === 'recording'"
        class="dh-recording-live"
        role="status"
        data-testid="dh-recording-live"
      >
        <span class="dh-recording-dot" aria-hidden="true"></span>
        录制中 · 已录 {{ elapsedLabel }}
      </p>
      <p v-else-if="recording.state === 'finalizing'" class="dh-notice" role="status" data-testid="dh-recording-finalizing">
        正在收尾本段录制…
        <template v-if="pollExhausted">
          收尾时间较长，可
          <button type="button" class="gl-link" data-testid="dh-recording-refresh" @click="emit('refresh')">
            手动刷新
          </button>
          查看。
        </template>
      </p>
      <p v-else-if="recording.state === 'saving'" class="dh-notice" role="status" data-testid="dh-recording-saving">
        正在保存为素材…
      </p>

      <template v-if="recording.state === 'recording'">
        <div class="gl-actions">
          <button
            type="button"
            class="gl-btn-secondary"
            :disabled="stopping"
            data-testid="dh-recording-stop"
            @click="emit('stop')"
          >
            {{ stopping ? '正在停止…' : '停止录制' }}
          </button>
        </div>
      </template>

      <template v-else-if="recording.state === 'ready'">
        <div class="dh-recording-ready" data-testid="dh-recording-ready">
          <p class="dh-recording-meta">
            <span v-if="recording.partial" class="badge badge-warning" data-testid="dh-recording-partial">
              部分录制（期间有溢出丢帧，非完整视频）
            </span>
            <span v-else class="badge badge-success">录制完整</span>
            <span v-if="recording.durationMs > 0" class="gl-num">时长 {{ Math.round(recording.durationMs / 1000) }} 秒</span>
            <span v-if="expiresLabel">临时保留至 {{ expiresLabel }}，过期不可保存</span>
          </p>
          <div class="gl-actions">
            <button
              type="button"
              class="gl-btn-secondary"
              :disabled="downloading"
              data-testid="dh-recording-download-mp4"
              @click="emit('download', 'mp4')"
            >
              下载 MP4
            </button>
            <button
              type="button"
              class="gl-btn-secondary"
              :disabled="downloading || !recording.subtitleAvailable"
              data-testid="dh-recording-download-srt"
              @click="emit('download', 'srt')"
            >
              下载字幕
            </button>
          </div>
          <form class="dh-recording-save-form" @submit.prevent="submitSave">
            <div class="gl-form-field">
              <label class="field-label" for="dh-recording-title">素材标题</label>
              <input
                id="dh-recording-title"
                v-model="title"
                class="gl-input"
                type="text"
                maxlength="100"
                placeholder="1～100 字符"
                data-testid="dh-recording-title"
              />
            </div>
            <label class="dh-recording-consent">
              <input
                v-model="includeSubtitles"
                type="checkbox"
                :disabled="!recording.subtitleAvailable"
                data-testid="dh-recording-include-subtitles"
              />
              <span>同时保存字幕作为素材附件（{{ recording.subtitleAvailable ? '本段有字幕' : '本段无字幕' }}）</span>
            </label>
            <div class="gl-actions">
              <button
                type="submit"
                class="gl-btn-primary"
                :disabled="saving"
                data-testid="dh-recording-save"
              >
                {{ saving ? '保存中…' : '保存到素材库' }}
              </button>
            </div>
          </form>
        </div>
      </template>

      <template v-else-if="recording.state === 'saved'">
        <div class="dh-recording-saved" data-testid="dh-recording-saved">
          <p class="dh-recording-meta">
            <span class="badge badge-success">已保存</span>
            素材编号 <span class="gl-num">{{ savedAssetId ?? recording.assetId }}</span>
          </p>
          <p class="dh-hint">
            已进入你的个人素材库（AI 创作中心 → 内容素材库）；后续按素材库生命周期管理。
          </p>
          <div class="gl-actions">
            <RouterLink class="gl-btn-secondary" :to="{ name: 'create' }" data-testid="dh-recording-library-link">
              前往素材库
            </RouterLink>
            <button
              type="button"
              class="gl-btn-secondary"
              :disabled="downloading"
              data-testid="dh-recording-download-mp4"
              @click="emit('download', 'mp4')"
            >
              下载 MP4
            </button>
          </div>
        </div>
      </template>

      <template v-else-if="recording.state === 'failed' || recording.state === 'expired' || recording.state === 'deleted'">
        <p class="dh-recording-meta" data-testid="dh-recording-failed">
          <span class="badge badge-danger">{{ recording.state === 'failed' ? '录制失败' : '已过期' }}</span>
          <template v-if="recording.errorCode">（{{ recording.errorCode }}）</template>
          本段产物不可保存或下载。
        </p>
      </template>
    </template>

    <!-- 开始确认（K09：acknowledgement 显式给出，不默认勾选）。 -->
    <GlModal
      v-if="confirmOpen"
      title="开始录制数字人输出"
      :persistent="starting"
      @close="confirmOpen = false"
    >
      <div class="dh-recording-confirm" data-testid="dh-recording-confirm">
        <ul class="dh-start-rules">
          <li>只录制数字人输出的画面与声音（含字幕），不采集你的摄像头或麦克风。</li>
          <li>录制临时保留 24 小时；保存到素材库后按素材生命周期管理。</li>
          <li>同一场会话最多录制 2 段。</li>
        </ul>
        <div class="gl-actions">
          <button type="button" class="gl-btn-secondary" @click="confirmOpen = false">取消</button>
          <button
            type="button"
            class="gl-btn-primary"
            :disabled="starting"
            data-testid="dh-recording-confirm-start"
            @click="handleConfirmStart"
          >
            {{ starting ? '正在开始…' : '确认开始录制' }}
          </button>
        </div>
      </div>
    </GlModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import GlModal from '../../../components/GlModal.vue'
import type { Recording } from '../../../types/digital-human'

const props = withDefaults(defineProps<{
  recording: Recording | null
  /** 目录能力声明（catalog.recordingEnabled）；不可用时按钮不可操作。 */
  supported: boolean
  /** 会话当前状态可录（ready/listening/responding）。 */
  operable: boolean
  starting: boolean
  stopping: boolean
  saving: boolean
  downloading: boolean
  pollExhausted: boolean
  savedAssetId: string | null
  error: string | null
}>(), {
  pollExhausted: false,
  savedAssetId: null,
  error: null,
})

const emit = defineEmits<{
  (e: 'start'): void
  (e: 'stop'): void
  (e: 'save', payload: { title: string; includeSubtitles: boolean }): void
  (e: 'download', artifact: 'mp4' | 'srt'): void
  (e: 'refresh'): void
}>()

const confirmOpen = ref(false)
const title = ref('')
const includeSubtitles = ref(false)
const nowTick = ref(Date.now())
let elapsedTimer: ReturnType<typeof setInterval> | null = null

function armElapsedTimer(active: boolean): void {
  if (elapsedTimer != null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
  if (active) {
    elapsedTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  }
}

watch(() => props.recording?.state, (state) => {
  armElapsedTimer(state === 'recording')
  if (state !== 'recording') nowTick.value = Date.now()
}, { immediate: true })

onBeforeUnmount(() => { armElapsedTimer(false) })

/** 录制中时长：从 startedAt 起的真实秒数（非虚百分比）。 */
const elapsedLabel = computed(() => {
  const startedAt = props.recording?.startedAt
  if (!startedAt) return '0:00'
  const seconds = Math.max(0, Math.floor((nowTick.value - Date.parse(startedAt)) / 1000))
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
})

const expiresLabel = computed(() => {
  const expiresAt = props.recording?.expiresAt
  if (!expiresAt) return null
  const parsed = Date.parse(expiresAt)
  if (!Number.isFinite(parsed)) return null
  return new Date(parsed).toLocaleString('zh-CN', { hour12: false })
})

function handleConfirmStart(): void {
  emit('start')
}

function submitSave(): void {
  emit('save', { title: title.value, includeSubtitles: includeSubtitles.value })
}
</script>
