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
}>()

type Kind = 'generated' | 'own-media'
const kind = ref<Kind>(props.current?.kind ?? 'generated')
const trimStartSeconds = ref(0)
const audioMode = ref<'source' | 'narration' | 'mute'>('narration')

watch(() => props.current, next => {
  kind.value = next?.kind ?? 'generated'
  if (next?.kind === 'own-media') {
    trimStartSeconds.value = Math.floor((next.trimStartMs ?? 0) / 1000)
    audioMode.value = next.audioMode
  }
}, { immediate: true })

watch(() => props.media?.id, () => {
  trimStartSeconds.value = 0
  if (props.media && !props.media.hasAudio && audioMode.value === 'source') {
    audioMode.value = 'narration'
  }
})

const durationMs = computed(() => props.media?.durationMs ?? null)
/** 可用截取窗口（秒）：0 ～ duration-镜头时长。 */
const maxStartSeconds = computed(() => {
  if (durationMs.value == null) return 0
  return Math.max(0, Math.floor((durationMs.value - props.plannedSeconds * 1000) / 1000))
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
  return trimStartSeconds.value >= 0 && trimStartSeconds.value <= maxStartSeconds.value
})

function submit(): void {
  if (!canSave.value || props.saving) return
  if (kind.value === 'generated') {
    emit('save', { kind: 'generated' })
    return
  }
  if (!props.media) return
  emit('save', {
    kind: 'own-media',
    mediaId: props.media.id,
    trimStartMs: isImage.value ? null : trimStartMs.value,
    trimEndMs: isImage.value ? null : trimEndMs.value,
    audioMode: isImage.value && audioMode.value === 'source' ? 'narration' : audioMode.value,
  })
}
</script>

<template>
  <form class="shot-source-editor" data-test="canvas-source-editor" @submit.prevent="submit">
    <fieldset class="gl-field">
      <legend class="field-label">制作来源</legend>
      <label class="source-option">
        <input type="radio" value="generated" v-model="kind" data-test="canvas-source-kind-generated" />
        <span>付费生成（AI 候选）</span>
      </label>
      <label class="source-option">
        <input type="radio" value="own-media" v-model="kind" data-test="canvas-source-kind-own" />
        <span>使用自有素材{{ media ? `：${media.name}` : '（先从素材轨选择）' }}</span>
      </label>
    </fieldset>

    <template v-if="kind === 'own-media'">
      <p v-if="!media" class="field-note" data-test="canvas-source-no-media">
        尚未选择素材——从左侧素材轨选择后在此指定制作来源
      </p>
      <template v-else>
        <p v-if="insufficient" class="field-note runbar-error" role="alert" data-test="canvas-source-insufficient">
          素材实测时长不足以截取 {{ plannedSeconds }} 秒（不循环、不补帧）
        </p>
        <fieldset v-if="!isImage" class="gl-field">
          <legend class="field-label">截取区间（{{ plannedSeconds }}s）</legend>
          <label class="field-note">
            起始秒（0 ～ {{ maxStartSeconds }}s）
            <input type="range" min="0" :max="maxStartSeconds" step="1"
              v-model.number="trimStartSeconds" data-test="canvas-source-trim" />
            <span class="gl-num" data-test="canvas-source-trim-label">
              {{ trimStartMs }}–{{ trimEndMs }} ms
            </span>
          </label>
        </fieldset>
        <p v-else class="field-note">图片素材按镜头时长展示，无裁剪。</p>
        <fieldset class="gl-field">
          <legend class="field-label">音轨</legend>
          <label class="source-option">
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
