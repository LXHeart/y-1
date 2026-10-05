<script setup lang="ts">
import { computed } from 'vue'
import { contractDisplayRows, type ContractSnapshot } from '../../../lib/task-contract'
import type { TaskPreview } from '../../../types/grassland'

const props = defineProps<{ terms: ContractSnapshot; preview?: TaskPreview }>()
const rows = computed(() => contractDisplayRows(props.terms, props.preview))
</script>

<template>
  <dl class="contract-summary" data-testid="contract-summary">
    <div v-for="row in rows" :key="row.key" :data-contract-field="row.key">
      <dt>{{ row.label }}</dt>
      <dd v-for="(line, index) in row.lines" :key="index">{{ line }}</dd>
    </div>
  </dl>
</template>

<style scoped>
.contract-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-md); margin: 0; overflow-wrap: anywhere; }
.contract-summary dt { font-size: var(--type-body-sm); color: var(--color-text-secondary); }
.contract-summary dd { margin: var(--space-xxs) 0 0; font-size: var(--type-body-sm); }
@media (max-width: 768px) { .contract-summary { grid-template-columns: minmax(0, 1fr); } }
</style>
