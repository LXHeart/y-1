<template>
  <section class="xhs-tags-editor" aria-label="话题标签">
    <header class="xhs-block-head">
      <div>
        <h2>话题标签</h2>
        <p class="gl-hint">{{ tagHint }}</p>
      </div>
      <p class="xhs-tags-count"><span class="gl-num">{{ topics.length }}</span>/{{ effectiveMax }} 个</p>
    </header>

    <p v-if="topics.length === 0" class="gl-empty">还没有话题标签：正文出现 #话题 后自动带出，也可在下方手动添加。</p>

    <ul v-else class="xhs-tags-list">
      <li v-for="(tag, index) in topics" :key="`${tag}-${index}`" class="xhs-tag-chip">
        <span class="xhs-tag-text"># {{ tag }}</span>
        <button
          type="button"
          class="xhs-tag-remove"
          :aria-label="`删除话题 ${tag}`"
          :disabled="disabled"
          @click="remove(index)"
        >×</button>
      </li>
    </ul>

    <div v-if="topics.length < effectiveMax" class="gl-row xhs-tag-add">
      <input
        v-model="draft"
        type="text"
        placeholder="输入话题（回车或点添加）"
        :disabled="disabled"
        @keydown.enter.prevent="add"
      >
      <button type="button" :disabled="disabled || !draft.trim()" @click="add">添加</button>
    </div>
    <p v-else class="gl-hint">已达上限 {{ effectiveMax }} 个，删除后可再添加。</p>

    <p v-if="addError" class="xhs-tags-error" role="alert">{{ addError }}</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 话题标签 chips 编辑（方案 §4.2 工程师 A，featureId=gen-tags reuse）：
 * deliveryDraft.topics 真实交付字段——编辑经 autosave.updateDelivery({topics}) 写入、
 * 持久化于 delivery；缺省 parseHashtagTopics 从正文 #话题 行派生（useArticleWorkspace）。
 *
 * 规范沿用 parseHashtagTopics 口径：单个 ≤30 字、上限 10 个；输入去 # 前缀；
 * 契约 tagHint（3-8 个话题）作常读建议。标签推荐库无数据源，不做（defer）。
 */
import { computed, ref } from 'vue'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'

const props = withDefaults(defineProps<{
  /** 仅测试注入口（F7）：缺省取 autosave.deliveryValue.topics。 */
  topics?: string[]
  maxCount?: number
  disabled?: boolean
}>(), {
  topics: undefined,
  maxCount: 10,
  disabled: undefined,
})

const emit = defineEmits<{
  update: [topics: string[]]
}>()

const ctx = useXhsStudioContext()

/** 单个话题长度上限（parseHashtagTopics 同口径）。 */
const TAG_MAX_LENGTH = 30

const topics = computed(() => props.topics ?? ctx.autosave.deliveryValue.value.topics ?? [])
const disabled = computed(() => props.disabled ?? ctx.autosave.readonly.value)
const effectiveMax = computed(() => props.maxCount)

const tagHint = computed(() =>
  ctx.format.formatRule.value?.tagHint ?? '正文末尾添加 3-8 个话题标签。')

const draft = ref('')
const addError = ref('')

/** 去重判断以规范化后的值为准（用户可能带 # 输入）。 */
function normalize(value: string): string {
  return value.replace(/^#+/, '').trim().slice(0, TAG_MAX_LENGTH)
}

function commit(next: string[]): void {
  emit('update', next)
  ctx.autosave.updateDelivery({ topics: next })
}

function add(): void {
  const value = normalize(draft.value)
  if (!value) return
  if (topics.value.includes(value)) {
    addError.value = `话题「${value}」已添加过`
    return
  }
  if (topics.value.length >= effectiveMax.value) return
  addError.value = ''
  commit([...topics.value, value])
  draft.value = ''
}

function remove(index: number): void {
  commit(topics.value.filter((_, i) => i !== index))
}
</script>

<style scoped>
.xhs-tags-editor {
  display: grid;
  gap: var(--space-sm);
}

.xhs-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.xhs-block-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-block-head .gl-hint {
  margin: var(--space-xxs) 0 0;
}

.xhs-tags-count {
  flex: 0 0 auto;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.xhs-tags-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.xhs-tag-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xxs);
  min-height: 32px;
  padding: 0 var(--space-xs) 0 var(--space-sm);
  border: 1px solid color-mix(in srgb, var(--color-accent) 45%, var(--color-border));
  border-radius: var(--radius-pill);
  background: var(--color-surface);
}

.xhs-tag-text {
  color: var(--color-accent-2);
  font-size: var(--type-body-sm);
  overflow-wrap: anywhere;
}

.xhs-tag-remove {
  min-height: 24px;
  padding: 0 var(--space-xxs);
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: var(--type-caption);
  line-height: 1;
}

.xhs-tag-remove:hover:not(:disabled) {
  color: var(--color-danger);
  background: var(--color-surface-hover);
}

.xhs-tags-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--type-caption);
}
</style>
