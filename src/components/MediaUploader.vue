<script setup lang="ts">
import { ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'

/**
 * 可复用素材上传组件（PRD §4.8 / Slice 14）。
 *
 * 融合 EngagementSubmissionPanel 的「选中即上传 + staged 列表」与 ImageGenerationView 的本地预览，
 * 底层复用 useGrassland.uploadContentAssetFile（intelligence 三步直传，purpose=content_asset）。
 *
 * 不负责挂接——confirm 后返回 mediaId 列表给父组件，由父组件按 libraryType 决定何时挂接
 * （个人/商家库可立即 createContentAsset，公共库需带 source/license/validUntil）。
 */

const props = withDefaults(defineProps<{
  /** 单文件上限（字节），默认 20MB，与后端 MEDIA_MAX_OBJECT_BYTES 一致。 */
  maxBytes?: number
  /** 最多暂存几个，默认 6（与 CreateSubmissionRequest.MAX_MEDIA 一致）。 */
  maxFiles?: number
  /** 接受的文件类型，默认图片/视频/PDF。 */
  accept?: string
}>(), {
  maxBytes: 20 * 1024 * 1024,
  maxFiles: 6,
  accept: 'image/*,video/*,application/pdf',
})

const emit = defineEmits<{
  /** 已 confirm 的 mediaId 列表变化（增删都触发）。 */
  change: [mediaIds: string[]]
}>()

const grassland = useGrassland()
const uploading = ref(false)
const uploadError = ref('')

/** 已 confirm、暂存待挂接的资产。 */
interface StagedItem {
  mediaId: string
  name: string
  sizeBytes: number
}
const staged = ref<StagedItem[]>([])

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 逐个上传选中的文件（串行，与 EngagementSubmissionPanel 同——并发占配额预留难对账）。 */
async function onPickFiles(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (files.length === 0) return

  uploadError.value = ''
  for (const file of files) {
    if (staged.value.length >= props.maxFiles) {
      uploadError.value = `最多 ${props.maxFiles} 个，其余已跳过`
      break
    }
    if (file.size === 0) {
      uploadError.value = `${file.name} 是空文件，已跳过`
      continue
    }
    if (file.size > props.maxBytes) {
      uploadError.value = `${file.name} 超过 ${formatSize(props.maxBytes)} 上限，已跳过`
      continue
    }

    uploading.value = true
    const mediaId = await grassland.uploadContentAssetFile(file)
    uploading.value = false

    if (!mediaId) {
      uploadError.value = grassland.error.value || `${file.name} 上传失败`
      continue
    }
    staged.value = [...staged.value, { mediaId, name: file.name, sizeBytes: file.size }]
    emit('change', staged.value.map((item) => item.mediaId))
  }
}

function removeStaged(mediaId: string): void {
  staged.value = staged.value.filter((item) => item.mediaId !== mediaId)
  emit('change', staged.value.map((item) => item.mediaId))
}

/** 重置（父组件挂接成功后调用，清空暂存区）。 */
function reset(): void {
  staged.value = []
  uploadError.value = ''
  emit('change', [])
}

defineExpose({ reset, staged })
</script>

<template>
  <div class="uploader">
    <p v-if="uploadError || grassland.error.value" class="uploader-err" role="alert">
      {{ uploadError || grassland.error.value }}
    </p>

    <ul v-if="staged.length > 0" class="uploader-staged">
      <li v-for="item in staged" :key="item.mediaId">
        <span class="uploader-name">{{ item.name }}</span>
        <span class="uploader-size">{{ formatSize(item.sizeBytes) }}</span>
        <button type="button" @click="removeStaged(item.mediaId)">移除</button>
      </li>
    </ul>

    <label class="uploader-pick">
      <input
        type="file"
        multiple
        :accept="accept"
        :disabled="uploading || staged.length >= maxFiles"
        @change="onPickFiles"
      />
      <span>{{ uploading ? '上传中…' : `添加素材（${staged.length}/${maxFiles}）` }}</span>
    </label>
  </div>
</template>

<style scoped>
.uploader { display: flex; flex-direction: column; gap: 8px; }
.uploader-err { margin: 0; padding: 6px 10px; border-radius: 6px; font-size: 12px; background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.uploader-staged { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.uploader-staged li { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 4px 8px; border-radius: 6px; background: var(--color-surface-strong); }
.uploader-name { flex: 1 1 auto; word-break: break-all; }
.uploader-size { opacity: 0.6; white-space: nowrap; }
.uploader-staged button { padding: 2px 10px; font-size: 12px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; }
.uploader-pick { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
.uploader-pick input[type="file"] { display: none; }
.uploader-pick span { padding: 6px 14px; border: 1px dashed var(--color-border); border-radius: 6px; }
.uploader-pick input:disabled + span { opacity: 0.5; cursor: not-allowed; }
</style>
