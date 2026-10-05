<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { useGrassland } from '../../../composables/useGrassland'
import { useWorkbenchTaskPreview } from '../composables/useWorkbenchTaskPreview'
import TaskTermsPreviewCard from './TaskTermsPreviewCard.vue'
import TaskContractSummary from './TaskContractSummary.vue'

const props = defineProps<{ taskId: string; version?: number; acceptedApplicationId?: string | null }>()
const { preview, acceptedTerms, loading, error, load } = useWorkbenchTaskPreview(
  useGrassland(), () => props.taskId, () => props.version ?? 0, () => props.acceptedApplicationId ?? null)
</script>

<template>
  <div :aria-busy="loading">
    <p v-if="loading" class="gl-empty" role="status">合作条款加载中…</p>
    <div v-else-if="error" class="gl-row">
      <p class="gl-hint" role="alert">{{ error }}</p>
      <button type="button" title="重新加载合作条款" aria-label="重新加载合作条款" @click="load"><RefreshCw :size="16" /></button>
    </div>
    <section v-else-if="acceptedTerms" aria-label="已接受的合同">
      <p class="gl-hint">已接受的合同 · 任务版本 {{ acceptedTerms.taskVersion }}，以接受时的快照为准</p>
      <TaskContractSummary :terms="acceptedTerms" />
    </section>
    <TaskTermsPreviewCard v-else-if="preview" :preview="preview" />
  </div>
</template>
