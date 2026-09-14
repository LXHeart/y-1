<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { CreationDeliveryContract } from '../../../types/creation'
import type { RenderPreview } from '../../../types/creation-studio'
import { deliveryReadiness } from '../../../lib/creation-delivery'
import { downloadExportManifest, exportCreationDraft, exportStudioDraft, readStudioExport, downloadStudioFile,
  type CreationExportDownload, type StudioExportFormat, type StudioExportResult } from '../../../lib/creation-export'
import { studioPost, useStudioGuard } from '../../../lib/creation-studio-http'
import type { ArticleRenderOptions } from '../../article/composables/useArticleRender'
import GlModal from '../../../components/GlModal.vue'
import WechatAccountPanel from './WechatAccountPanel.vue'
import WechatDraftPreview from './WechatDraftPreview.vue'
import WechatDraftSyncPanel from './WechatDraftSyncPanel.vue'
import { useWechatAccounts } from '../creation/useWechatAccounts'
import { useWechatDraftSync, type WechatDraftSync } from '../creation/useWechatDraftSync'

const props = defineProps<{
  modelValue: Partial<CreationDeliveryContract>; platform: string; disabled?: boolean
  hideFields?: string[]; mediaExpected?: boolean; draftId?: string; draftVersion?: number
  exportTitle?: string; beforeExport?: () => Promise<number | false>
  bodyReadonly?: boolean; summarySuggesting?: boolean; summarySuggestionEnabled?: boolean
  studioExportEnabled?: boolean; wechatEnabled?: boolean
  studioRenderOptions?: ArticleRenderOptions | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: Partial<CreationDeliveryContract>]; 'suggest-summary': [] }>()
const summaryVisible = computed(() => props.platform === 'wechat-official')
const shareCopyVisible = computed(() => props.platform === 'wechat-channels' || props.platform === 'moments')
const topicsVisible = computed(() => props.platform === 'xiaohongshu' || props.platform === 'douyin')
function patch(fields: Partial<CreationDeliveryContract>): void { emit('update:modelValue', { ...props.modelValue, ...fields }) }
const topicsText = computed({
  get: () => (props.modelValue.topics ?? []).join(' '),
  set: (value: string) => patch({ topics: value.split(/[\s,，]+/).map(item => item.replace(/^#/, '').trim()).filter(Boolean) }),
})
const checks = computed(() => deliveryReadiness(props.modelValue, { platform: props.platform, mediaExpected: props.mediaExpected }))
const adoptedMedia = computed(() => {
  const cover = props.modelValue.coverRef ?? null
  const cards = (props.modelValue.mediaRefs ?? []).filter(ref => ref.id !== cover?.id)
  return { cover, cards, total: cards.length + (cover ? 1 : 0) }
})
const exporting = ref(false)
const exportError = ref('')
const downloads = ref<CreationExportDownload[]>([])
const studioFormat = ref<StudioExportFormat>('bundle-zip')
const studioFormats: Array<{ id: StudioExportFormat; label: string }> = [
  { id: 'bundle-zip', label: '完整图文包 ZIP' }, { id: 'wechat-html', label: '文章 HTML' },
  { id: 'markdown', label: 'Markdown' }, { id: 'text', label: '纯文本' },
]
const studioExporting = ref(false)
const studioError = ref('')
const studioDownloaded = ref('')
const wechatAccountsOpen = ref(false)
const wechatAccountsTrigger = ref<HTMLButtonElement | null>(null)
const wechatPreviewOpen = ref(false)
const wechatPreviewPreparing = ref(false)
const wechatPreviewVersion = ref(0)
const wechatPreviewExportId = ref('')
const wechatPreviewFile = ref<StudioExportResult['file'] | null>(null)
const wechatPreview = ref<RenderPreview | null>(null)
const wechatPreviewTitle = ref('')
const wechatPreviewSummary = ref('')
const wechatSyncTrigger = ref<HTMLButtonElement | null>(null)
const wechatAccounts = useWechatAccounts()
const wechatSync = useWechatDraftSync(() => props.draftId)
const guard = useStudioGuard(() => props.draftId + ':' + props.platform)
const exportRequests = new Map<string, string>()
guard.onInvalidate(() => {
  downloads.value = []; studioDownloaded.value = ''; exportError.value = ''; studioError.value = ''
  exporting.value = false; studioExporting.value = false; wechatPreviewPreparing.value = false
  wechatPreviewOpen.value = false; wechatAccountsOpen.value = false; wechatPreview.value = null
  wechatPreviewFile.value = null; exportRequests.clear()
  wechatSync.reset()
})
async function saveVersion(): Promise<number> {
  const version = props.beforeExport ? await props.beforeExport() : props.draftVersion
  if (version === false || version === undefined || version < 1)
    throw new Error('修改尚未保存，请先处理保存提示再导出')
  return version
}
async function buildExport(id: string, version: number, format: StudioExportFormat, valid: () => boolean): Promise<StudioExportResult | null> {
  const options = props.studioRenderOptions ?? { theme: 'standard', includeTitle: false, citeExternalLinks: false }
  const key = JSON.stringify([id, version, format, options])
  if (!exportRequests.has(key)) exportRequests.set(key, crypto.randomUUID())
  let outcome = await exportStudioDraft(id, version, format, exportRequests.get(key)!, options)
  for (let attempt = 0; valid() && 'state' in outcome && outcome.state === 'building' && attempt < 30; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 4000))
    if (!valid()) return null
    outcome = await readStudioExport(outcome.exportId)
  }
  if (!valid()) return null
  if (!('file' in outcome)) throw new Error(outcome.error?.message ?? '导出仍在处理中，请稍后恢复本次导出')
  if (outcome.draftId !== id || outcome.version !== version || outcome.format !== format)
    throw new Error('导出版本与已保存草稿不一致，请重新核对')
  return outcome
}
async function onStudioExport(): Promise<void> {
  if (!props.draftId || !props.studioExportEnabled || props.disabled || studioExporting.value) return
  const id = props.draftId
  const valid = guard.capture()
  studioExporting.value = true; studioError.value = ''; studioDownloaded.value = ''
  try {
    const version = await saveVersion()
    if (!valid()) return
    const result = await buildExport(id, version, studioFormat.value, valid)
    if (result && valid()) { downloadStudioFile(result.file); studioDownloaded.value = result.file.filename }
  } catch (failure) { if (valid()) studioError.value = failure instanceof Error ? failure.message : '导出失败，请重试' }
  finally { if (valid()) studioExporting.value = false }
}
async function onExport(): Promise<void> {
  if (!props.draftId || props.disabled || exporting.value) return
  const id = props.draftId
  const valid = guard.capture()
  exporting.value = true; exportError.value = ''
  try {
    const version = props.beforeExport ? await props.beforeExport() : props.draftVersion
    if (version === false) throw new Error('修改尚未保存，未开始导出，请处理保存错误后重试')
    if (!valid()) return
    const result = await exportCreationDraft(id, version)
    if (!valid()) return
    downloadExportManifest(result, props.exportTitle || String(props.modelValue.titleOrOpening ?? '创作交付'))
    downloads.value = result.downloads
  } catch (failure) { if (valid()) exportError.value = failure instanceof Error ? failure.message : '导出失败，请重试' }
  finally { if (valid()) exporting.value = false }
}
function closeWechatAccounts(): void { wechatAccountsOpen.value = false; void nextTick(() => wechatAccountsTrigger.value?.focus()) }
function closeWechatPreview(): void { wechatPreviewOpen.value = false; void nextTick(() => wechatSyncTrigger.value?.focus()) }

async function onWechatSyncOpen(): Promise<void> {
  if (!props.draftId || props.disabled || !props.wechatEnabled || wechatPreviewPreparing.value) return
  const id = props.draftId
  const valid = guard.capture()
  wechatPreviewPreparing.value = true; studioError.value = ''
  try {
    const version = await saveVersion()
    if (!valid()) return
    const title = String(props.modelValue.titleOrOpening ?? props.exportTitle ?? '')
    const summary = String(props.modelValue.summary ?? '')
    const renderOptions = props.studioRenderOptions ?? { theme: 'standard', includeTitle: false, citeExternalLinks: false }
    const result = await buildExport(id, version, 'wechat-html', valid)
    if (!result || !valid()) return
    const preview = await studioPost<RenderPreview>('/api/creation-studio/render-previews',
      { draftId: id, version, ...renderOptions }, 120_000)
    await wechatAccounts.refresh()
    if (!valid()) return
    if (preview.draftId !== id || preview.version !== version) throw new Error('预览版本不一致，请重新核对')
    wechatPreviewVersion.value = version; wechatPreviewExportId.value = result.file.exportId
    wechatPreviewFile.value = result.file; wechatPreview.value = preview
    wechatPreviewTitle.value = title; wechatPreviewSummary.value = summary; wechatPreviewOpen.value = true
  } catch (failure) { if (valid()) studioError.value = failure instanceof Error ? failure.message : '同步准备失败，请重试' }
  finally { if (valid()) wechatPreviewPreparing.value = false }
}
function onWechatSubmitted(sync: WechatDraftSync): void {
  closeWechatPreview(); wechatSync.track(sync); void wechatSync.refresh()
}
function onSyncUpdated(sync?: WechatDraftSync): void {
  if (sync) wechatSync.track(sync)
  void wechatSync.refresh()
}
watch([() => props.draftId, () => props.studioExportEnabled, () => props.platform], ([id]) => {
  if (id && props.platform === 'wechat-official' && props.studioExportEnabled) void wechatSync.refresh()
}, { immediate: true })
</script>

<template>
  <section class="gl-zone delivery-panel studio-panel" data-test="delivery-panel">
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
        v-if="!bodyReadonly"
        id="delivery-body"
        data-test="delivery-body"
        :value="modelValue.bodyOrDescription ?? ''"
        :disabled="disabled"
        rows="3"
        maxlength="1000"
        @input="patch({ bodyOrDescription: ($event.target as HTMLTextAreaElement).value })"
      />
      <!-- C101-17：排版定稿正文只读呈现（元数据可编辑，正文回正文阶段改） -->
      <pre v-else id="delivery-body" data-test="delivery-body-readonly" class="body-readonly">{{ modelValue.bodyOrDescription ?? '' }}</pre>
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
      <div class="summary-row">
        <textarea
          id="delivery-summary"
          data-test="delivery-summary"
          :value="modelValue.summary ?? ''"
          :disabled="disabled"
          rows="2"
          maxlength="120"
          @input="patch({ summary: ($event.target as HTMLTextAreaElement).value })"
        />
        <!-- 任务书 #101 C101-17：摘要建议独立动作（显式点击才发起，永不因预览/主题触发） -->
        <button
          type="button"
          class="secondary"
          v-if="summarySuggestionEnabled"
          data-test="delivery-suggest-summary"
          :disabled="disabled || summarySuggesting"
          @click="emit('suggest-summary')"
        >{{ summarySuggesting ? '生成中…' : '生成摘要建议' }}</button>
      </div>
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

    <!-- 任务书 #101 C101-12：已采用媒体（服务端采用写回；未采用不显示） -->
    <div v-if="adoptedMedia.total" class="adopted-media" data-test="delivery-adopted-media">
      <h4>采用媒体</h4>
      <p class="hint">
        封面 {{ adoptedMedia.cover ? '已就绪' : '未采用' }} · 图卡 {{ adoptedMedia.cards.length }} 张；
        未采用项不会进入交付包。
      </p>
      <span
        v-for="(ref, index) in adoptedMedia.cards"
        :key="ref.id"
        class="badge"
        :data-test="`delivery-adopted-card-${index + 1}`"
      >{{ ref.position ?? index + 1 }}</span>
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
    <!-- 任务书 #101 C101-18：新格式真实文件导出（公众号/知乎交付） -->
    <div v-if="draftId && studioExportEnabled" class="actions studio-export">
      <label for="studio-export-format" class="hint">新格式</label>
      <select id="studio-export-format" v-model="studioFormat" data-test="studio-export-format" :disabled="disabled || studioExporting">
        <option v-for="item in studioFormats" :key="item.id" :value="item.id">{{ item.label }}</option>
      </select>
      <button
        type="button"
        class="primary gl-btn-primary"
        data-test="studio-export"
        :disabled="disabled || studioExporting"
        @click="onStudioExport"
      >{{ studioExporting ? '装配中…' : '导出真实文件' }}</button>
      <span v-if="studioDownloaded" class="hint" data-test="studio-export-done">已开始下载 {{ studioDownloaded }}</span>
    </div>
    <!-- 任务书 #101 C101-20：公众号连接管理入口（无连接不阻断导出） -->
    <div v-if="platform === 'wechat-official' && studioExportEnabled" class="actions wechat-channel">
      <button
        type="button"
        class="secondary"
        data-test="delivery-wechat-accounts"
        ref="wechatAccountsTrigger"
        @click="wechatAccountsOpen = true"
      >公众号连接管理</button>
      <button
        v-if="draftId && wechatEnabled"
        type="button"
        class="primary gl-btn-primary"
        data-test="delivery-wechat-sync"
        ref="wechatSyncTrigger"
        :disabled="disabled || wechatPreviewPreparing"
        @click="onWechatSyncOpen"
      >{{ wechatPreviewPreparing ? '快照装配中…' : '存入公众号草稿箱' }}</button>
      <span class="hint">未连接也可导出文件；发布到草稿箱前需绑定并校验连接。</span>
    </div>
    <GlModal v-if="wechatAccountsOpen" title="公众号连接管理" scroll trap-focus @close="closeWechatAccounts">
      <WechatAccountPanel />
    </GlModal>
    <!-- 任务书 #101 C101-22：发布前预览（冻结快照+评论选项确认）与同步状态/核实 -->
    <WechatDraftPreview
      v-if="wechatPreviewOpen"
      :draft-id="draftId!"
      :draft-version="wechatPreviewVersion"
      :export-id="wechatPreviewExportId"
      :export-file="wechatPreviewFile"
      :preview="wechatPreview"
      :article-title="wechatPreviewTitle"
      :summary="wechatPreviewSummary"
      :accounts="[...wechatAccounts.accounts.value]"
      @close="closeWechatPreview"
      @submitted="onWechatSubmitted"
    />
    <WechatDraftSyncPanel
      v-if="platform === 'wechat-official' && studioExportEnabled && draftId && (wechatSync.current.value || wechatSync.history.value.length)"
      :sync="wechatSync.current.value"
      :history="[...wechatSync.history.value]"
      :load-error="wechatSync.loadError.value"
      @updated="onSyncUpdated"
      @refresh="onSyncUpdated"
    />
    <p v-if="studioError" data-test="studio-export-error" class="error" role="alert">{{ studioError }}</p>
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
.delivery-panel { display: grid; gap: var(--space-sm); }
.delivery-panel h3 { margin: 0; font-size: var(--text-lg); }
.delivery-panel h4 { margin: 0 0 var(--space-xs); font-size: var(--text-base); }
.hint { margin: 0; color: var(--color-text-muted); font-size: var(--text-sm); }
.form-field { display: grid; gap: var(--space-xs); }
.form-field label { font-size: var(--text-sm); color: var(--color-text); font-weight: var(--weight-heading); }
.form-field input, .form-field textarea {
  padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border-control); border-radius: var(--radius-sm);
  background: var(--color-surface); color: var(--color-text);
}
.summary-row { display: grid; grid-template-columns: 1fr auto; gap: var(--space-xs); align-items: start; }
.summary-row .secondary { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); align-self: end; }
.body-readonly { margin: 0; white-space: pre-wrap; word-break: break-word; padding: var(--space-xs) var(--space-sm); border: var(--border-width) dashed var(--color-border); border-radius: var(--radius-sm); color: var(--color-text-secondary); font: inherit; max-height: var(--layout-rail); overflow: auto; }
@media (max-width: 768px) { .summary-row { grid-template-columns: 1fr; } }
.readiness ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-xxs); font-size: var(--text-sm); }
.adopted-media { display: grid; gap: var(--space-xxs); padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-sm); }
.adopted-media .badge { display: inline-block; min-width: var(--space-xl); text-align: center; padding: var(--space-micro) var(--space-xs); border-radius: var(--radius-pill); background: var(--surface-muted); color: var(--color-text-secondary); font-size: var(--text-xs); }
.readiness .ready { color: var(--color-text-secondary); }
.readiness .missing { color: var(--color-warning, var(--color-accent)); }
.actions { display: flex; gap: var(--space-sm); align-items: center; flex-wrap: wrap; }
.actions .secondary { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); }
.error { color: var(--color-danger); font-size: var(--text-sm); margin: 0; }
.downloads { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-xxs); font-size: var(--text-sm); }
.downloads a { color: var(--color-accent-2); }
</style>
