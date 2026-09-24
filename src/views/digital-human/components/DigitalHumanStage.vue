<template>
  <section class="dh-stage" aria-labelledby="dh-stage-title" data-testid="dh-stage">
    <div class="dh-stage-head">
      <h3 id="dh-stage-title" class="dh-stage-title">数字人画面</h3>
      <span class="dh-ai-badge" data-testid="dh-ai-badge">
        AI 生成<template v-if="testOnly"> · 测试画面</template>
      </span>
    </div>
    <div class="dh-stage-media" :class="{ 'dh-stage-error': errorCode }">
      <video
        ref="videoElement"
        class="dh-stage-video"
        autoplay
        playsinline
        :muted="false"
        :data-testid="'dh-stage-video'"
        @error="onMediaError"
      />
      <p v-if="connecting" class="dh-stage-overlay" role="status">正在连接数字人…</p>
      <button
        v-else-if="needsGesture"
        type="button"
        class="gl-btn-primary dh-stage-gesture"
        data-testid="dh-stage-play"
        @click="playStream"
      >
        点击播放
      </button>
      <p v-else-if="errorCode" class="dh-stage-overlay dh-stage-overlay-error" role="alert" data-testid="dh-stage-error">
        {{ errorText }}
        <button type="button" class="gl-link" @click="emit('reconnect')">重新连接</button>
      </p>
      <p v-else-if="!stream && !connecting" class="dh-stage-overlay" role="status">等待媒体…</p>
    </div>
    <p v-if="stateLabel" class="dh-hint">会话状态：{{ stateLabel }}</p>
  </section>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：媒体播放与 AI 标识；autoplay 失败显示「点击播放」而非静默无声（E-03 步骤2）。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { MediaStreamLike } from '../composables/useDigitalHumanMedia'

const props = defineProps<{
  stream: MediaStreamLike | null
  state: string | null
  connecting?: boolean
  errorCode?: string | null
  errorMessage?: string | null
  /** mock/test_only 后端加「测试画面」标注（K11）。 */
  testOnly?: boolean
}>()

const emit = defineEmits<{ (e: 'reconnect'): void }>()

const videoElement = ref<HTMLVideoElement | null>(null)
const needsGesture = ref(false)
const mediaError = ref(false)

const STATE_LABELS: Record<string, string> = {
  preparing: '准备中', queued: '排队中', connecting: '连接中', ready: '就绪', listening: '聆听中',
  responding: '回应中', paused: '已暂停', reconnecting: '重连中', ending: '结束中', ended: '已结束',
  failed: '失败',
}

const stateLabel = computed(() => (props.state ? STATE_LABELS[props.state] ?? props.state : null))
const errorText = computed(() => props.errorMessage ?? '媒体连接失败，可重试。')

function attemptPlay(): void {
  const video = videoElement.value
  if (!video) return
  video.play()?.catch(() => {
    // 自动播放被拒：不冒充 ready，给出显式手动动作。
    needsGesture.value = true
  })
}

function playStream(): void {
  const video = videoElement.value
  if (!video) return
  needsGesture.value = false
  video.play()?.catch(() => {
    needsGesture.value = true
  })
}

function attachStream(stream: MediaStreamLike | null): void {
  const video = videoElement.value
  if (!video) return
  if (stream) {
    video.srcObject = stream as unknown as MediaStream
    attemptPlay()
  } else {
    video.srcObject = null
  }
}

watch(() => props.stream, (stream) => {
  mediaError.value = false
  needsGesture.value = false
  attachStream(stream)
}, { immediate: true })

// immediate watch 在挂载前拿不到 video ref：挂载后补一次挂流。
onMounted(() => { attachStream(props.stream) })

function onMediaError(): void {
  mediaError.value = true
}

onBeforeUnmount(() => {
  const video = videoElement.value
  if (video) video.srcObject = null
})
</script>

<style scoped>
.dh-stage { display: grid; gap: var(--space-xs); }
.dh-stage-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.dh-stage-title {
  margin: 0; font-family: var(--font-display); font-size: var(--type-card-title);
  font-weight: var(--weight-heading); color: var(--color-text);
}
.dh-ai-badge {
  padding: var(--space-xxs) var(--space-xs); border-radius: var(--radius-sm);
  background: var(--surface-info); color: var(--color-info); font-size: var(--type-caption);
}
.dh-stage-media {
  position: relative; aspect-ratio: 16 / 9; border-radius: var(--radius-lg);
  background: var(--color-media-backdrop); overflow: hidden;
}
.dh-stage-video { width: 100%; height: 100%; object-fit: contain; }
.dh-stage-overlay {
  position: absolute; inset: 0; display: grid; place-items: center; margin: 0;
  color: var(--color-media-ink); font-size: var(--type-body-sm);
  background: color-mix(in srgb, var(--color-media-backdrop) 72%, transparent);
}
.dh-stage-overlay-error { gap: var(--space-xs); }
.dh-stage-gesture { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); }
.dh-stage-error { outline: 1px solid var(--color-danger); }
</style>
