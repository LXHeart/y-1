<script setup lang="ts">
import { computed } from 'vue'
import type { CreationBrief } from '../../types/creation'
import { revisionDiff, reviewFacts } from '../../composables/useCreationRevisionReview'
const props = defineProps<{ original: string; edited: string; brief?: CreationBrief | null; disabled?: boolean; previewOnly?: boolean }>()
defineEmits<{ keep: []; restore: [] }>()
const diff = computed(() => revisionDiff(props.original, props.edited))
const facts = computed(() => reviewFacts(props.brief))
</script>
<template>
  <section class="gl-zone" data-testid="creation-revision-review">
    <h3>改稿复核</h3>
    <p>请核对数字、否定、条件与身份，自动对比不能验证全部事实。</p>
    <details open><summary>原始事实清单</summary><ul v-if="facts.length"><li v-for="(fact, index) in facts" :key="index">{{ fact }}</li></ul><p v-else>尚未填写确认事实，请对照原始材料。</p></details>
    <details open><summary>原稿与修改稿</summary><p>原稿</p><pre>{{ original }}</pre><p>修改稿</p><pre>{{ edited }}</pre></details>
    <p aria-label="字符差异" class="revision-diff">{{ diff.prefix }}<del>{{ diff.removed }}</del><ins>{{ diff.added }}</ins>{{ diff.suffix }}</p>
    <p v-if="original === edited">原稿未改动，可保持原文。</p>
    <div v-if="!previewOnly" class="gl-row"><button type="button" class="secondary-command" :disabled="disabled" @click="$emit('keep')">保留修改</button><button type="button" class="secondary-command" :disabled="disabled" @click="$emit('restore')">恢复原稿</button></div>
  </section>
</template>
<style scoped>
pre, .revision-diff { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
del { background: var(--surface-danger); color: var(--color-danger); }
ins { background: var(--surface-success); color: var(--color-success); }
</style>
