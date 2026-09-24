<template>
  <form class="gl-field dh-profile-form" novalidate @submit.prevent="onSubmit" data-testid="dh-profile-form">
    <div class="gl-form-field">
      <label class="field-label" for="dh-profile-name">角色名称 <span class="gl-num">{{ codePoints(fields.name) }}/{{ PROFILE_LIMITS.name }}</span></label>
      <input
        id="dh-profile-name"
        v-model="fields.name"
        type="text"
        :readonly="readonly"
        :disabled="saving"
        :aria-invalid="errors.name ? 'true' : undefined"
        aria-describedby="dh-profile-name-error"
        data-testid="dh-profile-name"
        @compositionend="syncAfterIme"
      />
      <p v-if="errors.name" id="dh-profile-name-error" class="gl-alert gl-alert-error" role="alert">{{ errors.name }}</p>
    </div>

    <div class="gl-form-field">
      <label class="field-label" for="dh-profile-persona">
        人设描述 <span class="gl-num">{{ codePoints(fields.persona) }}/{{ PROFILE_LIMITS.persona }}</span>
      </label>
      <textarea
        id="dh-profile-persona"
        v-model="fields.persona"
        rows="5"
        :readonly="readonly"
        :disabled="saving"
        :aria-invalid="errors.persona ? 'true' : undefined"
        aria-describedby="dh-profile-persona-error"
        data-testid="dh-profile-persona"
      />
      <p v-if="errors.persona" id="dh-profile-persona-error" class="gl-alert gl-alert-error" role="alert">{{ errors.persona }}</p>
    </div>

    <div class="gl-form-field">
      <label class="field-label" for="dh-profile-greeting">
        开场白（可选） <span class="gl-num">{{ codePoints(fields.greeting) }}/{{ PROFILE_LIMITS.greeting }}</span>
      </label>
      <input
        id="dh-profile-greeting"
        v-model="fields.greeting"
        type="text"
        :readonly="readonly"
        :disabled="saving"
        :aria-invalid="errors.greeting ? 'true' : undefined"
        aria-describedby="dh-profile-greeting-error"
        data-testid="dh-profile-greeting"
      />
      <p v-if="errors.greeting" id="dh-profile-greeting-error" class="gl-alert gl-alert-error" role="alert">{{ errors.greeting }}</p>
    </div>

    <div class="gl-form-grid">
      <div class="gl-form-field">
        <label class="field-label" for="dh-profile-tone">语气</label>
        <select id="dh-profile-tone" v-model="fields.tone" :disabled="saving || readonly" data-testid="dh-profile-tone">
          <option value="natural">自然</option>
          <option value="professional">专业</option>
          <option value="friendly">亲切</option>
        </select>
      </div>

      <div class="gl-form-field">
        <label class="field-label" for="dh-profile-voice">音色</label>
        <select
          id="dh-profile-voice"
          v-model="fields.voiceId"
          :disabled="saving || readonly"
          :aria-invalid="errors.voiceId ? 'true' : undefined"
          aria-describedby="dh-profile-voice-error"
          data-testid="dh-profile-voice"
        >
          <option value="" disabled>请选择音色</option>
          <option v-for="voice in selectableVoices" :key="voice.id" :value="voice.id">
            {{ voice.name }}（{{ voice.language }}）
          </option>
        </select>
        <p v-if="errors.voiceId" id="dh-profile-voice-error" class="gl-alert gl-alert-error" role="alert">{{ errors.voiceId }}</p>
        <div v-if="!readonly" class="dh-voice-preview">
          <button
            type="button"
            class="gl-btn-secondary"
            :disabled="!fields.voiceId || previewState === 'requesting'"
            data-testid="dh-voice-preview-button"
            @click="emit('preview', fields.voiceId)"
          >
            {{ previewState === 'requesting' ? '试听准备中…' : '试听该音色' }}
          </button>
          <audio
            v-if="previewUrl"
            :src="previewUrl"
            controls
            data-testid="dh-voice-preview-audio"
            @error="emit('preview-ended')"
          />
          <p v-if="previewError" class="gl-alert gl-alert-error" role="alert">{{ previewError }}</p>
        </div>
      </div>
    </div>

    <DigitalHumanAvatarPicker
      v-model="fields.avatarId"
      :avatars="avatars"
      :approved-backend-ids="approvedBackendIds"
      :disabled="saving || readonly"
    />
    <p v-if="errors.avatarId" class="gl-alert gl-alert-error" role="alert">{{ errors.avatarId }}</p>

    <p v-if="saveError" class="gl-alert gl-alert-error" role="alert" data-testid="dh-profile-save-error">
      {{ saveError }}
      <template v-if="versionConflict">本地内容已保留；其他页面已更新该角色，请重新载入后再保存。</template>
    </p>
    <p v-if="savedNotice" class="gl-alert gl-alert-ok" role="status">{{ savedNotice }}</p>

    <div class="gl-actions" v-if="!readonly">
      <button type="submit" class="gl-btn-primary" :disabled="saving" data-testid="dh-profile-save">
        {{ saving ? '保存中…' : (profile ? '保存修改' : '创建角色') }}
      </button>
      <button
        v-if="versionConflict"
        type="button"
        class="gl-btn-secondary"
        data-testid="dh-profile-reload"
        @click="emit('reload')"
      >
        重新载入最新版本
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
// 纯 props/emits（K11）：本地字段态（失败不重置=失败保输入）；保存/试听/重载动作交给父级。
import { computed, reactive, watch } from 'vue'
import DigitalHumanAvatarPicker from './DigitalHumanAvatarPicker.vue'
import {
  codePoints, draftOf, emptyDraft, PROFILE_LIMITS, validateProfileInput,
} from '../composables/useDigitalHumanProfiles'
import type {
  AvatarItem, Profile, ProfileInput, Tone, VoiceItem,
} from '../../../types/digital-human'
import type { PreviewState } from '../composables/useDigitalHumanProfiles'

const props = defineProps<{
  profile: Profile | null
  catalogVersion: number
  avatars: AvatarItem[]
  voices: VoiceItem[]
  approvedBackendIds: string[]
  readonly?: boolean
  saving?: boolean
  saveError?: string | null
  versionConflict?: boolean
  savedNotice?: string | null
  previewState?: PreviewState
  previewUrl?: string | null
  previewError?: string | null
}>()

const emit = defineEmits<{
  (e: 'save', input: ProfileInput): void
  (e: 'preview', voiceId: string): void
  (e: 'reload'): void
  (e: 'preview-ended'): void
}>()

// 本地字段态：seed 自 profile（null=新建）；保存失败不清空（失败保输入）。
const fields = reactive<ProfileInput>(props.profile
  ? draftOf(props.profile)
  : emptyDraft(props.catalogVersion))
const errors = reactive<Record<string, string>>({})

// 外部切换编辑目标（或新建）时重置本地态；同一 profile 的版本刷新不覆盖用户输入。
watch(() => props.profile?.id ?? null, (id, previousId) => {
  if (id === previousId) return
  resetFields()
})

function resetFields(): void {
  const seeded = props.profile ? draftOf(props.profile) : emptyDraft(props.catalogVersion)
  Object.assign(fields, seeded)
  clearErrors()
}

function clearErrors(): void {
  for (const key of Object.keys(errors)) delete errors[key]
}

/** IME 组合期不校验不提交（compositionend 后再同步一次）。 */
function syncAfterIme(): void {
  clearErrors()
}

function onSubmit(): void {
  clearErrors()
  const input: ProfileInput = {
    ...fields,
    name: fields.name.trimStart().trimEnd(),
    persona: fields.persona.trimStart().trimEnd(),
    greeting: fields.greeting.trimStart().trimEnd(),
    tone: fields.tone as Tone,
    catalogVersion: props.profile?.catalogVersion ?? props.catalogVersion,
  }
  const validation = validateProfileInput(input)
  if (Object.keys(validation).length > 0) {
    Object.assign(errors, validation)
    return
  }
  emit('save', input)
}

// 可选音色 = enabled 且与 approved 后端兼容（K02；不展示任意模型 URL）。
const selectableVoices = computed(() =>
  props.voices.filter((voice) => voice.enabled
    && voice.compatibleBackendIds.some((id) => props.approvedBackendIds.includes(id))))
</script>

<style scoped>
.dh-profile-form { display: grid; gap: var(--space-sm); }
.dh-voice-preview { display: grid; gap: var(--space-xxs); }
</style>
