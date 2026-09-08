<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CreationDeliveryContract } from '../../../types/creation'
import { deliveryReadiness } from '../../../lib/creation-delivery'
import { downloadExportManifest, exportCreationDraft, type CreationExportDownload } from '../../../lib/creation-export'

/**
 * 交付材料面板（AI内容中心改造-02 §2.4/2.5、任务3 §3.4）：
 * 平台编辑稿分字段（标题/描述/话题/摘要/分享配文）+ readiness 检查 + 指定版本导出。
 * 修改配文/描述只写 delivery，不触发任何媒体重生成（任务书红线）。
 */
const props = defineProps<{
  modelValue: Partial<CreationDeliveryContract>
  platform: string
  disabled?: boolean
  /** 正文等字段已在别处编辑时隐藏对应输入（如朋友圈文案框）。 */
  hideFields?: string[]
  /** complete-content 意图下媒体为必需项。 */
  mediaExpected?: boolean
  /** 提供草稿 ID 即显示导出；version 缺省导出当前版本。 */
  draftId?: string
  draftVersion?: number
  exportTitle?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: Partial<CreationDeliveryContract>] }>()

const summaryVisible = computed(() => props.platform === 'wechat-official')
const shareCopyVisible = computed(() => props.platform === 'wechat-channels' || props.platform === 'moments')
const topicsVisible = computed(() => props.platform === 'xiaohongshu' || props.platform === 'douyin')

function patch(fields: Partial<CreationDeliveryContract>): void {
  emit('update:modelValue', { ...props.modelValue, ...fields })
}

const topicsText = computed({
  get: () => (props.modelValue.topics ?? []).join(' '),
  set: (value: string) => {
    const topics = value.split(/[\s,，]+/).map(item => item.replace(/^#/, '').trim()).filter(Boolean)
    patch({ topics })
  },
})

const checks = computed(() => deliveryReadiness(props.modelValue, {
  platform: props.platform, mediaExpected: props.mediaExpected,
}))

const exporting = ref(false)
const exportError = ref('')
const downloads = ref<CreationExportDownload[]>([])

async function onExport(): Promise<void> {
  if (!props.draftId || exporting.value) return
  exporting.value = true
  exportError.value = ''
  try {
    const result = await exportCreationDraft(props.draftId, props.draftVersion)
    downloadExportManifest(result, props.exportTitle || String(props.modelValue.titleOrOpening ?? '创作交付'))
    downloads.value = result.downloads
  } catch (error) {
    exportError.value = error instanceof Error ? error.message : '导出失败，请稍后重试'
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <section class="gl-zone delivery-panel" data-test="delivery-panel">
    <h3>发布材料</h3>
    <p class="hint">以下字段随项目保存；修改文案不会触发图片/视频重新生成。</p>

    <div v-if="!hideFields?.includes('titleOrOpening')" class="form-field">
      <label for="delivery-title">标题{{ platform === 'zhihu' ? '或回答开头' : '' }}</label>
      <input
        id="delivery-title"
        data-test="delivery-title"
        :value="modelValue.titleOrOpening ?? ''"
        :disabled="disabled"
        maxlength="200"
        @input="patch({ titleOrOpening: ($event.target as HTMLInputElement).value })"
      >
    </div>

    <div v-if="!hideFields?.includes('bodyOrDescription')" class="form-field">
      <label for="delivery-body">{{ platform === 'moments' ? '发布文案' : '发布描述' }}</label>
      <textarea
        id="delivery-body"
        data-test="delivery-body"
        :value="modelValue.bodyOrDescription ?? ''"
        :disabled="disabled"
        rows="3"
        maxlength="1000"
        @input="patch({ bodyOrDescription: ($event.target as HTMLTextAreaElement).value })"
      />
    </div>

    <div v-if="topicsVisible && !hideFields?.includes('topics')" class="form-field">
      <label for="delivery-topics">话题（空格或逗号分隔，可带 #）</label>
      <input
        id="delivery-topics"
        data-test="delivery-topics"
        v-model="topicsText"
        :disabled="disabled"
        maxlength="300"
        placeholder="#探店 #新手教程"
      >
    </div>

    <div v-if="summaryVisible" class="form-field">
      <label for="delivery-summary">摘要（公众号 digest）</label>
      <textarea
        id="delivery-summary"
        data-test="delivery-summary"
        :value="modelValue.summary ?? ''"
        :disabled="disabled"
        rows="2"
        maxlength="120"
        @input="patch({ summary: ($event.target as HTMLTextAreaElement).value })"
      />
    </div>

    <div v-if="shareCopyVisible" class="form-field">
      <label for="delivery-share">分享配文{{ platform === 'wechat-channels' ? '（群/朋友圈转发用）' : '（转发用短句）' }}</label>
      <input
        id="delivery-share"
        data-test="delivery-share"
        :value="modelValue.shareCopy ?? ''"
        :disabled="disabled"
        maxlength="200"
        @input="patch({ shareCopy: ($event.target as HTMLInputElement).value })"
      >
    </div>

    <div class="readiness" data-test="delivery-readiness">
      <h4>交付检查</h4>
      <ul>
        <li v-for="check in checks" :key="check.key" :class="check.state === 'ready' ? 'ready' : 'missing'">
          {{ check.state === 'ready' ? '✓' : '○' }} {{ check.label }}{{ check.state === 'ready' ? '' : '待补' }}
        </li>
      </ul>
    </div>

    <div v-if="draftId" class="actions">
      <button
        type="button"
        class="secondary"
        data-test="delivery-export"
        :disabled="disabled || exporting"
        @click="onExport"
      >
        {{ exporting ? '导出中…' : '导出交付包' }}
      </button>
      <span class="hint">导出 manifest 与媒体短期下载链接；缺失项会在包内明确标注。</span>
    </div>
    <p v-if="exportError" data-test="delivery-export-error" class="error" role="alert">{{ exportError }}</p>
    <ul v-if="downloads.length" class="downloads" data-test="delivery-downloads">
      <li v-for="(item, index) in downloads" :key="`${item.id}-${index}`">
        <a v-if="item.url" :href="item.url" :download="`media-${index + 1}`" target="_blank" rel="noopener">
          媒体 {{ item.position ?? index + 1 }}（{{ item.role ?? item.refType }}）
        </a>
        <span v-else>媒体 {{ item.position ?? index + 1 }}：{{ item.unavailable === 'expired' ? '已失效，重新导出可按授权重取' : '暂无下载链接' }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.delivery-panel { display: grid; gap: 12px; }
.delivery-panel h3 { margin: 0; font-size: 1rem; }
.delivery-panel h4 { margin: 0 0 6px; font-size: .86rem; }
.hint { margin: 0; color: var(--color-text-muted); font-size: .84rem; }
.form-field { display: grid; gap: 6px; }
.form-field label { font-size: var(--text-sm); color: var(--color-text); font-weight: 600; }
.form-field input, .form-field textarea {
  padding: 8px var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  font: inherit; background: var(--color-surface); color: var(--color-text);
}
.readiness ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; font-size: .84rem; }
.readiness .ready { color: var(--color-text-secondary); }
.readiness .missing { color: var(--color-warning, var(--color-accent)); }
.actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.actions .secondary { min-height: 38px; padding: 0 var(--space-md); border-radius: var(--radius-sm); }
.error { color: var(--color-danger); font-size: .84rem; margin: 0; }
.downloads { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; font-size: .84rem; }
.downloads a { color: var(--color-accent); }
</style>
