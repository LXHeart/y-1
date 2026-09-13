<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ShotMediaSource } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-13：每镜制作来源编辑器（§6.5 / TC-026/027 界面）。
 *
 * generated（付费生成）/ own-media（自有素材）二选一；own 需选素材并给裁剪区间
 * （整数毫秒，长度=镜头时长——UI 默认从 0 截取；单位秒展示、毫秒提交）与音轨策略；
 * 素材无原音时禁用 source 并说明；图片素材不出现裁剪输入。拖入画布只加参考，
 * 由本表单的明确「保存来源」动作选择制作来源（区别于参考线）。
 */
const props = defineProps<{
  shotId: string
  plannedSeconds: number
  /** 素材元信息（当前选中 own 素材；null=未选）。 */
  media: { id: string; name: string; isImage: boolean; durationMs: number | null; hasAudio: boolean } | null
  current: ShotMediaSource | null
  saving?: boolean
  error?: string
}>()

const emit = defineEmits<{
  (e: 'save', source: ShotMediaSource): void
  (e: 'stage', source: ShotMediaSource | null, dirty: boolean): void
}>()

type Kind = 'generated' | 'own-media'
const kind = ref<Kind>(props.current?.kind ?? 'generated')
const trimStartSeconds = ref(0)
const audioMode = ref<'source' | 'narration' | 'mute'>('narration')
const touched = ref(false)

watch(() => [props.shotId, props.current] as const, ([id, next], previous) => {
  if (id === previous?.[0] && touched.value && JSON.stringify(currentInput()) !== JSON.stringify(next ?? { kind: 'generated' })) return
  touched.value = false
  kind.value = next?.kind ?? 'generated'
  trimStartSeconds.value = next?.kind === 'own-media' ? (next.trimStartMs ?? 0) / 1000 : 0
  audioMode.value = next?.kind === 'own-media' ? next.audioMode : 'narration'
}, { immediate: true })

watch(() => props.media?.id, () => {
  if (props.media?.id && (props.current?.kind !== 'own-media' || props.media.id !== props.current.mediaId)) {
    touched.value = true
    trimStartSeconds.value = 0
  }
  if (props.media && (props.media.isImage || !props.media.hasAudio) && audioMode.value === 'source') {
    audioMode.value = 'narration'
  }
})

const durationMs = computed(() => props.media?.durationMs ?? null)
/** 可用截取窗口（秒）：0 ～ duration-镜头时长。 */
const maxStartSeconds = computed(() => {
  if (durationMs.value == null) return null
  return Math.max(0, (durationMs.value - props.plannedSeconds * 1000) / 1000)
})
const trimStartMs = computed(() => Math.round(trimStartSeconds.value * 1000))
const trimEndMs = computed(() => trimStartMs.value + props.plannedSeconds * 1000)
const sourceDisabled = computed(() => props.media != null && !props.media.hasAudio)
const isImage = computed(() => props.media?.isImage ?? false)
/** 素材不足（实测时长 < 镜头时长）：不可保存，说明原因（不循环不补假帧）。 */
const insufficient = computed(() => durationMs.value != null
  && durationMs.value < props.plannedSeconds * 1000)

const canSave = computed(() => {
  if (kind.value === 'generated') return true
  if (!props.media || insufficient.value) return false
  if (isImage.value) return audioMode.value !== 'source'
  if (audioMode.value === 'source' && sourceDisabled.value) return false
  return Number.isFinite(trimStartSeconds.value) && Number.isSafeInteger(trimStartMs.value)
    && trimStartSeconds.value >= 0 && (maxStartSeconds.value == null || trimStartSeconds.value <= maxStartSeconds.value)
})

function currentInput(): ShotMediaSource | null {
  if (!canSave.value) return null
  if (kind.value === 'generated') {
    return { kind: 'generated' }
  }
  if (!props.media) return null
  return {
    kind: 'own-media',
    mediaId: props.media.id,
    trimStartMs: isImage.value ? null : trimStartMs.value,
    trimEndMs: isImage.value ? null : trimEndMs.value,
    audioMode: isImage.value && audioMode.value === 'source' ? 'narration' : audioMode.value,
  }
}
watch(() => [touched.value, currentInput(), props.current] as const, ([edited, source, current]) => {
  emit('stage', source, edited && JSON.stringify(source) !== JSON.stringify(current ?? { kind: 'generated' }))
}, { deep: true })
function submit(): void {
  const source = currentInput()
  if (source && !props.saving) emit('save', source)
}
</script>

<template>
  <form class="shot-source-editor" data-test="canvas-source-editor" @submit.prevent="submit" @input="touched = true" @change="touched = true">
    <fieldset class="gl-field" :disabled="saving">
      <legend class="field-label">制作来源</legend>
      <label class="source-option">
        <input type="radio" value="generated" v-model="kind" data-test="canvas-source-kind-generated" />
        <span>付费生成（AI 候选）</span>
      </label>
      <label class="source-option">
        <input type="radio" value="own-media" v-model="kind" data-test="canvas-source-kind-own" />
        <span>使用自有素材{{ media ? `：${media.name}` : '（在上方选择）' }}</span>
      </label>
    </fieldset>

    <template v-if="kind === 'own-media'">
      <p v-if="!media" class="field-note" data-test="canvas-source-no-media">
        尚未选择素材，请在上方的制作来源选项中选择。
      </p>
      <template v-else>
        <p v-if="insufficient" class="field-note runbar-error" role="alert" data-test="canvas-source-insufficient">
          素材实测时长不足以截取 {{ plannedSeconds }} 秒（不循环、不补帧）
        </p>
        <fieldset v-if="!isImage" class="gl-field" :disabled="saving">
          <legend class="field-label">截取区间（{{ plannedSeconds }}s）</legend>
          <label class="gl-form-field">
            <span>起始秒（精确到 0.001 秒{{ maxStartSeconds == null ? '' : `，最多 ${maxStartSeconds} 秒` }}）</span>
            <input type="number" min="0" :max="maxStartSeconds ?? undefined" step="0.001"
              v-model.number="trimStartSeconds" data-test="canvas-source-trim" />
            <span class="gl-num" data-test="canvas-source-trim-label">
              {{ trimStartMs }}–{{ trimEndMs }} ms
            </span>
          </label>
        </fieldset>
        <p v-else class="field-note">图片素材按镜头时长展示，无裁剪。</p>
        <fieldset class="gl-field" :disabled="saving">
          <legend class="field-label">音轨</legend>
          <label v-if="!isImage" class="source-option">
            <input type="radio" value="source" v-model="audioMode" :disabled="sourceDisabled"
              data-test="canvas-source-audio-source" />
            <span>保留原音{{ sourceDisabled ? '（素材无原音，不可用）' : '' }}</span>
          </label>
          <label class="source-option">
            <input type="radio" value="narration" v-model="audioMode" data-test="canvas-source-audio-narration" />
            <span>AI 旁白（替换原音）</span>
          </label>
          <label class="source-option">
            <input type="radio" value="mute" v-model="audioMode" data-test="canvas-source-audio-mute" />
            <span>静音</span>
          </label>
        </fieldset>
      </template>
    </template>

    <p v-if="error" class="field-note runbar-error" role="alert" data-test="canvas-source-error">{{ error }}</p>
    <button type="submit" class="gl-btn-primary" :disabled="!canSave || saving"
      data-test="canvas-source-save">{{ saving ? '保存中…' : '保存来源' }}</button>
  </form>
</template>

<style scoped>
.shot-source-editor { display: flex; flex-direction: column; gap: var(--space-sm); min-width: 0; }
.source-option { display: flex; align-items: center; gap: var(--space-xs); min-height: var(--touch-target); }
.source-option input { width: var(--icon-size); height: var(--icon-size); flex: 0 0 var(--icon-size); accent-color: var(--color-accent); }
.source-option span { overflow-wrap: anywhere; }
</style>
