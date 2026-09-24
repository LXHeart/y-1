<template>
  <!-- 自有形象上传区（任务书 #105F C105F-04 / K03 API30、K09、K14.3）：文件与授权选择在组件内，
       上传/处理编排由 useDigitalHumanAvatar 驱动。授权不默认勾选；失败保留文件与授权供重试；
       进度只反映真实阶段（上传中/处理中），无虚百分比。 -->
  <div class="dh-avatar-upload" data-testid="dh-avatar-upload">
    <div class="gl-form-field">
      <label class="field-label" for="dh-avatar-file">上传自有形象图片</label>
      <input
        id="dh-avatar-file"
        class="gl-input"
        type="file"
        accept="image/jpeg,image/png"
        data-testid="dh-avatar-file"
        @change="onFileChange"
      />
      <p class="dh-hint">JPG / PNG，不超过 10MiB；需要包含单张清晰人脸。</p>
    </div>

    <label class="dh-recording-consent">
      <input
        v-model="rightsAccepted"
        type="checkbox"
        data-testid="dh-avatar-rights"
      />
      <span>
        我确认对上传图片拥有使用权，且图中人物为本人或已获得其授权（权利声明版本
        {{ rightsVersion }}）。
      </span>
    </label>

    <p v-if="error" class="gl-alert gl-alert-error" role="alert" data-testid="dh-avatar-error">{{ error }}</p>
    <p v-if="stage === 'uploading'" class="dh-notice" role="status" data-testid="dh-avatar-uploading">
      正在上传图片…
    </p>
    <p v-else-if="stage === 'processing'" class="dh-notice" role="status" data-testid="dh-avatar-processing">
      正在处理形象（检测人脸并规范化）…
    </p>
    <p v-else-if="stage === 'ready'" class="dh-recording-meta" data-testid="dh-avatar-ready">
      <span class="badge badge-success">形象已就绪</span>
      已加入可选形象列表（保存角色时选择）。
    </p>

    <div class="gl-actions">
      <button
        type="button"
        class="gl-btn-secondary"
        :disabled="!canSubmit || busy"
        data-testid="dh-avatar-submit"
        @click="handleSubmit"
      >
        {{ busy ? '处理中…' : '上传并处理' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AvatarStage } from '../composables/useDigitalHumanAvatar'

const props = withDefaults(defineProps<{
  stage: AvatarStage
  error: string | null
  rightsVersion: string
}>(), {
  error: null,
})

const emit = defineEmits<{
  (e: 'submit', file: File): void
}>()

const selectedFile = ref<File | null>(null)
const rightsAccepted = ref(false) // 不默认勾选（K09/K14.3：显式授权）

const busy = computed(() => props.stage === 'uploading' || props.stage === 'processing')
const canSubmit = computed(() => selectedFile.value != null && rightsAccepted.value)

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files != null && input.files.length > 0 ? input.files[0] : null
}

function handleSubmit(): void {
  const file = selectedFile.value
  if (file == null || !rightsAccepted.value || busy.value) return // 未确认不提交
  emit('submit', file)
}
</script>
