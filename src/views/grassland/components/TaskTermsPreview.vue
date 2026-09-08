<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { useGrassland } from '../../../composables/useGrassland'
import { useWorkbenchTaskPreview } from '../composables/useWorkbenchTaskPreview'
import TaskTermsPreviewCard from './TaskTermsPreviewCard.vue'

const props = defineProps<{ taskId: string; version?: number }>()
const { preview, loading, error, load } = useWorkbenchTaskPreview(useGrassland(), () => props.taskId, () => props.version ?? 0)
</script>

<template>
  <div :aria-busy="loading">
    <p v-if="loading" class="gl-empty" role="status">合作条款加载中…</p>
    <div v-else-if="error" class="gl-row">
      <p class="gl-hint" role="alert">{{ error }}</p>
      <button type="button" title="重新加载合作条款" aria-label="重新加载合作条款" @click="load"><RefreshCw :size="16" /></button>
    </div>
    <TaskTermsPreviewCard v-else-if="preview" :preview="preview" />
  </div>
</template>
