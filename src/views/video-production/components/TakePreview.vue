<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

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
}>(), {
  placeholder: '',
  note: null,
  dataTest: 'take-preview',
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

let observer: IntersectionObserver | null = null

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    visible.value = entries.some(entry => entry.isIntersecting)
    if (!visible.value) videoEl.value?.pause()
  })
  if (root.value) observer.observe(root.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

watch(() => props.url, () => {
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
      v-if="url && !failed && visible"
      :key="reloadKey"
      ref="videoEl"
      :src="url"
      class="take-preview-video"
      controls
      muted
      playsinline
      preload="metadata"
      :data-test="`${dataTest}-video`"
      @error="failed = true"
      @pointerdown="onPointerDown"
    ></video>
    <div v-else-if="failed" class="take-preview-fallback" :data-test="`${dataTest}-failed`">
      <span class="field-note">媒体已失效或不可用</span>
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
  max-height: 260px;
  object-fit: contain;
  background: var(--color-surface-strong);
  border-radius: var(--radius-sm);
}

.take-preview-fallback {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: var(--space-xxs);
  aspect-ratio: 9 / 16;
  max-height: 260px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.take-preview-retry {
  min-height: 38px;
  padding: 0 var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text);
  font-size: var(--text-sm);
  cursor: pointer;
}

.take-preview-retry:hover {
  border-color: var(--color-border-hover);
  background: var(--surface-hover);
}

/* §8.4：窄屏触控目标 ≥44px */
@media (max-width: 767px) {
  .take-preview-retry {
    min-height: 44px;
  }
}
</style>
