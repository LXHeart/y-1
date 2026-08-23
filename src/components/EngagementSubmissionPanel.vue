<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  EngagementSubmission,
  EngagementSubmissionAttachment,
  SubmissionStatus,
  VerificationStatus,
} from '../types/grassland'

/**
 * 履约交付物面板（PRD 九第一步：推荐官提交凭证 → 商家核验）。
 *
 * 同一份数据两个视角，故用 `role` 区分动作而不是写两个组件：
 * - 推荐官：提交发布链接 + 说明 + 截图/视频附件；被退回后可修改重交；已有待核验的一份时不能再提交。
 * - 商家：看到链接、说明与附件（可下载），可「退回补交」并写原因；确认履约由父组件按钮触发。
 *
 * 附件走 intelligence 三步直传（ticket → PUT MinIO → confirm），**选中即上传**而非提交时才传：
 * 每个文件 confirm 后就是一份 active 资产，拿到 mediaId 暂存本地，提交交付物时一次带上。
 * 这样单个文件失败只影响它自己，不会把整次提交拖垮；代价是用户放弃提交时会留下已 confirm 的孤儿资产
 * （占 owner 配额，需自行在媒体库删除——后端 1 小时宽限清理只回收**未** confirm 的 pending 行）。
 */

const props = defineProps<{
  taskId: string
  applicationId: string
  role: 'merchant' | 'recommender'
  /** 任务书 #23：互动任务（contentForm=interaction）展示平台账号标识输入与截图提示。 */
  taskContentForm?: string | null
  /** 缺口清偿之九：actionType=comment 时展示评论文本输入（必填 ≤500）。 */
  interactionActionType?: string | null
}>()

const emit = defineEmits<{ changed: [] }>()

const grassland = useGrassland()

/** 与后端 `CreateSubmissionRequest.MAX_MEDIA` 一致；超量后端 400。 */
const MAX_ATTACHMENTS = 6
/** 与 `MEDIA_MAX_OBJECT_BYTES` 默认值（20MB）一致；本地先挡一道，省一次必失败的往返。 */
const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

/** 已 confirm、等着随交付物提交的附件。`mediaId` 是 confirm 后的 media_reference id。 */
interface StagedAttachment {
  mediaId: string
  name: string
  sizeBytes: number
}

const submissions = ref<EngagementSubmission[]>([])
const notice = ref('')
const contentUrl = ref('')
const platformHandle = ref('')
/** 缺口清偿之九：评论任务的评论文本（≤500，必填；提交时后端 L1 词库审核）。 */
const commentText = ref('')
const note = ref('')
const rejectNote = ref('')
const staged = ref<StagedAttachment[]>([])
/** 附件错误单独存：不该被下一次 refresh 的成功清掉，也不该盖住提交本身的报错。 */
const uploadError = ref('')
const uploading = ref(false)

const STATUS_LABEL: Record<SubmissionStatus, string> = {
  submitted: '待商家核验',
  accepted: '已核验通过',
  rejected: '已退回',
}

/** 履约核验 tri-state 徽章文案（Slice 11 Verification v1）。 */
const VERIFICATION_LABEL: Record<VerificationStatus, string> = {
  passed: '核验通过',
  failed: '核验未过',
  inconclusive: '核验存疑',
}

/** 单项核验明细的可读名（兜底原 type，避免后端新增 check 类型时前端裸露 key）。 */
const CHECK_TYPE_LABEL: Record<string, string> = {
  link_reachability: '链接可达',
  platform_identity: '发布平台',
  evidence_completeness: '凭证完整性',
  ai_visual: 'AI 视觉',
  interaction_screenshot: '互动截图核验',
  official_data: '官方数据核验',
}

const pending = computed(() => submissions.value.find((s) => s.status === 'submitted') || null)
const interactionTask = computed(() => props.taskContentForm === 'interaction')
const commentTask = computed(() => interactionTask.value && props.interactionActionType === 'comment')
const canSubmit = computed(
  () => !pending.value && !uploading.value && contentUrl.value.trim().length > 0
    && (!interactionTask.value || platformHandle.value.trim().length > 0)
  && (!commentTask.value || commentText.value.trim().length > 0))
const stagedFull = computed(() => staged.value.length >= MAX_ATTACHMENTS)

function formatSize(bytes: number | null): string {
  if (bytes === null) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 列表里的附件只有 mediaId + mime + size，没有原始文件名（后端不存），故按类型给个可读标签。 */
function attachmentLabel(attachment: EngagementSubmissionAttachment): string {
  const mime = attachment.mimeType || ''
  if (mime.startsWith('image/')) return '图片附件'
  if (mime.startsWith('video/')) return '视频附件'
  if (mime.startsWith('audio/')) return '音频附件'
  if (mime === 'application/pdf') return 'PDF 附件'
  return '附件'
}

async function refresh(): Promise<void> {
  const list = await grassland.listDeliverables(props.taskId, props.applicationId)
  if (list) submissions.value = list
  // 商家打开面板时，对未核验的待核验交付物懒触发自动核验（GET 保持纯读；核验是付费 POST，故客户端触发）。
  if (props.role === 'merchant') {
    await lazyVerifyUnverified()
  }
}

/**
 * 对未核验（无 verification）的待核验交付物逐个触发自动核验。已核验的不重跑（避免每次打开都付费）。
 * 单条失败不阻断（error 已进 grassland.error）——链接/AI 核验都可能 inconclusive，留给商家手动决策。
 */
async function lazyVerifyUnverified(): Promise<void> {
  const targets = submissions.value.filter((s) => s.status === 'submitted' && !s.verification)
  for (const s of targets) {
    const result = await grassland.runVerificationChecks(props.taskId, props.applicationId, s.id)
    // 防御：返回体不是合规核验记录（缺 checks）时不 patch，避免把脏数据渲染成徽章。
    if (!result || !result.checks) continue
    submissions.value = submissions.value.map((row) =>
      row.id === s.id ? { ...row, verification: result } : row)
  }
}

/** 商家手动「重新核验」：强制重跑（即便已有结论），就地刷新徽章。 */
async function recheck(submission: EngagementSubmission): Promise<void> {
  const result = await grassland.runVerificationChecks(props.taskId, props.applicationId, submission.id)
  if (!result) return
  submissions.value = submissions.value.map((row) =>
    row.id === submission.id ? { ...row, verification: result } : row)
}

watch(() => [props.taskId, props.applicationId], refresh, { immediate: true })

/**
 * 逐个上传选中的文件。串行而非并发：并发会同时占满 owner 配额预留，
 * 其中一个失败后另几个已 confirm 的更难对账；附件上限只有 6，串行的等待也可接受。
 */
async function onPickFiles(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  // 立刻清空 input：否则用户删掉暂存项后再选同一个文件不触发 change。
  input.value = ''
  if (files.length === 0) return

  uploadError.value = ''
  for (const file of files) {
    if (staged.value.length >= MAX_ATTACHMENTS) {
      uploadError.value = `最多 ${MAX_ATTACHMENTS} 个附件，其余已跳过`
      break
    }
    if (file.size === 0) {
      uploadError.value = `${file.name} 是空文件，已跳过`
      continue
    }
    if (file.size > MAX_ATTACHMENT_BYTES) {
      uploadError.value = `${file.name} 超过 ${formatSize(MAX_ATTACHMENT_BYTES)} 上限，已跳过`
      continue
    }

    uploading.value = true
    const mediaId = await grassland.uploadEngagementAttachment(file)
    uploading.value = false

    if (!mediaId) {
      uploadError.value = grassland.error.value || `${file.name} 上传失败`
      continue
    }
    staged.value = [...staged.value, { mediaId, name: file.name, sizeBytes: file.size }]
  }
}

function removeStaged(mediaId: string): void {
  staged.value = staged.value.filter((item) => item.mediaId !== mediaId)
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  notice.value = ''
  uploadError.value = ''
  const created = await grassland.submitDeliverable(
    props.taskId, props.applicationId, contentUrl.value.trim(),
    note.value.trim() || undefined,
    staged.value.map((item) => item.mediaId),
    interactionTask.value ? platformHandle.value.trim() : undefined,
    commentTask.value ? commentText.value.trim() : undefined)
  if (!created) return
  contentUrl.value = ''
  note.value = ''
  platformHandle.value = ''
  commentText.value = ''
  staged.value = []
  notice.value = '已提交，等待商家核验'
  await refresh()
  emit('changed')
}

/**
 * 取签名 URL 后新标签打开。不用 `<a href>` 直挂：URL 是短时签名的（默认 5 分钟），
 * 提前渲染到 DOM 里等用户点，点的时候大概率已过期。
 */
async function openAttachment(
  submission: EngagementSubmission, attachment: EngagementSubmissionAttachment,
): Promise<void> {
  const download = await grassland.getAttachmentDownloadUrl(
    props.taskId, props.applicationId, submission.id, attachment.mediaId)
  if (!download) return
  window.open(download.downloadUrl, '_blank', 'noopener,noreferrer')
}

async function reject(submission: EngagementSubmission): Promise<void> {
  notice.value = ''
  const rejected = await grassland.rejectDeliverable(
    props.taskId, props.applicationId, submission.id, rejectNote.value.trim() || undefined)
  if (!rejected) return
  rejectNote.value = ''
  notice.value = '已退回，推荐官可修改后重新提交'
  await refresh()
  emit('changed')
}
</script>

<template>
  <section class="sub">
    <p v-if="grassland.error.value" class="sub-alert sub-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="uploadError" class="sub-alert sub-err" role="alert">{{ uploadError }}</p>
    <p v-if="notice" class="sub-alert sub-ok">{{ notice }}</p>

    <p v-if="submissions.length === 0" class="sub-hint">
      {{ role === 'recommender' ? '尚未提交履约凭证。发布内容后把链接交上来，商家核验通过才会进入结算。'
        : '推荐官尚未提交履约凭证——在此之前无法确认履约。' }}
    </p>

    <ul v-else class="sub-list">
      <li v-for="s in submissions" :key="s.id">
        <div class="sub-main">
          <a :href="s.contentUrl" target="_blank" rel="noopener noreferrer">{{ s.contentUrl }}</a>
          <span class="sub-tag" :class="`sub-${s.status}`">{{ STATUS_LABEL[s.status] || s.status }}</span>
          <span v-if="s.verification" class="sub-tag" :class="`sub-verify-${s.verification.status}`">
            {{ VERIFICATION_LABEL[s.verification.status] || s.verification.status }}
          </span>
        </div>
        <p v-if="s.note" class="sub-meta">说明：{{ s.note }}</p>
        <p v-if="s.commentText" class="sub-meta">
          评论：{{ s.commentText }}
          <span v-if="s.commentFlagged" class="sub-tag sub-flagged">平台判违规</span>
        </p>
        <p v-if="s.reviewNote" class="sub-meta sub-reject">退回原因：{{ s.reviewNote }}</p>

        <ul v-if="s.verification?.checks?.length" class="sub-checks">
          <li v-for="c in s.verification.checks" :key="c.type">
            <span class="sub-tag" :class="`sub-verify-${c.status}`">
              {{ CHECK_TYPE_LABEL[c.type] || c.type }}：{{ VERIFICATION_LABEL[c.status] || c.status }}
            </span>
            <span v-if="c.detail" class="sub-check-detail">{{ c.detail }}</span>
          </li>
        </ul>

        <ul v-if="s.attachments && s.attachments.length > 0" class="sub-atts">
          <li v-for="a in s.attachments" :key="a.mediaId">
            <span class="sub-att-name">{{ attachmentLabel(a) }}</span>
            <span class="sub-att-size">{{ formatSize(a.sizeBytes) }}</span>
            <button type="button" :disabled="grassland.loading.value" @click="openAttachment(s, a)">
              下载
            </button>
          </li>
        </ul>

        <div v-if="role === 'merchant' && s.status === 'submitted'" class="sub-row">
          <input v-model="rejectNote" placeholder="退回原因（建议填写）" />
          <button type="button" :disabled="grassland.loading.value" @click="reject(s)">退回补交</button>
          <button type="button" :disabled="grassland.loading.value" @click="recheck(s)">重新核验</button>
        </div>
      </li>
    </ul>

    <div v-if="role === 'recommender'" class="sub-form">
      <div class="sub-row">
        <input v-model="contentUrl" :placeholder="interactionTask
          ? '互动目标链接（https://…）' : '发布链接（https://…）'" />
        <input v-if="interactionTask" v-model="platformHandle" placeholder="平台账号标识（必填，如 @xxx）" />
        <textarea
          v-if="commentTask"
          v-model="commentText"
          rows="3"
          maxlength="500"
          placeholder="评论内容（必填，≤500 字；提交时将做内容安全检查）"
          aria-label="评论内容"
        ></textarea>
      </div>

      <ul v-if="staged.length > 0" class="sub-atts">
        <li v-for="item in staged" :key="item.mediaId">
          <span class="sub-att-name">{{ item.name }}</span>
          <span class="sub-att-size">{{ formatSize(item.sizeBytes) }}</span>
          <button type="button" @click="removeStaged(item.mediaId)">移除</button>
        </li>
      </ul>

      <div class="sub-row">
        <label class="sub-file">
          <input
            type="file"
            multiple
            accept="image/*,video/*,application/pdf"
            :disabled="uploading || stagedFull"
            @change="onPickFiles"
          />
          <span>{{ uploading ? '上传中…' : `添加附件（${staged.length}/${MAX_ATTACHMENTS}）` }}</span>
        </label>
      </div>

      <div class="sub-row">
        <input
          v-model="note"
          maxlength="500"
          placeholder="补充说明（可选，≤500 字；提交时将做内容安全检查）"
        />
        <button type="button" :disabled="grassland.loading.value || !canSubmit" @click="submit">提交履约</button>
      </div>

      <p class="sub-hint">
        <span v-if="pending">已有一份待商家核验，等核验结果或被退回后才能重新提交。</span>
        <span v-else-if="stagedFull">附件已满（{{ MAX_ATTACHMENTS }} 个上限），移除一个才能再加。</span>
        <span v-else-if="interactionTask">
          互动任务请上传动作截图（展示你的账号已完成点赞/收藏/关注操作的界面，至少 1 张）；
          截图与账号标识会被 AI 核验。
        </span>
        <span v-else>
          链接须为 http(s)；附件单个不超过 {{ formatSize(MAX_ATTACHMENT_BYTES) }}，选中即上传，提交时一并带上。
        </span>
      </p>
    </div>
  </section>
</template>

<style scoped>
.sub { display: flex; flex-direction: column; gap: 8px; padding-top: 8px; border-top: 1px dashed var(--color-border); }
.sub-alert { margin: 0; padding: 6px 10px; border-radius: var(--radius-sm); font-size: 12px; }
.sub-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.sub-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.sub-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.sub-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.sub-list > li { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.sub-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.sub-main a { color: var(--color-accent); word-break: break-all; }
.sub-meta { margin: 0; font-size: 12px; opacity: 0.7; }
.sub-reject { color: var(--color-danger); opacity: 0.9; }
.sub-tag { font-size: 11px; padding: 1px 6px; border-radius: var(--radius-xs); background: var(--color-surface-strong); }
.sub-submitted { background: color-mix(in srgb, var(--color-accent) 22%, transparent); }
.sub-accepted { background: color-mix(in srgb, var(--color-success) 22%, transparent); }
.sub-rejected { background: color-mix(in srgb, var(--color-danger) 22%, transparent); }
.sub-flagged { background: color-mix(in srgb, var(--color-danger) 22%, transparent); color: var(--color-danger); margin-left: 6px; }
.sub-verify-passed { background: color-mix(in srgb, var(--color-success) 22%, transparent); }
.sub-verify-failed { background: color-mix(in srgb, var(--color-danger) 22%, transparent); }
.sub-verify-inconclusive { background: color-mix(in srgb, var(--color-accent) 22%, transparent); }
.sub-checks { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.sub-checks li { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; }
.sub-check-detail { opacity: 0.65; word-break: break-all; }
.sub-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sub-form { display: flex; flex-direction: column; gap: 6px; }
.sub-atts { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.sub-atts li { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 4px 8px; border-radius: var(--radius-sm); background: var(--color-surface-strong); }
.sub-att-name { flex: 1 1 auto; word-break: break-all; }
.sub-att-size { opacity: 0.6; white-space: nowrap; }
.sub-atts button { padding: 2px 10px; font-size: 12px; }
.sub-file { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
.sub-file input[type="file"] { display: none; }
.sub-file span { padding: 6px 14px; border: 1px dashed var(--color-border); border-radius: var(--radius-sm); }
.sub-file input:disabled + span { opacity: 0.5; cursor: not-allowed; }
input { flex: 1 1 220px; padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
