<template>
  <div v-show="active" class="ops-panel">
    <div class="ops-filters">
      <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </div>
    <p class="ops-hint">
      交易争议只读队列（脱敏）：客服直裁按 SLA 到期排序在前；写操作仍在用户端争议流程，此处仅排查与裁定参考。
    </p>
    <p v-if="loaded && rows.length === 0" class="ops-hint">当前没有争议。</p>

    <div v-if="rows.length" class="ops-table-scroll">
      <table class="ops-table">
        <thead>
          <tr><th>状态</th><th>优先级</th><th>理由</th><th>登记时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td><span class="ops-status" :class="'ops-st-' + row.status">{{ STATUS_LABEL[row.status] || row.status }}</span></td>
            <td>
              {{ row.supportPriority }}
              <span v-if="row.premiumSupport" class="ops-sev">premium</span>
            </td>
            <td><code class="ops-reason">{{ row.reason || '—' }}</code></td>
            <td class="ops-time">{{ time(row.createdAt) }}</td>
            <td><button type="button" class="ops-quiet" @click="openRow(row)">详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="hasMore" class="ops-actions ops-more">
      <button type="button" class="ops-quiet" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '加载中...' : '加载更多' }}
      </button>
    </div>

    <!-- ---------- 争议详情抽屉（只读脱敏） ---------- -->
    <div v-if="detail" class="ops-drawer-mask" @click.self="closeDetail">
      <aside class="ops-drawer" role="dialog" aria-label="争议详情">
        <header class="ops-drawer-head">
          <h3>{{ KIND_LABEL[detail.kind] || detail.kind }} · {{ STATUS_LABEL[detail.status] || detail.status }}</h3>
          <div class="ops-drawer-actions">
            <button v-if="returnCaseId" type="button" class="ops-quiet" @click="backToCase">返回处置单</button>
            <button type="button" class="ops-quiet" @click="closeDetail">关闭</button>
          </div>
        </header>

        <dl class="ops-meta">
          <div><dt>通道</dt><dd>{{ CHANNEL_LABEL[detail.channel] || detail.channel }}</dd></div>
          <div v-if="detail.channel === 'cs_direct'"><dt>SLA 到期</dt><dd>{{ time(detail.csDueAt) }}</dd></div>
          <div><dt>优先级</dt><dd>{{ detail.supportPriority }} <span v-if="detail.premiumSupport" class="ops-sev">premium</span></dd></div>
          <div><dt>发起方</dt><dd><code>{{ detail.openedByAlias }}</code>（{{ detail.openedByRole }}）</dd></div>
          <div><dt>被诉方</dt><dd><code>{{ detail.respondentAlias }}</code>{{ detail.respondentAnswered ? ' · 已答辩' : '' }}</dd></div>
          <div><dt>质证截止</dt><dd>{{ time(detail.evidenceDeadline) }}</dd></div>
          <div><dt>标的</dt><dd><code>{{ shortId(detail.engagementRef) }}</code></dd></div>
          <div><dt>平台</dt><dd>{{ detail.taskPlatform || '—' }}</dd></div>
          <div><dt>轮次 / 版本</dt><dd>{{ detail.round }} / v{{ detail.version }}</dd></div>
          <div><dt>登记时间</dt><dd>{{ time(detail.createdAt) }}</dd></div>
        </dl>

        <p v-if="detail.reason" class="ops-resolution">理由：{{ detail.reason }}</p>
        <p v-if="detail.decision" class="ops-resolution">裁定：{{ detail.decision }}</p>
        <p v-if="detail.finalDecision" class="ops-resolution">
          终局：{{ detail.finalDecision }}<span v-if="detail.finalDecidedByAlias">（{{ detail.finalDecidedByAlias }}）</span>
        </p>

        <h4 class="ops-sub">证据（{{ detail.evidenceSummary }}）</h4>
        <p v-if="detail.evidence.length === 0" class="ops-hint">暂无证据。</p>
        <ul v-else class="ops-log">
          <li v-for="item in detail.evidence" :key="item.id">
            <span class="ops-log-action">{{ EVIDENCE_KIND_LABEL[item.kind] || item.kind }}</span>
            <span class="ops-log-detail">{{ item.caption ? `${item.caption}：` : '' }}{{ item.content || '—' }}</span>
            <span class="ops-pos">{{ item.submittedByAlias }} · {{ item.submittedByRole }}</span>
          </li>
        </ul>

        <h4 class="ops-sub">
          <button type="button" class="ops-quiet" @click="toggleAudits">
            {{ auditsOpen ? '收起审计时间线' : '展开审计时间线' }}
          </button>
        </h4>
        <p v-if="auditsOpen && auditsLoading" class="ops-hint">审计加载中...</p>
        <ol v-else-if="auditsOpen" class="ops-timeline">
          <li v-if="audits.length === 0" class="ops-hint">暂无审计记录。</li>
          <li v-for="entry in audits" :key="entry.id">
            <div class="ops-tl-head">
              <span class="ops-log-action">{{ entry.action }}</span>
              <span class="ops-tl-actor">{{ entry.actorRole || 'system' }}</span>
              <span class="ops-time">{{ time(entry.createdAt) }}</span>
            </div>
            <div v-if="entry.note" class="ops-tl-body"><span class="ops-tl-note">{{ entry.note }}</span></div>
          </li>
        </ol>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 争议队列面板（任务书 #95 D95-02/D95-03/D95-07）：OpsConsole 第六个页签。
 *
 * 只读脱敏——列表/详情全部来自 trust DisputeAdminController（假名 + maskText + 内联脱敏证据），
 * 本组件不提供任何写操作。游标分页（首屏 50，hasMore「加载更多」追加，不做页码跳转）；
 * 审计时间线懒加载复用既有 GET /api/trust/disputes/{id}/audit（D95-04）。
 * 「前往客服裁定」由父级调 openDispute(disputeId, fromCaseId)；「返回处置单」emit 给父级切回。
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import type { AdminDisputeRow, AdminDisputeDetail, DisputeAudit, DisputeChannel, DisputeStatus } from '../../../types/grassland'

const props = defineProps<{
  active: boolean
  grassland: ReturnType<typeof useGrassland>
}>()

const emit = defineEmits<{
  notice: [message: string, bad?: boolean]
  'back-to-case': [caseId: string]
}>()

const rows = ref<AdminDisputeRow[]>([])
const loaded = ref(false)
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
const loadingMore = ref(false)

const detail = ref<AdminDisputeDetail | null>(null)
/** 从处置单跳入时记住来源 caseId，「返回处置单」据此切回。 */
const returnCaseId = ref<string | null>(null)
const audits = ref<DisputeAudit[]>([])
const auditsOpen = ref(false)
const auditsLoading = ref(false)

/** 中文标签与用户端 DisputeDetailView 口径一致（状态机/通道共用词表）。 */
const STATUS_LABEL: Record<DisputeStatus, string> = {
  open: '受理中', evidence: '举证质证期', voting: '评审中', decided: '已裁决', appealed: '上诉中', final: '已终局',
}
const CHANNEL_LABEL: Record<DisputeChannel, string> = { court: '小法庭', cs_direct: '客服直裁' }
const KIND_LABEL: Record<string, string> = { standard: '标准争议', merchant_rejection: '商家履约异议' }
const EVIDENCE_KIND_LABEL: Record<string, string> = { text: '文本', screenshot: '截图', link: '链接' }

watch(() => props.active, (on) => {
  if (on && !loaded.value) void refresh()
})

async function refresh(): Promise<void> {
  const page = await props.grassland.listAdminDisputes({ limit: 50 })
  // 容忍缺字段的测试 stub（与 toPagedArray 同款防御口径）
  rows.value = page?.items ?? []
  hasMore.value = page?.hasMore ?? false
  nextCursor.value = page?.nextCursor ?? null
  loaded.value = true
}

async function loadMore(): Promise<void> {
  if (!nextCursor.value) return
  loadingMore.value = true
  const page = await props.grassland.listAdminDisputes({ limit: 50, cursor: nextCursor.value })
  if (page?.items?.length) {
    rows.value.push(...page.items)
  }
  hasMore.value = page?.hasMore ?? false
  nextCursor.value = page?.nextCursor ?? null
  loadingMore.value = false
}

/** 父级「前往客服裁定」入口（D95-05）：开指定争议详情并记住来源处置单；404 等错误不静默。 */
async function openDispute(disputeId: string, fromCaseId?: string): Promise<void> {
  returnCaseId.value = fromCaseId ?? null
  audits.value = []
  auditsOpen.value = false
  const result = await props.grassland.getAdminDispute(disputeId)
  if (result) {
    detail.value = result
  } else {
    emit('notice', props.grassland.error.value || '争议详情加载失败', true)
  }
}

function openRow(row: AdminDisputeRow): void {
  void openDispute(row.id)
}

function closeDetail(): void {
  detail.value = null
  returnCaseId.value = null
}

async function backToCase(): Promise<void> {
  const caseId = returnCaseId.value
  closeDetail()
  if (caseId) emit('back-to-case', caseId)
}

/** 审计时间线懒加载（首次展开才拉取）。 */
async function toggleAudits(): Promise<void> {
  auditsOpen.value = !auditsOpen.value
  if (auditsOpen.value && detail.value && audits.value.length === 0) {
    auditsLoading.value = true
    const entries = await props.grassland.listDisputeAudit(detail.value.id)
    audits.value = entries ?? []
    auditsLoading.value = false
  }
}

/** Escape 关抽屉（对齐 OpsConsole.handleDrawerKeydown 手法；与处置单抽屉互斥，仅自身开着时挂监听）。 */
function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeDetail()
}

watch(detail, (open) => {
  if (open) document.addEventListener('keydown', handleKeydown)
  else document.removeEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

function shortId(id: string | null): string {
  return id ? `${id.slice(0, 8)}…` : '—'
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

defineExpose({ openDispute })
</script>

<style scoped>
@import '../ops-console-shared.css';
/* 理由列脱敏原文截断（code 样式，超长省略）。 */
.ops-reason { display: inline-block; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }
.ops-drawer-actions { display: flex; align-items: center; gap: 8px; }
.ops-more { justify-content: center; }
</style>
