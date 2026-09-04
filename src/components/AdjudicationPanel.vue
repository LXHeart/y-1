<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import MediaUploader from './MediaUploader.vue'
import type { AdjudicationSnapshot, Judge, JudgeExamQuestion, VoteChoice } from '../types/grassland'

/**
 * 审判看板——把 Slice 6C 建的审判能力 + 任务书 #74 小法庭结构暴露到 UI。
 *
 * 分区按角色显示：
 * - 所有人：争议时间线（质证中 → 评审中 → 已裁决 → 上诉期 → 终局）/ 轮次 / 面板进度 / 计票条
 * - 当事方（质证期）：被告答辩 / 原告补充质证 / 双方「质证完毕」
 * - 审判官（已入池且在本轮面板）：投票（理由必填 ≥20 字，卡 C/D2）
 * - 当事方（decided 态）：上诉
 * - 客服：终审三选（维持 / 改判 / 发回重审，卡 F）
 * - 推荐官：审判官报名 + 准入考试（Lv4 通道，卡 E）
 */

const props = defineProps<{ disputeId: string }>()

const grassland = useGrassland()

const snapshot = ref<AdjudicationSnapshot | null>(null)
const judge = ref<Judge | null>(null)
const voteRationale = ref('')
/** 卡 F：终审三选。 */
const csAction = ref<'maintain' | 'overturn' | 'retrial'>('maintain')
const csDecision = ref<'for_merchant' | 'for_recommender'>('for_merchant')
const localNotice = ref('')
const reauthPassword = ref('')
/** 本次会话重认证时刻（本地展示用；权威值在 session，由断言透传给后端）。 */
const reauthAt = ref('')

/** 卡 B：质证操作区（后端按角色强校验，409 人话文案经 grassland.error 展示）。 */
const evidenceMode = ref<'answer' | 'rebuttal'>('answer')
const evidenceText = ref('')
const evidenceMediaIds = ref<string[]>([])
const evidenceDoneBusy = ref(false)

/** 卡 E：准入考试（Lv4 报名通道）。 */
const examOpen = ref(false)
const examQuestions = ref<JudgeExamQuestion[]>([])
const examChoices = ref<Record<string, number>>({})
const examBusy = ref(false)
const examResult = ref<{ score: number; passed: boolean; cooldownUntil: string | null } | null>(null)

const isVoting = computed(() => snapshot.value?.status === 'voting')
const isDecided = computed(() => snapshot.value?.status === 'decided')
const isFinal = computed(() => snapshot.value?.status === 'final')
/** 质证期（open 为存量兼容态，同样可质证——后端口径 open|evidence）。 */
const isEvidencePhase = computed(() => snapshot.value?.status === 'evidence' || snapshot.value?.status === 'open')
const isEnrolledJudge = computed(() => judge.value?.active === true)
const isProbationJudge = computed(() => judge.value?.probation === true)
const isSuspendedJudge = computed(() => judge.value?.suspendedNow === true)
const isCsDirect = computed(() => snapshot.value?.channel === 'cs_direct')

const tallies = computed(() => snapshot.value?.tallies ?? null)

/** 投票理由必填且 ≥20 字（卡 C/D2；后端同规则 400 兜底）。 */
const RATIONALE_MIN = 20
const rationaleValid = computed(() => voteRationale.value.trim().length >= RATIONALE_MIN)

/**
 * 窗口倒计时。后端给的 remainingSeconds 是拉取瞬间的快照，本地每秒递减做平滑显示，
 * 避免为了走秒去轮询后端。归零后提示「等待系统处理」——真正的到期由 Temporal Timer 驱动，
 * 可能有秒级偏差，故不据此断言状态已变。
 */
const remaining = ref<number | null>(null)
let ticker: ReturnType<typeof setInterval> | undefined

function startTicker(): void {
  if (ticker) clearInterval(ticker)
  ticker = setInterval(() => {
    if (remaining.value !== null && remaining.value > 0) remaining.value -= 1
  }, 1000)
}

/**
 * 落地一份快照：**同时**同步倒计时。所有写 `snapshot` 的路径都必须走这里。
 */
function applySnapshot(snap: AdjudicationSnapshot): void {
  snapshot.value = snap
  remaining.value = snap.window?.remainingSeconds ?? null
  if (remaining.value !== null) {
    startTicker()
  } else if (ticker) {
    clearInterval(ticker)  // 终局无窗口：停表，避免空转
    ticker = undefined
  }
}

onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

const windowLabel = computed(() => {
  const phase = snapshot.value?.window?.phase
  if (phase === 'evidence') return '举证质证窗口'
  if (phase === 'vote') return '投票窗口'
  if (phase === 'appeal') return '上诉窗口'
  return ''
})

/** 剩余时长的人类可读形式（1天2小时 / 5分30秒 / 已到期）。 */
const remainingText = computed(() => {
  const value = remaining.value
  if (value === null) return ''
  if (value <= 0) return '已到期，等待系统处理'
  const days = Math.floor(value / 86400)
  const hours = Math.floor((value % 86400) / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  const seconds = value % 60
  if (days > 0) return `剩余 ${days} 天 ${hours} 小时`
  if (hours > 0) return `剩余 ${hours} 小时 ${minutes} 分`
  if (minutes > 0) return `剩余 ${minutes} 分 ${seconds} 秒`
  return `剩余 ${seconds} 秒`
})

/** 争议时间线节点（卡 B：质证中 → 评审中 → 已裁决 → 上诉期）。 */
const timeline = computed(() => {
  const status = snapshot.value?.status
  const stages = [
    { key: 'evidence', label: '质证中' },
    { key: 'voting', label: '评审中' },
    { key: 'decided', label: '已裁决' },
    { key: 'appealed', label: '上诉期' },
    { key: 'final', label: '已终局' },
  ]
  const order = ['open', 'evidence', 'voting', 'decided', 'appealed', 'final']
  const current = order.indexOf(status ?? 'open')
  return stages.map((stage, index) => {
    const stageIndex = order.indexOf(stage.key)
    const active = stage.key === status
      || (stage.key === 'evidence' && status === 'open')
    return { ...stage, done: stageIndex < current, active: active || (stage.key === 'final' && false) }
  })
})

/** 计票条宽度（按面板满员算，未投票部分留白 → 直观看出投票进度）。 */
function barWidth(count: number): string {
  const size = tallies.value?.panelSize ?? 0
  if (size <= 0) return '0%'
  return `${Math.round((count / size) * 100)}%`
}

const statusLabel = computed(() => {
  const map: Record<string, string> = {
    open: '已受理（质证期）',
    evidence: '举证质证中',
    voting: '评审投票中',
    decided: '已判决（上诉窗口内）',
    appealed: '已上诉（待客服终审）',
    final: '已终局',
  }
  return map[snapshot.value?.status ?? ''] || snapshot.value?.status || '—'
})

const channelLabel = computed(() => {
  if (snapshot.value?.channel === 'cs_direct') return '客服直裁'
  return '小法庭'
})

const decisionLabel = (value: string | null): string => {
  if (!value) return '—'
  if (value === 'for_merchant') return '商家方胜诉'
  if (value === 'for_recommender') return '推荐官方胜诉'
  if (value === 'retrial') return '发回重审'
  return value
}

async function refresh(): Promise<void> {
  const snap = await grassland.getAdjudication(props.disputeId)
  // 以后端值为准重置倒计时（本地 tick 只做平滑，不作权威）
  if (snap) applySnapshot(snap)
  judge.value = await grassland.getMyJudgeStatus()
}

watch(() => props.disputeId, refresh, { immediate: true })

async function startAdjudication(): Promise<void> {
  localNotice.value = ''
  const started = await grassland.startAdjudication(props.disputeId)
  if (!started) return
  applySnapshot(started)
  localNotice.value = `审判已启动（第 ${started.round} 轮，面板 ${started.panel.size} 人）`
}

async function enroll(): Promise<void> {
  localNotice.value = ''
  const enrolled = await grassland.enrollAsJudge()
  if (!enrolled) return
  judge.value = enrolled
  localNotice.value = enrolled.eligibilityTier >= 5
    ? '已加入审判官池（Lv5 直入），后续争议可能抽中你'
    : '已报名。Lv4 须通过准入考试获得见习资格后方可被抽签'
}

async function leave(): Promise<void> {
  const left = await grassland.leaveJudgePool()
  if (!left) return
  judge.value = left
  localNotice.value = '已退出审判官池'
}

async function vote(choice: VoteChoice): Promise<void> {
  localNotice.value = ''
  if (!rationaleValid.value) {
    localNotice.value = `投票理由必填且不少于 ${RATIONALE_MIN} 字（弃权同样需要）`
    return
  }
  const cast = await grassland.castVote(props.disputeId, choice, voteRationale.value.trim())
  if (!cast) return
  voteRationale.value = ''
  localNotice.value = '投票已记录（每轮一票，不可更改；达 4/7 多数即提前终局）'
  await refresh()
}

// ---------- 卡 B：质证 ----------

async function submitEvidence(): Promise<void> {
  localNotice.value = ''
  const text = evidenceText.value.trim()
  const items = [
    ...(text ? [{ kind: 'text' as const, contentRef: text, caption: text.slice(0, 100) }] : []),
    ...evidenceMediaIds.value.map((id) => ({ kind: 'screenshot' as const, contentRef: id, caption: '凭证截图' })),
  ]
  if (items.length === 0) {
    localNotice.value = '请填写说明或上传截图'
    return
  }
  const submit = evidenceMode.value === 'answer'
    ? grassland.submitDisputeAnswer(props.disputeId, items)
    : grassland.submitDisputeRebuttal(props.disputeId, items)
  const saved = await submit
  if (!saved) return
  evidenceText.value = ''
  evidenceMediaIds.value = []
  localNotice.value = evidenceMode.value === 'answer' ? '答辩已提交（每案至多一次）' : '补充质证已提交（每案至多一次）'
  await refresh()
}

async function markEvidenceDone(): Promise<void> {
  localNotice.value = ''
  evidenceDoneBusy.value = true
  try {
    const done = await grassland.markEvidenceDone(props.disputeId)
    if (!done) return
    localNotice.value = done.bothDone
      ? '双方均已质证完毕，案件将立即开庭'
      : '已标记你方质证完毕，等待对方完成（窗口到点也会自动开庭）'
    await refresh()
  } finally {
    evidenceDoneBusy.value = false
  }
}

// ---------- 卡 E：准入考试 ----------

async function drawExam(): Promise<void> {
  localNotice.value = ''
  examResult.value = null
  const drawn = await grassland.drawJudgeExam()
  if (!drawn) return
  examQuestions.value = drawn.questions
  examChoices.value = {}
  examOpen.value = true
}

async function submitExam(): Promise<void> {
  localNotice.value = ''
  if (Object.keys(examChoices.value).length < examQuestions.value.length) {
    localNotice.value = '还有题目未作答'
    return
  }
  examBusy.value = true
  try {
    const result = await grassland.submitJudgeExam(examChoices.value)
    if (!result) return
    examResult.value = result
    if (result.passed) {
      localNotice.value = '考试通过！你已获得见习审判官资格（参与 10 轮投票无异常后自动转正）'
      judge.value = await grassland.getMyJudgeStatus()
    } else {
      localNotice.value = '本次未通过，24 小时后可重考'
    }
  } finally {
    examBusy.value = false
  }
}

async function appeal(): Promise<void> {
  localNotice.value = ''
  const appealed = await grassland.appealDispute(props.disputeId, '对判决有异议')
  if (!appealed) return
  applySnapshot(appealed)
  localNotice.value = '上诉已提交，等待客服终审'
}

async function doReauthenticate(): Promise<void> {
  localNotice.value = ''
  if (!reauthPassword.value) return
  const result = await grassland.reauthenticate(reauthPassword.value)
  reauthPassword.value = ''
  if (!result) return
  reauthAt.value = result.reauthenticatedAt ? new Date(result.reauthenticatedAt).toLocaleTimeString() : ''
  localNotice.value = `重认证成功（${result.authStrength}），5 分钟内可执行敏感操作`
}

/** 卡 F：客服终审三选（维持/改判/发回重审）。 */
async function submitFinalDecision(): Promise<void> {
  localNotice.value = ''
  const needsDecision = csAction.value !== 'retrial'
  const finalized = await grassland.finalDecision(props.disputeId, {
    action: csAction.value,
    decision: needsDecision ? csDecision.value : undefined,
  })
  if (!finalized) return
  applySnapshot(finalized)
  localNotice.value = csAction.value === 'retrial'
    ? '已发回重审：案件将重抽面板进入新一轮投票（资金继续冻结）'
    : '终审已生效'
}
</script>

<template>
  <section class="adj">
    <header class="adj-head">
      <h4>审判看板</h4>
      <div class="adj-head-meta">
        <span class="badge" :class="isCsDirect ? 'badge-warning' : 'badge-accent'">{{ channelLabel }}</span>
        <button type="button" class="adj-refresh" :disabled="grassland.loading.value" @click="refresh">刷新</button>
      </div>
    </header>

    <p v-if="grassland.error.value" class="adj-alert adj-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="localNotice" class="adj-alert adj-ok">{{ localNotice }}</p>

    <div v-if="snapshot" class="adj-body">
      <!-- 争议时间线（#74 卡 B：质证中 → 评审中 → 已裁决 → 上诉期 → 终局） -->
      <ol class="adj-timeline" aria-label="争议阶段">
        <li v-for="stage in timeline" :key="stage.key" class="adj-stage"
            :class="{ done: stage.done, active: stage.active }">
          <i class="adj-stage-dot" aria-hidden="true" />
          <span>{{ stage.label }}</span>
        </li>
      </ol>

      <!-- 状态概览 -->
      <dl class="adj-meta">
        <div><dt>状态</dt><dd>{{ statusLabel }}</dd></div>
        <div><dt>轮次</dt><dd>{{ snapshot.round || '未开始' }}</dd></div>
        <div v-if="!isCsDirect"><dt>面板</dt><dd>{{ snapshot.panel.size }} 人 / 已投 {{ snapshot.panel.voted }}</dd></div>
        <div v-if="snapshot.matchedPlatformCount != null">
          <dt>熟手席</dt><dd>{{ snapshot.matchedPlatformCount }}/{{ snapshot.panel.size || 7 }}</dd>
        </div>
        <div v-if="snapshot.probationCount"><dt>见习席</dt><dd>{{ snapshot.probationCount }}</dd></div>
        <div><dt>判决</dt><dd>{{ decisionLabel(snapshot.decision) }}</dd></div>
        <div v-if="snapshot.finalDecision"><dt>终审</dt><dd>{{ decisionLabel(snapshot.finalDecision) }}</dd></div>
      </dl>

      <!-- 窗口倒计时：让用户知道当前阶段还要等多久（生产 24h/48h，dev 可配秒级） -->
      <div v-if="windowLabel && remaining !== null" class="adj-window">
        <span class="adj-window-label">{{ windowLabel }}</span>
        <span class="adj-window-time" :class="{ expired: remaining <= 0 }">{{ remainingText }}</span>
        <span v-if="snapshot.window.deadline" class="adj-hint">
          （截止 {{ new Date(snapshot.window.deadline).toLocaleString() }}）
        </span>
      </div>

      <!-- 卡 A：客服直裁受理提示 -->
      <div v-if="isCsDirect && !isFinal" class="adj-window">
        <span class="adj-window-label">客服直裁通道</span>
        <span class="adj-hint">
          平台客服将在 5 天内裁决{{ snapshot.csDueAt ? `（截止 ${new Date(snapshot.csDueAt).toLocaleString()}）` : '' }}
        </span>
      </div>

      <!-- 卡 B（D1）：被诉方缺席仅标注，等待审判官综合裁量 -->
      <p v-if="snapshot.respondentAbsent && isVoting" class="adj-absent">
        对方未在质证期答辩——已标注，等待审判官综合裁量（不因此判负）
      </p>

      <!-- 计票条 -->
      <div v-if="tallies && tallies.panelSize > 0" class="adj-tally">
        <div class="adj-tally-row">
          <span class="adj-tally-label">商家方</span>
          <div class="adj-bar"><i class="adj-bar-fill adj-bar-m" :style="{ width: barWidth(tallies.forMerchant) }" /></div>
          <span class="adj-tally-num">{{ tallies.forMerchant }}</span>
        </div>
        <div class="adj-tally-row">
          <span class="adj-tally-label">推荐官方</span>
          <div class="adj-bar"><i class="adj-bar-fill adj-bar-r" :style="{ width: barWidth(tallies.forRecommender) }" /></div>
          <span class="adj-tally-num">{{ tallies.forRecommender }}</span>
        </div>
        <div class="adj-tally-row">
          <span class="adj-tally-label">弃权</span>
          <div class="adj-bar"><i class="adj-bar-fill adj-bar-a" :style="{ width: barWidth(tallies.abstain) }" /></div>
          <span class="adj-tally-num">{{ tallies.abstain }}</span>
        </div>
        <p class="adj-major">
          {{ tallies.majority ? `已过半：${decisionLabel(tallies.majority)}` : '尚无一方过半（4/7 多数即终局，平票将重开下一轮）' }}
        </p>
      </div>

      <!-- 启动审判（自愈/重试入口：正常流程由系统在质证期满后自动开庭） -->
      <div v-if="isEvidencePhase && !isCsDirect" class="adj-act">
        <button type="button" :disabled="grassland.loading.value" @click="startAdjudication">立即开庭 / 重试组建面板</button>
        <span class="adj-hint">质证期满系统自动开庭；此处可手动触发或修复面板</span>
      </div>

      <!-- 卡 B：质证操作区（答辩 / 补充 / 质证完毕） -->
      <div v-if="isEvidencePhase && !isCsDirect" class="adj-act adj-evidence">
        <strong class="adj-block-title">举证质证</strong>
        <div class="adj-row">
          <label class="adj-radio">
            <input v-model="evidenceMode" type="radio" value="answer" /> 我是被诉方：提交答辩
          </label>
          <label class="adj-radio">
            <input v-model="evidenceMode" type="radio" value="rebuttal" /> 我是发起方：补充质证
          </label>
        </div>
        <textarea v-model="evidenceText" rows="3"
                  :placeholder="evidenceMode === 'answer' ? '针对争议作出答辩说明…（每案至多一次）' : '针对对方答辩的补充说明…（须对方已答辩，每案至多一次）'" />
        <MediaUploader max-files="3" @change="evidenceMediaIds = $event" />
        <div class="adj-row">
          <button type="button" :disabled="grassland.loading.value" @click="submitEvidence">
            {{ evidenceMode === 'answer' ? '提交答辩' : '提交补充质证' }}
          </button>
          <button type="button" class="adj-quiet" :disabled="grassland.loading.value || evidenceDoneBusy"
                  @click="markEvidenceDone">质证完毕</button>
        </div>
        <span class="adj-hint">
          双方各自点「质证完毕」可提前开庭；质证窗口到点系统自动开庭。答辩角色不符时后端会拒绝并说明原因。
        </span>
      </div>

      <!-- 审判官区 -->
      <div class="adj-act adj-judge">
        <template v-if="!isEnrolledJudge">
          <button type="button" :disabled="grassland.loading.value" @click="enroll">报名成为审判官</button>
          <button type="button" class="adj-quiet" :disabled="grassland.loading.value" @click="drawExam">
            参加准入考试（Lv4 通道）
          </button>
          <span class="adj-hint">仅推荐官可报名；Lv5 直入，Lv4 须完成 ≥20 任务并通过考试成为见习审判官</span>
        </template>
        <template v-else>
          <div class="adj-row adj-judge-badges">
            <span class="badge" :class="isProbationJudge ? 'badge-warning' : 'badge-success'">
              {{ isProbationJudge ? '见习审判官' : '正式审判官' }}
            </span>
            <span v-if="isProbationJudge" class="adj-hint">参与 10 轮投票无异常后自动转正</span>
            <span v-if="isSuspendedJudge" class="badge badge-danger">已暂停</span>
          </div>
          <div v-if="isVoting && !isSuspendedJudge" class="adj-vote">
            <textarea v-model="voteRationale" rows="3" placeholder="投票理由（必填，不少于 20 字；终局后随判例脱敏展示）" />
            <span class="adj-hint" :class="{ 'adj-rationale-warn': !rationaleValid }">
              {{ voteRationale.trim().length }} / {{ RATIONALE_MIN }} 字
            </span>
            <div class="adj-vote-btns">
              <button type="button" :disabled="grassland.loading.value || !rationaleValid" @click="vote('for_merchant')">支持商家</button>
              <button type="button" :disabled="grassland.loading.value || !rationaleValid" @click="vote('for_recommender')">支持推荐官</button>
              <button type="button" :disabled="grassland.loading.value || !rationaleValid" @click="vote('abstain')">弃权（也需理由）</button>
            </div>
            <span class="adj-hint">仅本轮面板成员可投；每官每轮一票，不可更改</span>
          </div>
          <div v-else class="adj-row">
            <span class="adj-hint">
              {{ isSuspendedJudge ? '你的审判官资格处于暂停期' : '你在审判官池中（当前争议不在投票阶段）' }}
            </span>
            <button type="button" class="adj-quiet" :disabled="grassland.loading.value" @click="leave">退出池</button>
          </div>
        </template>
      </div>

      <!-- 卡 E：考试答题卡 -->
      <div v-if="examOpen && examQuestions.length" class="adj-act adj-exam">
        <strong class="adj-block-title">审判官准入考试（{{ examQuestions.length }} 题，≥80 分及格）</strong>
        <div v-for="(q, index) in examQuestions" :key="q.id" class="adj-exam-q">
          <p class="adj-exam-q-text">{{ index + 1 }}. {{ q.question }}</p>
          <label v-for="(option, optionIndex) in q.options" :key="optionIndex" class="adj-radio">
            <input v-model="examChoices[q.id]" type="radio" :name="q.id" :value="optionIndex" />
            {{ option }}
          </label>
        </div>
        <div class="adj-row">
          <button type="button" :disabled="examBusy" @click="submitExam">交卷</button>
          <button type="button" class="adj-quiet" @click="examOpen = false">收起</button>
        </div>
        <p v-if="examResult" class="adj-hint">
          得分 {{ examResult.score }}
          {{ examResult.passed ? '——已通过' : `——未通过，${examResult.cooldownUntil ? '24 小时后可重考' : ''}` }}
        </p>
      </div>

      <!-- 当事方上诉 -->
      <div v-if="isDecided" class="adj-act">
        <button type="button" :disabled="grassland.loading.value" @click="appeal">对判决提起上诉</button>
        <span class="adj-hint">仅判决后的上诉窗口内可提起，每争议一次</span>
      </div>

      <!-- 客服终审三选（卡 F） -->
      <div v-if="!isFinal" class="adj-act adj-cs">
        <details>
          <summary>客服终审（维持 / 改判 / 发回重审）</summary>
          <div class="adj-row">
            <input v-model="reauthPassword" type="password" placeholder="密码（重认证）" />
            <button type="button" :disabled="grassland.loading.value" @click="doReauthenticate">重认证</button>
            <span v-if="reauthAt" class="adj-hint">已重认证 {{ reauthAt }}</span>
          </div>
          <div class="adj-row adj-cs-actions">
            <label class="adj-radio">
              <input v-model="csAction" type="radio" value="maintain" /> 维持
            </label>
            <label class="adj-radio">
              <input v-model="csAction" type="radio" value="overturn" /> 改判
            </label>
            <label class="adj-radio">
              <input v-model="csAction" type="radio" value="retrial" /> 发回重审
            </label>
          </div>
          <div v-if="csAction !== 'retrial'" class="adj-row">
            <select v-model="csDecision">
              <option value="for_merchant">判商家方胜诉</option>
              <option value="for_recommender">判推荐官方胜诉</option>
            </select>
          </div>
          <div class="adj-row">
            <button type="button" :disabled="grassland.loading.value" @click="submitFinalDecision">
              {{ csAction === 'retrial' ? '发回重审（重抽面板再投）' : '提交终审' }}
            </button>
          </div>
          <p class="adj-hint">
            需账号角色为客服（或管理员）+ 5 分钟内完成过重认证。
            {{ csAction === 'retrial' ? '发回重审仅对已上诉案件开放，案件将重抽面板并排除历轮成员。' : '维持/改判将直接终局。' }}
          </p>
        </details>
      </div>
    </div>

    <p v-else class="adj-hint">加载中…</p>
  </section>
</template>

<style scoped>
.adj { border: 1px solid var(--color-border); border-radius: var(--radius-lg); padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.adj-head { display: flex; justify-content: space-between; align-items: center; }
.adj-head-meta { display: flex; align-items: center; gap: 8px; }
.adj-head h4 { margin: 0; font-size: 15px; }
.adj-refresh { font-size: 12px; padding: 3px 10px; }
.adj-alert { margin: 0; padding: 7px 11px; border-radius: var(--radius-sm); font-size: 13px; }
.adj-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.adj-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.adj-body { display: flex; flex-direction: column; gap: 14px; }
.adj-timeline { display: flex; flex-wrap: wrap; gap: 4px 14px; list-style: none; margin: 0; padding: 0; }
.adj-stage { display: flex; align-items: center; gap: 5px; font-size: 12px; opacity: 0.55; }
.adj-stage-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.5; }
.adj-stage.done { opacity: 0.8; }
.adj-stage.done .adj-stage-dot { background: var(--color-success); opacity: 0.9; }
.adj-stage.active { opacity: 1; font-weight: 500; }
.adj-stage.active .adj-stage-dot { background: var(--color-accent); opacity: 1; }
.adj-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; margin: 0; }
.adj-meta div { display: flex; flex-direction: column; gap: 2px; }
.adj-meta dt { font-size: 11px; opacity: 0.6; }
.adj-meta dd { margin: 0; font-size: 13px; font-weight: 500; }
.adj-window {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 7px 11px; border-radius: var(--radius-sm); background: var(--color-surface-strong); font-size: 13px;
}
.adj-window-label { font-weight: 500; }
.adj-window-time { font-variant-numeric: tabular-nums; }
.adj-window-time.expired { color: var(--color-accent-warm); }
.adj-absent { margin: 0; font-size: 12px; padding: 7px 10px; border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-accent-warm) 10%, transparent); color: var(--color-text-muted); }
.adj-tally { display: flex; flex-direction: column; gap: 6px; }
.adj-tally-row { display: flex; align-items: center; gap: 8px; }
.adj-tally-label { flex: 0 0 62px; font-size: 12px; opacity: 0.75; }
.adj-bar { flex: 1; height: 9px; background: var(--color-surface-strong); border-radius: var(--radius-sm); overflow: hidden; }
.adj-bar-fill { display: block; height: 100%; border-radius: var(--radius-sm); transition: width 0.3s ease; }
.adj-bar-m { background: var(--color-accent); }
.adj-bar-r { background: var(--color-success); }
.adj-bar-a { background: var(--color-text-muted); }
.adj-tally-num { flex: 0 0 20px; text-align: right; font-size: 12px; font-variant-numeric: tabular-nums; }
.adj-major { margin: 2px 0 0; font-size: 12px; opacity: 0.7; }
.adj-act { display: flex; flex-direction: column; gap: 6px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.adj-block-title { font-size: 13px; font-weight: 500; }
.adj-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.adj-radio { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; }
.adj-vote { display: flex; flex-direction: column; gap: 8px; }
.adj-vote textarea,
.adj-evidence textarea {
  padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; font-family: inherit; resize: vertical;
}
.adj-rationale-warn { color: var(--color-accent-warm); opacity: 1; }
.adj-vote-btns { display: flex; gap: 8px; flex-wrap: wrap; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.adj-quiet { opacity: 0.7; font-size: 12px; }
select { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; }
.adj-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.adj-cs summary { font-size: 13px; cursor: pointer; }
.adj-exam { gap: 10px; }
.adj-exam-q { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.adj-exam-q-text { margin: 0; font-size: 13px; }
.adj-warn { margin: 8px 0 0; font-size: 12px; color: var(--color-accent-warm); background: color-mix(in srgb, var(--color-accent-warm) 12%, transparent); padding: 7px 10px; border-radius: var(--radius-sm); }
</style>
