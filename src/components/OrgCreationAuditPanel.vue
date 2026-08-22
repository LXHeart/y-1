<template>
  <div class="org-audit">
    <div class="panel-toolbar">
      <select v-model="kind" @change="reload">
        <option value="">全部类型</option>
        <option v-for="(label, key) in KIND_LABELS" :key="key" :value="key">{{ label }}</option>
      </select>
      <button type="button" :disabled="loading" @click="reload">刷新</button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="!loading && items.length === 0" class="gl-hint">该主体暂无创作产出记录。</p>
    <table v-if="items.length > 0" class="audit-table">
      <thead>
        <tr>
          <th>时间</th>
          <th>成员</th>
          <th>类型</th>
          <th>模式</th>
          <th>Provider / Model</th>
          <th>产出</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td>{{ time(item.createdAt) }}</td>
          <td><code>{{ shortId(item.ownerAccountId) }}</code></td>
          <td>{{ KIND_LABELS[item.kind] || item.kind }}</td>
          <td>{{ item.mode === 'task' ? '任务' : '独立' }}</td>
          <td>{{ item.provider }}{{ item.model ? ` · ${item.model}` : '' }}</td>
          <td>{{ item.resultTitle }}</td>
        </tr>
      </tbody>
    </table>
    <button
      v-if="nextBefore"
      type="button"
      class="audit-more"
      :disabled="loading"
      @click="loadMore"
    >{{ loading ? '加载中…' : '加载更多' }}</button>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { listOrgCreationGenerations } from '../composables/useCreationGenerations'
import {
  CREATION_GENERATION_KIND_LABELS,
  type CreationGenerationKind,
  type OrgCreationGenerationSummary,
} from '../types/grassland/creation-generation'

const props = defineProps<{ organizationId: string }>()

const KIND_LABELS = CREATION_GENERATION_KIND_LABELS
const items = ref<OrgCreationGenerationSummary[]>([])
const nextBefore = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const kind = ref<CreationGenerationKind | ''>('')

watch(() => props.organizationId, () => { void reload() }, { immediate: true })

async function loadPage(reset: boolean): Promise<void> {
  if (loading.value || !props.organizationId) return
  loading.value = true
  error.value = ''
  try {
    const page = await listOrgCreationGenerations(props.organizationId, {
      kind: kind.value || undefined,
      limit: 20,
      before: reset ? undefined : nextBefore.value || undefined,
    })
    // 兜底：上游缺 items（异常 envelope/桩数据）时按空页处理，避免渲染层炸 undefined.length
    const fresh = page.items ?? []
    items.value = reset ? fresh : [...items.value, ...fresh]
    nextBefore.value = page.nextBefore || null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '生成记录加载失败'
  } finally {
    loading.value = false
  }
}

async function reload(): Promise<void> {
  await loadPage(true)
}

async function loadMore(): Promise<void> {
  await loadPage(false)
}

function time(value: string | null): string {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}
</script>
