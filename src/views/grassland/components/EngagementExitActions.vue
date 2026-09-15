<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ApplicationSettlement, EngagementExitRequest } from '../../../types/grassland'
import { formatYuan } from '../../../lib/money'
import GlModal from '../../../components/GlModal.vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useEngagementExitFunds } from '../../../composables/useEngagementExitFunds'

/**
 * 任务书 #97 C97-03：协商退出动作区（推荐官「我的任务」与商家「报名管理」双面板共用）。
 * 服务端动作契约驱动：exit_pending_confirm（对方视角=待确认，含预演金额弹窗）、
 * exit_await_response（发起方视角=待回应/撤回）；accepted 且无开放申请时提供「协商退出」发起入口。
 * 资金数字一律服务端 settlementPreview（前端不复算，§5.3）。
 */
const props = defineProps<{
  client: ReturnType<typeof useGrassland>
  taskId: string
  applicationId: string
  settlement?: ApplicationSettlement | null
  /** 报名状态（两面板行结构不同，统一传字符串；accepted=合作进行中）。 */
  applicationStatus?: string | null
}>()

const emit = defineEmits<{ refresh: [] }>()

const group = computed(() => props.settlement?.nextActionGroup || '')
const pending = ref<EngagementExitRequest | null>(null)
const loading = ref(false)
const reasonDraft = ref('')

async function loadPending(): Promise<void> {
  const list = await props.client.listEngagementExitRequests(props.taskId, props.applicationId)
  pending.value = list?.find((row) => row.status === 'pending') || null
}

watch(() => [props.applicationId, group.value], () => {
  if (group.value === 'exit_pending_confirm' || group.value === 'exit_await_response') {
    void loadPending()
  } else {
    pending.value = null
  }
}, { immediate: true })

const requestOpen = ref(false)
const confirmOpen = ref(false)
const errorText = ref('')

async function submitRequest(): Promise<void> {
  const reason = reasonDraft.value.trim()
  if (reason.length < 5 || reason.length > 500) {
    errorText.value = '协商退出原因长度须在 5 到 500 字之间'
    return
  }
  loading.value = true
  errorText.value = ''
  const created = await props.client.requestEngagementExit(props.taskId, props.applicationId, reason)
  loading.value = false
  if (!created) return
  requestOpen.value = false
  reasonDraft.value = ''
  emit('refresh')
}

async function respond(approve: boolean): Promise<void> {
  if (!pending.value) return
  loading.value = true
  const done = await props.client.respondEngagementExitRequest(
    props.taskId, props.applicationId, pending.value.id, approve)
  loading.value = false
  if (!done) return
  confirmOpen.value = false
  emit('refresh')
}

async function withdraw(): Promise<void> {
  if (!pending.value) return
  loading.value = true
  const done = await props.client.cancelEngagementExitRequest(
    props.taskId, props.applicationId, pending.value.id)
  loading.value = false
  if (done) emit('refresh')
}

const preview = computed(() => pending.value?.settlementPreview)

// 任务书 #103 C103-04：退出终态后的资金态展示（业务已终止 ≠ 资金已到账）。
// 仅在当前 application 为 withdrawn 时激活；切对象旧数据立即清空，迟到回包不回写。
const exited = computed(() => props.applicationStatus === 'withdrawn')
const { funds: exitFunds, loading: fundsLoading, refresh: refreshFunds, target: fundsTarget } =
  useEngagementExitFunds(props.client)
watch(() => [props.taskId, props.applicationId, exited.value] as const, ([taskId, appId, isExited]) => {
  fundsTarget(isExited ? taskId : null, isExited ? appId : null)
}, { immediate: true })
const fundsText = computed(() => {
  const state = exitFunds.value?.state
  if (!state) return '资金状态待查询'
  switch (state) {
    case 'pending': case 'processing': case 'retry_wait': return '资金处理中'
    case 'needs_review': return '资金待核对'
    default: return '资金处理完成'
  }
})
const fundsBadge = computed(() => {
  switch (exitFunds.value?.state) {
    case 'succeeded': return 'badge badge-success'
    case 'needs_review': return 'badge badge-warning'
    default: return 'badge badge-info'
  }
})
async function queryFunds(): Promise<void> {
  loading.value = true
  await refreshFunds()
  loading.value = false
}
</script>

<template>
  <span class="exit-actions" data-testid="engagement-exit-actions">
    <!-- 任务书 #103 C103-04：退出终态结果区——展示业务终止 + 资金态（服务端事实，前端不复算）。 -->
    <span v-if="exited" class="exit-funds" data-testid="exit-funds-result">
      <span :class="fundsBadge">{{ fundsText }}</span>
      <span v-if="exitFunds?.amounts" class="gl-hint">
        押金退 {{ formatYuan(exitFunds.amounts.deposit_refundCents ?? exitFunds.amounts.depositRefundCents ?? 0) }}
        · 赏金付 {{ formatYuan(exitFunds.amounts.bounty_captureCents ?? exitFunds.amounts.bountyCaptureCents ?? 0) }}
        · 赏金退 {{ formatYuan(exitFunds.amounts.bounty_releaseCents ?? exitFunds.amounts.bountyReleaseCents ?? 0) }}
      </span>
      <button
        type="button" class="linklike" :disabled="loading || fundsLoading"
        data-action="refresh-exit-funds" @click="queryFunds"
      >查询资金状态</button>
    </span>

    <!-- 发起入口：合作进行中且无开放申请（终态/争议态由服务端组判别，不由前端推断） -->
    <button
      v-if="applicationStatus === 'accepted' && !group.startsWith('exit_')"
      type="button" class="linklike" :disabled="loading"
      data-action="open-exit-request"
      @click="requestOpen = true"
    >协商退出</button>

    <!-- 发起方：撤回 pending 申请 -->
    <button
      v-if="group === 'exit_await_response' && pending"
      type="button" class="linklike" :disabled="loading"
      data-action="cancel-exit-request"
      @click="withdraw()"
    >撤回退出申请</button>

    <!-- 对方：确认（弹窗预演金额）/ 拒绝 -->
    <template v-if="group === 'exit_pending_confirm' && pending">
      <button type="button" :disabled="loading" data-action="confirm-exit-request" @click="confirmOpen = true">确认退出</button>
      <button type="button" class="linklike" :disabled="loading" data-action="reject-exit-request" @click="respond(false)">拒绝</button>
    </template>

    <GlModal v-if="requestOpen" title="发起协商退出" persistent @close="requestOpen = false">
      <p class="gl-hint">
        对方将在 72 小时内响应；超时申请自动失效，合作按原履约继续。确认后按<b>已确认里程碑 + 合同取消条款</b>部分结算并终态合作；无已确认里程碑时零补偿、全额释放。
      </p>
      <label class="gl-field-label">退出原因（必填，5–500 字）
        <textarea v-model="reasonDraft" rows="4" maxlength="500" data-testid="exit-reason-input"
                  placeholder="说明协商退出的原因，如档期冲突、内容方向调整" />
      </label>
      <p v-if="errorText" class="gl-hint" role="alert">{{ errorText }}</p>
      <template #actions>
        <button type="button" class="secondary" @click="requestOpen = false">取消</button>
        <button type="button" :disabled="loading" data-testid="exit-request-submit" @click="submitRequest">提交申请</button>
      </template>
    </GlModal>

    <GlModal v-if="confirmOpen" title="确认协商退出" persistent @close="confirmOpen = false">
      <p v-if="pending">对方（{{ pending.initiatedRole === 'merchant' ? '商家' : '推荐官' }}）申请协商退出，原因：{{ pending.reason }}</p>
      <p class="gl-hint">确认后合作立即终态，按以下口径部分结算（服务端按当前已确认里程碑计算）：</p>
      <dl v-if="preview" class="exit-preview" data-testid="exit-settlement-preview">
        <div><dt>脚本里程碑</dt><dd>{{ formatYuan(preview.scriptCents) }}</dd></div>
        <div><dt>交付里程碑</dt><dd>{{ formatYuan(preview.deliverableCents) }}</dd></div>
        <div><dt>发布里程碑</dt><dd>{{ formatYuan(preview.publishedCents) }}</dd></div>
        <div class="exit-preview-total"><dt>应付合计（≤ 已预留赏金）</dt><dd>{{ formatYuan(preview.totalCents) }}</dd></div>
      </dl>
      <p v-else class="gl-hint">预演金额加载中…</p>
      <template #actions>
        <button type="button" class="secondary" :disabled="loading" @click="confirmOpen = false">再想想</button>
        <button type="button" class="secondary" :disabled="loading" @click="respond(false)">拒绝退出</button>
        <button type="button" class="gl-btn-primary" :disabled="loading" data-testid="exit-confirm-submit" @click="respond(true)">确认退出并结算</button>
      </template>
    </GlModal>
  </span>
</template>

<style scoped>
.exit-actions { display: inline-flex; gap: var(--space-xs); align-items: center; flex-wrap: wrap; }
.linklike { background: none; border: none; padding: 0; color: var(--color-accent-2); cursor: pointer; font-size: inherit; }
.linklike:hover { text-decoration: underline; }
.exit-preview { display: grid; gap: var(--space-xs); margin: var(--space-sm) 0; }
.exit-preview > div { display: flex; justify-content: space-between; gap: var(--space-md); }
.exit-preview-total { font-weight: var(--weight-heading); border-top: 1px solid var(--color-border); padding-top: var(--space-xs); }
.exit-funds { display: inline-flex; gap: var(--space-xs); align-items: center; flex-wrap: wrap; }
</style>
