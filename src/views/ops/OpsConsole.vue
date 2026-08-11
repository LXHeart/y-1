<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useGrassland } from '../../composables/useGrassland'
import { parseVerificationChecks } from '../../types/grassland'
import type {
  OpsActionKind,
  OpsCase,
  OpsCaseDetail,
  OpsCaseSourceKind,
  OpsCaseStatus,
  OpsDltMessage,
  OpsPendingVerification,
} from '../../types/grassland'

/**
 * 运营处置台（GL-P1-OPS-001 Stage 3）。
 *
 * 后端门禁是平台角色 `customer_service` 或 `admin`（marketplace `requireOpsOperator`），
 * 本组件由 `App.vue` 按 `useAuth().currentUser.role` 决定是否挂载 —— 那只是不给无权者看见入口，
 * 真正的授权在服务端。
 *
 * 三个视图对应三种性质不同的东西，刻意不合并：
 * - **处置单**：有状态机 + 双人审批 + 资金动作
 * - **死信**：有重投/弃置，但幂等键与处置单共用一套台账
 * - **待判定**：自动核验 inconclusive 队列；人工复核写 override，不覆盖自动核验真相
 */

const grassland = useGrassland()

type Tab = 'cases' | 'dlt' | 'pending'
const tab = ref<Tab>('cases')

const cases = ref<OpsCase[]>([])
/** 空串 = 未终态队列（后端默认口径）。 */
const statusFilter = ref<'' | OpsCaseStatus>('')
const sourceFilter = ref<'' | OpsCaseSourceKind>('')
const severityOnly = ref(false)
const casesLoaded = ref(false)

const detail = ref<OpsCaseDetail | null>(null)
const notice = ref('')
/**
 * 提示是否为失败。刻意用独立标志而非在文案里找「失败」二字：动作失败原因来自 finance 的
 * 原始报错，靠关键字猜会把成功染红、也会把失败留成绿色 —— 运营看颜色下判断，染错比不染更糟。
 */
const noticeBad = ref(false)

function say(message: string, bad = false): void {
  notice.value = message
  noticeBad.value = bad
}
const note = ref('')
const resolution = ref('')

const dlt = ref<OpsDltMessage[]>([])
const dltStatusFilter = ref<'' | OpsDltMessage['status']>('')
const dltLoaded = ref(false)

const pending = ref<OpsPendingVerification[]>([])
const pendingLoaded = ref(false)
const pendingNotes = ref<Record<string, string>>({})

const SOURCE_LABEL: Record<OpsCaseSourceKind, string> = {
  settlement_blocked: '对账阻断',
  settlement_held: '结算暂缓',
  dlt_message: '死信消息',
}

const STATUS_LABEL: Record<OpsCaseStatus, string> = {
  open: '待提审',
  in_review: '待审批',
  approved: '已批准',
  rejected: '已驳回',
  resolved: '已收单',
}

const ACTION_LABEL: Record<string, string> = {
  registered: '系统登记',
  submitted: '提审',
  approved: '批准',
  rejected: '驳回',
  resolved: '收单',
  action_executed: '动作执行',
  action_failed: '动作失败',
  retry_reconciliation: '重试对账',
  release_funds: '释放托管资金',
  dlt_replay: '死信重投',
  dlt_discard: '死信弃置',
}

/** 处置单可执行的动作，按来源收窄 —— 后端对不匹配的组合返回 400，前端先不给点。 */
const ACTIONS_BY_SOURCE: Record<OpsCaseSourceKind, OpsActionKind[]> = {
  settlement_blocked: ['retry_reconciliation'],
  settlement_held: ['release_funds'],
  dlt_message: [],
}

const filteredCases = computed(() => cases.value.filter((c) => {
  if (sourceFilter.value && c.sourceKind !== sourceFilter.value) return false
  if (severityOnly.value && c.severity !== 'high') return false
  return true
}))

async function refreshCases(): Promise<void> {
  const list = await grassland.listOpsCases(statusFilter.value || undefined)
  if (list) cases.value = list
  casesLoaded.value = true
}

async function refreshDlt(): Promise<void> {
  const list = await grassland.listOpsDlt(dltStatusFilter.value || undefined)
  if (list) dlt.value = list
  dltLoaded.value = true
}

/**
 * 从死信卡跳到它的处置单。后端 409「须先经双人审批」只说了要审批、没说去哪审批，
 * 而死信 sourceRef 恰好是 `topic:partition:offset`，前端能自己算出来 —— 不必为此加接口。
 */
async function openDltCase(message: OpsDltMessage): Promise<void> {
  const ref = `${message.topic}:${message.partition}:${message.offset}`
  tab.value = 'cases'
  statusFilter.value = ''
  sourceFilter.value = 'dlt_message'
  await refreshCases()
  const target = cases.value.find((c) => c.sourceKind === 'dlt_message' && c.sourceRef === ref)
  if (target) await openDetail(target.id)
  else say('未找到该死信对应的处置单（可能已超出列表条数上限）', true)
}

async function refreshPending(): Promise<void> {
  const list = await grassland.listOpsPendingVerifications()
  if (list) pending.value = list
  pendingLoaded.value = true
}

async function overridePending(row: OpsPendingVerification, status: 'passed' | 'failed'): Promise<void> {
  const note = (pendingNotes.value[row.submissionId] || '').trim()
  if (!note) {
    say('人工复核必须填写原因', true)
    return
  }
  const result = await grassland.overrideOpsVerification(row.submissionId, status, note)
  if (result) {
    pendingNotes.value[row.submissionId] = ''
    say(status === 'passed' ? '已人工判定通过' : '已人工判定不通过')
    await refreshPending()
  } else {
    say(grassland.error.value || '人工复核失败', true)
  }
}

onMounted(refreshCases)

async function switchTab(next: Tab): Promise<void> {
  tab.value = next
  say('')
  grassland.clearError()
  if (next === 'dlt' && !dltLoaded.value) await refreshDlt()
  if (next === 'pending' && !pendingLoaded.value) await refreshPending()
}

async function openDetail(id: string): Promise<void> {
  say('')
  note.value = ''
  resolution.value = ''
  detail.value = await grassland.getOpsCase(id)
}

function closeDetail(): void {
  detail.value = null
}

/**
 * Escape 关抽屉。抽屉是全屏遮罩，打开后页面头部（含退出登录）点不到，不给键盘出路会把人困在里面。
 *
 * <p>监听挂在 document 而非遮罩自身：流转后按钮会随状态变化被移出 DOM（如 open→in_review 时「提审」消失），
 * 焦点掉回 body，挂在遮罩上的 keydown 就再也收不到了。
 */
function handleDrawerKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeDetail()
}

watch(detail, (open) => {
  if (open) document.addEventListener('keydown', handleDrawerKeydown)
  else document.removeEventListener('keydown', handleDrawerKeydown)
})

onBeforeUnmount(() => document.removeEventListener('keydown', handleDrawerKeydown))

/** 流转后同时刷详情与队列：状态变了，队列的筛选归属可能也变了（如 resolved 退出未终态队列）。 */
async function afterTransition(message: string): Promise<void> {
  say(message)
  note.value = ''
  const id = detail.value?.case.id
  if (id) detail.value = await grassland.getOpsCase(id)
  await refreshCases()
}

async function submit(): Promise<void> {
  const c = detail.value?.case
  if (!c) return
  const updated = await grassland.submitOpsCase(c.id, c.version, note.value.trim() || undefined)
  if (updated) await afterTransition('已提审，等待另一名运营审批')
}

async function decide(approve: boolean): Promise<void> {
  const c = detail.value?.case
  if (!c) return
  const updated = await grassland.decideOpsCase(c.id, c.version, approve, note.value.trim() || undefined)
  if (updated) await afterTransition(approve ? '已批准，可执行处置动作' : '已驳回（终态）')
}

async function resolve(): Promise<void> {
  const c = detail.value?.case
  if (!c) return
  const text = resolution.value.trim()
  if (!text) {
    say('请填写处置结果', true)
    return
  }
  const updated = await grassland.resolveOpsCase(c.id, c.version, text, note.value.trim() || undefined)
  if (updated) {
    resolution.value = ''
    await afterTransition('已收单')
  }
}

async function runAction(action: OpsActionKind): Promise<void> {
  const c = detail.value?.case
  if (!c) return
  const executed = await grassland.executeOpsAction(c.id, action, grassland.newOperationId(action))
  if (!executed) return
  const ok = executed.status === 'succeeded'
  say(ok
    ? `${ACTION_LABEL[action] || action}：成功（${executed.outcome || '已完成'}）`
    : `${ACTION_LABEL[action] || action}：失败 — ${executed.error || '未知原因'}`, !ok)
  detail.value = await grassland.getOpsCase(c.id)
}

async function runDltAction(message: OpsDltMessage, replay: boolean): Promise<void> {
  const executed = await grassland.executeOpsDltAction(
    message.id, replay, grassland.newOperationId(replay ? 'dlt_replay' : 'dlt_discard'))
  if (!executed) return
  const ok = executed.status === 'succeeded'
  say(ok
    ? (replay ? `已重投至 ${message.originalTopic}` : '已弃置（记录保留可审计）')
    : `执行失败 — ${executed.error || '未知原因'}`, !ok)
  await refreshDlt()
}

function shortId(id: string | null): string {
  return id ? `${id.slice(0, 8)}…` : '—'
}

/**
 * 死信 error_summary 是 Spring 的整段 listener 报错（"Async Fail / Endpoint handler details: Method [...]
 * Bean [...]; 真正原因"）。真正原因在最后一个 `; ` 之后，前面全是样板 —— 直接铺开会把原因埋掉，
 * 故摘出尾段做标题、整段收进 details 供排查。
 */
function isTerminal(status: OpsCaseStatus): boolean {
  return status === 'rejected' || status === 'resolved'
}

function errorHeadline(summary: string): string {
  const flat = summary.replace(/\s+/g, ' ').trim()
  const cut = flat.lastIndexOf('; ')
  return cut >= 0 ? flat.slice(cut + 2) : flat
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

function checksOf(row: OpsPendingVerification) {
  return parseVerificationChecks(row.checks)
}
</script>

<template>
  <section class="ops">
    <header class="ops-head">
      <div>
        <h2 class="ops-title">运营处置台</h2>
        <p class="ops-desc">阻断/暂缓单据的双人审批与受限处置动作、死信重投、待人工判定核验</p>
      </div>
    </header>

    <nav class="ops-tabs" role="tablist" aria-label="处置视图">
      <button
        type="button" role="tab" class="ops-tab" :class="{ 'ops-tab-on': tab === 'cases' }"
        :aria-selected="tab === 'cases'" @click="switchTab('cases')"
      >处置单</button>
      <button
        type="button" role="tab" class="ops-tab" :class="{ 'ops-tab-on': tab === 'dlt' }"
        :aria-selected="tab === 'dlt'" @click="switchTab('dlt')"
      >死信队列</button>
      <button
        type="button" role="tab" class="ops-tab" :class="{ 'ops-tab-on': tab === 'pending' }"
        :aria-selected="tab === 'pending'" @click="switchTab('pending')"
      >待判定核验</button>
    </nav>

    <p v-if="grassland.error.value" class="ops-alert ops-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="ops-alert" :class="noticeBad ? 'ops-err' : 'ops-ok'">{{ notice }}</p>

    <!-- ---------- 处置单队列 ---------- -->
    <div v-if="tab === 'cases'" class="ops-panel">
      <div class="ops-filters">
        <label>状态
          <select v-model="statusFilter" @change="refreshCases">
            <option value="">未终态</option>
            <option value="open">待提审</option>
            <option value="in_review">待审批</option>
            <option value="approved">已批准</option>
            <option value="rejected">已驳回</option>
            <option value="resolved">已收单</option>
          </select>
        </label>
        <label>来源
          <select v-model="sourceFilter">
            <option value="">全部</option>
            <option value="settlement_blocked">对账阻断</option>
            <option value="settlement_held">结算暂缓</option>
            <option value="dlt_message">死信消息</option>
          </select>
        </label>
        <label class="ops-check">
          <input v-model="severityOnly" type="checkbox" />
          仅看高危
        </label>
        <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="refreshCases">刷新</button>
      </div>

      <p v-if="casesLoaded && filteredCases.length === 0" class="ops-hint">当前筛选下没有处置单。</p>

      <table v-if="filteredCases.length" class="ops-table">
        <thead>
          <tr><th>来源</th><th>原因</th><th>标的</th><th>状态</th><th>登记时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="c in filteredCases" :key="c.id" :class="{ 'ops-row-high': c.severity === 'high' }">
            <td>
              {{ SOURCE_LABEL[c.sourceKind] || c.sourceKind }}
              <span v-if="c.severity === 'high'" class="ops-sev">高危</span>
            </td>
            <td><code>{{ c.reason }}</code></td>
            <td><code>{{ shortId(c.sourceRef) }}</code></td>
            <td><span class="ops-status" :class="'ops-st-' + c.status">{{ STATUS_LABEL[c.status] || c.status }}</span></td>
            <td class="ops-time">{{ time(c.createdAt) }}</td>
            <td><button type="button" class="ops-quiet" @click="openDetail(c.id)">详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ---------- 死信队列 ---------- -->
    <div v-if="tab === 'dlt'" class="ops-panel">
      <div class="ops-filters">
        <label>状态
          <select v-model="dltStatusFilter" @change="refreshDlt">
            <option value="">待处置</option>
            <option value="replayed">已重投</option>
            <option value="discarded">已弃置</option>
          </select>
        </label>
        <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="refreshDlt">刷新</button>
      </div>

      <p class="ops-hint">重投与弃置都须对应处置单已通过双人审批 —— 未审批时后端返回 409。</p>
      <p class="ops-hint">
        重投是<b>投回原 topic</b>：该 topic 上每个消费组都会再收一次。仍失败的消费组各自登记一条新死信
        （各带自己的处置单），所以一次重投可能换来多条 —— 消息本身有问题时应当弃置，而不是反复重投。
      </p>
      <p v-if="dltLoaded && dlt.length === 0" class="ops-hint">当前筛选下没有死信。</p>

      <section v-for="m in dlt" :key="m.id" class="ops-item">
        <div class="ops-item-head">
          <code>{{ m.topic }}</code>
          <span class="ops-pos">p{{ m.partition }} / offset {{ m.offset }}</span>
          <span class="ops-status" :class="'ops-st-' + m.status">{{ m.status }}</span>
        </div>
        <dl class="ops-meta">
          <div><dt>原 topic</dt><dd><code>{{ m.originalTopic }}</code></dd></div>
          <div><dt>消息 key</dt><dd><code>{{ m.messageKey || '—' }}</code></dd></div>
          <div><dt>入队时间</dt><dd>{{ time(m.createdAt) }}</dd></div>
        </dl>
        <div v-if="m.errorSummary" class="ops-err-summary">
          <p class="ops-err-head">{{ errorHeadline(m.errorSummary) }}</p>
          <details><summary>完整报错</summary><pre>{{ m.errorSummary }}</pre></details>
        </div>
        <pre class="ops-payload">{{ m.payload }}</pre>
        <div v-if="m.status === 'pending'" class="ops-actions">
          <button type="button" :disabled="grassland.loading.value" @click="runDltAction(m, true)">重投原 topic</button>
          <button type="button" class="ops-danger" :disabled="grassland.loading.value" @click="runDltAction(m, false)">弃置</button>
          <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="openDltCase(m)">查看处置单</button>
        </div>
        <p v-else class="ops-hint">
          {{ m.status === 'replayed' ? `已于 ${time(m.replayedAt)} 重投` : `已于 ${time(m.discardedAt)} 弃置` }}
        </p>
      </section>
    </div>

    <!-- ---------- 待判定核验 ---------- -->
    <div v-if="tab === 'pending'" class="ops-panel">
      <div class="ops-filters">
        <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="refreshPending">刷新</button>
      </div>
      <p class="ops-hint">
        自动核验结论为「无法判定」且交付物仍待商家处理。人工复核会单独记录 override，
        不覆盖自动核验原始结论；人工判定不通过会阻断商家确认与后续结算。
      </p>
      <p v-if="pendingLoaded && pending.length === 0" class="ops-hint">当前没有待判定的核验。</p>

      <section v-for="row in pending" :key="row.verificationId" class="ops-item">
        <div class="ops-item-head">
          <strong>{{ row.taskTitle }}</strong>
          <span class="ops-pos">推荐官 <code>{{ shortId(row.recommenderAccountId) }}</code></span>
        </div>
        <dl class="ops-meta">
          <div><dt>交付链接</dt><dd><span class="ops-url">{{ row.contentUrl }}</span></dd></div>
          <div><dt>最近核验</dt><dd>{{ time(row.lastCheckedAt) }}</dd></div>
          <div><dt>提交时间</dt><dd>{{ time(row.submittedAt) }}</dd></div>
        </dl>
        <ul class="ops-checks">
          <li v-for="(check, i) in checksOf(row)" :key="i">
            <span class="ops-check-type">{{ check.type }}</span>
            <span class="ops-check-status">{{ check.status }}</span>
            <span class="ops-check-detail">{{ check.detail || '' }}</span>
          </li>
        </ul>
        <div class="ops-actions ops-review-actions">
          <input
            v-model="pendingNotes[row.submissionId]"
            class="ops-review-note"
            type="text"
            maxlength="500"
            placeholder="填写人工复核原因"
          />
          <button
            type="button"
            :disabled="grassland.loading.value"
            @click="overridePending(row, 'passed')"
          >判定通过</button>
          <button
            type="button"
            class="ops-danger"
            :disabled="grassland.loading.value"
            @click="overridePending(row, 'failed')"
          >判定不通过</button>
        </div>
      </section>
    </div>

    <!-- ---------- 详情抽屉 ---------- -->
    <div
      v-if="detail"
      class="ops-drawer-mask"
      @click.self="closeDetail"
    >
      <aside class="ops-drawer" role="dialog" aria-label="处置单详情">
        <header class="ops-drawer-head">
          <h3>{{ SOURCE_LABEL[detail.case.sourceKind] || detail.case.sourceKind }} · {{ detail.case.reason }}</h3>
          <button type="button" class="ops-quiet" @click="closeDetail">关闭</button>
        </header>

        <dl class="ops-meta">
          <div><dt>状态</dt><dd>{{ STATUS_LABEL[detail.case.status] || detail.case.status }}（v{{ detail.case.version }}）</dd></div>
          <div><dt>标的</dt><dd><code>{{ detail.case.sourceRef }}</code></dd></div>
          <div><dt>组织</dt><dd><code>{{ shortId(detail.case.organizationId) }}</code></dd></div>
          <div><dt>履约</dt><dd><code>{{ shortId(detail.case.applicationId) }}</code></dd></div>
          <div><dt>提审人</dt><dd><code>{{ shortId(detail.case.submittedBy) }}</code></dd></div>
          <div><dt>审批人</dt><dd><code>{{ shortId(detail.case.approvedBy) }}</code></dd></div>
        </dl>

        <p v-if="detail.case.resolution" class="ops-resolution">处置结果：{{ detail.case.resolution }}</p>

        <!-- 终态（已驳回/已收单）没有任何可做的事，整块流转区收起来，别留一个写了没用的备注框。 -->
        <section v-if="!isTerminal(detail.case.status)" class="ops-flow">
          <input v-model="note" placeholder="备注（可选，写进审计流水）" />
          <div class="ops-actions">
            <button
              v-if="detail.case.status === 'open'" type="button"
              :disabled="grassland.loading.value" @click="submit"
            >提审</button>
            <template v-if="detail.case.status === 'in_review'">
              <button type="button" :disabled="grassland.loading.value" @click="decide(true)">批准</button>
              <button type="button" class="ops-danger" :disabled="grassland.loading.value" @click="decide(false)">驳回</button>
            </template>
            <button
              v-for="action in (detail.case.status === 'approved' ? ACTIONS_BY_SOURCE[detail.case.sourceKind] : [])"
              :key="action" type="button" :disabled="grassland.loading.value" @click="runAction(action)"
            >{{ ACTION_LABEL[action] || action }}</button>
          </div>
          <p v-if="detail.case.status === 'in_review'" class="ops-hint">
            双人审批：审批人不能是提审人 —— 由另一名运营账号完成。
          </p>
          <div v-if="detail.case.status === 'approved'" class="ops-actions">
            <input v-model="resolution" placeholder="处置结果（收单必填）" />
            <button type="button" :disabled="grassland.loading.value" @click="resolve">收单</button>
          </div>
        </section>

        <h4 class="ops-sub">动作台账</h4>
        <p v-if="detail.actions.length === 0" class="ops-hint">尚未执行任何处置动作。</p>
        <ul v-else class="ops-log">
          <li v-for="a in detail.actions" :key="a.id">
            <span class="ops-log-action">{{ ACTION_LABEL[a.action] || a.action }}</span>
            <span class="ops-status" :class="'ops-st-' + a.status">{{ a.status }}</span>
            <span class="ops-log-detail">{{ a.outcome || a.error || '' }}</span>
            <span class="ops-time">{{ time(a.createdAt) }}</span>
          </li>
        </ul>

        <h4 class="ops-sub">审计时间线</h4>
        <ol class="ops-timeline">
          <li v-for="a in detail.audits" :key="a.id">
            <div class="ops-tl-head">
              <span class="ops-log-action">{{ ACTION_LABEL[a.action] || a.action }}</span>
              <span class="ops-tl-actor">
                {{ a.actorRole === 'system' ? '系统' : `${a.actorRole} ${shortId(a.actorAccountId)}` }}
              </span>
              <span class="ops-time">{{ time(a.createdAt) }}</span>
            </div>
            <div class="ops-tl-body">
              <span class="ops-tl-transition">{{ a.fromStatus || '—' }} → {{ a.toStatus }}</span>
              <span v-if="a.note" class="ops-tl-note">{{ a.note }}</span>
            </div>
          </li>
        </ol>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.ops { display: flex; flex-direction: column; gap: 14px; }
.ops-head { display: flex; justify-content: space-between; align-items: flex-start; }
.ops-title { margin: 0; font-size: 17px; }
.ops-desc { margin: 4px 0 0; font-size: 12px; opacity: 0.62; }
.ops-tabs { display: flex; gap: 6px; border-bottom: 1px solid var(--color-border); }
.ops-tab { padding: 7px 14px; border: none; background: transparent; color: var(--color-text); cursor: pointer; font-size: 13px; opacity: 0.65; border-bottom: 2px solid transparent; }
.ops-tab-on { opacity: 1; border-bottom-color: var(--color-accent, currentColor); }
.ops-panel { display: flex; flex-direction: column; gap: 10px; }
.ops-filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 12px; }
.ops-filters label { display: flex; align-items: center; gap: 6px; opacity: 0.8; }
.ops-check { cursor: pointer; }
.ops-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.ops-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.ops-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.ops-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.ops-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ops-table th { text-align: left; padding: 6px 8px; opacity: 0.6; font-weight: 500; border-bottom: 1px solid var(--color-border); }
.ops-table td { padding: 7px 8px; border-bottom: 1px solid var(--color-border); }
.ops-row-high td:first-child { border-left: 2px solid var(--color-danger); }
.ops-sev { margin-left: 6px; font-size: 10px; padding: 1px 5px; border-radius: 4px; color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 12%, transparent); }
.ops-status { font-size: 11px; padding: 1px 7px; border-radius: 4px; background: var(--color-surface-strong); }
.ops-st-approved, .ops-st-succeeded, .ops-st-replayed { color: var(--color-success); }
.ops-st-rejected, .ops-st-failed { color: var(--color-danger); }
.ops-time { font-size: 11px; opacity: 0.6; white-space: nowrap; }
.ops-item { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.ops-item-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.ops-pos { font-size: 11px; opacity: 0.65; }
.ops-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 8px; margin: 0; }
.ops-meta div { display: flex; flex-direction: column; gap: 2px; }
.ops-meta dt { font-size: 11px; opacity: 0.6; }
.ops-meta dd { margin: 0; font-size: 12px; word-break: break-all; }
.ops-url { word-break: break-all; }
.ops-err-summary { font-size: 12px; color: var(--color-danger); word-break: break-all; }
.ops-err-head { margin: 0; }
.ops-err-summary details { margin-top: 4px; }
.ops-err-summary summary { cursor: pointer; opacity: 0.8; }
.ops-err-summary pre { margin: 4px 0 0; white-space: pre-wrap; font-size: 11px; opacity: 0.85; }
.ops-payload { margin: 0; padding: 8px; border-radius: 6px; background: var(--color-surface-strong); font-size: 11px; max-height: 120px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.ops-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ops-actions input { flex: 1; min-width: 160px; }
.ops-checks { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.ops-checks li { display: flex; gap: 10px; font-size: 12px; }
.ops-check-type { flex: 0 0 120px; opacity: 0.7; }
.ops-check-status { flex: 0 0 90px; }
.ops-check-detail { flex: 1; opacity: 0.7; word-break: break-all; }
.ops-drawer-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.42); display: flex; justify-content: flex-end; z-index: 40; }
.ops-drawer { width: min(560px, 94vw); height: 100%; overflow-y: auto; padding: 16px; background: var(--color-surface); border-left: 1px solid var(--color-border); display: flex; flex-direction: column; gap: 12px; }
.ops-drawer-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.ops-drawer-head h3 { margin: 0; font-size: 15px; }
.ops-resolution { margin: 0; padding: 7px 10px; border-radius: 6px; background: var(--color-surface-strong); font-size: 12px; }
.ops-flow { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.ops-sub { margin: 4px 0 0; font-size: 13px; }
.ops-log { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.ops-log li { display: flex; align-items: center; gap: 8px; font-size: 12px; flex-wrap: wrap; }
.ops-log-action { font-weight: 500; }
.ops-log-detail { flex: 1; opacity: 0.72; word-break: break-all; }
.ops-timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.ops-timeline li { padding-left: 10px; border-left: 2px solid var(--color-border); display: flex; flex-direction: column; gap: 3px; }
.ops-tl-head { display: flex; align-items: center; gap: 8px; font-size: 12px; flex-wrap: wrap; }
.ops-tl-actor { opacity: 0.68; }
.ops-tl-body { display: flex; gap: 10px; font-size: 11px; opacity: 0.7; flex-wrap: wrap; }
.ops-tl-transition { font-family: monospace; }
input, select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.ops-danger { color: var(--color-danger); }
.ops-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
code { font-size: 11px; }
</style>
