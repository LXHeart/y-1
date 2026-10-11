<template>
  <section class="zhihu-tags-editor" aria-label="话题标签">
    <header class="zhihu-block-head">
      <div>
        <h2>话题标签</h2>
        <p class="gl-hint">知乎文章可加 3-5 个话题；回答挂在问题下，不单独加话题。</p>
      </div>
      <p class="zhihu-tags-count"><span class="gl-num">{{ topics.length }}</span>/{{ effectiveMax }} 个</p>
    </header>

    <p v-if="topics.length === 0" class="gl-empty" data-test="zhihu-tags-empty">
      还没有话题：正文出现 #话题 后自动带出，也可在下方手动添加。
    </p>

    <ul v-else class="zhihu-tags-list">
      <li v-for="(tag, index) in topics" :key="`${tag}-${index}`" class="zhihu-tag-chip">
        <span class="zhihu-tag-text"># {{ tag }}</span>
        <button
          type="button"
          class="zhihu-tag-remove"
          :aria-label="`删除话题 ${tag}`"
          :disabled="disabled"
          @click="remove(index)"
        >×</button>
      </li>
    </ul>

    <div v-if="topics.length < effectiveMax" class="gl-row zhihu-tag-add">
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

    <p v-if="addError" class="zhihu-tags-error" role="alert">{{ addError }}</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 话题标签 chips 编辑（文章模式专用，对位 xhs-studio TopicTagsEditor）：
 * deliveryDraft.topics 真实交付字段——编辑经 autosave.updateDelivery({topics}) 写入、
 * 持久化于 delivery；缺省 parseHashtagTopics 从正文 #话题 行派生。
 *
 * 规范沿用 parseHashtagTopics 口径：单个 ≤30 字、上限 10 个；输入去 # 前缀；
 * 契约 tagHint（3-5 个话题）作常读建议。标签推荐库无数据源，不做（defer）。
 * 回答模式不渲染本区（由视图 v-if 控制，与契约「回答绑定问题」一致）。
 */
import { computed, ref } from 'vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'

const props = withDefaults(defineProps<{
  /** 仅测试注入口（F7 同款）：缺省取 autosave.deliveryValue.topics。 */
  topics?: string[]
  maxCount?: number
  disabled?: boolean
}>(), {
  topics: undefined,
  maxCount: 10,
  disabled: undefined,
})

const emit = defineEmits<{ update: [topics: string[]] }>()

const ctx = useZhihuStudioContext()

const topics = computed(() => props.topics ?? ctx.autosave.deliveryValue.value.topics ?? [])
const disabled = computed(() => props.disabled ?? ctx.autosave.readonly.value)
const effectiveMax = computed(() => props.maxCount)

const draft = ref('')
const addError = ref('')

function add(): void {
  const value = draft.value.replace(/^#/, '').trim()
  if (!value || disabled.value) return
  if (value.length > 30) {
    addError.value = '单个话题不超过 30 字'
    return
  }
  if (topics.value.includes(value)) {
    addError.value = '话题已存在'
    return
  }
  if (topics.value.length >= effectiveMax.value) return
  const next = [...topics.value, value]
  addError.value = ''
  draft.value = ''
  emit('update', next)
  ctx.autosave.updateDelivery({ topics: next })
}

function remove(index: number): void {
  if (disabled.value) return
  const next = topics.value.filter((_, i) => i !== index)
  emit('update', next)
  ctx.autosave.updateDelivery({ topics: next })
}
</script>

<style scoped>
.zhihu-tags-editor {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.zhihu-block-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-block-head .gl-hint {
  margin: var(--space-xxs) 0 0;
}

.zhihu-tags-count {
  margin: 0;
  flex: none;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.zhihu-tags-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.zhihu-tag-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xxs);
  padding: var(--space-xxs) var(--space-sm);
  border-radius: var(--radius-pill);
  background: var(--color-surface-highlight);
  border: 1px solid color-mix(in srgb, var(--color-accent) 30%, transparent);
}

.zhihu-tag-text {
  color: var(--color-text);
  font-size: var(--type-caption);
}

.zhihu-tag-remove {
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: var(--type-body-sm);
  line-height: 1;
  padding: 0 var(--space-xxs);
}

.zhihu-tag-remove:hover:not(:disabled) {
  color: var(--color-danger);
}

.zhihu-tag-add {
  display: flex;
  gap: var(--space-xs);
}

.zhihu-tag-add input {
  flex: 1;
  min-width: 0;
}

.zhihu-tag-add button {
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.zhihu-tag-add button:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.zhihu-tag-add button:disabled {
  background: var(--surface-muted);
  cursor: not-allowed;
}

.zhihu-tags-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--type-caption);
}
</style>
