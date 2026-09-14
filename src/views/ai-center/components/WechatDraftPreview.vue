<template>
  <GlModal :title="title" wide scroll trap-focus @close="emit('close')">
    <div class="preview gl-field studio-panel" data-test="wechat-draft-preview">
      <!-- 快照确认：版本与导出产物（不混入正在编辑的内容——AC101-22） -->
      <section class="snapshot" data-test="wechat-draft-preview-snapshot">
        <h4>发布快照</h4>
        <h3 data-test="wechat-preview-title">{{ articleTitle || '请先填写文章标题' }}</h3>
        <p data-test="wechat-preview-summary">{{ summary || '未填写摘要' }}</p>
        <div v-if="preview" class="preview-content" data-test="wechat-preview-content" v-html="previewHtml" />
        <p class="hint">
          将写入草稿箱的版本：v{{ draftVersion }}（已固化的导出快照，之后的本地编辑不会进入本次同步）。
        </p>
        <p v-if="exportFile" class="hint">
          产物：{{ exportFile.filename }}（{{ exportFile.contentType }}，
          {{ Math.max(1, Math.round((exportFile.sizeBytes || 0) / 1024)) }} KB）
          <a v-if="exportFile.url" :href="exportFile.url" target="_blank" rel="noopener" data-test="wechat-draft-preview-file">下载预览文件（图片包请解压后打开）</a>
        </p>
        <p v-else-if="exportPending" class="hint">正在装配导出快照…</p>
      </section>

      <!-- 账号选择：仅已验证连接 -->
      <section class="accounts">
        <h4>发布账号</h4>
        <p v-if="activeAccounts.length === 0" class="hint" data-test="wechat-preview-no-account">
          尚无已验证的公众号连接——请先在「公众号连接管理」中绑定并校验。
        </p>
        <label v-else>
          <select v-model="selectedAccountId" data-test="wechat-preview-account" :disabled="submitting">
            <option value="">请选择已验证的公众号</option>
            <option v-for="account in activeAccounts" :key="account.id" :value="account.id">
              {{ account.displayName }}（{{ account.appId }}）
            </option>
          </select>
        </label>
      </section>

      <!-- 评论选项与可选元数据 -->
      <section class="options">
        <h4>评论选项</h4>
        <label class="option"><input type="checkbox" v-model="needOpenComment" :disabled="submitting" data-test="wechat-preview-open-comment"> 开启留言</label>
        <label class="option"><input type="checkbox" v-model="onlyFansCanComment" :disabled="submitting" data-test="wechat-preview-fans-comment"> 仅粉丝可留言</label>
        <label class="option wide">作者（可选）
          <input v-model.trim="author" maxlength="64" :disabled="submitting" data-test="wechat-preview-author" placeholder="默认不填">
        </label>
        <label class="option wide">原文链接（可选）
          <input v-model.trim="contentSourceUrl" maxlength="512" :disabled="submitting" data-test="wechat-preview-source-url" placeholder="https://...">
        </label>
      </section>

      <p class="hint">提交后仅写入公众号「草稿箱」，不会公开发布；你可以随时在微信公众号后台查看与删除。</p>
      <p v-if="error" class="error" role="alert" data-test="wechat-preview-error">{{ error }}</p>
    </div>
    <template #actions>
      <button type="button" class="secondary" data-test="wechat-preview-cancel" :disabled="submitting" @click="emit('close')">取消</button>
      <button
        type="button"
        class="primary gl-btn-primary"
        data-test="wechat-preview-submit"
        :disabled="!canSubmit || submitting"
        @click="onSubmit"
      >{{ submitting ? '提交中…' : '存入草稿箱' }}</button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { sanitizeArticleHtml } from '../../article/composables/useArticleRender'
import type { RenderPreview } from '../../../types/creation-studio'
import { useStudioGuard } from '../../../lib/creation-studio-http'
import GlModal from '../../../components/GlModal.vue'
import type { WechatAccount } from '../creation/useWechatAccounts'
import { createDraftSync, syncActionError, type WechatDraftSync } from '../creation/useWechatDraftSync'

/**
 * 任务书 #101 C101-22：公众号发布前预览与确认（TC101-105/108）。
 * 展示冻结快照（版本/导出产物）与评论选项；无已验证连接不能提交（但不阻断文件导出——那是
 * DeliveryPanel 的职责）；双击由 submitting 拦截；提交后 syncId 交回父层跟踪。
 */
const props = defineProps<{
  draftId: string
  draftVersion: number
  exportId: string
  exportFile?: { filename: string; contentType: string; sizeBytes: number; url?: string } | null
  exportPending?: boolean
  accounts: WechatAccount[]
  preview?: RenderPreview | null
  articleTitle?: string
  summary?: string
}>()

const emit = defineEmits<{
  close: []
  submitted: [sync: WechatDraftSync]
}>()

const title = computed(() => `存入公众号草稿箱（v${props.draftVersion}）`)
const activeAccounts = computed(() => props.accounts.filter((account) => account.state === 'active'))
const selectedAccountId = ref('')
const previewHtml = computed(() => sanitizeArticleHtml(props.preview?.html ?? ''))

const needOpenComment = ref(false)
const onlyFansCanComment = ref(false)
const author = ref('')
const contentSourceUrl = ref('')
const submitting = ref(false)
const error = ref('')
const guard = useStudioGuard(() => props.draftId + ':' + props.draftVersion + ':' + props.exportId)
guard.onInvalidate(() => { submitting.value = false; error.value = ''; selectedAccountId.value = '' })
let pendingKey = ''
let requestId = crypto.randomUUID()

const canSubmit = computed(() => activeAccounts.value.some(account => account.id === selectedAccountId.value)
  && !props.exportPending && Boolean(props.exportId) && props.preview?.draftId === props.draftId
  && props.preview.version === props.draftVersion && !props.preview.unresolvedMediaIds.length
  && Boolean(props.articleTitle?.trim()) && [...(props.articleTitle ?? '')].length <= 64
  && [...(props.summary ?? '')].length <= 120)

async function onSubmit(): Promise<void> {
  if (submitting.value || !canSubmit.value) return
  if ([...author.value].length > 32) { error.value = '作者最多 32 字'; return }
  const valid = guard.capture()
  const account = activeAccounts.value.find((item) => item.id === selectedAccountId.value)
  if (!account) {
    error.value = '所选连接不可用，请重新选择'
    return
  }
  if (contentSourceUrl.value && !/^https?:\/\//.test(contentSourceUrl.value)) {
    error.value = '原文链接须为 http(s) 地址'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const payload = {
      accountId: account.id,
      expectedAccountVersion: account.version,
      draftId: props.draftId,
      draftVersion: props.draftVersion,
      exportId: props.exportId,
      author: author.value || undefined,
      contentSourceUrl: contentSourceUrl.value || undefined,
      needOpenComment: needOpenComment.value ? 1 : 0,
      onlyFansCanComment: onlyFansCanComment.value ? 1 : 0,
    }
    const key = JSON.stringify(payload)
    if (pendingKey !== key) { pendingKey = key; requestId = crypto.randomUUID() }
    const sync = await createDraftSync({ ...payload, requestId } as Parameters<typeof createDraftSync>[0])
    if (valid()) emit('submitted', sync)
  } catch (failure) {
    if (valid()) error.value = syncActionError(failure, '提交同步失败，请稍后重试').message
  } finally {
    if (valid()) submitting.value = false
  }
}
</script>

<style scoped>
.preview { display: grid; gap: var(--space-md); }
.preview h4 { margin: 0 0 var(--space-xs); font-size: var(--type-body); }
.snapshot, .accounts, .options { display: grid; gap: var(--space-xs); }
.hint { margin: 0; color: var(--color-text-muted); font-size: var(--type-body-sm); }
.hint a { color: var(--color-accent-2); }
.accounts select { padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border-control); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); min-width: 0; }
.option { display: flex; align-items: center; gap: var(--space-xs); font-size: var(--type-body-sm); color: var(--color-text-secondary); }
.option.wide { display: grid; gap: var(--space-xxs); }
.option.wide input { padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border-control); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.error { color: var(--color-danger); font-size: var(--type-body-sm); margin: 0; }
</style>
