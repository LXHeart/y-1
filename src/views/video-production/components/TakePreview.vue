<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'

/**
 * 任务书 #100 C100-06：候选媒体预览（快速/专业两模式共用）。
 *
 * 只预加载 metadata（不整文件预载）；IntersectionObserver 可用时进入可视区才挂 src、
 * 离开可视区暂停并卸载媒体；加载失败（含签名过期）给重试入口并上抛 media-error 由
 * 宿主重取合法 URL；容器隔断 pointerdown，不触发画布节点拖拽/选中。
 */
const props = withDefaults(defineProps<{
  url: string | null
  /** 无 url 时的占位文案（如候选状态）。 */
  placeholder?: string
  /** 占位补充说明（如失败原因）。 */
  note?: string | null
  dataTest?: string
  trimStartMs?: number | null
  trimEndMs?: number | null
  muted?: boolean
}>(), {
  placeholder: '',
  note: null,
  dataTest: 'take-preview',
  muted: true,
})

const emit = defineEmits<{
  (e: 'media-error'): void
}>()

const root = ref<HTMLElement | null>(null)
const videoEl = ref<HTMLVideoElement | null>(null)
/** 无 IntersectionObserver 环境（旧测试运行时）回落为立即可见。 */
const visible = ref(true)
const failed = ref(false)
const reloadKey = ref(0)
const active = ref(true)
const trimmed = computed(() => props.trimStartMs != null || props.trimEndMs != null)
const validRange = computed(() => !trimmed.value || (Number.isSafeInteger(props.trimStartMs)
  && Number.isSafeInteger(props.trimEndMs) && props.trimStartMs! >= 0 && props.trimEndMs! > props.trimStartMs!))
let endTimer: ReturnType<typeof setTimeout> | null = null
let corrections = 0
function stopTimer(): void { if (endTimer) clearTimeout(endTimer); endTimer = null }
function pause(): void { stopTimer(); videoEl.value?.pause() }
function enforceRange(): void {
  const video = videoEl.value
  if (!video || video.seeking || !trimmed.value || !validRange.value) return
  if (video.currentTime < props.trimStartMs! / 1000) {
    if (++corrections > 2) { pause(); failed.value = true; return }
    video.currentTime = props.trimStartMs! / 1000
  } else corrections = 0
  if (video.currentTime >= props.trimEndMs! / 1000) {
    if (video.currentTime > props.trimEndMs! / 1000) video.currentTime = props.trimEndMs! / 1000
    pause(); return
  }
  if (!video.paused) scheduleEnd()
}
function scheduleEnd(): void {
  stopTimer(); const video = videoEl.value
  if (!video || video.seeking || !trimmed.value || !validRange.value || video.paused) return
  endTimer = setTimeout(() => {
    if (videoEl.value !== video) return
    video.currentTime = props.trimEndMs! / 1000; pause()
  }, Math.max(0, (props.trimEndMs! - video.currentTime * 1000) / Math.max(0.1, video.playbackRate)))
}
function metadata(): void {
  corrections = 0
  if (videoEl.value && trimmed.value && validRange.value) videoEl.value.currentTime = props.trimStartMs! / 1000
}
function play(): void {
  const video = videoEl.value
  if (!video || !validRange.value || !active.value || !visible.value) { pause(); return }
  if (trimmed.value && !video.seeking && (video.currentTime < props.trimStartMs! / 1000 || video.currentTime >= props.trimEndMs! / 1000)) video.currentTime = props.trimStartMs! / 1000
  scheduleEnd()
}
function keyboardPlayback(event: KeyboardEvent): void {
  const video = videoEl.value
  if (event.repeat || !video || !active.value || !visible.value || !validRange.value) return
  if (!video.paused) { pause(); return }
  if (trimmed.value && (video.currentTime < props.trimStartMs! / 1000 || video.currentTime >= props.trimEndMs! / 1000)) {
    video.currentTime = props.trimStartMs! / 1000
  }
  const source = props.url; const generation = reloadKey.value
  void video.play().catch(() => {
    if (videoEl.value === video && props.url === source && reloadKey.value === generation && active.value) {
      pause(); failed.value = true
    }
  })
}
function visibilityChanged(): void { if (document.hidden) pause() }

let observer: IntersectionObserver | null = null

onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged)
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    visible.value = entries.some(entry => entry.isIntersecting)
    if (!visible.value) pause()
  })
  if (root.value) observer.observe(root.value)
})

onBeforeUnmount(() => {
  pause(); document.removeEventListener('visibilitychange', visibilityChanged)
  observer?.disconnect()
  observer = null
})

onDeactivated(() => { pause(); active.value = false })
onActivated(() => { active.value = true })

watch(() => [props.url, props.trimStartMs, props.trimEndMs], () => {
  pause()
  failed.value = false
  reloadKey.value += 1
})

function retry(): void {
  failed.value = false
  reloadKey.value += 1
  emit('media-error')
}

/** 控件交互不外泄（§8.2：交互控件内的按下保留原生行为）。 */
function onPointerDown(event: PointerEvent): void {
  event.stopPropagation()
}
</script>

<template>
  <div ref="root" class="take-preview" :data-test="dataTest" @pointerdown="onPointerDown">
    <video
      v-if="url && !failed && visible && active && validRange"
      :key="reloadKey"
      ref="videoEl"
      :src="url"
      class="take-preview-video"
      controls
      tabindex="0"
      aria-label="视频预览，空格播放或暂停"
      :muted="muted"
      playsinline
      preload="metadata"
      :data-test="`${dataTest}-video`"
      @error="pause(); failed = true"
      @loadedmetadata="metadata" @play="play" @pause="stopTimer" @timeupdate="enforceRange" @seeking="stopTimer" @seeked="enforceRange" @ratechange="scheduleEnd"
      @keydown.space.prevent.stop="keyboardPlayback"
      @pointerdown="onPointerDown"
    ></video>
    <div v-else-if="failed || !validRange" class="take-preview-fallback" :data-test="`${dataTest}-failed`">
      <span class="field-note">{{ validRange ? '媒体已失效或不可用' : '素材裁剪区间无效，请重新保存来源' }}</span>
      <button type="button" class="take-preview-retry" :data-test="`${dataTest}-retry`" @click="retry">重试</button>
    </div>
    <div v-else class="take-preview-fallback" :data-test="`${dataTest}-placeholder`">
      <span>{{ placeholder }}</span>
      <span v-if="note" class="field-note">{{ note }}</span>
    </div>
  </div>
</template>

<style scoped>
.take-preview {
  display: flex;
  flex-direction: column;
  gap: var(--space-xxs);
}

.take-preview-video {
  width: 100%;
  aspect-ratio: 9 / 16;
  max-height: var(--layout-rail);
  object-fit: contain;
  background: var(--color-media-backdrop);
  border-radius: var(--radius-sm);
}

.take-preview-fallback {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: var(--space-xxs);
  aspect-ratio: 9 / 16;
  max-height: var(--layout-rail);
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
}

.take-preview-retry {
  min-height: var(--touch-target);
  padding: 0 var(--space-md);
  border: var(--border-width) solid var(--color-border-control);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text);
  font-size: var(--type-body-sm);
  cursor: pointer;
}

.take-preview-retry:hover {
  border-color: var(--color-border-hover);
  background: var(--surface-hover);
}

</style>
